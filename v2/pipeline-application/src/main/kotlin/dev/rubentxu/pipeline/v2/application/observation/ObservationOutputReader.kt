package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal

/**
 * The OUTPUT lane of the read model: committed bytes, in the order PipelineK observed them, as
 * records a query can select.
 *
 * ## This is not `ConsoleReadService`, and the difference is the whole point
 *
 * `pipeline console` needs bytes and needs them soon, so `ConsoleReadService` continues by **byte
 * cursor** and concatenates the two channels in a FIXED order. That is the right trade for a verb
 * whose job is "show me the transcript": a cursor knows its own stream, so a resume is exact, and
 * the fixed order is a stated projection rather than a claim about interleaving.
 *
 * The read model needs something else. A reader here has to answer `--channel stderr`, and it has to
 * say *which bytes came from which channel in the order they were published* — a question only
 * [OutputFrameIndex] can answer. So this reader is positioned by **frame ordinal**, walks the frames,
 * and reads each one's bytes from the store.
 *
 * ```text
 * ConsoleReadService   byte cursor   fixed channel order   returns bytes   console verb
 * ObservationOutputReader  frame ordinal   observation order   returns records   filters, follow
 * ```
 *
 * Neither replaces the other, and merging them would mean either losing channel attribution or
 * claiming an interleaving the fixed-order reader never had.
 *
 * ## Why [ObservationOutputRead] is a sealed result and not an empty list
 *
 * "There are no records because nothing has been written yet" and "there are no records because the
 * store refused to answer" are different facts, and collapsing them into an empty list is the defect
 * [dev.rubentxu.pipeline.v2.events.EventRecordRead] was built to close on the event side. A `--follow`
 * consumer that read a refusal as "nothing yet" would poll a broken store forever, and one that read
 * it as "the run produced no output" would report a silent step as a truncated one.
 *
 * ## The window is the caller's, and that is deliberate
 *
 * There is no default. `frameLimit` is required because the number that belongs here is a **measured**
 * budget, not a constant chosen at a keyboard: how many frames one read should pull depends on what
 * the consumer does with them, and a default would hide that choice behind a call site that appears to
 * say nothing about it. A reader that materialises more than it was asked for is the mechanism by
 * which a `--follow` loop turns a 200 MB build log into memory pressure.
 */
interface ObservationOutputReader {

    /**
     * Records for [runId]'s frames after [afterOrdinal], in observation order, at most [frameLimit]
     * of them.
     *
     * @param afterOrdinal `-1` reads from the first frame of the run; it is not a cursor into bytes
     *   and is not comparable with an event sequence.
     * @param frameLimit how many frames this read may pull. Bounds the work and the memory.
     */
    fun readOutput(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead
}

/** One bounded page of the output lane, or the reason there isn't one. */
sealed interface ObservationOutputRead {

    /** Records in observation order, plus the position to continue from. */
    data class Page(val page: ObservationOutputPage) : ObservationOutputRead

