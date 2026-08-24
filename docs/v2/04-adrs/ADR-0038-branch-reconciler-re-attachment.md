# ADR-0038: BranchReconciler Re-Attachment Contract

- **Status:** Accepted for M3-R4.3
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.3 Implementation:** T-03 (C3)

## Context

When a pipeline run crashes mid-execution (for example, during a parallel frame with multiple branches running concurrently), some branches may be left in the `RUNNING` state in the `operation_journal` table with no terminal status (`ended_at IS NULL`). On restart, the system must be able to:

1. Identify which branches were left running.
2. Determine the last durable checkpoint for each branch.
3. Re-attach to the branch at the correct position without replaying completed work.

This ADR defines the **BranchReconciler** contract: the interface for scanning the journal and returning re-attachment metadata.

## Decision

### Class Signature

```kotlin
class BranchReconciler(
    private val opJournal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val clock: Clock,
)
```

### Primary Method

```kotlin
suspend fun reconcileRunningOperations(runId: String): List<ReconciledBranch>
```

### Semantics

1. **Journal scan**: Query `operation_journal` for all rows where:
   - `run_id = runId`
   - `status = 'RUNNING'`
   - `ended_at IS NULL`

2. **Branch identification**: For each running operation, parse the `op_id` using `OpId.parse()`. If `opId.branchIndex == null`, the operation is not a branch-scoped operation and is skipped.

3. **Checkpoint lookup**: For each running branch, fetch the last durable checkpoint from `cursorStore.getStageIndex(branchOpId)` (via `ReplayCursorStore`). If no cursor exists for that branch, the branch was opened but no step was durable-checkpointed — return `lastCheckpointStageIndex = 0`.

4. **Return type**: `ReconciledBranch` with fields:
   - `opId: String` — the branch's operation ID
   - `lastStage: Int` — the stage index of the last durable checkpoint
   - `status: success | needsReattach | stuck`
   - `suggestedAction: String` — human-readable action description

5. **Race semantics**: If a second concurrent call to `reconcileRunningOperations` arrives while the first is still running, the second caller receives an empty list (lock is held). This prevents duplicate re-attachment attempts.

### Status Values

| Status | Meaning |
|--------|---------|
| `success` | Branch completed normally (no RUNNING row found) |
| `needsReattach` | Branch was left RUNNING; last checkpoint found |
| `stuck` | Branch was left RUNNING; last checkpoint is older than the configured timeout (default: 30 minutes) |

## Alternatives Considered

1. **Optimistic resume**: Skip the reconciliation scan and immediately try to resume from the last known cursor position. Rejected because it risks re-executing steps that already completed, violating the no-replay invariant (EC-6).

2. **Blocking re-attachment**: `reconcileRunningOperations` blocks until all re-attached branches have re-completed. Rejected because it mixes identification and execution concerns; the reconciler should only identify, not execute.

3. **Polling-based detection**: Periodically poll the journal for new RUNNING rows during execution. Rejected because it adds unnecessary overhead during normal execution and requires a background thread.

## Consequences

- Kill+resume is deterministic: no replay of completed branches.
- The reconciler is fail-closed: if it cannot determine the checkpoint, it returns `stuck` rather than guessing.
- Concurrent reconciler calls are serialized via lock — no duplicate re-attachment.
- The `ReconciledBranch` data class provides sufficient metadata for the caller to decide the next action.

## Evidence and Provenance

- M3-R4.3 design decision from `design.md` §C3
- `UatDurable009KillResumeBranchTest` (EC-6) validates all 4 kill-resume scenarios
- ADR-0037 defines the `OpId` format used for branch identification
