# ADR-0042: Resume Re-Attach Event Semantics

- **Status:** Accepted for M3-R4.4
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.4 Implementation:** T-03 (C3)

## Context

On resume, `BranchReconciler.reconcileRunningOperations` returns a list of `ReconciledBranch` records, each with `status: SUCCESS | NEEDS_REATTACH | STUCK`. The question is: what events does `walkParallelFrame` emit for each status category?

The current `walkParallelFrame` (`PipelineRun.kt:1250-1273`) emits `ParallelBranchStarted` for ALL branches unconditionally, and `ParallelBranchFinished` after each `walkBranchDurable` call. On a fresh run this is correct. On a resumed run:

- `SUCCEEDED` branches: their journal rows are terminal. Re-executing `walkBranchDurable` would double-write events and re-run side effects (counter files, etc.). The counter invariant (EC-6(d)) requires completed branches NOT be re-executed.
- `NEEDS_REATTACH` branches: their journal rows are `RUNNING`. They need a fresh `ParallelBranchStarted` to signal re-attachment to observers.
- `STUCK` branches: fail-closed; throw `DivergenceException` before any branch execution.

## Decision

`SUCCEEDED` branches skip execution entirely — do not call `walkBranchDurable`, do not emit `ParallelBranchStarted`/`Finished`. Their original journaled events are retained as the source of truth.

`NEEDS_REATTACH` branches emit a fresh `ParallelBranchStarted` event and proceed through `walkBranchDurable`. The reconciler's `ReconciledBranch.lastStage` is passed as the resume point so execution resumes from the correct checkpoint.

`STUCK` branches: throw `DivergenceException` with the branch's opId, fail-closed.

The reconciliation result drives the dispatch: `walkParallelFrame` receives the `List<ReconciledBranch>` from `ctx.branchReconciler.reconcileRunningOperations(runId)` and branches its logic accordingly.

## Alternatives Considered

1. **Always re-emit `ParallelBranchStarted` for all branches**: Including `SUCCEEDED` branches get a fresh event, then `walkBranchDurable` is called and detects the journal row is terminal, skipping step execution internally. Rejected — double-writes events for already-completed branches; adds noise to the event timeline; still re-executes the branch dispatcher overhead even if steps are skipped.

2. **Never re-emit events for re-attached branches**: Skip `ParallelBranchStarted` for `NEEDS_REATTACH` too, relying solely on the journal. Rejected — loses the observable signal that a branch was re-attached; the event timeline becomes inconsistent with the observable execution flow; harder to debug.

3. **Suppress only step execution, re-emit branch lifecycle**: `walkBranchDurable` always runs but short-circuits on journal detection. Rejected — same as option 1: double-write of events; branch dispatcher overhead still incurred.

## Consequences

- Event timeline is consistent: `SUCCEEDED` branches have exactly one `ParallelBranchStarted`/`Finished` pair from the original run.
- `NEEDS_REATTACH` branches have a fresh `ParallelBranchStarted` on resume, making re-attachment observable.
- `STUCK` branches fail-closed before any branch execution.
- EC-6(d) counter invariant is provable: `SUCCEEDED` branches do not re-execute, so their counter files remain `"1"`.
- Replay-only suppression (suppressing step execution but not branch dispatch) is not needed — entire branch dispatch is skipped for `SUCCEEDED`.
