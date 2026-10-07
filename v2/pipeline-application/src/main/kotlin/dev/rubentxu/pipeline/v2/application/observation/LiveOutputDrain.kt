package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputTailState
import java.io.Writer

/**
 * OBS-E4: the run-time half of the read model — a drain that keeps up with a run IN PROGRESS.
 *
 * ```text
 * process stdout/stderr -> OutputIngress -> SegmentOutputStore (committed)
 *                                                    │
 *                                                    ▼
 *                             this drain, reading DURABLE bytes only
 * ```
 *
 * ## Why a live drain exists at all, when the follower already drains
 *
 * [ObservationOutputFollower] drains on demand, for a consumer that asks. A `pipeline run` has a
 * different job: the bytes must reach the user's terminal WHILE the step that produced them is
 * still running, because the whole value of live output is that a build's progress is visible
 * before it fails. A consumer that only reads at the end is a document reader wearing a live
 * console's name.
 *
 * So this type is the run-time counterpart: it polls the committed extent and emits as the plane
 * advances. The two share the reader, the frame ordinals and the encoders, so there is exactly one
 * implementation of "which bytes are next" and one of "how does a record look on the wire".
 *
 * ## It reads DURABLE bytes, never the producer
 *
 * The drain is attached to the Output Plane, not to the pump. That is not an implementation
 * preference: the consumer-backpressure law says no observation consumer may reach the execution
 * path, and a pipe from the pump would make the terminal a participant in the run. Here a slow,
 * wedged or killed consumer can only make its own output late.
 *
 * ## It never blocks the run, and it never loses bytes
 *
 * Both properties come from the same place: the run does not wait for this thread, and the thread
 * remembers its position in [ObservationRecord.Output.frame].ordinal. A consumer that stops reading
 * and resumes loses nothing, because the resume point is a durable ordinal and the bytes it skipped
 * are still committed.
 *
 * ## Failure is reported, never swallowed
 *
 * A [ObservationOutputRead.Refused] ends the drain with a refusal rather than an empty result. The
 * run itself is unaffected — this thread is an observer and its failure is not the run's — but the
 * caller learns that the console is incomplete instead of being shown a plausible gap. That
 * distinction is the same one `ObservationOutputRead` was sealed to protect on the output lane.
 */
class LiveOutputDrain(
    private val reader: ObservationOutputReader,
    private val frameLimit: Int,
    private val pollIntervalMs: Long,
) {

    init {
        require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive, got $pollIntervalMs" }
    }

    /**
     * Drains [runId] until the plane reports the run sealed, or until [shouldStop] says otherwise.
     *
     * Runs on the CALLING thread, which is the drain's own thread — this type deliberately has no
     * executor, no scheduler and no global state, so the owner of a run decides when the drain lives
     * and when it is asked to stop. A component that started its own thread would make "stop
     * observing" unobservable from outside, and a run that could not stop observing is a run whose
     * console outlives it.
     *
     * @param shouldStop consulted before every read. A run finishing is the usual reason to stop,
     *   but so is an interrupt, and both are the owner's decision rather than this type's.
     * @param emit receives one record at a time, in observation order. It is called only for bytes
     *   that are already committed, so an emitter that throws has emitted nothing false.
     * @return why the drain ended. [LiveOutputDrainResult.Drained] means the plane said the run is
     *   sealed and nothing was left behind.
     */
    fun drain(
        runId: String,
        shouldStop: () -> Boolean,
        emit: (ObservationRecord.Output) -> Unit,
    ): LiveOutputDrainResult {
        var ordinal = -1L

        while (true) {
            if (shouldStop()) return LiveOutputDrainResult.Stopped

            when (val read = reader.readOutput(runId, ordinal, frameLimit)) {
                is ObservationOutputRead.Refused -> return LiveOutputDrainResult.Refused(read.reason)
                is ObservationOutputRead.Page -> {
                    val page = read.page
                    for (record in page.records) {
                        emit(record)
                        // The position advances per RECORD, not per page. Advancing per page would
                        // skip any record an emitter rejected and lose it for good.
                        ordinal = record.frame.ordinal
                    }

                    // Two conditions, and BOTH must hold before this is a finish:
                    //
                    // 1. `moreFrames` is false — the page was not truncated by frameLimit. A
                    //    truncated page means bytes are waiting to be read, so returning here would
                    //    end the console mid-transcript.
                    // 2. Every stream is Sealed. An EMPTY tail list is not "sealed": a run that
                    //    has not written a byte yet has no streams, and treating that as finished
                    //    would cut the console at the exact moment the build starts. Same law as
                    //    elsewhere in this package — null is not Sealed, and an empty history is
                    //    not a completion.
                    val tails = reader.tailStatesOf(runId)
                    val everyStreamSealed = tails.isNotEmpty() && tails.all { it is OutputTailState.Sealed }
                    if (!page.moreFrames && everyStreamSealed) return LiveOutputDrainResult.Drained
                }
            }

            if (!sleep(pollIntervalMs)) return LiveOutputDrainResult.Stopped
        }
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/** Why a [LiveOutputDrain] stopped. */
sealed interface LiveOutputDrainResult {

    /** Every stream of the run is sealed and every frame was emitted. */
    data object Drained : LiveOutputDrainResult

    /** The owner asked it to stop. Bytes may remain committed and un-emitted. */
    data object Stopped : LiveOutputDrainResult

    /**
     * The Output Plane would not answer.
     *
     * The run is unaffected; the CONSOLE is incomplete, and saying so is the whole point. Reporting
     * this as a clean finish would show a user a transcript with a silent hole in it.
     */
    data class Refused(val reason: OutputRefusal) : LiveOutputDrainResult
}