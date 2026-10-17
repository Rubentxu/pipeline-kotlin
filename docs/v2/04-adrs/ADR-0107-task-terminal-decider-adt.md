---
type: adr
id: ADR-0107
title: "TaskExecutionResult termination decider — sealed ADT over flag bag"
status: accepted
date: 2026-10-17
accepted: 2026-10-17
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - AGENTS.md §STRICT TYPED FUNCTIONAL DESIGN
  - AGENTS.md §HARNESS FIDELITY LAW
  - ADR-0072  # layered test-harness fidelity
  - ADR-0071  # executable scenarios
---

# ADR-0107 — One decider, one ADT, one success predicate

## Status

**ACCEPTED 2026-10-17** as the executable law for the `TaskExecutionResult` contract in
`pipeline-domain`. Established incrementally across nine commits on `main` (see References),
all of which were integrated without breaking any existing contract suite.

## Context

`TaskExecutionResult` is the return type of `DurableTaskRuntime.execute`. It carries an
integer `exitCode` plus two mutually-non-exclusive boolean flags (`timedOut`, `cancelled`)
plus two epoch-millisecond timestamps. The runtime's `init` already forbids
`(timedOut && cancelled)`, but a consumer reading the result still saw four input states:

```text
input state                       what a consumer could read
─────────────────────────────────  ──────────────────────────────────
exit=0,  timedOut=false, cancel=false  → "the process exited successfully"
exit=N,  timedOut=false, cancel=false  → "the process exited with code N"
exit=N,  timedOut=true,  cancel=false  → "the watchdog killed the process (N is whatever the OS recorded)"
exit=0,  timedOut=true,  cancel=false  → "the watchdog killed the process with recorded code 0"
[exit=*, timedOut=true, cancel=true   → refused by init]
```

The pre-existing consumer in `ShExecution.executeNonDurableInvocation` (line 731 of
`v2/pipeline-application/.../ShExecution.kt` at the time of adoption) collapsed all four
into a 3-way `when {}` over the two booleans:

```kotlin
val terminal = when {
    result.timedOut   -> DurableTaskTerminal.Cancelled(InterruptionRecord(TIMEOUT, ...))
    result.cancelled  -> DurableTaskTerminal.Cancelled(InterruptionRecord(PARENT_CANCELLED, ...))
    else              -> DurableTaskTerminal.Exited(exitCode = result.exitCode, ...)
}
```

The "else" branch lost the cause of termination. A watchdog kill that recorded `exitCode=137`
collapsed into `Exited(137)`, indistinguishable from a process that genuinely exited with 137.
Downstream consumers had to re-derive "this was a watchdog kill" from exit-code heuristics.

A second, parallel consumer in `ProcessDurableTaskRuntime` (line 164 at the time of
adoption) wrote the audit record string as:

```kotlin
val record = if (result.timedOut) "TIMED_OUT" else "EXIT ${result.exitCode}"
```

The class KDoc at the time documented the contract as
`EXIT <code> | TIMED_OUT | CANCELLED` (three states) but the code only ever emitted two.
The `CANCELLED` case was structurally unreachable at this site (the catch block above
already wrote `"CANCELLED"` and rethrew) but the compiler could not enforce that invariant.

## Decision

Introduce a single pure decider, a single sealed ADT, and a single success predicate in
`pipeline-domain`:

```kotlin
sealed interface TaskTerminalDecision {
    data class Exited(val exitCode: Int, val elapsedMs: Long) : TaskTerminalDecision
    data class TimedOut(val elapsedMs: Long) : TaskTerminalDecision
    data class Cancelled(val elapsedMs: Long) : TaskTerminalDecision
}

fun decideTaskTerminal(result: TaskExecutionResult): TaskTerminalDecision = ...
fun isSuccessFromDecision(decision: TaskTerminalDecision): Boolean =
    decision is TaskTerminalDecision.Exited && decision.exitCode == 0
```

