package dev.rubentxu.pipeline.v2.domain.post

import dev.rubentxu.pipeline.v2.domain.StepNode
import kotlinx.serialization.Serializable

/**
 * The finalized state of one stage, as `post` sees it.
 *
 * This is a STAGE-scoped outcome, deliberately not [dev.rubentxu.pipeline.v2.domain.RunOutcome]:
 * a `post` block reacts to how ITS OWN stage finished, which is a fact the
 * coordinator already knows at the stage boundary. Reusing the run-level ADT
 * here would make every finalizer depend on stages that have not run yet.
 */
@Serializable
sealed interface StageOutcome {
    data object Succeeded : StageOutcome
    data object Unstable : StageOutcome
    data object Failed : StageOutcome
    data object Aborted : StageOutcome
    data object Skipped : StageOutcome
}

/**
 * The conditions a `post` block can declare, mirroring Jenkins.
 *
 * The set is CLOSED and ORDERED, and the order is part of the contract, not an
 * implementation detail: [PostPlanner] walks [EXECUTION_ORDER] so the same
 * program produces the same finalizer sequence on every run, on every machine,
 * forever. An unordered set here would make `post` output depend on hash order.
 */
@Serializable
enum class PostCondition {
    ALWAYS,
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    UNSUCCESSFUL,
    CLEANUP,
    ;

    companion object {
        /**
         * The versioned execution order.
         *
         * `ALWAYS` first because it is the unconditional finalizer; the
         * outcome-specific blocks follow in declaration-independent order;
         * `CLEANUP` is LAST unconditionally, so a cleanup step can rely on
         * every other finalizer having already run.
         */
        val EXECUTION_ORDER: List<PostCondition> = listOf(
            ALWAYS,
            SUCCESS,
            FAILURE,
            UNSTABLE,
            ABORTED,
            UNSUCCESSFUL,
            CLEANUP,
        )

        /**
         * The closed mapping from a durable `StageFinished.outcome` string to
         * the stage outcome the `post` planner consumes.
         *
         * This is the ONLY place the event vocabulary and the post vocabulary
         * meet, and the mapping is total over the outcomes the coordinator can
         * emit: an unknown string fails CLOSED (typed infrastructure failure
         * upstream of any finalizer) rather than being silently read as
         * success, which would skip failure blocks at the exact moment they
         * matter.
         */
        fun outcomeOf(stageFinishedOutcome: String): StageOutcome? = when (stageFinishedOutcome) {
            "success" -> StageOutcome.Succeeded
            "unstable" -> StageOutcome.Unstable
            "failed" -> StageOutcome.Failed
            "aborted" -> StageOutcome.Aborted
            "skipped" -> StageOutcome.Skipped
            else -> null
        }
    }
}

/**
 * Which blocks a given [StageOutcome] selects.
 *
 * A total, pure membership question — no sequencing, no effects. Keeping it
 * separate from [PostPlanner] means the Jenkins semantics of "which blocks fire"
 * are testable one outcome at a time, without asserting anything about order.
 */
fun PostCondition.selects(outcome: StageOutcome): Boolean = when (this) {
    PostCondition.ALWAYS -> true
    PostCondition.CLEANUP -> true
    PostCondition.SUCCESS -> outcome == StageOutcome.Succeeded
    PostCondition.FAILURE -> outcome == StageOutcome.Failed
    PostCondition.UNSTABLE -> outcome == StageOutcome.Unstable
    PostCondition.ABORTED -> outcome == StageOutcome.Aborted
    // "not successful" is the negation of success, and it EXCLUDES a skip:
    // a stage that never ran was not unsuccessful, it simply did not happen.
    PostCondition.UNSUCCESSFUL -> outcome == StageOutcome.Failed || outcome == StageOutcome.Unstable
}

/**
 * The finalizers a stage declared, keyed by the condition that guards each.
 *
 * A LIST of [StepNode] per condition rather
 * than one body per condition, because Jenkins allows several `always { }`
 * blocks and their order inside the block is the author's. The condition is
 * the only grouping key.
 *
 * The plan carries the REAL nodes, not references to them: there is no second
 * `BodyRef -> node` registry to consult, so a ref without a binding would be
 * exactly the fake-reference path the Step Constitution forbids. The same
 * [StepNode] values execute identically on a
 * durable rerun, which is the property replay requires.
 *
 * Generic over the node type so the pure planner never needs to know how the
 * IR spells its nodes; the coordinator instantiates it at [StepNode].
 */
@Serializable
data class PostPlan(
    val bodies: Map<PostCondition, List<StepNode>> = emptyMap(),
) {
    init {
        require(bodies.keys.all { it in PostCondition.EXECUTION_ORDER }) {
            "PostPlan holds an unknown condition: ${bodies.keys - PostCondition.EXECUTION_ORDER.toSet()}"
        }
    }

    val isEmpty: Boolean get() = bodies.isEmpty()
}

/**
 * The pure outcome -> ordered finalizers decision.
 *
 * Total by construction: every [StageOutcome] and every [PostPlan] yields a
 * list, never null and never an exception. The ordering comes from
 * [PostCondition.EXECUTION_ORDER], not from map iteration, so the result is
 * deterministic for identical inputs.
 */
object PostPlanner {

    fun plan(plan: PostPlan, outcome: StageOutcome): List<StepNode> =
        PostCondition.EXECUTION_ORDER
            .filter { condition -> condition.selects(outcome) }
            .flatMap { condition -> plan.bodies[condition].orEmpty() }

    /**
     * Which declared conditions fire and which are excluded, in execution
     * order. Pure projection of the same law [plan] applies, exposed for the
     * decision event so "what will run and why" is observable without
     * re-deriving it from the step stream.
     */
    fun selectedConditions(plan: PostPlan, outcome: StageOutcome): List<PostCondition> =
        PostCondition.EXECUTION_ORDER.filter { condition -> plan.bodies.containsKey(condition) && condition.selects(outcome) }

    fun skippedConditions(plan: PostPlan, outcome: StageOutcome): List<PostCondition> =
        PostCondition.EXECUTION_ORDER.filter { condition -> plan.bodies.containsKey(condition) && !condition.selects(outcome) }
}
