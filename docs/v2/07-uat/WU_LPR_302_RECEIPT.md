# WU-LPR-302 — Body/Control Execution Consolidation (closure receipt)

**Status:** `PARTIALLY CLOSED` with explicit follow-up (`WU-LPR-302P`).

**Result map (per slice objective):**

| Slice                                  | Status                                  |
|----------------------------------------|------------------------------------------|
| Retry body execution                   | **GREEN** — extracted to `retry/RetryEngine` |
| WaitUntil body execution               | **GREEN** — extracted to `waituntil/WaitUntilEngine` |
| Sequential / scoped body seam          | **GREEN** — `BodyInvocationContext` consumed, runner projects per-attempt bodyPath |
| Parallel branch convergence            | **DEFERRED — FOLLOW-UP REQUIRED** — separate primitive, intentionally unchanged |

The WU is closed at the seam of what the existing architecture could safely
release. The parallel branch body re-entry model is documented as a separate
follow-up WU rather than forced into a `ParallelEngine` whose invariants
have not been proven equivalent.

---

## 1. Delta — coordinator

### Before

```text
CanonicalDurableRunCoordinator.kt = 2653 LOC

  orchestration
  + lifecycle
  + durable retry loop           (in-memory counter path)
  + durable retry loop           (RETRY-D / RetryReconciler path)
  + waitUntil loop               (legacy inline)
  + waitUntil loop               (WU-G5R.5 / WaitUntilReconciler path)
  + parallel branch execution    (runParallelStage, executeBranchSteps)
  + body traversal                (shared, single-loop invariant)
```

### After

```text
CanonicalDurableRunCoordinator.kt = 2224 LOC

  orchestration
  + lifecycle
  + body traversal                (shared, single-loop invariant)
  + durable retry delegation      (to RetryEngine when retryControlJournal != null)
  + legacy retry inline loop      (preserved bit-equivalent, no journal)
  + durable waitUntil delegation  (to WaitUntilEngine when waitUntilControlJournal != null)
  + legacy waitUntil inline loop  (preserved bit-equivalent, no journal)
  + parallel branch execution     (runParallelStage — INTENTIONALLY UNCHANGED, see §6)
```

LOC is a secondary datum. The structural change is **who owns the
control-flow loops**:

| Loop                                          | Owner                                              |
|-----------------------------------------------|----------------------------------------------------|
| Durable retry control row reconciliation      | `retry/RetryEngine` (extracted, Phase 2)            |
| Legacy retry inline loop                      | `CanonicalDurableRunCoordinator.execute*` (preserved) |
| Durable waitUntil polling                     | `waituntil/WaitUntilEngine` (extracted, Phase 3)     |
| Legacy waitUntil inline loop                  | `CanonicalDurableRunCoordinator.executeWaitUntilBodyInline` (preserved) |
| Parallel branch concurrency + aggregate plan  | `CanonicalDurableRunCoordinator.runParallelStage` (UNCHANGED) |

**Coordinator net delta:** `−429 LOC` (2653 → 2224, ~16 %).

---

## 2. Bugfixes surfaced by extraction (not refactor-only)

### 2.1 WaitUntil predicate terminal-persistence gap

**Before** (the inline `executeWaitUntilBody` loop, pre-Phase 3):

```text
RUNNING persist
   ↓
invoke body
   ↓
fold to PredicateOutcome.Failed / .Unsatisfied
   ↓
// (no FAILED persistence)
   ↓
reconcile()
   ↓
// attempt 1 still RUNNING in the journal
   ↓
ResumeAttempt(1)   ← same RUNNING poll
   ↓
re-execute body at attempt 1
   ↓
(repeat forever)
```

The inline loop could spin on the same RUNNING poll indefinitely.

**After** (`WaitUntilEngine`):

```text
RUNNING persist
   ↓
invoke body
   ↓
fold to PredicateOutcome.Failed / .Unsatisfied
   ↓
persist FAILED (this poll)    ← terminal transition
   ↓
reconcile()
   ↓
// attempt 1 now FAILED → next plan() returns
//   AdvanceAfterPredicateUnsatisfied(2, nextBackoffMs)
//   or DeadlineExceeded(2)
```

**Consistent with**: durable semantics (a terminal state is a terminal
state; the planner must observe the transition before advancing).

**Tests that prove it** (`WULpr302WaitUntilEngineTest.OutcomeFolding`):

