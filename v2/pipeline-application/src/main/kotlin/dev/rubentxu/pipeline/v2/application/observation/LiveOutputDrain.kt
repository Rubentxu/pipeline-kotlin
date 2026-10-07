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
     * @param shouldStop consulted after every read, and it is what ENDS the drain. Only the owner
     *   knows the run is over — see the race documented in the body. The tail states then classify
     *   the ending ([Drained] versus [Stopped]); they never decide it.
     * @param emit receives one record at a time, in observation order. It is called only for bytes
     *   that are already committed, so an emitter that throws has emitted nothing false.
     * @return why the drain ended. [LiveOutputDrainResult.Drained] means the owner stopped it and
     *   every stream was sealed; [LiveOutputDrainResult.Stopped] means the owner stopped it while
     *   some tail was still open, which is the normal state for a run that died mid-step.
     */
    fun drain(
        runId: String,
        shouldStop: () -> Boolean,
        emit: (ObservationRecord.Output) -> Unit,
    ): LiveOutputDrainResult {
        var ordinal = -1L

        while (true) {
            // Read FIRST, then ask whether to stop. The order is the whole fix for a race that a
            // seal-based terminator cannot avoid:
            //
            //   `ShExecution` seals a step's streams from INSIDE `invoke`, while the run continues.
            //   So between one `sh` finishing and the next one declaring its streams, every stream
            //   the run has is Sealed. A drain that treated "all sealed" as "the run is over" would
            //   exit there — and silently drop the output of every LATER step. LIVE-4 pins that.
            //
            // Reading before asking means the bytes committed up to the moment the owner stopped are
            // emitted, and asking before reading would drop the last poll's worth.
            when (val read = reader.readOutput(runId, ordinal, frameLimit)) {
                is ObservationOutputRead.Refused -> return LiveOutputDrainResult.Refused(read.reason)
                is ObservationOutputRead.Page -> {
                    for (record in read.page.records) {
                        emit(record)
                        // The position advances per RECORD, not per page. Advancing per page would
                        // skip any record an emitter rejected and lose it for good.
                        ordinal = record.frame.ordinal
                    }
                }
            }

            if (shouldStop()) {
                // The OWNER ends the drain, because only the owner knows the run is over. The tail
                // states then classify HOW it ended, and they classify rather than decide: an
                // empty tail list is fine here, because an owner that stopped a run with no output
                // really did drain everything there was.
                val tails = reader.tailStatesOf(runId)
                return if (tails.all { it is OutputTailState.Sealed }) {
                    LiveOutputDrainResult.Drained
                } else {
                    LiveOutputDrainResult.Stopped
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

    /** The owner stopped it and every stream was sealed. Nothing was left behind. */
    data object Drained : LiveOutputDrainResult

    /**
     * The owner stopped it while some tail was still open.
     *
     * Not an error and not a gap: a run that died mid-step leaves its tail open deliberately, so
     * that a resumed run can append to the same streams rather than start a second transcript.
     */
    data object Stopped : LiveOutputDrainResult

    /**
     * The Output Plane would not answer.
     *
     * The run is unaffected; the CONSOLE is incomplete, and saying so is the whole point. Reporting
     * this as a clean finish would show a user a transcript with a silent hole in it.
     */
    data class Refused(val reason: OutputRefusal) : LiveOutputDrainResult
}