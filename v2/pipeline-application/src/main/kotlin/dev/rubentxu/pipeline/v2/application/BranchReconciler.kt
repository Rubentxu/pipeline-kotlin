package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.application.durable.OpId

/**
 * Result of reconciling a single branch that was left RUNNING after a crash.
 *
 * Contains the branch's last known [OpId] and the [OperationStatus] it was in
 * when the crash occurred.
 *
 * @property branchIndex The index of the branch that was reconciled.
 * @property opId The last known OpId for this branch.
 * @property status The status at the time of the crash (typically RUNNING).
 * @property lastCheckpointStageIndex The stage index of the last durable checkpoint.
 */
data class ReconciledBranch(
    val branchIndex: Int,
    val opId: String,
    val status: dev.rubentxu.pipeline.v2.domain.durable.OperationStatus,
    val lastCheckpointStageIndex: Int,
)

/**
 * Reconciles branches that were left RUNNING after a crash.
 *
 * Scans the [OperationJournal] for rows where:
 * - status = RUNNING
 * - opId contains a branchIndex (format: `...-b{N}`)
 *
 * For each running branch, fetches the last durable checkpoint and returns
 * a [ReconciledBranch] describing what was found and the checkpoint position.
 *
 * ## M3-R4.3 upgrade path
 *
 * This class is the foundation for proper branch crash recovery. In M3-R4.2,
 * it is a stub that identifies RUNNING branches but does not take recovery action.
 * M3-R4.3 will extend this to perform actual branch re-attachment.
 *
 * @param opJournal The operation journal to scan.
 * @param clock The clock for timestamps.
 */
class BranchReconciler(
    private val opJournal: OperationJournal,
    private val clock: Clock,
) {
    /**
     * Scans for branches left RUNNING after a crash and returns reconciliation results.
     *
     * For each RUNNING operation with a branchIndex in its opId, this method:
     * 1. Parses the opId to extract branchIndex
     * 2. Determines the last durable checkpoint (stage index) for that branch
     * 3. Returns a [ReconciledBranch] with the reconciliation findings
     *
     * @param runId The pipeline run to reconcile.
     * @return A list of [ReconciledBranch] results, one per RUNNING branch found.
     */
    suspend fun reconcileRunningOperations(runId: String): List<ReconciledBranch> {
        val runningOps = opJournal.listForRun(runId)
            .filter { it.status == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING }

        return runningOps.mapNotNull { op ->
            // Parse opId to check if this is a branch operation
            val parsedOpId = OpId.parse(op.id) ?: return@mapNotNull null

            // Only process branch-scoped operations
            val branchIndex = parsedOpId.branchIndex ?: return@mapNotNull null

            // Determine the last durable checkpoint for this branch
            // In M3-R4.2, we use the op's stage index as the checkpoint
            // M3-R4.3 will look up the actual last durable checkpoint from events
            val lastCheckpointStageIndex = parsedOpId.stageIndex

            ReconciledBranch(
                branchIndex = branchIndex,
                opId = op.id,
                status = op.status,
                lastCheckpointStageIndex = lastCheckpointStageIndex,
            )
        }
    }
}