| Test                                                                              | What it proves                                   |
|-----------------------------------------------------------------------------------|--------------------------------------------------|
| `fail then success returns Success after backoff and persists SUCCEEDED at poll 2`| Two polls executed under distinct `attempt.index`; attempt 2 SUCCEEDED |
| `deadline exceeded returns Failure TIMEOUT and persists FAILED_TIMEOUT`           | The engine emits `WaitUntilCompleted("deadline-exceeded")` and returns `StepOutcome.Failure(TIMEOUT)` when the planner says `DeadlineExceeded` |
| `cancelled body becomes Failure ENGINE`                                           | `BodyOutcome.Cancelled` no longer folds to `PredicateOutcome.Failed` and re-enters the planner |

The pre-Phase-3 inline code path did not survive these scenarios — the
fitness loops would have spun until the `@Timeout` killed the test (60 s
JVM budget, the same symptom observed during the Phase 3 authoring
session before the fix landed).

### 2.2 Cancellation classification

**Before**:

```text
BodyOutcome.Cancelled(reason)
   ↓
engine.fold:
   StepOutcome.Failure(FailureKind.SCRIPT, "waitUntil body cancelled...")
   ↓
PredicateOutcome.Failed(...)
   ↓
persist FAILED
   ↓
emit WaitUntilCompleted("completed")     ← wrong outcome string
   ↓
return Failure(SCRIPT)
```

A cancelled body looked like a failed body: the journal recorded a typed
`SCRIPT` failure, the completion event lied, and the cancellation reason
was lost.

**After** (`WaitUntilEngine`):

```text
BodyOutcome.Cancelled(reason)
   ↓
engine.fold:
   PredicateOutcome.Cancelled(reason)
   ↓
persist ABORTED on this poll
   ↓
emit WaitUntilCompleted("aborted")
   ↓
return Failure(ENGINE)
```

**Consistent with**: `WaitUntilPredicateOutcome.Cancelled` is a distinct
sealed-constructor case (LFC-5.3 design §14.3: predicate outcome and body
execution are independent concerns). The journal now distinguishes
ABORTED (caller intent) from FAILED (typed body failure).

**Tests** (`WULpr302WaitUntilEngineTest.OutcomeFolding.cancelled body...`):

* Asserts `StepOutcome.Failure(ENGINE)`.
* Asserts `BodyOutcome.Cancelled` reaches the engine through the
  `BodyInvoker` port (not by re-entry of the legacy inline path).

### 2.3 Reconciler invariant gap (filed, not fixed)

**Not** introduced by this WU. Pre-existing `WaitUntilReconciler.computeNextBackoff`:

```kotlin
val next = base * 2
return minOf(next, maxBackoffMs)   // (a)
```

…and the planner:

```kotlin
if (next > input.maxBackoffMs) {
    return DeadlineExceeded(...)
}                                    // (b)
```

`(a)` caps `next` at `maxBackoffMs` before the comparison `(b)`, so the
inequality `next > max` is never satisfied. `DeadlineExceeded` therefore
fires only via the pre-terminalised `FAILED_TIMEOUT` row shortcut, not
through the "next attempt would exceed ceiling" branch.

**Workaround in `WaitUntilEngine`'s tests**: seed the journal with a
pre-terminalised `FAILED_TIMEOUT` row so the engine exercises the
`DeadlineExceeded` decision branch deterministically. The engine is
correct; the reconciler has a separate invariant gap.

**Filed for follow-up**: this is a `pipeline-domain` bug, not a
`pipeline-application` bug; it stays out of WU-LPR-302 scope. The next
slice that touches the reconciler should evaluate whether `computeNextBackoff`
should return the un-capped next value and let the planner decide.

---

## 3. Architecture fitness

`v2/pipeline-architecture-tests/.../Lfc2WULpr302BodyControlSeamFitnessTest.kt`
(7 properties, all green).

| # | Property                                                                     | Where it lives                              |
|---|------------------------------------------------------------------------------|---------------------------------------------|
| 1 | Only `BodyInvoker` is a body re-entry port in production source              | Module scan over `pipeline-application`     |
| 2 | `BodyInvocationContext` is consumed by `CanonicalBodyInvokerAdapter`        | Adapter source scan                          |
| 3 | Coordinator contains no inline retry mechanics                               | Coordinator source scan, comments stripped   |
| 4 | Coordinator contains no inline waitUntil mechanics                           | Coordinator source scan, comments stripped   |
| 5 | Parallel branch dispatch remains a separate primitive (frontier, not convergence) | Coordinator source scan                  |
| 6 | Legacy retry inline loop preserved for no-journal callers                    | Coordinator source scan                      |
| 7 | Legacy waitUntil inline loop preserved for no-journal callers               | Coordinator source scan                      |

The fitness pins **properties**, not filenames: renaming
`RetryEngine.kt` or moving it to another package does not weaken the
guard, but reintroducing a `RetryReconciliationDecision.X ->` branch in
the coordinator does.

