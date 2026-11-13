package dev.rubentxu.pipeline.v2.domain.post

import dev.rubentxu.pipeline.v2.domain.StepNode
import kotlinx.serialization.Serializable

/**
 * The finalized state of one stage, as `post` sees it.
 * This is a STAGE-scoped outcome, deliberately not [dev.rubentxu.pipeline.v2.domain.RunOutcome]:
 * a `post` block reacts to how ITS OWN stage finished, which is a fact the
 * coordinator already knows at the stage boundary. Reusing the run-level ADT
 * here would make every finalizer depend on stages that have not run yet.
*/
@Serializable
sealed interface StageOutcome {
    /**
     * The stage finished, and every step that ran reported success.
     *
     * The finalizers guarded by [PostCondition.SUCCESS], by
     * [PostCondition.ALWAYS] and by [PostCondition.CLEANUP] run;
     * [PostCondition.UNSUCCESSFUL] does not.
     */
    data object Succeeded : StageOutcome

    /**
     * The stage finished with a warning: it ran, and something went wrong
     * without failing it.
     *
     * An unstable stage counts as unsuccessful, so the finalizers guarded by
     * [PostCondition.UNSTABLE], by [PostCondition.UNSUCCESSFUL], by
     * [PostCondition.ALWAYS] and by [PostCondition.CLEANUP] run.
     */
    data object Unstable : StageOutcome

    /**
     * The stage finished with a failure.
     *
     * The finalizers guarded by [PostCondition.FAILURE], by
     * [PostCondition.UNSUCCESSFUL], by [PostCondition.ALWAYS] and by
     * [PostCondition.CLEANUP] run.
     */
    data object Failed : StageOutcome

    /**
     * The stage was stopped before it finished.
     *
     * An aborted stage is not an unsuccessful one: the finalizers guarded by
     * [PostCondition.ABORTED], by [PostCondition.ALWAYS] and by
     * [PostCondition.CLEANUP] run, and [PostCondition.UNSUCCESSFUL] does not.
     */
    data object Aborted : StageOutcome

    /**
     * The stage never ran, because a condition skipped it.
     *
     * A stage that did not happen did not fail either: only the
     * unconditional finalizers, [PostCondition.ALWAYS] and
     * [PostCondition.CLEANUP], run.
     */
    data object Skipped : StageOutcome
}

/**
 * The conditions a `post` block can declare, mirroring Jenkins.
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

    /**
     * The order the planner walks, and the mapping from a durable outcome
     * word to the stage outcome it names.
     *
     * Walking that order is what makes the same program produce the same
     * finalizer sequence on every run. The mapping is total and refuses to
     * guess: a word it does not know yields `null` instead of being read as a
     * success, which would skip the failure finalizers at the moment they
     * matter. No production caller reads that mapping: the planner receives
     * the typed outcome directly.
     */
    companion object {
        /**
         * The versioned execution order.
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
         * **NO PRODUCTION CALLER REMAINS.**
         * This used to be the only place where the event vocabulary and the post
         * vocabulary met: `StageExecutionEngine.runPostBlock` took the outcome as a
         * `String` and re-parsed it here, so a decision already made as a typed ADT
         * was stringified, carried across a function boundary and parsed back. The
         * type system could not see any of it. The seam also failed OPEN in the other
         * direction — it accepted five tokens while `StageFinished` produced three — so
         * the planner could read states the producer never emitted.
         * The planner now receives the ADT directly and this call is gone from the
         * runtime. It survives only because `pipeline-domain` is published ABI and its
         * Removal is a compatibility decision, not a mechanical one
         * NOTHING PREVENTS IT BEING REUSED. This comment used to say a fitness test pinned
         * that no production source calls it again, "because a helper with no callers is exactly the kind
         * of thing that gets helpfully reused". That file is gone, and the guard it
         * described was a SOURCE-TEXT FITNESS -- a scan of the tree -- which
         * AGENTS.md bans inside unit tests. The honest position is the one the
         * paragraph below already takes for the helper itself: it is kept total and
         * fail-closed PRECISELY because nothing keeps it unreachable. If it is reused,
         * its first job is to refuse what the typed ADT refuses, not to succeed.
         * It is kept total and fail-closed rather than deleted for as long as it exists:
         * an unknown string returns `null` instead of being read as success, which would
         * skip failure blocks at the exact moment they matter.
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
 * The outcome a PIPELINE's `post` reacts to.
 *
 * A stage's `post` reacts to how ITS OWN stage finished, and that is a fact
 * the coordinator knows at the stage boundary. The pipeline's `post` reacts to
 * how the WHOLE RUN finished, and that is the run's own result: the four
 * words of [dev.rubentxu.pipeline.v2.domain.RunOutcome] projected onto the
 * vocabulary the planner consumes.
 *
 * The projection is TOTAL and it never guesses: every word of the run's
 * vocabulary names exactly one outcome of the post's. `Aborted` maps to
 * [StageOutcome.Aborted] and not to `Failed`, because a run that was stopped
 * is not a run that failed, and a `failure` block that fired for an aborted
 * run would report a cause nobody observed.
 */
fun dev.rubentxu.pipeline.v2.domain.RunOutcome.postOutcome(): StageOutcome =
    when (this) {
        dev.rubentxu.pipeline.v2.domain.RunOutcome.Success -> StageOutcome.Succeeded
        dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable -> StageOutcome.Unstable
        is dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure -> StageOutcome.Failed
        dev.rubentxu.pipeline.v2.domain.RunOutcome.Aborted -> StageOutcome.Aborted
    }

/**
 * Which blocks a given [StageOutcome] selects.
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
 * A LIST of [StepNode] per condition rather
 * than one body per condition, because Jenkins allows several `always { }`
 * blocks and their order inside the block is the author's. The condition is
 * the only grouping key.
 * The plan carries the REAL nodes, not references to them: there is no second
 * `BodyRef -> node` registry to consult, so a ref without a binding would be
 * exactly the fake-reference path the contract forbids. The same
 * [StepNode] values execute identically on a
 * durable rerun, which is the property replay requires.
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
