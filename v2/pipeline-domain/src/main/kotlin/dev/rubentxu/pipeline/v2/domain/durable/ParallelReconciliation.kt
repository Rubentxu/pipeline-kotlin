package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Pure snapshot of one parallel branch's durable child evidence, grouped
 * by branch index. Each row is a child step of the branch.
*/
data class ParallelBranchChildSnapshot(
    val branchIndex: Int,
    val childIndex: Int,
    val status: OperationStatus,
)

/**
 * Durable snapshot of the parallel aggregate control row, when present.
 * [semanticOutcome] carries the exact typed outcome stored at close time
 * (lossless carrier); it is null for a row that never closed.
*/
data class ParallelAggregateSnapshot(
    val id: ParallelAggregateId,
    val fingerprint: Fingerprint,
    val status: OperationStatus,
    val semanticOutcome: BranchTerminal?,
)

/**
 * OBSERVED durable state — pure function of journal facts + structure.
 * Observation, never a decision.
*/
sealed interface ParallelReconciliationState {
    /** No aggregate row, no branch children. */
    data object Fresh : ParallelReconciliationState

    /** Aggregate row RUNNING, zero branch child rows. */
    data object AdmittedNotLaunched : ParallelReconciliationState

    /**: some branches terminal, others not. */
    data class PartiallyCompleted(
        val completedBranches: Map<Int, BranchTerminal>,
        val incompleteBranches: List<Int>,
    ) : ParallelReconciliationState

    /** Every branch has terminal children, aggregate row stale. */
    data class AllChildrenTerminal(
        val branchOutcomes: Map<Int, BranchTerminal>,
    ) : ParallelReconciliationState

    /** Aggregate row terminal with an exact semantic outcome. */
    data class AggregateTerminal(val outcome: BranchTerminal) : ParallelReconciliationState

    /** Structural fingerprint mismatch. */
    data class Diverged(val reason: String) : ParallelReconciliationState
}

/**
 * DECISION — what the coordinator must DO. No effect happens inside the ADT.
 * Every variant makes the executed/not-executed branch sets explicit.
*/
sealed interface ParallelDecision {
    /** Launch the listed branches (all). */
    data class Start(val branches: List<Int>) : ParallelDecision

    /**: launch ONLY the listed branches; all others must not dispatch. */
    data class ResumeBranches(val branches: List<Int>) : ParallelDecision

    /** Aggregate terminal success — zero executions anywhere. */
    data object ReuseSuccess : ParallelDecision

    /** Aggregate terminal unstable — zero executions anywhere. */
    data object ReuseUnstable : ParallelDecision

    /** Aggregate terminal failure — zero executions anywhere. */
    data object ReuseFailure : ParallelDecision

    /** Fold children into an aggregate result, persist, zero executions. */
    data class CloseFromChildren(val outcome: BranchTerminal) : ParallelDecision

    /** Structural divergence — fail closed before any effect. */
    data class RejectDivergence(val reason: String) : ParallelDecision

    /**
     * Durable evidence cannot reconstruct the exact semantic outcome
     * (e.g. a child row FAILED that may hide an Unstable fold, with no
     * lossless carrier). ambiguity != Failure. Fail closed, zero executions.
*/
    data class RejectAmbiguousOutcome(val reason: String) : ParallelDecision
}

/**
 * Pure input to the parallel reconciler: observed durable facts only
 * No clock, no journal handles, no effects.
*/
data class ParallelReconciliationInput(
    val aggregateId: ParallelAggregateId,
    val currentFingerprint: Fingerprint,
    val aggregateRow: ParallelAggregateSnapshot?,
    /** Structural branch identity for the CURRENT compiled parallel stage. */
    val branchCount: Int,
    /** Per-branch child durable rows, grouped by branch index (current facts). */
    val childrenByBranch: Map<Int, List<ParallelBranchChildSnapshot>>,
)
