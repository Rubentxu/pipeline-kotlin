package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import java.time.Instant
import java.util.UUID

/**
 * The run-lifecycle bookends, extracted from [CanonicalDurableRunCoordinator]
 * (WU-PR-017 slice 1; ACTION_CATALOG PR-017).
 *
 * Owns exactly two responsibilities that used to live as coordinator fields:
 *
 *  1. the RunStarted/RunFinished bookend pair, including the
 *     "RunFinished only if RunStarted was emitted" correlation invariant
 *     (SKIP replay paths still get their closing bookend);
 *  2. the run's terminal outcome, folded as the run progresses and read
 *     exactly once at the end.
 *
 * NOT a coordinator replacement and not a second execution path: the
 * coordinator still orchestrates stages, dispatch and failure folding. This
 * engine only records and closes the lifecycle. Its behavior is pinned by
 * `CoordinatorRunLifecycleCharacterizationTest`, which it must satisfy
 * UNCHANGED: the emitted event content and their ordering are the contract,
 * including the quirks (lifecycle events always carry sequence 0).
 */
internal class RunLifecycleEngine(private val eventSink: EventSink) {

    private var runStartedEmitted: Boolean = false
    private var outcome: RunOutcome = RunOutcome.Success

    /** Opens the run: resets per-run state and emits the RunStarted bookend. */
    fun openRun(pipeline: CompiledPipeline, runId: RunId) {
        outcome = RunOutcome.Success
        runStartedEmitted = false
        eventSink.append(
            RunStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                scriptPath = pipeline.source.path,
            ),
        )
        runStartedEmitted = true
    }

    /** Folds the running outcome as the coordinator progresses. */
    fun fold(value: RunOutcome) {
        outcome = value
    }

    /** The outcome the run carries right now. */
    fun outcome(): RunOutcome = outcome

    /**
     * The stage bookend pair (WU-PR-017 slice 2). Same events, same field
     * values, same sequence-0 quirk the coordinator has always emitted; the
     * only variable is the outcome string.
     */
    fun stageStarted(runId: RunId, stageIndex: Int, stageName: String) {
        eventSink.append(
            dev.rubentxu.pipeline.v2.events.StageStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stageName = stageName,
            ),
        )
    }

    fun stageFinished(runId: RunId, stageIndex: Int, stageName: String, outcome: String) {
        eventSink.append(
            dev.rubentxu.pipeline.v2.events.StageFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stageName = stageName,
                outcome = outcome,
            ),
        )
    }

    fun decideStageContinuation(
        outcome: StepOutcome,
        stageName: String,
        runIdValue: String,
        executionContext: ExecutionContext,
    ): CanonicalContinuation = when (outcome) {
        StepOutcome.Success -> CanonicalContinuation.Continue
        StepOutcome.Unstable -> CanonicalContinuation.ContinueUnstable
        is StepOutcome.Failure -> walkCatchErrorChain(outcome.failure, stageName, runIdValue, executionContext)
    }

    private fun walkCatchErrorChain(
        failure: PipelineFailure,
        stageName: String,
        runIdValue: String,
        executionContext: ExecutionContext,
    ): CanonicalContinuation {
        // CTX-P2: identical EM-5/6 walk over the pure trailing chain (outermost-first fold order).
        val chain = executionContext.trailingCatchErrorChain()
        for (overlay in chain) {
            // P3-E E4: parse ONCE at the decision, over the closed vocabulary, and never
            // with a default. The previous `else -> ContinueUnstable` read UNSTABLE for
            // every token that was not FAILURE or SUCCESS, so a typo in the pipeline's own
            // error handling suppressed the failure it was installed to catch.
            val declared = CatchErrorBuildResult.parse(overlay.buildResult)
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.CatchErrorTriggered(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdValue,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageName,
                    // An unreadable result is published as ABSENT rather than echoed back:
                    // the field is nullable precisely so this case has a truthful encoding,
                    // and echoing an unrecognised token would put a value in the durable
                    // stream that no consumer can classify.
                    buildResult = declared?.let { overlay.buildResult },
                    stageResult = overlay.stageResult,
                    message = overlay.message,
                ),
            )
            when (declared) {
                CatchErrorBuildResult.Failure -> Unit // re-throw outward to the next enclosing catch scope
                CatchErrorBuildResult.Success -> return CanonicalContinuation.Continue
                CatchErrorBuildResult.Unstable -> return CanonicalContinuation.ContinueUnstable
                null -> return CanonicalContinuation.Abort(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                        "catchError declared buildResult '${overlay.buildResult}', which is not one of " +
                            "${CatchErrorBuildResult.supportedTokens.joinToString(", ")}; " +
                            "the caught failure is not suppressed and cannot be classified, so the run fails closed",
                    ),
                )
            }
        }
        // Exhausted enclosing catch scopes (or no catch overlay) without a suppressor: abort.
        return CanonicalContinuation.Abort(failure)
    }

    /**
     * Closes the run: emits RunFinished in the coordinator's finally, only if
     * RunStarted was emitted, with the outcome mapping the coordinator has
     * always used.
     */
    fun closeRun(runId: RunId) {
        if (!runStartedEmitted) {
            return
        }
        eventSink.append(
            RunFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                outcome = when (val o = outcome) {
                    is RunOutcome.Success -> "success"
                    is RunOutcome.Unstable -> "unstable"
                    is RunOutcome.Failure -> "failure"
                    is RunOutcome.Aborted -> "aborted"
                },
                diagnostics = emptyList(),
            ),
        )
    }
}
