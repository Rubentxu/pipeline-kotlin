package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
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
