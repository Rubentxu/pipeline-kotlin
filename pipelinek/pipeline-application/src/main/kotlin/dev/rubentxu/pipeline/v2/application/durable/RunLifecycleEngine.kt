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
 * .
 * Owns exactly two responsibilities that used to live as coordinator fields:
 *  1. the RunStarted/RunFinished bookend pair, including the
 *     "RunFinished only if RunStarted was emitted" correlation invariant
 *     (SKIP replay paths still get their closing bookend);
 *  2. the run's terminal outcome, folded as the run progresses and read
 *     exactly once at the end.
 * NOT a coordinator replacement and not a second execution path: the
 * coordinator still orchestrates stages, dispatch and failure folding. This
 * engine only records and closes the lifecycle.
 * **This extraction is currently UNVERIFIED, and the previous version of this
 * paragraph claimed the opposite.** It said the behavior "is pinned by
 * a characterisation test, which it must satisfy
 * UNCHANGED". That test does not exist: its name occurs on exactly one line in
 * the repository, the one that cited it. The testing reset deleted it, along with
 * the rest of the inherited corpus, and the project policy forbids restoring
 * that corpus. A sweep of the test sources finds nothing that names this engine,
 * and nothing that exercises the coordinator's lifecycle bookends either, so the
 * emitted content and ordering are a real contract with no instrument left
 * measuring it. Someone changing a bookend here would see nothing go red.
 * It is therefore stated as an assertion, not as a fact: the extraction was meant
 * to preserve behavior, and preserving it has not been demonstrated since the
 * witness was removed. Restoring a witness is a decision, not a side effect --
 * the engine reads `Instant.now()` and `UUID.randomUUID()`, so pinning it would
 * mean giving it a clock and an id source first.
 * The same paragraph also asserted a quirk that no longer holds here: it said
 * lifecycle events "always carry sequence 0". They are CONSTRUCTED with
 * `sequence = 0L`, but 0 is the request for an assigned sequence, not a value:
 * `SqliteEventStore.appendAssigned` maps `sequence == 0L` to
 * `counter.incrementAndGet()`. What reaches the store is the next per-run
 * sequence, and the durable `UNIQUE(run_id, sequence)` constraint applies to
 * that assigned value.
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
     * The stage bookend pair. Same events, same field
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
        // Identical walk over the pure trailing chain (outermost-first fold order).
        val chain = executionContext.trailingCatchErrorChain()
        for (overlay in chain) {
            // Parsed once at the decision, over the closed vocabulary, never with a
            // default: the previous `else -> ContinueUnstable` read UNSTABLE for every token
            // that was not FAILURE or SUCCESS, so a typo in the pipeline's own error handling
            // suppressed the failure it was installed to catch.
            // Removed the parse. `overlay.buildResult` IS the vocabulary now, so the
            // `null` arm this used to need — an unrecognised token, aborted with SCHEMA — is
            // no longer reachable, and that is the point: the illegal state became
            // unrepresentable rather than handled. Refusal did not disappear, it moved to
            // the three places where untrusted text still enters: StructuralOverlayProjection
            // (unknown token -> no overlay), CatchErrorBuildResult.Serializer (unknown token
            // -> SerializationException) and the event decoder (unknown token -> record
            // refused). None of them can reach this walk with a value outside the type.
            val declared = overlay.buildResult
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.CatchErrorTriggered(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdValue,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageName,
                    // Nullable because ABSENCE is a real historical encoding on this field,
                    // not because this producer can fail to classify: it cannot any more.
                    buildResult = declared,
                    stageResult = overlay.stageResult,
                    message = overlay.message,
                ),
            )
            when (declared) {
                CatchErrorBuildResult.Failure -> Unit // re-throw outward to the next enclosing catch scope
                CatchErrorBuildResult.Success -> return CanonicalContinuation.Continue
                CatchErrorBuildResult.Unstable -> return CanonicalContinuation.ContinueUnstable
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
                outcome = outcome.wireToken,
                diagnostics = emptyList(),
            ),
        )
    }
}
