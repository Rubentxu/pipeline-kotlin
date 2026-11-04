package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame

/**
 * Interface for persistent replay cursor storage.
 *
 * ## Contract
 *
 * This interface is stable for consumption.
 *
 */
interface ReplayCursorStore {
    /**
     * Loads the replay cursor for a given [runId].
     *
     * @param runId The run identifier.
     * @return The [ReplayCursor] if found, or `null` if no cursor exists for this run.
     */
    fun load(runId: String): ReplayCursor?

    /**
     * Advances the replay cursor to the given [opId] and [stageIndex].
     *
     * This operation is idempotent: if the cursor already points to the same
     * or a later position, the update is a no-op.
     *
     * ## mitigation reminder
     *
     * This function MUST be called only AFTER [OperationJournal.append]
     * has returned successfully. Do NOT call it before the append.
     *
     * @param runId      The run identifier.
     * @param opId       The ID of the operation that was just successfully journaled.
     * @param stageIndex The stage index at which execution should resume.
     */
    fun advance(runId: String, opId: String, stageIndex: Int)

    /**
     * Advances the replay cursor past a parallel frame, using the maximum
     * stage index from all branch results as the next cursor position.
     *
     * This is the join barrier for parallel frames. It computes
     * `max(branchResults.map { it.stageIndex })` and atomically writes
     * the new cursor position using the same idempotent CAS semantics as [advance].
     *
     * @param runId The run identifier, used to derive cursor keys.
     * @param frame The parallel frame that just completed.
     * @param branchResults The results of each branch, containing the stage index.
     * @param explicitMaxStageIndex Optional explicit max stage index. When non-null,
     *        overrides the computed max from branchResults. Use this when the caller
     *        knows the stage index more accurately than branchResults can provide.
     * @return The new [StageIndex] after advancing.
     */
    fun advancePastParallelFrame(
        runId: String,
        frame: ParallelFrame,
        branchResults: List<BranchExecutionResult>,
        explicitMaxStageIndex: Int? = null,
    ): StageIndex
}

/**
 * Result of a single branch execution, used by [ReplayCursorStore.advancePastParallelFrame].
 *
 * This is a minimal data class defined in the events module to avoid a dependency
 * from events on the step-sdk runtime module (where [BranchResult] lives).
 *
 * @property branchIndex The index of the branch.
 * @property stageIndex The stage index after this branch completed.
 */
data class BranchExecutionResult(
    val branchIndex: Int,
    val stageIndex: Int,
)

/**
 * A wrapper around [Int] representing a stage index in the replay cursor.
 */
@JvmInline
value class StageIndex(val value: Int)
