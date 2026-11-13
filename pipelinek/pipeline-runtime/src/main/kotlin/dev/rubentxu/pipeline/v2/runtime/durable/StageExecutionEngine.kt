package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.IdGenerator
import dev.rubentxu.pipeline.v2.runtime.SpanScope
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.post.PostPlanner
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * What a stage DOES, out of the run loop
 * The coordinator owns the run: its bookends, the stage loop, and what the run does
 * next. This engine owns a single stage once the run has decided to start it: its
 * linear body, its continuation, and its `post` finalizers.
 * The hard rule it respects is that a collaborator may not write `return@run` or
 * `continue@stagesLoop`. A stage can therefore do only two things to a run: report
 * a typed [StageVerdict], and hand back the context it left behind. Every path that
 * used to write a non-local return is now one of those two cases, which is what lets
 * the run loop be read as a loop.
 * A stage is not a Step. Its identity is the stage, its finalizers are a second
 * dispatch surface with their own durable key space, and its outcome is chosen by a
 * planner rather than a handler. Keeping that here stops it leaking into the run.
*/
internal class StageExecutionEngine(
    private val stepDispatch: StepDispatcher,
    private val runLifecycle: RunLifecycleEngine,
    private val eventSink: EventSink,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
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

    /**
     * The projection of a stage outcome onto the wire spellings that have always
     * been published, carried UNCHANGED.
     * `success` / `unstable` / `failed` are not interchangeable with the `failure` of
     * `RunFinished` or the `succeeded` of `RetryAttemptFinished`; they are three different
     * vocabularies that have always coexisted, and "normalising" them would rewrite history
     * for no gain. The mismatch is recorded, not fixed.
     * Five cases, not three: a skipped stage reports `PostConditionSelected` without ever
     * producing a `StageFinished`, so the projection has to name states the stage terminal
     * does not have. Collapsing this into the three emittable cases would force the skip path
     * to invent a `StageFinished` value, which is the exact defect [StageFinishedDecision]
     * exists to prevent.
*/
    internal enum class StageOutcomeWire(val wire: String) {
        SUCCEEDED("success"),
        UNSTABLE("unstable"),
        FAILED("failed"),
        SKIPPED("skipped"),
        ABORTED("aborted"),
    }

    /**
     * Why a stage that reached an outcome records no `StageFinished`.
     * This is a closed set because each case is a distinct FACT about the run, and a fact
     * that has to be re-derived from a boolean at a call site is a fact nobody can check.
*/
    internal enum class StageFinishSuppression {
        /** The run is being failed and carries the failure itself; a stage terminal here would record a run end that never happened. */
        RUN_ABORTS_WITH_THIS_FAILURE,

        /** A skipped stage is reported by its own `StageSkipped(reason)`. Two events for one fact would let a consumer disagree about it. */
        SKIPPED_ALREADY_HAS_ITS_OWN_EVENT,

        /** An aborted stage did not finish: the run ended, not the stage. */
        ABORTED_IS_NOT_A_STAGE_TERMINAL,
    }

    /**
     * The CLOSED decision, replacing `outcome` plus an `emitStageFinished` flag
     * that had to be kept in step by hand at every call site.
     * The flag was the defect. Two coordinated values make four representable states and
     * only two of them legal, so `FAILED + emit=true` — a stage that reports a clean
     * terminal while the run is aborting — was one careless argument away. Here the two
     * legal readings are two constructors, and the illegal ones are not nameable.
*/
    internal sealed interface StageFinishedDecision {

        /** Record the stage terminal with this projection. */
        data class Emit(val outcome: StageOutcomeWire) : StageFinishedDecision

        /** Record nothing, for a stated reason. */
        data class DoNotEmit(val reason: StageFinishSuppression) : StageFinishedDecision
    }

    /** The whole projection, total over [StageOutcome] with no `else`. */
    internal fun StageOutcome.wire(): StageOutcomeWire = when (this) {
        StageOutcome.Succeeded -> StageOutcomeWire.SUCCEEDED
        StageOutcome.Unstable -> StageOutcomeWire.UNSTABLE
        StageOutcome.Failed -> StageOutcomeWire.FAILED
        StageOutcome.Skipped -> StageOutcomeWire.SKIPPED
        StageOutcome.Aborted -> StageOutcomeWire.ABORTED
    }

    /**
     * Whether the stage terminal is recorded, as one pure function.
     * Exhaustive over the wire, so a new outcome is a compile error here rather than a
     * silent `else` that reports success — the defect class this exists to prevent.
*/
    internal fun StageOutcome.finishedDecision(): StageFinishedDecision = when (wire()) {
        StageOutcomeWire.SUCCEEDED -> StageFinishedDecision.Emit(StageOutcomeWire.SUCCEEDED)
        StageOutcomeWire.UNSTABLE -> StageFinishedDecision.Emit(StageOutcomeWire.UNSTABLE)
        StageOutcomeWire.FAILED -> StageFinishedDecision.Emit(StageOutcomeWire.FAILED)
        StageOutcomeWire.SKIPPED ->
            StageFinishedDecision.DoNotEmit(StageFinishSuppression.SKIPPED_ALREADY_HAS_ITS_OWN_EVENT)
        StageOutcomeWire.ABORTED ->
            StageFinishedDecision.DoNotEmit(StageFinishSuppression.ABORTED_IS_NOT_A_STAGE_TERMINAL)
    }

    /** The same decision for a stage whose run is being failed: never a stage terminal. */
    private fun StageOutcome.finishedDecisionWhileRunAborts(): StageFinishedDecision =
        StageFinishedDecision.DoNotEmit(StageFinishSuppression.RUN_ABORTS_WITH_THIS_FAILURE)

    /**
     * Runs one stage body: the stage bookend, the declared steps, the continuation
     * after each, and the `post` finalizers for whichever outcome the stage reached.
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
        // The stage's span is the parent of every step the stage dispatches: the
        // stage caused them, and a trace that does not say so is a list of
        // events with timestamps.
        val stageParent = runLifecycle.stageStarted(runId, stageIndex, stage.name)
        var context = ambient
        var stageUnstable = false
        for (stepIndex in steps.indices) {
            val dispatched = stepDispatch.dispatch(
                StepDispatchRequest(
                    step = steps[stepIndex],
                    runId = runId,
                    stageName = stage.name,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    shOptions = stageShOptions,
                    bodyPath = emptyList(),
                    spanScope = SpanScope(context, stageParent),
                ),
            )
            context = dispatched.context
            // The stage fold needs the OUTCOME, so it projects it explicitly and leaves the
            // carrier alone. See DispatchedStep for why no `outcome` shortcut exists.
            when (val continuation = runLifecycle.decideStageContinuation(dispatched.result.outcome)) {
                StageContinuation.Continue -> Unit
                StageContinuation.ContinueUnstable -> {
                    runLifecycle.fold(dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable)
                    stageUnstable = true
                }
                is StageContinuation.Abort -> {
                    // A step failure is still a stage outcome. The
                    // failure/always/cleanup finalizers MUST run before the run
                    // aborts, or `post { failure { ... } }` would be dead code
                    // for the exact case it exists for. A finalizer that itself
                    // fails replaces the reason, because the run is being failed
                    // either way and the later failure is the more recent truth.
                    val failure = finalizeStageWhileRunAborts(
                        stage, stageIndex, StageOutcome.Failed, runId, stageShOptions, context,
                    ) ?: continuation.failure
                    return StageVerdict.Abort(failure, context)
                }
            }
        }
        // The stage's own steps decided the outcome; the `post`
        // block finalizes the stage BEFORE its StageFinished, so the
        // terminal record already includes the finalizers' work.
        val outcome = if (stageUnstable) StageOutcome.Unstable else StageOutcome.Succeeded
        finalizeStage(stage, stageIndex, outcome, runId, stageShOptions, context)
            ?.let { failure -> return StageVerdict.Abort(failure, context) }
        return StageVerdict.Completed(outcome, context)
    }

    /**
     * Runs one SCRIPTED stage body, through the same rule as a linear one.
     * ## Why this is a method and not a coordinator branch
     * The continuation decision and the finalisation are [finalizeStage]'s, and a scripted stage
     * obeys both for the same reasons a linear stage does: a `failure`/`always`/`cleanup` finalizer
     * has to run when the body did not reach its end, and `StageFinished` must not be emitted for a
     * stage whose run is being failed. Writing those rules again in the coordinator would have been
     * the third copy — the two the [finalizeStage] KDoc already records as having drifted.
     * ## What a refusal is
     * A body that could not run is a **stage failure**, not an empty stage. A run that reported
     * SUCCESS because a scripted stage was silently skipped would be the silent no-op the semantic
     * constitution forbids, so the refusal travels as a [StepOutcome.Failure] into the SAME
     * continuation rule, which means the failure finalizers run before the run aborts.
     * ## What this does not do
     * It does not advance the canonical replay cursor, and neither does anything else on this path:
     * a scripted stage's operations are journaled in the scripted namespace and reused by their own
     * replay policy, so a resumed run re-enters the body rather than skipping it. That is the
     * Whole-program scripted guarantee, and records the open question of whether a
     * scripted stage ought eventually to carry a cursor position of its own.
*/
    suspend fun runScriptedStage(
        stage: StageNode,
        stageIndex: Int,
        scriptedExecution: ScriptedStageExecution,
        stageShOptions: ShOptions,
        runId: RunId,
        ambient: ExecutionContext,
    ): StageVerdict {
        val stageParent = runLifecycle.stageStarted(runId, stageIndex, stage.name)
        val bodyOutcome = when (
            val verdict = scriptedExecution.run(stage, stageIndex, runId, stageShOptions, stageParent)
        ) {
            is ScriptedStageExecution.Verdict.Completed -> verdict.outcome
            is ScriptedStageExecution.Verdict.Refused -> StepOutcome.Failure(
                PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    message = "scripted stage '${stage.name}' did not run: ${verdict.reason.describe()}",
                ),
            )
        }
        return finalizeStageOutcome(bodyOutcome, stage, stageIndex, runId, stageShOptions, ambient)
    }

    /**
     * The stage TAIL, shared by every body shape that produces one outcome.
     * A linear stage folds after every step, so it cannot use this; but a `parallel` body and a
     * `scripted` body each produce exactly one outcome for the whole stage, and both need the same
     * two things: decide the continuation, then finalise. Those two steps were written out in the
     * coordinator's parallel arm — with outcome STRINGS, which had already drifted from the enum
     * the engine passed — and would have been written a third time for the scripted arm.
     * So the tail lives here, once, and both arms are a call. Ordering is unchanged:
     * `StageStarted < body < PostConditionSelected < post steps < StageFinished`.
*/
    suspend fun finalizeStageOutcome(
        bodyOutcome: StepOutcome,
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): StageVerdict = when (
        val continuation = runLifecycle.decideStageContinuation(bodyOutcome)
    ) {
        StageContinuation.Continue -> {
            finalizeStage(stage, stageIndex, StageOutcome.Succeeded, runId, stageShOptions, ambient)
                ?.let { failure -> return StageVerdict.Abort(failure, ambient) }
            StageVerdict.Completed(StageOutcome.Succeeded, ambient)
        }
        StageContinuation.ContinueUnstable -> {
            runLifecycle.fold(dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable)
            finalizeStage(stage, stageIndex, StageOutcome.Unstable, runId, stageShOptions, ambient)
                ?.let { failure -> return StageVerdict.Abort(failure, ambient) }
            StageVerdict.Completed(StageOutcome.Unstable, ambient)
        }
        is StageContinuation.Abort -> {
            // A body that did not reach its end is still a stage outcome: the failure/always/
            // cleanup finalizers MUST run before the run aborts. A finalizer that itself fails
            // replaces the reason, because the run is being failed either way and the later
            // failure is the more recent truth.
            val failure = finalizeStageWhileRunAborts(
                stage, stageIndex, StageOutcome.Failed, runId, stageShOptions, ambient,
            ) ?: continuation.failure
            StageVerdict.Abort(failure, ambient)
        }
    }

    /**
     * The ONE place where a stage that has already REACHED AN OUTCOME is finalised.
     * ## Why this exists, and why it is here rather than in the coordinator
     * Three call sites needed the same three things — run the `post` block for a known outcome,
     * emit `StageFinished` if the run is not being failed, and report a failure if a finalizer
     * failed — and they were written out three times: the linear tail, the per-step abort inside
     * [runLinearStage], and the coordinator's parallel arm. Three copies of a rule is not a
     * convention, it is three places to forget one, and the copies had already drifted: the
     * coordinator passed outcome STRINGS while the engine passed [StageOutcome].
     * So the rule lives here, and the coordinator's parallel arm becomes three short calls. This
     * also removes the duplicated call the growth guardrail was measuring, which is why the
     * coordinator shrinks rather than grows when the scripted body arrives.
     * ## Ordering, unchanged
     * `StageStarted < body < PostConditionSelected < post steps < StageFinished`, and a finalizer
     * that fails ABORTS the run instead of finishing the stage — cleanup is exactly the code that
     * must run after bad news, but it does not get to paper over the news.
     * @return the typed failure to abort the run with, or null when the stage finalised cleanly.
*/
    suspend fun finalizeStage(
        stage: StageNode,
        stageIndex: Int,
        outcome: StageOutcome,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? =
        finalize(stage, stageIndex, outcome, runId, stageShOptions, ambient, outcome.finishedDecision())

    /**
     * The finalisation of a stage whose RUN is being failed.
     * This is a separate entry point rather than a flag on [finalizeStage] on purpose. The
     * difference between the two is not an optimisation: `RunFinished` carries the failure, so a
     * `StageFinished` before it would record a terminal the run never reached. Expressing that as
     * `emitStageFinished = false` made the illegal combination reachable from any call site by
     * passing the wrong boolean; as a second function, the choice is in the name and there is no
     * boolean to get wrong.
*/
    suspend fun finalizeStageWhileRunAborts(
        stage: StageNode,
        stageIndex: Int,
        outcome: StageOutcome,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? =
        finalize(
            stage, stageIndex, outcome, runId, stageShOptions, ambient,
            outcome.finishedDecisionWhileRunAborts(),
        )

    /** The shared body: run the finalizers, then apply the already-made terminal decision. */
    private suspend fun finalize(
        stage: StageNode,
        stageIndex: Int,
        outcome: StageOutcome,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
        decision: StageFinishedDecision,
    ): PipelineFailure? {
        val postFailure = runPostBlock(
            stage = stage,
            stageIndex = stageIndex,
            stageOutcome = outcome,
            runId = runId,
            stageShOptions = stageShOptions,
            ambient = ambient,
        )
        // The stage span ends HERE, on BOTH paths, and not after the early
        // return the failing finalizer used to take. A stage whose finalizer
        // failed reached an outcome too — a failed one — and a span left open
        // is a span the exporter never publishes, which would make the trace
        // quieter than the run.
        runLifecycle.stageEnded(stageIndex, if (postFailure == null) outcome else StageOutcome.Failed)
        postFailure?.let { return it }
        when (decision) {
            is StageFinishedDecision.Emit ->
                runLifecycle.stageFinished(runId, stageIndex, stage.name, decision.outcome.wire)
            is StageFinishedDecision.DoNotEmit -> Unit
        }
        return null
    }

    /**
     * Interprets a stage's declared `post` block for a KNOWN stage
     * outcome. The single stage-level finalizer seam of the canonical
     * coordinator.
     * Decision/interpretation split:
     *  - the PURE decision is `PostPlanner.plan`: which blocks fire, in which order,
     *    for which outcome. No I/O;
     *  - the INTERPRETATION is this method: dispatching the selected nodes
     *    through the SAME canonical `dispatch` spine as stage-body steps, each
     *    with a deterministic `post:<CONDITION>` [BlockSegment] so journal
     *    identity, replay and divergence behave exactly like any other step.
     * Ordering law: StageStarted <... < [PostConditionSelected] < post steps
     * < StageFinished. A declared-but-not-selected block appears only in the
     * event's `skippedConditions`, never as a silent no-op.
     * Failure containment: a FAILING finalizer fails the RUN (typed USER
     * failure naming the stage and condition) but never rolls back the
     * remaining finalizers — cleanup is exactly the code that must be allowed
     * to run after bad news. A post failure therefore ABORTS the run with the
     * failing finalizer's message; StageFinished is not emitted for a run the
     * coordinator is failing.
     * @param stageOutcome the canonical stage outcome.: this was a `String`
     *   re-parsed here by `PostCondition.outcomeOf`, which made the runtime round-trip
     *   `typed -> String -> typed` across a function boundary where the type system could
     *   not see it. It also failed OPEN in the other direction: the parser accepted five
     *   tokens while `StageFinished` produced three, so the seam could read states the
     *   producer never emitted. Taking the ADT makes the whole conversion unnecessary,
     *   and with it the last place where the event vocabulary and the planner vocabulary met.
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
        stageOutcome: StageOutcome,
        runId: RunId,
        stageShOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? = runPostOf(
        postSpec = stage.post,
        stageIndex = stageIndex,
        stageName = stage.name,
        outcome = stageOutcome,
        owner = "stage '${stage.name}'",
        runId = runId,
        shOptions = stageShOptions,
        ambient = ambient,
    )

    /**
     * The `post` of the PIPELINE, at the pipeline's close.
     *
     * It is the SAME machinery a stage's `post` runs on — the same pure
     * planner, the same selection event, the same dispatch spine — with two
     * differences, and both are facts about WHERE it runs:
     *
     *  - the OUTCOME is the run's, not a stage's: the caller projects the run's
     *    result onto the post vocabulary, and the planner selects from that;
     *  - the INDEX is one past the last stage. The index is part of the durable
     *    identity of every dispatched step, so the pipeline's finalizers can
     *    never collide with a stage's — and it is what tells a reader that the
     *    post is not one of the stages.
     *
     * The name is the pipeline's own: the run's source path. The event
     * vocabulary of `post` is stage-shaped because every `post` used to belong
     * to a stage, and the pipeline's post belongs to the RUN, whose name in
     * the event plane is its source path.
     */
    suspend fun runPipelinePostBlock(
        post: dev.rubentxu.pipeline.v2.domain.PostSpec?,
        closeIndex: Int,
        pipelineName: String,
        outcome: StageOutcome,
        runId: RunId,
        shOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? = runPostOf(
        postSpec = post,
        stageIndex = closeIndex,
        stageName = pipelineName,
        outcome = outcome,
        owner = "the pipeline",
        runId = runId,
        shOptions = shOptions,
        ambient = ambient,
    )

    /**
     * The ONE place a `post` block runs.
     *
     * A stage's `post` and the pipeline's `post` are the same decision over
     * the same pure planner, so they run here, once: two copies of this walk
     * would be two places where the selected conditions and the executed nodes
     * could disagree.
     *
     * The DURABLE IDENTITY of every dispatched finalizer is
     * `(runId, stageIndex, stepIndex, bodyPath)`. The body path carries the
     * block identity — `post:<CONDITION>:<i>` — and the index is the stage's
     * own, or, for the pipeline's post, the index one past the last stage. Two
     * finalizers of the same condition in the same run therefore never share
     * an identity, and the pipeline's finalizers never share one with a
     * stage's.
     *
     * That identity is also what makes a RESUME honest: the second attempt of
     * a run dispatches the same finalizers under the same identity, and the
     * durable model — not this method — decides whether the journalled
     * completion is served again or the step runs afresh. The close point
     * does not decide "already ran"; it dispatches, and the journal answers.
     */
    private suspend fun runPostOf(
        postSpec: dev.rubentxu.pipeline.v2.domain.PostSpec?,
        stageIndex: Int,
        stageName: String,
        outcome: StageOutcome,
        owner: String,
        runId: RunId,
        shOptions: ShOptions,
        ambient: ExecutionContext,
    ): PipelineFailure? {
        if (postSpec == null || postSpec.isEmpty) return null
        val plan = postSpec.toPostPlan()
        // The pure decision, made ONCE: if the planner selects nothing for this
        // outcome, the block is inert for this stage and emits nothing.
        if (PostPlanner.plan(plan, outcome).isEmpty()) return null

        eventSink.append(
            dev.rubentxu.pipeline.v2.events.PostConditionSelected(
                eventId = idGenerator.next(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = clock.now(),
                stageIndex = stageIndex,
                stageName = stageName,
                stageOutcome = outcome.wire().wire,
                selectedConditions = PostPlanner.selectedConditions(plan, outcome).map { it.name },
                skippedConditions = PostPlanner.skippedConditions(plan, outcome).map { it.name },
            ),
        )

        // One decision source: the planner's own condition projection drives
        // the walk, so the event lists and the executed nodes can never
        // disagree about which blocks fired.
        var dispatched = 0
        // A finalizer belongs to the stage it finalises, so it hangs under
        // that stage's span — and under the run's span when the stage never
        // opened one, which is the case of a stage that was skipped, and of
        // the pipeline's close, which belongs to no stage at all.
        val stageScope = SpanScope(ambient, runLifecycle.parentFor(stageIndex))
        for (condition in PostPlanner.selectedConditions(plan, outcome)) {
            val nodes = plan.bodies[condition].orEmpty()
            for ((index, node) in nodes.withIndex()) {
                val bodyPath = listOf(BlockSegment("post:${condition.name}:$index"))
                val dispatchedStep = stepDispatch.dispatch(
                    StepDispatchRequest(
                        step = node,
                        runId = runId,
                        stageName = stageName,
                        stageIndex = stageIndex,
                        stepIndex = POST_BASE_STEP_INDEX + dispatched,
                        shOptions = shOptions,
                        bodyPath = bodyPath,
                        spanScope = stageScope,
                    ),
                )
                dispatched++
                if (dispatchedStep.result.outcome !is StepOutcome.Success) {
                    val reason = "post ${condition.name} finalizer of $owner failed"
                    return PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.USER, reason)
                }
            }
        }
        return null
    }

    internal companion object {
        /**
         * Post finalizers dispatch at step indices AFTER every declared body
         * step (the DSL cap is 512), with the `post:<CONDITION>:<i>` body path
         * segment carrying the block identity, so a post op can never collide
         * with a body-step OpId of the same stage.
*/
        const val POST_BASE_STEP_INDEX = 1000
    }
}