The decider is the **single** reader of `TaskExecutionResult.timedOut` and
`TaskExecutionResult.cancelled` in production code. Precedence is `timedOut > cancelled > exited`
(invariant: the runtime's `init` already refuses the first two being simultaneously true).
`isSuccessFromDecision` is the single authority for "did this task succeed" — it
encapsulates the consumer's notion of success so a future fourth case (e.g. `Refused`)
is a compile error at every call site, not a silent gap.

The data class `TaskExecutionResult` keeps its two boolean fields because the runtime
writes them; **consumers MUST NOT read them**. The architectural rule is enforced by
`TaskTerminalDecisionFitnessTest` (see Verification), which scans every `.kt` file
under `pipeline-application/src/main` and every `pipeline-step-sdk/*/src/main` and
fails on any direct `result.timedOut` / `result.cancelled` access outside the decider.

`CapturedRun` (a different domain — a coarse boolean projection in `pipeline-step-sdk/runtime`
for scm-git callers) is intentionally out of scope here. Its own data class and
consumers have their own refactor, and the fitness test excludes it via the
`(?<![a-zA-Z_])result.` prefix on the regex.

## Consequences

**Positive:**
- Watchdog kills are `TaskTerminalDecision.TimedOut` by construction, not by exit-code
  guessing. The cause of termination is preserved through every consumer.
- A future fourth case (e.g. `Refused`) is a compile error at every `when (decision)` site
  and at every `isSuccessFromDecision` call, not a silent green.
- The success predicate is one line and one function. A consumer that needs a
  different notion of success (e.g. "exited cleanly OR cancelled before any work
  started") is a one-line additional predicate, not a flag-bag proliferation.
- The architectural guard catches future regressions: in the most recent expansion
  (commit `8f207e1e`), the fitness test caught a real `CapturedRun.kt:43` regression
  on its first extended run.

**Cost:**
- `TaskExecutionResult` no longer carries the derived `succeeded` boolean (removed
  in commit `34585a2a`). The 7 call sites in tests were updated to
  `isSuccessFromDecision(decideTaskTerminal(result))`. Production code had only one
  consumer (TarWriter.kt:105) which was updated.
- The audit record at `ProcessDurableTaskRuntime.kt:164` now has an explicit
  `error("...")` at the structurally-unreachable `TaskTerminalDecision.Cancelled` arm.
  This is the audit-trail witness that the catch block still writes "CANCELLED"; a
  future refactor that drops the catch's write will hit it at runtime.
- The fitness test must be updated when adding a new module that consumes
  `TaskExecutionResult`. Documented in the test's KDoc.

**Reversibility:** a future ADR can add a fourth `TaskTerminalDecision` case or migrate
to a different ADT shape. The pattern — one decider, one ADT, one success predicate,
enforced by a fitness test that scans the call sites — is the preserved invariant; the
specific shapes are not.

## Verification

- 9 reducer law tests in `pipeline-domain/.../TaskTerminalDecisionTest.kt` (classification,
  precedence, `elapsedMs` invariant, purity, exhaustive + mutually-exclusive guard,
  `isSuccessFromDecision` only `Exited(0, _)`).
- 2 architecture fitness tests in `pipeline-application/.../TaskTerminalDecisionFitnessTest.kt`:
  one scans every `.kt` file under `pipeline-application/src/main` and every
  `pipeline-step-sdk/*/src/main` for `result.timedOut` / `result.cancelled`; the other
  scans `ShExecution.kt` for the legacy `when { result.timedOut/cancelled }` signature.
  Both are mutation-verified: injecting a regression breaks the test.
- 19 test classes across `pipeline-domain`, `pipeline-application`, and
  `pipeline-step-sdk/runtime` continue to pass (e.g. `ShStepContractSuiteTest` 17/17,
  `A4_2ShellOperationsCapabilityTest` 14/14, `ProcessDurableTaskRuntimeTest` 11/11
  including `M3-006 timeout records TIMED_OUT` and `M3-006 cancel records CANCELLED`,
  `Lpr040OutputObservationHarnessTest` 6/6).

## References

- `d2bf51b0`  refactor(durable): extract `TaskTerminalDecision` ADT — pure decider
- `2f68325d`  test(fitness): guard TaskTerminalDecision architecture (single-module)
- `34585a2a`  refactor(durable): remove `TaskExecutionResult.succeeded` (single source of truth)
- `b9ff90ef`  docs(evidence): BQ5 p50/p95 reproducible metric on durable slice
- `b1111624`  docs(evidence): BQ5 p50/p95 on BQ2 lane (test-harness)
- `306e844f`  refactor(step-sdk): route `ProcessDurableTaskRuntime` record through decider
- `8f207e1e`  refactor(step-sdk): route `CapturedRun` through decider + extend fitness scope
- `c0235be9`  test(fitness): refine regex to all pipeline-step-sdk sub-modules
- `68687365`  chore(cleanup): remove 10 stale files orphaned in working tree
