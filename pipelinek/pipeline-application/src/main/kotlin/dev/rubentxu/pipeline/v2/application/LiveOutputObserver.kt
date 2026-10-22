package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputDrain
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputDrainResult
import dev.rubentxu.pipeline.v2.application.observation.LiveOutputPresentation
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import java.nio.file.Path

/**
 * the run-time output observer, started before the pipeline and stopped after it.
 *
 * ## What this is for
 *
 * `ConsolePrintingEventSink` mirrors the EVENT spine as events are appended. Process output is not
 * on that spine — it lives in the Output Plane — so before this the bytes a step printed were
 * durable and unobservable until someone ran `pipeline console` against the control directory, which
 * is not a public verb. A build's progress was therefore invisible while the build ran.
 *
 * ## Why a thread, and why the run never waits for it
 *
 * The observer polls, so it cannot run on the thread that is executing the pipeline. That makes the
 * backpressure law the reason for its existence as much as the convenience: the run does not wait on
 * it, so a wedged terminal, a closed pipe or a slow reader can delay only its own output. The
 * consumer-backpressure law says no observation consumer may reach the execution path, and a
 * terminal that can block the run is exactly that.
 *
 * ## Why the owner stops it, not the plane
 *
 * `ShExecution` seals a step's streams from INSIDE `invoke`, while the run continues, so there is a
 * window in which every stream the run has is sealed and the run is not over. An observer that
 * treated "all sealed" as "done" would end inside that window and drop the output of every later
 * step. `runFinished` is therefore the terminator, and the tail states only classify how it ended.
 *
 * ## `json` is the one format that does not stream
 *
 * A JSON array is a document, and a document cannot be streamed. So for `--format json` this starts
 * NO thread and collects nothing; the end-of-run document already renders every event, and adding
 * output to it is the job of a later block rather than of a half-measure here.
 */

internal fun startLiveOutputObserver(
    runId: String,
    controlDirRoot: java.nio.file.Path,
    format: ObservationFormat,
): AutoCloseable {
    if (format == ObservationFormat.JSON) {
        return AutoCloseable { }
    }

    val presentation = LiveOutputPresentation(format, System.out, System.err)
    val store = OutputPlaneProvider.storeForReading(controlDirRoot)
    val reader = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)
    val drain = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 10)
    val runFinished = java.util.concurrent.atomic.AtomicBoolean(false)

    val thread = Thread(
        {
            when (val result = drain.drain(runId, { runFinished.get() }) { record ->
                presentation.emit(record)
            }) {
                is LiveOutputDrainResult.Refused ->
                    presentation.reportRefusal(result.reason)

                else -> Unit
            }
        },
        "pipelinek-output-observer",
    ).apply {
        // A daemon, so an observer that is somehow never stopped cannot keep the JVM alive past the
        // run that owns it. The run does not depend on this thread existing.
        isDaemon = true
    }
    thread.start()

    return AutoCloseable {
        runFinished.set(true)
        // Bounded, because a run that has ended must not be held hostage by a reader that is wedged.
        // The bytes stay committed either way: this is an observer, not the authority.
        thread.join(OBSERVER_JOIN_TIMEOUT_MS)
    }
}

/**
 * How long a finished run waits for its observer to notice and flush.
 *
 * Bounded because the run has already produced its result and holding the terminal for an unbounded
 * wait would turn a slow reader into a hung command. What is lost by stopping early is nothing the
 * run owns: the bytes are committed and `observe` can read them afterwards.
 */

private const val OBSERVER_JOIN_TIMEOUT_MS = 5_000L
