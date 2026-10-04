package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.post.PostPlanner
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.time.Instant
import java.util.UUID

/**
 * TRAIN H4 / PR-020 — what a stage DOES, out of the run loop.
 *
 * The coordinator owns the run: its bookends, the stage loop, and what the run does
 * next. This engine owns a single stage once the run has decided to start it: its
 * linear body, its continuation, and its `post` finalizers.
 *
 * The hard rule it respects is that a collaborator may not write `return@run` or
 * `continue@stagesLoop`. A stage can therefore do only two things to a run: report
 * a typed [StageVerdict], and hand back the context it left behind. Every path that
 * used to write a non-local return is now one of those two cases, which is what lets
 * the run loop be read as a loop.
 *
 * A stage is not a Step. Its identity is the stage, its finalizers are a second
 * dispatch surface with their own durable key space, and its outcome is chosen by a
 * planner rather than a handler. Keeping that here stops it leaking into the run.
 */
internal class StageExecutionEngine(
    private val stepDispatch: StepDispatchEngine,
    private val runLifecycle: RunLifecycleEngine,
    private val eventSink: EventSink,
) {

    /**
     * What a finished stage obliges the run to do. Closed, because "carry on" and
     * "abort with this typed reason" are opposite obligations, and the context a stage
     * leaves behind must travel with BOTH of them: a stage can open a scope frame that
     * the next stage inherits, so dropping it on the abort path would silently lose it.
     */
    sealed interface StageVerdict {
        /**
         * The stage completed with [outcome] as its declared result. [context] is what it
         * left behind for the next stage.
         */
        data class Completed(
            val outcome: StageOutcome,
            val context: ExecutionContext,
        ) : StageVerdict

        /** The run must abort with [failure]. [context] is the state at the moment of abort. */
        data class Abort(
            val failure: PipelineFailure,
            val context: ExecutionContext,
        ) : StageVerdict
    }

    /** The declared result of a stage that reached its end. A stage outcome, not a Step outcome. */
    enum class StageOutcome(val text: String) {
        SUCCESS("success"),
        UNSTABLE("unstable"),
        FAILED("failed"),
        SKIPPED("skipped"),
    }

    /**
     * Runs one stage body: the stage bookend, the declared steps, the continuation
     * after each, and the `post` finalizers for whichever outcome the stage reached.
     *
     * Ordering law, preserved verbatim: StageStarted < steps < PostConditionSelected <
     * post steps < StageFinished. A failing finalizer fails the RUN but never rolls back
     * the remaining finalizers, because cleanup is exactly the code that must be allowed
     * to run after bad news.
     */
    suspend fun runLinearStage(
        stage: StageNode,
        stageIndex: Int,
        steps: List<StepNode>,
        stageShOptions: ShOptions,
        runId: RunId,
        ambient: ExecutionContext,
    ): StageVerdict {
        runLifecycle.stageStarted(runId, stageIndex, stage.name)
        var context = ambient
        var stageUnstable = false
        for (stepIndex in steps.indices) {
            val dispatched = stepDispatch.dispatch(
                steps[stepIndex], runId, stage.name, stageIndex, stepIndex, stageShOptions, emptyList(), context,
            )
            context = dispatched.context
            // R14: the stage fold needs the OUTCOME, so it projects it explicitly and leaves the
            // carrier alone. See StepDispatchEngine.Dispatched for why no `outcome` shortcut exists.
            when (val continuation = runLifecycle.decideStageContinuation(
                dispatched.result.outcome, stage.name, runId.value, context,
            )) {
                CanonicalContinuation.Continue -> Unit
                CanonicalContinuation.ContinueUnstable -> {
                    runLifecycle.fold(dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable)
                    stageUnstable = true
                }
                is CanonicalContinuation.Abort -> {
                    // S2-B: a step failure is still a stage outcome. The
                    // failure/always/cleanup finalizers MUST run before the run
                    // aborts, or `post { failure { ... } }` would be dead code
                    // for the exact case it exists for. A finalizer that itself
                    // fails replaces the reason, because the run is being failed
                    // either way and the later failure is the more recent truth.
                    val postFailure = runPostBlock(
                        stage = stage,
                        stageIndex = stageIndex,
                        stageFinishedOutcome = StageOutcome.FAILED.text,
                        runId = runId,
                        stageShOptions = stageShOptions,
                        ambient = context,
                    )
                    val failure = postFailure ?: continuation.failure
                    return StageVerdict.Abort(failure, context)
                }
            }
        }
        // S2-B: the stage's own steps decided the outcome; the `post`
        // block finalizes the stage BEFORE its StageFinished, so the
        // terminal record already includes the finalizers' work.
        val outcome = if (stageUnstable) StageOutcome.UNSTABLE else StageOutcome.SUCCESS
        runPostBlock(
            stage = stage,
            stageIndex = stageIndex,
            stageFinishedOutcome = outcome.text,
            runId = runId,
            stageShOptions = stageShOptions,
            ambient = context,
        )?.let { failure -> return StageVerdict.Abort(failure, context) }
        runLifecycle.stageFinished(runId, stageIndex, stage.name, outcome.text)
        return StageVerdict.Completed(outcome, context)
    }

    /**
     * S2-B: interprets a stage's declared `post` block for a KNOWN stage
     * outcome. The single stage-level finalizer seam of the canonical
     * coordinator.
     *
     * Decision/interpretation split (Step Constitution rule 7):
     *  - the PURE decision is `PostCondition.outcomeOf` + `PostPlanner.plan`:
     *    which blocks fire, in which order, for which outcome. No I/O;
     *  - the INTERPRETATION is this method: dispatching the selected nodes
     *    through the SAME canonical `dispatch` spine as stage-body steps, each
     *    with a deterministic `post:<CONDITION>` [BlockSegment] so journal
     *    identity, replay and divergence behave exactly like any other step.
     *
     * Ordering law: StageStarted < ... < [PostConditionSelected] < post steps
     * < StageFinished. A declared-but-not-selected block appears only in the
     * event's `skippedConditions`, never as a silent no-op.
     *
     * Failure containment: a FAILING finalizer fails the RUN (typed USER
     * failure naming the stage and condition) but never rolls back the
     * remaining finalizers — cleanup is exactly the code that must be allowed
     * to run after bad news. A post failure therefore ABORTS the run with the
     * failing finalizer's message; StageFinished is not emitted for a run the
     * coordinator is failing.
     *
     * @return the typed failure to abort the run with, or null when every
     *         selected finalizer succeeded (including the empty-plan no-op).
     */
    /**
     * The stage finalizer seam, exposed because a run needs it for the two stage shapes
     * that never enter [runLinearStage]: a SKIPPED stage, whose finalizers are the ones
     * the planner selects for Skipped, and a PARALLEL stage, whose aggregate outcome is
     * decided elsewhere. Both are stage outcomes, so both finalize the same way.
     */
    suspend fun runPostBlock(
        stage: StageNode,
        stageIndex: Int,
        stageFinishedOutcome: String,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? {
        val postSpec = stage.post ?: return null
        if (postSpec.isEmpty) return null
        val stageOutcome = PostCondition.outcomeOf(stageFinishedOutcome)
            ?: return PipelineFailure(
                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                "post block for stage '${stage.name}' received unknown stage outcome '$stageFinishedOutcome'; " +
                    "refusing to select finalizers on an unreadable outcome",
            )
        val plan = postSpec.toPostPlan()
        // The pure decision, made ONCE: if the planner selects nothing for this
        // outcome, the block is inert for this stage and emits nothing.
        if (PostPlanner.plan(plan, stageOutcome).isEmpty()) return null

        eventSink.append(
            dev.rubentxu.pipeline.v2.events.PostConditionSelected(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stageName = stage.name,
                stageOutcome = stageFinishedOutcome,
                selectedConditions = PostPlanner.selectedConditions(plan, stageOutcome).map { it.name },
                skippedConditions = PostPlanner.skippedConditions(plan, stageOutcome).map { it.name },
            ),
        )

        // One decision source: the planner's own condition projection drives
        // the walk, so the event lists and the executed nodes can never
        // disagree about which blocks fired.
        var dispatched = 0
        for (condition in PostPlanner.selectedConditions(plan, stageOutcome)) {
            val nodes = plan.bodies[condition].orEmpty()
            for ((index, node) in nodes.withIndex()) {
                val bodyPath = listOf(BlockSegment("post:${condition.name}:$index"))
                val dispatchedStep = stepDispatch.dispatch(
                    step = node,
                    runId = runId,
                    stageName = stage.name,
                    stageIndex = stageIndex,
                    stepIndex = POST_BASE_STEP_INDEX + dispatched,
                    stageShOptions = stageShOptions,
                    bodyPath = bodyPath,
                    executionContext = ambient,
                )
                dispatched++
                if (dispatchedStep.result.outcome !is StepOutcome.Success) {
                    val reason = "post ${condition.name} finalizer of stage '${stage.name}' failed"
                    return PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, reason)
                }
            }
        }
        return null
    }
    private companion object {
        /**
         * Post finalizers dispatch at step indices AFTER every declared body
         * step (the DSL cap is 512), with the `post:<CONDITION>:<i>` body path
         * segment carrying the block identity, so a post op can never collide
         * with a body-step OpId of the same stage.
         */
        const val POST_BASE_STEP_INDEX = 1000
    }
}
