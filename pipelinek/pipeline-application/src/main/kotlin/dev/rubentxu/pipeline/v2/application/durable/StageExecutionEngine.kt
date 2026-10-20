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
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
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

    /**
     * P3-E E2 — the projection of a stage outcome onto the wire spellings that have always
     * been published, carried UNCHANGED.
     *
     * `success` / `unstable` / `failed` are not interchangeable with the `failure` of
     * `RunFinished` or the `succeeded` of `RetryAttemptFinished`; they are three different
     * vocabularies that have always coexisted, and "normalising" them would rewrite history
     * for no gain. The mismatch is recorded, not fixed.
     *
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
     * P3-E E2 — why a stage that reached an outcome records no `StageFinished`.
     *
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
     * P3-E E2 — the CLOSED decision, replacing `outcome` plus an `emitStageFinished` flag
     * that had to be kept in step by hand at every call site.
     *
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

    /** P3-E E2 — the whole projection, total over [StageOutcome] with no `else`. */
    internal fun StageOutcome.wire(): StageOutcomeWire = when (this) {
        StageOutcome.Succeeded -> StageOutcomeWire.SUCCEEDED
        StageOutcome.Unstable -> StageOutcomeWire.UNSTABLE
        StageOutcome.Failed -> StageOutcomeWire.FAILED
        StageOutcome.Skipped -> StageOutcomeWire.SKIPPED
        StageOutcome.Aborted -> StageOutcomeWire.ABORTED
    }

    /**
     * P3-E E2 — whether the stage terminal is recorded, as one pure function.
     *
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
                    val failure = finalizeStageWhileRunAborts(
                        stage, stageIndex, StageOutcome.Failed, runId, stageShOptions, context,
                    ) ?: continuation.failure
                    return StageVerdict.Abort(failure, context)
                }
            }
        }
        // S2-B: the stage's own steps decided the outcome; the `post`
        // block finalizes the stage BEFORE its StageFinished, so the
        // terminal record already includes the finalizers' work.
        val outcome = if (stageUnstable) StageOutcome.Unstable else StageOutcome.Succeeded
        finalizeStage(stage, stageIndex, outcome, runId, stageShOptions, context)
            ?.let { failure -> return StageVerdict.Abort(failure, context) }
        return StageVerdict.Completed(outcome, context)
    }

    /**
     * S4-F2 — runs one SCRIPTED stage body, through the same rule as a linear one.
     *
     * ## Why this is a method and not a coordinator branch
     *
     * The continuation decision and the finalisation are [finalizeStage]'s, and a scripted stage
     * obeys both for the same reasons a linear stage does: a `failure`/`always`/`cleanup` finalizer
     * has to run when the body did not reach its end, and `StageFinished` must not be emitted for a
     * stage whose run is being failed. Writing those rules again in the coordinator would have been
     * the third copy — the two the [finalizeStage] KDoc already records as having drifted.
     *
     * ## What a refusal is
     *
     * A body that could not run is a **stage failure**, not an empty stage. A run that reported
     * SUCCESS because a scripted stage was silently skipped would be the silent no-op the semantic
     * constitution forbids, so the refusal travels as a [StepOutcome.Failure] into the SAME
     * continuation rule, which means the failure finalizers run before the run aborts.
     *
     * ## What this does not do
     *
     * It does not advance the canonical replay cursor, and neither does anything else on this path:
     * a scripted stage's operations are journaled in the scripted namespace and reused by their own
     * replay policy, so a resumed run re-enters the body rather than skipping it. That is the
     * whole-program scripted guarantee, and ADR-0103 D7 records the open question of whether a
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
        runLifecycle.stageStarted(runId, stageIndex, stage.name)
        val bodyOutcome = when (val verdict = scriptedExecution.run(stage, stageIndex, runId, stageShOptions)) {
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
     * S4-F2 — the stage TAIL, shared by every body shape that produces one outcome.
     *
     * A linear stage folds after every step, so it cannot use this; but a `parallel` body and a
     * `scripted` body each produce exactly one outcome for the whole stage, and both need the same
     * two things: decide the continuation, then finalise. Those two steps were written out in the
     * coordinator's parallel arm — with outcome STRINGS, which had already drifted from the enum
     * the engine passed — and would have been written a third time for the scripted arm.
     *
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
        val continuation = runLifecycle.decideStageContinuation(bodyOutcome, stage.name, runId.value, ambient)
    ) {
        CanonicalContinuation.Continue -> {
            finalizeStage(stage, stageIndex, StageOutcome.Succeeded, runId, stageShOptions, ambient)
                ?.let { failure -> return StageVerdict.Abort(failure, ambient) }
            StageVerdict.Completed(StageOutcome.Succeeded, ambient)
        }
        CanonicalContinuation.ContinueUnstable -> {
            runLifecycle.fold(dev.rubentxu.pipeline.v2.domain.RunOutcome.Unstable)
            finalizeStage(stage, stageIndex, StageOutcome.Unstable, runId, stageShOptions, ambient)
                ?.let { failure -> return StageVerdict.Abort(failure, ambient) }
            StageVerdict.Completed(StageOutcome.Unstable, ambient)
        }
        is CanonicalContinuation.Abort -> {
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
     * S4-F2 — the ONE place where a stage that has already REACHED AN OUTCOME is finalised.
     *
     * ## Why this exists, and why it is here rather than in the coordinator
     *
     * Three call sites needed the same three things — run the `post` block for a known outcome,
     * emit `StageFinished` if the run is not being failed, and report a failure if a finalizer
     * failed — and they were written out three times: the linear tail, the per-step abort inside
     * [runLinearStage], and the coordinator's parallel arm. Three copies of a rule is not a
     * convention, it is three places to forget one, and the copies had already drifted: the
     * coordinator passed outcome STRINGS while the engine passed [StageOutcome].
     *
     * So the rule lives here, and the coordinator's parallel arm becomes three short calls. This
     * also removes the duplicated call the growth guardrail was measuring, which is why the
     * coordinator shrinks rather than grows when the scripted body arrives.
     *
     * ## Ordering, unchanged
     *
     * `StageStarted < body < PostConditionSelected < post steps < StageFinished`, and a finalizer
     * that fails ABORTS the run instead of finishing the stage — cleanup is exactly the code that
     * must run after bad news, but it does not get to paper over the news.
     *
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
     * P3-E E2 — the finalisation of a stage whose RUN is being failed.
     *
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
        runPostBlock(
            stage = stage,
            stageIndex = stageIndex,
            stageOutcome = outcome,
            runId = runId,
            stageShOptions = stageShOptions,
            ambient = ambient,
        )?.let { return it }
        when (decision) {
            is StageFinishedDecision.Emit ->
                runLifecycle.stageFinished(runId, stageIndex, stage.name, decision.outcome.wire)
            is StageFinishedDecision.DoNotEmit -> Unit
        }
        return null
    }

    /**
     * S2-B: interprets a stage's declared `post` block for a KNOWN stage
     * outcome. The single stage-level finalizer seam of the canonical
     * coordinator.
     *
     * Decision/interpretation split (Step Constitution rule 7):
     *  - the PURE decision is `PostPlanner.plan`: which blocks fire, in which order,
     *    for which outcome. No I/O;
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
     * @param stageOutcome the canonical stage outcome. P3-E E3: this was a `String`
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
    ): PipelineFailure? {
        val postSpec = stage.post ?: return null
        if (postSpec.isEmpty) return null
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
                stageOutcome = stageOutcome.wire().wire,
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
