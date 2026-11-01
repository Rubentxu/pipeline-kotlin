package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.application.LiveSpanHandle
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanStatus
import dev.rubentxu.pipeline.v2.application.LiveSpans
import dev.rubentxu.pipeline.v2.application.PipelineTelemetry
import dev.rubentxu.pipeline.v2.application.liveStatusOfStageOutcome
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
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
internal class RunLifecycleEngine(
    private val eventSink: EventSink,
    private val telemetry: PipelineTelemetry,
) {

    private var runStartedEmitted: Boolean = false
    private var outcome: RunOutcome = RunOutcome.Success

    /**
     * The stage spans this run has opened and not yet closed.
     *
     * The stage span is opened where the stage STARTS ([stageStarted]) and closed
     * where the stage REACHES AN OUTCOME ([stageEnded]); the two are deliberately
     * not in the same method, because the events they write are also unbalanced by
     * design: a stage that is aborted mid-way never emits its `StageFinished`, and
     * the span must follow the same truth rather than inventing a terminal the
     * stage never reached.
     *
     * The map is what makes the exception path honest: [closeOpenStages] ends
     * whatever is left, so a stage whose body threw does not leak an open span.
     */
    private val openStageSpans = mutableMapOf<Int, LiveSpanHandle>()

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
        // The stage span opens here, where the stage's START is a fact. The three
        // bodies (linear, parallel, scripted) all reach this method, so there is
        // exactly one place that opens a stage span and no body that has to
        // remember to.
        LiveSpans.open(
            telemetry = telemetry,
            name = LiveSpanNames.STAGE,
            attributes = listOf(
                LiveSpanAttributes.runId(runId.value),
                LiveSpanAttributes.stageIndex(stageIndex),
            ),
        )?.let { span -> openStageSpans[stageIndex] = span }
    }

    /**
     * Closes the stage span of the stage that REACHED AN OUTCOME.
     *
     * The stage's terminal is a fact when the body ends, whatever the body's shape,
     * so this is the one place the span is closed. A stage whose body threw never
     * reaches it, and [closeOpenStages] is what covers that case: a span that was
     * opened and never ended would be a span the exporter never sees, which is
     * exactly the "incomplete span" the packaging rules forbid publishing as a
     * completed one.
     */
    fun stageEnded(stageIndex: Int, outcome: StageOutcome) {
        val span = openStageSpans.remove(stageIndex) ?: return
        LiveSpans.end(span, liveStatusOfStageOutcome(outcome))
    }

    /**
     * Ends the stage spans that are still open when the run stops.
     *
     * A stage that threw never reached [stageEnded], and its span would otherwise
     * stay open forever. Ending it as Failed is the truth: the stage did not
     * complete, and the run is not going to tell the operator otherwise.
     */
    fun closeOpenStages() {
        val leftovers = openStageSpans.values.toList()
        openStageSpans.clear()
        leftovers.forEach { span -> LiveSpans.end(span, LiveSpanStatus.Failed) }
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

    /**
     * The stage continuation rule.
     *
     * A scope that CONTAINS a failure decides it, and that decision IS the scope's
     * own outcome: a `catchError` that absorbs the failure reports Success or
     * Unstable, so the stage never receives a Failure it would have to interpret.
     * What arrives here is therefore an outcome the stage reads directly — a
     * Success continues, an Unstable continues and degrades the stage, and a
     * Failure has no suppressor left and aborts.
     *
     * The walk this used to perform — over the context overlay the scope had
     * pushed, publishing a `CatchErrorTriggered` per frame — is GONE, and with it
     * the SECOND authority on what a control scope means. The scope's own fold
     * ([BodyOutcomeFolder]) owns that decision now, inside the scope, and it
     * publishes what it saw. A stage that received the scope's outcome cannot
     * disagree with the scope about it.
     */
    fun decideStageContinuation(outcome: StepOutcome): CanonicalContinuation = when (outcome) {
        StepOutcome.Success -> CanonicalContinuation.Continue
        StepOutcome.Unstable -> CanonicalContinuation.ContinueUnstable
        is StepOutcome.Failure -> CanonicalContinuation.Abort(outcome.failure)
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
