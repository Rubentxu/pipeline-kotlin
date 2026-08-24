package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.application.durable.OpId

/**
 * Result of reconciling a single branch that was left RUNNING after a crash.
 *
 * Contains the branch's last known [opId], the [OperationStatus] it was in
 * when the crash occurred, and metadata for re-attachment.
 *
 * @property opId The last known operation ID for this branch.
 * @property lastStage The stage index of the last durable checkpoint.
 * @property status The reconciliation status: success, needsReattach, or stuck.
 * @property suggestedAction Human-readable description of the recommended action.
 */
data class ReconciledBranch(
    val opId: String,
    val lastStage: Int,
    val status: ReconciliationStatus,
    val suggestedAction: String,
)

/**
 * Status of a branch reconciliation result.
 */
enum class ReconciliationStatus {
    /** Branch completed normally (no RUNNING row found or already terminal). */
    SUCCESS,
    /** Branch was left RUNNING; a checkpoint was found; re-attachment is possible. */
    NEEDS_REATTACH,
    /** Branch was left RUNNING for longer than the configured timeout; manual intervention may be required. */
    STUCK,
}

/**
 * Reconciles branches that were left RUNNING after a crash.
 *
 * Scans the [OperationJournal] for rows where:
 * - status = RUNNING
 * - opId contains a branchIndex (format: `...-b{N}`)
 *
 * For each running branch, fetches the last durable checkpoint from [ReplayCursorStore]
 * and returns a [ReconciledBranch] with the reconciliation findings.
 *
 * ## ADR-0038 Re-Attachment Contract
 *
 * This class implements the ADR-0038 contract for branch re-attachment:
 * - Queries `operations WHERE ended_at IS NULL` (RUNNING rows)
 * - Returns `ReconciledBranch(opId, lastStage, status: success|needsReattach|stuck, suggestedAction)`
 * - Fail-closed: if checkpoint cannot be determined, marks as NEEDS_REATTACH with stage 0
 *
 * ## Concurrency
 *
 * Concurrent calls to [reconcileRunningOperations] are serialized via an internal lock
 * to prevent duplicate re-attachment attempts. The second caller blocks until the first
 * completes (no empty-list shortcut).
 *
 * @param opJournal The operation journal to scan.
 * @param cursorStore The replay cursor store for checkpoint lookup.
 * @param clock The clock for timeout detection.
 * @param stuckThresholdMinutes Threshold in minutes beyond which a RUNNING branch
 *        is considered STUCK (default: 30 minutes).
 */
class BranchReconciler(
    private val opJournal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val clock: Clock,
    private val stuckThresholdMinutes: Long = 30L,
) {
    private val reconciliationLock = Any()

    /**
     * Scans for branches and returns reconciliation results for ALL branches found.
     *
     * For each branch operation found in the journal:
     * - If it has a RUNNING row → NEEDS_REATTACH (unless stuck) or STUCK
     * - If it has a terminal row (SUCCEEDED/FAILED) → SUCCESS
     *
     * This ensures walkParallelFrame can skip completed branches even when they
     * have no RUNNING row (normal completion before kill).
     *
     * @param runId The pipeline run to reconcile.
     * @return A list of [ReconciledBranch] results, one per branch found.
     */
    suspend fun reconcileRunningOperations(runId: String): List<ReconciledBranch> {
        return synchronized(reconciliationLock) {
            val allOps = opJournal.listForRun(runId)

            // Collect all branch indices that have any journal entry
            val allBranchIndices = allOps
                .mapNotNull { op -> OpId.parse(op.id)?.branchIndex }
                .toSet()

            allBranchIndices.mapNotNull { branchIndex ->
                // Find the RUNNING operation for this branch (if any)
                val runningOp = allOps.firstOrNull {
                    OpId.parse(it.id)?.branchIndex == branchIndex &&
                    it.status == OperationStatus.RUNNING
                }

                if (runningOp != null) {
                    // Branch is still RUNNING — check if stuck
                    val cursor = cursorStore.load(runId)
                    val lastCheckpointStageIndex = cursor?.stageIndex ?: 0

                    val startedAt = opJournal.getStartedAt(runningOp.id, runningOp.attempt)
                    val isStuck = if (startedAt != null) {
                        val elapsedMinutes = (clock.now().toEpochMilli() - startedAt) / 60_000L
                        elapsedMinutes >= stuckThresholdMinutes
                    } else {
                        false
                    }

                    val status = when {
                        isStuck -> ReconciliationStatus.STUCK
                        else -> ReconciliationStatus.NEEDS_REATTACH
                    }

                    val suggestedAction = when (status) {
                        ReconciliationStatus.SUCCESS -> "No action needed."
                        ReconciliationStatus.NEEDS_REATTACH ->
                            "Re-attach branch $branchIndex at stage $lastCheckpointStageIndex. " +
                            "Branch was left RUNNING but has a valid checkpoint."
                        ReconciliationStatus.STUCK ->
                            "Branch $branchIndex is stuck (RUNNING for more than $stuckThresholdMinutes minutes). " +
                            "Manual intervention may be required."
                    }

                    ReconciledBranch(
                        opId = runningOp.id,
                        lastStage = lastCheckpointStageIndex,
                        status = status,
                        suggestedAction = suggestedAction,
                    )
                } else {
                    // No RUNNING row — branch completed normally (SUCCEEDED/FAILED)
                    val terminalOp = allOps.firstOrNull {
                        OpId.parse(it.id)?.branchIndex == branchIndex
                    }
                    ReconciledBranch(
                        opId = terminalOp?.id ?: "unknown",
                        lastStage = 0,
                        status = ReconciliationStatus.SUCCESS,
                        suggestedAction = "No action needed. Already completed.",
                    )
                }
            }
        }
    }
}
