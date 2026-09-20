package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.CloseFromChildren
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectAmbiguousOutcome
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ResumeBranches
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.Start

/**
 * WU-LPR-023 — Pure plan port for parallel branch dispatch.
 *
 * ## Why this exists
 *
 * The [ParallelReconciler] returns a [ParallelDecision] that says WHAT the
 * durable state implies for the parallel aggregate (Start fresh / Resume
 * specific branches / Close from existing children / Reuse terminal outcome /
 * Reject divergence). The [BranchInvoker] translates that decision into a
 * [BranchPlan] that says WHAT the runner must DO, with names attached.
 *
 * Splitting the two layers keeps the parallel path deterministic: the
 * reconciler owns "what is the truth", the invoker owns "what should happen
 * now", and the runner (coordinator) owns "perform the effects in parallel".
 *
 * ## What this is NOT
 *
 *  - NOT an effect. The plan is a typed value; the runner is the effectful
 *    component that materializes it (launches branches, joins via
 *    `supervisorScope`, persists child rows).
 *  - NOT a StepKey branch. The mapping is closed over the [ParallelDecision]
 *    ADT — adding a new decision case forces this `when` to be revisited
 *    (compile-time exhaustiveness check).
 *  - NOT a thread/coroutine primitive. Concurrency primitives (scope choice,
 *    dispatcher) are the runner's concern. The plan names the branches; the
 *    runner chooses HOW to launch them.
 *
 * ## Migration note (post WU-LPR-023)
 *
 * The plan port defined here is the seam the canonical coordinator will
 * consume once it migrates its inline `runParallelStage` loop behind an
 * engine (this is part of WU-LPR-024, InvocationEngine seam). Until then,
 * the canonical `CanonicalDurableRunCoordinator.runParallelStage` continues
 * to inline the branch dispatch and join, and this port is a pure contract
 * test target.
 */
fun interface BranchInvoker {
    /**
     * `(decision, branches) -> BranchPlan`. Pure, total, no effects.
     *
     * @param decision The reconciler's verdict on the parallel aggregate.
     * @param branches The named branches of the parallel stage, indexed by
     *   the integer in [StageBody.Parallel.branches]. The names are carried
     *   into the plan so the runner can emit `ParallelBranchStarted(name=...)`
     *   events with stable human-readable identifiers.
     */
    fun plan(decision: ParallelDecision, branches: List<NamedBranch>): BranchPlan
}

/**
 * Named branch: integer position in `StageBody.Parallel.branches` plus the
 * stable human-readable name (DSL `branch("name") { ... }`). Carrying both
 * keeps the runner's events deterministic and the plan self-describing.
 */
data class NamedBranch(val index: Int, val name: String) {
    init { require(index >= 0) { "NamedBranch.index must be >= 0, got $index" } }
    init { require(name.isNotBlank()) { "NamedBranch.name must not be blank" } }
}

/**
 * Pure plan for parallel branch dispatch. Closed ADT over the legal next
 * actions a runner can take.
 *
 * Mapping (decision → plan):
 *
 *  - [Start] → [BranchPlan.LaunchAll] with the listed branches materialized
 *    against the named list (fail-closed: indices out of range → empty list).
 *  - [ResumeBranches] → [BranchPlan.Resume] with the listed branches
 *    materialized.
 *  - [ReuseSuccess] / [ReuseUnstable] / [ReuseFailure] / [CloseFromChildren] /
 *    [RejectDivergence] / [RejectAmbiguousOutcome] → [BranchPlan.NoOp]
 *    (the runner closes the aggregate from existing durable state, zero
 *    child executions).
 */
sealed interface BranchPlan {

    /**
     * Launch the listed branches from scratch. The runner is responsible for
     * persisting the aggregate RUNNING row BEFORE any branch dispatch
     * (single-writer law, ADR-0075 §11).
     */
    data class LaunchAll(val branches: List<NamedBranch>) : BranchPlan

    /**
     * Re-attach to in-flight branches (mid-run journal evidence). No new
     * child execution; the runner resumes from existing durable state.
     */
    data class Resume(val branches: List<NamedBranch>) : BranchPlan

    /**
     * The aggregate is closed (terminal) without launching anything. The
     * runner persists the closure row, emits `StageFinished`, and returns.
     */
    data object NoOp : BranchPlan
}

/**
 * WU-LPR-023 — Reference implementation of [BranchInvoker].
 *
 * Translation table (decision → plan):
 *
 * | Decision                       | Plan                       |
 * |--------------------------------|----------------------------|
 * | `Start(branchIndices)`         | `LaunchAll(resolve(branchIndices, branches))` |
 * | `ResumeBranches(branchIndices)`| `Resume(resolve(branchIndices, branches))`   |
 * | `ReuseSuccess`                 | `NoOp`                                        |
 * | `ReuseUnstable`                | `NoOp`                                        |
 * | `ReuseFailure`                 | `NoOp`                                        |
 * | `CloseFromChildren(outcome)`   | `NoOp`                                        |
 * | `RejectDivergence(reason)`     | `NoOp`                                        |
 * | `RejectAmbiguousOutcome(reason)`| `NoOp`                                       |
 *
 * Indices in the decision's `branches` list are filtered against the named
 * branches: out-of-range indices are dropped silently (the reconciler is
 * authoritative for which branches to dispatch; the invoker only resolves
 * them). The runner never sees an invalid index.
 *
 * Total over the decision ADT — adding a decision case forces this `when`
 * to be revisited (compile-time exhaustiveness check).
 */
object DefaultBranchInvoker : BranchInvoker {
    override fun plan(decision: ParallelDecision, branches: List<NamedBranch>): BranchPlan =
        when (decision) {
            is Start -> BranchPlan.LaunchAll(resolve(decision.branches, branches))
            is ResumeBranches -> BranchPlan.Resume(resolve(decision.branches, branches))
            is ReuseSuccess -> BranchPlan.NoOp
            is ReuseUnstable -> BranchPlan.NoOp
            is ReuseFailure -> BranchPlan.NoOp
            is CloseFromChildren -> BranchPlan.NoOp
            is RejectDivergence -> BranchPlan.NoOp
            is RejectAmbiguousOutcome -> BranchPlan.NoOp
        }

    private fun resolve(indices: List<Int>, branches: List<NamedBranch>): List<NamedBranch> =
        indices.mapNotNull { i -> branches.getOrNull(i) }
}