    /** The store could not answer. The bytes are NOT absent; they are unread. */
    data class Refused(val reason: OutputRefusal) : ObservationOutputRead
}

/**
 * A bounded window of the output lane.
 *
 * @property records one record per frame, in ascending ordinal. Each carries its own bytes, decoded.
 * @property lastOrdinal the ordinal to continue from. Equal to the requested `afterOrdinal` when the
 *   page is empty, so a caller can always resume from the value on the page it just received instead
 *   of tracking what it asked for.
 * @property moreFrames whether the index had frames left AFTER the ones returned. Says nothing about
 *   whether more will ever arrive — that is [dev.rubentxu.pipeline.v2.output.OutputTailState]'s
 *   question, and answering it here would be the `next == null` mistake OBS-C3 closed.
 */
data class ObservationOutputPage(
    val records: List<ObservationRecord.Output>,
    val lastOrdinal: Long,
    val moreFrames: Boolean,
) {
    init {
        require(records.zipWithNext().all { (a, b) -> a.frame.ordinal < b.frame.ordinal }) {
            "records must ascend by ordinal: ${records.map { it.frame.ordinal }}"
        }
        // The resume position IS the last record. A page that carried any other value would make a
        // caller resume from a frame it had already been shown, or skip one it had not.
        require(records.isEmpty() || lastOrdinal == records.last().frame.ordinal) {
            "lastOrdinal ($lastOrdinal) must be the last record's ordinal " +
                "(${records.last().frame.ordinal})"
        }
    }

    /** The concatenated text of this page, in observation order. A projection, never persisted. */
    val text: String get() = records.joinToString(separator = "") { it.text }
}

/**
 * Composes the two Output Plane authorities into the output lane.
 *
 * Neither alone is enough: the index knows WHICH ranges were published and in what order, and the
 * store knows what the bytes say. Asking either for the other's question is what produces a merged
 * transcript with no attribution or a frame index holding payloads.
 *
 * ## The frame's bytes are read at the frame's own range
 *
 * A frame names `[from, to)` in one stream, and the read is addressed to that exact range rather than
 * to "whatever comes next". If a resumed run has appended more since the frame was published, the
 * frame's range is still the frame's range, so a reader replaying history sees the same bytes it saw
 * the first time instead of a window that grew underneath it.
 */
class FrameIndexedObservationOutputReader(
    private val index: OutputFrameIndex,
    private val bytes: OutputReadPort,
) : ObservationOutputReader {

    override fun readOutput(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
        require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }

        val frames = index.framesOfRun(runId, afterOrdinal, frameLimit)
        val records = ArrayList<ObservationRecord.Output>(frames.size)
        for (frame in frames) {
            when (val read = readFrameBytes(frame)) {
                is ReadOutcome.Refused -> return ObservationOutputRead.Refused(read.reason)
                is ReadOutcome.Bytes -> records += ObservationRecord.Output(frame, read.text)
            }
        }
        return ObservationOutputRead.Page(
            ObservationOutputPage(
                records = records,
                // An empty page hands back the position it was GIVEN. Advancing it would make a
                // caller that polled with no new frames skip one on the next read.
                lastOrdinal = frames.lastOrNull()?.ordinal ?: afterOrdinal,
                // The index answered with a bounded window; frames after the last one it returned
                // is the only claim this can make. Whether any WILL arrive is not its question.
                moreFrames = frames.size == frameLimit,
            ),
        )
    }

    private sealed interface ReadOutcome {
        data class Bytes(val text: String) : ReadOutcome
        data class Refused(val reason: OutputRefusal) : ReadOutcome
    }

    private fun readFrameBytes(frame: OutputFrame): ReadOutcome {
        val length = frame.length
        // A frame is a commit window, never a stream. Refusing the impossible is better than an
        // Int overflow that would silently ask for the wrong number of bytes.
        require(length <= Int.MAX_VALUE) { "frame ${frame.ordinal} names $length bytes, beyond one window" }

        return when (val read = bytes.read(frame.stream, OutputCursor(frame.stream, frame.from), length.toInt())) {
            is OutputReadResult.Refused -> ReadOutcome.Refused(read.reason)
            is OutputReadResult.Page -> ReadOutcome.Bytes(decodeWindow(read.page.bytes))
        }
    }

    /**
     * Decodes ONE frame's bytes, and only that frame's.
     *
     * A multi-byte character split across two frames does not survive the split: it appears as
     * U+FFFD in exactly one of them. That is a real cost, and it is paid here on purpose rather than
     * papered over with a carry-over buffer, because a carry would be reader-local state and would
     * make the SAME bytes decode differently depending on where a reader happened to resume. The
     * Output Plane keeps no record of the partial character that preceded a resume point, so a reader
     * that claims to reconstruct it would be inventing it.
     *
     * Determinism and resumability are worth more here than one intact character at a frame
     * boundary, and a caller who needs whole characters should read a larger window rather than
     * depend on state this contract deliberately does not keep.
     */
    private fun decodeWindow(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}