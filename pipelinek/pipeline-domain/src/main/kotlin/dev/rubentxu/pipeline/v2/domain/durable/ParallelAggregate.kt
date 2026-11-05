package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment

/**
 * Typed control identity for a parallel aggregate
 * Never a sentinel `stepIndex`/`branchIndex` inside `OpId`: the aggregate
 * is identified by run + stage + the parallel control kind. Nested parallel
 * (future) extends this with the canonical bodyPath.
 * MUST NOT embed: branch completion order, coroutine id, thread id,
 * scheduler order, wall clock.
*/
data class ParallelAggregateId(
    val runId: String,
    val stageIndex: Int,
    val bodyPath: List<BlockSegment> = emptyList(),
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(stageIndex >= 0) { "stageIndex must be >= 0" }
    }
}

/** Typed terminal branch outcome (never inferred from OperationStatus.FAILED alone). */
sealed interface BranchTerminal {
    val asText: String

    /**
     * The branch ran and every one of its steps succeeded.
     *
     * It is the outcome the fold falls back to when no branch failed and none
     * declared itself unstable.
     */
    data object Succeeded : BranchTerminal { override val asText get() = "success" }

    /**
     * The branch ran to completion and declared itself unstable.
     *
     * The work happened and something in it deserves attention, without the
     * branch having failed. It is a case of its own because the two mean
     * different things to the caller, and collapsing them would turn a
     * declared-unstable branch into a declared-failed one.
     */
    data object Unstable : BranchTerminal { override val asText get() = "unstable" }

    /**
     * The branch did not succeed.
     *
     * [message] carries the reason when there is one, and `null` when the
     * failure has nothing to say. A failure dominates the fold: an aggregate
     * with one failed branch fails, whatever the others did.
     */
    data class Failed(val message: String?) : BranchTerminal { override val asText get() = "failure" }
}

/** AwaitAll semantics: Failure dominates, else Unstable, else Success (lowest branch index wins ties). */
fun foldAwaitAll(outcomes: Map<Int, BranchTerminal>): BranchTerminal {
    val failure = outcomes.entries.filter { it.value is BranchTerminal.Failed }.minByOrNull { it.key }
    if (failure != null) return failure.value
    val unstable = outcomes.entries.filter { it.value is BranchTerminal.Unstable }.minByOrNull { it.key }
    if (unstable != null) return unstable.value
    return BranchTerminal.Succeeded
}
