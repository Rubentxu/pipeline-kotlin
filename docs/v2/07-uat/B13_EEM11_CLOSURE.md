# B13 / E-EM-11 — Closure: durable retry / timeout / parallel UAT evidence

Date: 2026-09-09. Scope: canonical spine only
(`DSL → StepSpec → compiled IR → CanonicalDurableRunCoordinator`).
Status: **CLOSED — all three durable UATs GREEN.**

This document closes the accepted-loss record opened when LEG-1.3 deleted the
legacy engine and, with it, the durable UAT evidence for block semantics.
Every behavior was re-provisioned through the canonical coordinator; no
StepSpec re-interpretation, no second engine, no per-step dispatcher
collections (ADR-0073 direction preserved throughout).

## Slices and evidence

| Slice | Commit | Implementation seam | UAT (canonical CLI process) | Result |
| --- | --- | --- | --- | --- |
| retry | `11cb170e` | `BlockShellScope.Retry(maxAttempts)` ADT case; ONE generic attempt loop in `dispatchBody`; deterministic attempt `BlockSegment("{attempt}:retry-attempt")` appended to child bodyPath (journal-reconstructed attempt identity, no memory counters); existing `RetryAttemptStarted` event | `UatRetryBlockDurableTest` (WL-R1 fail→retry→success; WL-R2 success = exactly one attempt; WL-R3 durable rerun no duplication) | 3/3 GREEN |
| timeout | `c9826214` | `BlockShellScope.Timeout(budgetMs)` → child `shOptions.copy(timeoutMs = min(inherited, budget))` — reuses the CERTIFIED `DurableShellExecutor` watchdog seam (flag-then-kill, cookie-scan process-tree kill, FAILED_TIMEOUT). No coroutine-interruption hack | `UatTimeoutBlockDurableTest` (WL-T1 before-deadline success; WL-T2 deadline expires → child killed, run fails; WL-T3 rerun no duplication) | 3/3 GREEN |
| parallel | `6c5733e1` | `run()` now branches on `StageBody.Parallel` (previously invariant rejection); `runParallelStage()`/`executeBranchSteps()` dispatch branch steps concurrently via `scope.async` through the SAME `dispatch()` spine; branch identity = first deterministic `BlockSegment("b{N}:branch")` in bodyPath; join = `JoinPolicy.ALL_COMPLETE` (existing contract), aggregate = first failure by lowest branch index; existing `ParallelBranchStarted`/`ParallelBranchFinished` events | `UatParallelBlockDurableTest` (WL-P1 both branches execute + branch events; WL-P2 failing branch fails aggregate, sibling independent; WL-P3 durable rerun no duplication) | 3/3 GREEN |

## Root-cause defect found by the parallel slice (fixed in `6c5733e1`)

`ShOperationsAdapter` derived a lossy `OpId(runId, 0, stepIndex)` per call,
discarding the bodyPath-carrying canonical `OpId` that `dispatch()` had placed
in `CanonicalRuntimeContext`. Two concurrent branches whose first `sh` had
`stepIndex = 0` collapsed onto the SAME control dir (`{controlRoot}/{opId}`),
and two concurrent `DurableShellExecutor` instances interleaved byte-level
writes into one `script.sh`/`result.txt` (observed literally in `console.log`:
`exit 3k >> '/tmp/ptest/marker.txt'` — the fused text of `exit 3` and
`echo ok >> ...`). The fix binds `CanonicalRuntimeContext.opId` at adapter
construction: the control dir IS the durable process identity, so the
canonical (branch- and bodyPath-aware) opId must reach `ShExecution.invokeShell`
intact. The adapter is constructed per-invocation from the context, so no
shared mutable state is introduced.

## Regression evidence

- `CanonicalDurableRunCoordinatorTest`: base-vs-head failure set IDENTICAL
  (12 pre-existing failures, none added).
- Adapter constructor change: `A4_2ShellOperationsCapabilityTest` (14),
  `A4_8LegacyRegistrySemanticParityTest` (12),
  `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` (11) — all green.
- `FArchLeg1ExecutionAuthorityTest` 4/4 green — direct StepSpec execution = 0
  preserved.
- Determinism spot-check (manual, outside JUnit): the WL-P2 scenario repeated
  4/4 with the sibling marker present; the WL-P3 scenario repeated 8/8 with
  `marker = [one, two]` and a journal-carrying rerun adding no duplicate rows.

## Fitness / constitution conformance

- The engine exhaustively matches the closed `StageBody` ADT; no concrete-Step
  switch was added (parallel is a structural body shape, not a Step).
- Branches re-enter `dispatch()` (the de-facto body machinery; `BodyInvoker`/
  `BranchInvoker` remain ADR-0073-proposed and were NOT invented here).
- Declared capability == used capability unchanged; `core.sh` still reaches
  execution only through `SHELL_OPERATIONS_CAPABILITY`.
- Fail-closed behavior preserved: a non-linear branch body is an
  `EngineInvariantViolation`, not a silent linearization.
