package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
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
     * Scans for branches left RUNNING after a crash and returns reconciliation results.
     *
     * For each RUNNING operation with a branchIndex in its opId, this method:
     * 1. Parses the opId to extract the runId and branchIndex
     * 2. Looks up the last durable checkpoint from [cursorStore]
     * 3. Determines if the branch is stuck (RUNNING for too long)
     * 4. Returns a [ReconciledBranch] with the reconciliation findings
     *
     * @param runId The pipeline run to reconcile.
     * @return A list of [ReconciledBranch] results, one per RUNNING branch found.
     */
    suspend fun reconcileRunningOperations(runId: String): List<ReconciledBranch> {
        return synchronized(reconciliationLock) {
            val runningOps = opJournal.listForRun(runId)
                .filter { it.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING }

            runningOps.mapNotNull { op ->
                // Parse opId to check if this is a branch operation
                val parsedOpId = OpId.parse(op.id) ?: return@mapNotNull null

                // Only process branch-scoped operations
                val branchIndex = parsedOpId.branchIndex ?: return@mapNotNull null

                // Look up the last durable checkpoint for this branch's run
                val cursor = cursorStore.load(runId)
                val lastCheckpointStageIndex = cursor?.stageIndex ?: 0

                // Check if the branch has been RUNNING for too long (stuck detection)
                val startedAt = opJournal.getStartedAt(op.id, op.attempt)
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
                    opId = op.id,
                    lastStage = lastCheckpointStageIndex,
                    status = status,
                    suggestedAction = suggestedAction,
                )
            }
        }
    }
}