The `Lfc2ConcreteBodyRoutingDebtFitnessTest` continues to pin the
body-path discipline (W1d empty ledger); the new fitness adds the
control-flow extraction discipline on top of it.

---

## 4. Tests / gates

| Suite                                                                            | Count | Result |
|----------------------------------------------------------------------------------|-------|--------|
| `WULpr302Phase1bTest` (Phase 1b)                                                | 11    | GREEN   |
| `WULpr302RetryEngineTest` (Phase 2)                                              | 10    | GREEN   |
| `WULpr302WaitUntilEngineTest` (Phase 3)                                          | 8     | GREEN   |
| `Lfc2WaitUntilCanonicalReentryFitnessTest` (WU-LPR-301)                         | 4     | GREEN   |
| `B11ContextBlocksRuntimeTest`                                                   | 7     | GREEN   |
| `RetryAwareDispatchIntegrationTest`                                              | 3     | GREEN   |
| `WindowCRetryRecoveryProductionWiringTest`                                      | 1     | GREEN   |
| `RetryReconciliationDriverTest`                                                 | 6     | GREEN   |
| `FileBasedRetryControlJournalTest`                                              | 11    | GREEN   |
| `FileBasedWaitUntilControlJournalTest`                                          | 14    | GREEN   |
| `WaitUntilStepContractSuiteTest`                                                | 18    | GREEN   |
| `Lfc2 G5-RESTORE` (DSL project)                                                 | 2     | GREEN   |
| `Lfc2WULpr302BodyControlSeamFitnessTest` (Phase 6)                               | 7     | GREEN   |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` (pre-existing guard)                   | 16    | GREEN   |
| **Subtotal (WU-LPR-302 covered scope)**                                         | **118** | **GREEN** |

### Pre-existing failures outside WU-LPR-302 scope

`Lfc2DurableAggregateIdentityFitnessTest` carries two failures that
existed before this WU opened. Neither is a regression introduced by
WU-LPR-302:

1. **`expected <2> but was <3>`** — the test asserts that the durable
   ledger has two aggregate identities (Retry + Parallel), but the
   ledger now has three (Retry + Parallel + WaitUntil, the latter
   introduced by WU-G5R.5).
2. **`A durable role must cite the ADR that defines it; found [ADR-0075, ADR-0076, WU-G5R.5 / ADR-0075 analog]`** — the
   `AggregateDurableRole.WAIT_UNTIL_CONTROL_ROW` enum cites
   `"WU-G5R.5 / ADR-0075 analog"` rather than an `ADR-####` reference.

Both are documentation / ledger bookkeeping gaps from WU-G5R.5; the
ledger has not been updated since. `BodyAggregateIdentity.kt` was last
touched at commit `39ab42a6` (`feat(wu-g5r.5): durable waitUntil control
journal and reconciler`), before this WU opened. These failures do NOT
gate WU-LPR-302 closure and are filed in `WU-LPR-302` debt ledger
below.

---

## 5. Production behaviour changes

The external observability surface of the durable coordinator changed
in two places, both as a result of the bugfixes above:

1. **Cancelled waitUntil bodies** now emit `WaitUntilCompleted(outcome = "aborted")`
   and return `StepOutcome.Failure(FailureKind.ENGINE)` where they
   previously emitted `WaitUntilCompleted(outcome = "completed")` and
   returned `StepOutcome.Failure(FailureKind.SCRIPT)`. The journal row
   transitions to `OperationStatus.ABORTED` instead of `FAILED`.
2. **Unsatisfied waitUntil predicates** now persist `OperationStatus.FAILED`
   on the attempt before advancing. Restart / replay of a previously
   in-flight unsatisfied poll now reuses the cached failure rather than
   re-executing the body.

External surface that **did NOT** change:

* Retry semantics (existing durable retry loop already terminated
  attempts before extracting).
* Parallel stage concurrency, join policy, or aggregate identity.
* `core.echo`, `core.sh`, `core.waitUntil`, `core.retry`, `core.parallel`
  Step contracts.
* `BodyInvoker` public surface (`open(runner)`, `invoke(ref, ctx)`, `close(ref)`).
* `BodyInvocationContext` shape.

---

## 6. Parallel — follow-up (WU-LPR-302P)

### Current state (UNCHANGED by WU-LPR-302)

```text
current authority:    runParallelStage / branch durable machinery
current invocation:   executeBranchSteps(branch, ...)
identity:             existing durable -bp{N}-b{N} scheme
problem:              BodyInvoker / BodyRef model has not yet proven semantic equivalence
risk:                 forcing convergence may alter replay / reconciliation identity
```

`ParallelEngine` was deliberately not created. Forcing the
`RetryEngine` / `WaitUntilEngine` shape onto parallel would have required
one of:

* introducing a `BranchInvoker` or `BodyExecutor` port (forbidden by
  WU-LPR-302 spec);
* introducing `BodyRefs.branchBody()` adapters that map
  `-bp{N}-b{N}` OpIds onto a `BodyRef` (no parity proof);
* routing the parallel body through the canonical `BodyInvoker` (would
  lose the parallel-specific semantics: branch durability, branch join
  policy, branch snapshot reconciliation).

Each of those is an architectural decision, not a refactor. The WU
spec says: *"Treat as incomplete realization of existing architecture,
no new ADR unless decision changes."* — the parallel case **is** a
decision change, so it gets its own WU.

### Follow-up WU: `WU-LPR-302P — Parallel Body Identity Convergence`

Before implementation, answer:

1. Can `BodyRefs.branchBody(parentBodyPath, branchIndex)` express the
   existing `-bp{N}-b{N}` durable identity exactly, byte-for-byte?
2. How does the candidate `BodyRef` map to the current OpId
   `runId-stage{N}-bp{N}-b{N}:branch` without changing the `OpId`,
   fingerprint, or replay cursor?
3. Is `executeBranchSteps` truly a second body-child execution
   authority, or is it a structural primitive with a different
   join/lifecycle shape (concurrent branches, supervisor scope,
   per-branch journal snapshot reconciliation)?
4. Can branch re-entry reuse the same `BodyInvocationContext` seam
   (`attempt = AttemptSegment(branchIndex, "branch")`) without losing
   branch isolation or interfering with sibling branches' durable rows?
5. What is the **authority of concurrency** in the parallel stage
   today, and is it the durable spine or the structured-concurrency
   `supervisorScope`?
6. What is the **authority of reconciliation** (`ParallelReconciler`),
   and does it belong to the engine or to the coordinator?
7. Can migration be staged with **bit-equivalent parity tests**
   (same journal rows, same replay cursors, same `RunOutcome`)?
8. Is convergence **desirable**, or should parallel remain a
   primitive that the durable spine composes rather than owns?

**Until those questions are answered, `DO NOT REFACTOR PARALLEL`.**

---

## 7. Debt remaining (new)

| Debt item                                                                  | Severity | Filed against |
|----------------------------------------------------------------------------|----------|----------------|
| `Lfc2DurableAggregateIdentityFitnessTest` ledger expects 2, has 3          | Low      | WU-LPR-302     |
| `AggregateDurableRole.WAIT_UNTIL_CONTROL_ROW` cites non-ADR `WU-G5R.5`    | Low      | WU-LPR-302     |
| `WaitUntilReconciler.computeNextBackoff` cap-vs-DeadlineExceeded invariant | Medium   | WU-LPR-302     |
| `BodyInvoker.invoke` does not project `BodyInvocationContext.patch` for credential leases (Phase 1b explicitly fell through) | Medium | WU-LPR-401 if DSL hardening touches credentials |
| Parallel body re-entry convergence                                          | Open     | WU-LPR-302P    |

None of these block WU-LPR-302 closure or the next WU (WU-LPR-401, honest
DSL).

---

## 8. Roadmap impact

* **WU-LPR-302** is closed at the seam of what the existing architecture
  can release without forcing symmetry.
* **WU-LPR-401** (Honest DSL) is the next slice; its concerns are
  independent of the parallel follow-up.
* **WU-LPR-302P** (Parallel Body Identity Convergence) is filed as a
  separate WU; the eight investigation questions in §6 must be answered
  before any implementation begins.

---

## 9. Production baseline

* HEAD: `4f80e343` (Phase 6 commit).
* Trunk: `origin/main` == `4f80e343` (every WU-LPR-302 commit was pushed).
* Coordinator LOC: `2653 → 2224` (`−429 LOC`).
* New production modules: `pipeline-application/.../durable/retry/RetryEngine.kt`,
  `pipeline-application/.../durable/waituntil/WaitUntilEngine.kt`.
* New test modules: `WULpr302RetryEngineTest`, `WULpr302WaitUntilEngineTest`,
  `Lfc2WULpr302BodyControlSeamFitnessTest`.
* Behaviour change: §5 (Cancelled classification + Unsatisfied terminal
  persistence; both bugfixes, not refactor artefacts).

---

## 10. STOP

WU-LPR-302 is `PARTIALLY CLOSED` with the explicit follow-up
`WU-LPR-302P`. The next session can resume with WU-LPR-401 (Honest DSL)
without waiting for the parallel follow-up, per the user's direction
("the existence of a follow-up of parallel MUST NOT block Honest DSL if
its concerns are independent").

**STOP.**
