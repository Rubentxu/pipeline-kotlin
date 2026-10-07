package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState

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

    /**
     * Tail state of every stream DECLARED for [runId], in a stable order.
     *
     * This is what makes [followDecision] answerable for a follower that has read nothing. Reading
     * frames is not enough: a run whose steps have not produced output yet has declared streams and
     * no frames, and a follower that lists only what it has seen would conclude nothing about them.
     *
     * A `null` entry means the store does not know that stream — which is NOT
     * [dev.rubentxu.pipeline.v2.output.OutputTailState.Sealed], and deliberately keeps a follower
     * waiting. That is the safe direction: a stream declared a moment ago may not have a directory
     * yet, and a follower must not treat "not there yet" as "finished".
     */
    fun tailStatesOf(runId: String): List<OutputTailState?>

    /**
     * The last [tailBytes] bytes of [runId]'s output, as one bounded page.
     *
     * ## What "the last" means here, and why it is the run's order and not the wall clock
     *
     * Ordered by [OutputFrame.ordinal], which is the order PipelineK OBSERVED the chunks — not the
     * order the kernel wrote them, and not a timestamp. That is the only order this plane has, and
     * it is enough for a tail: "the most recently observed bytes" is exactly what a reader wants
     * when it cannot afford the whole transcript. It is deliberately not compared with an event
     * sequence, for the reason [ObservationRecord] refuses [dev.rubentxu.pipeline.v2.application.observation.ObservationView.FULL].
     *
     * ## Per STREAM would be the other reading, and it is not this one
     *
     * The obvious alternative — the last N bytes of each stream — is what `failure-context` already
     * does per channel, and it is what `kubectl logs --tail-bytes` means per container. It is not
     * what this can answer cheaply, and the reason is structural rather than incidental: the frame
     * index is organised PER RUN, so the last frames of a stream that finished early sit near the
     * beginning of the run's ordinals. Bounding by stream would make finding the tail of an early
     * step cost a scan of everything the run did afterwards — the exact "200 MB instead of 30 KiB"
     * this flag exists to avoid. Bounding by the run's own order costs what it reads.
     *
     * The trade is named rather than hidden: with this reading, a run with one enormous early step
     * and a quiet late one returns bytes only from the late one.
     *
     * ## The oldest frame included is NARROWED, never dropped and never invented
     *
     * The byte budget usually lands in the middle of a frame, so that frame is re-issued with its
     * `from` moved forward and its `ordinal` KEPT. The ordinal is not reassigned: it is still that
     * frame's position in the run, and a reader resuming from [ObservationOutputPage.lastOrdinal]
     * continues exactly where the tail ended. Nothing is dropped, and no range is claimed that was
     * not read.
     */
    fun readTail(runId: String, tailBytes: Long): ObservationOutputRead
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
    private val tails: OutputTailPort,
) : ObservationOutputReader {

    override fun tailStatesOf(runId: String): List<OutputTailState?> =
        index.streamsOfRun(runId).map { tails.tailState(it) }

    override fun readOutput(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
        require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }

        val frames = index.framesOfRun(runId, afterOrdinal, frameLimit)
        val records = ArrayList<ObservationRecord.Output>(frames.size)
        for (frame in frames) {
            when (val read = readFrameBytes(frame)) {
                is ReadOutcome.Refused -> return ObservationOutputRead.Refused(read.reason)
                is ReadOutcome.Bytes -> records += ObservationRecord.Output(frame, read.bytes, read.text)
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

    override fun readTail(runId: String, tailBytes: Long): ObservationOutputRead {
        require(tailBytes > 0) { "tailBytes must be positive, got $tailBytes" }

        val last = index.lastOrdinal(runId)
            // Nothing was ever committed for this run. Distinct from "committed nothing readable":
            // there is no position to resume from, and a caller that invented one would be told to
            // continue from an ordinal that never existed.
            ?: return ObservationOutputRead.Page(ObservationOutputPage(emptyList(), -1L, moreFrames = false))

        val tail = tailFrames(runId, last, tailBytes)
        val records = ArrayList<ObservationRecord.Output>(tail.size)
        for (frame in tail) {
            when (val read = readFrameBytes(frame)) {
                is ReadOutcome.Refused -> return ObservationOutputRead.Refused(read.reason)
                is ReadOutcome.Bytes -> records += ObservationRecord.Output(frame, read.bytes, read.text)
            }
        }
        return ObservationOutputRead.Page(
            ObservationOutputPage(
                records = records,
                lastOrdinal = records.lastOrNull()?.frame?.ordinal ?: -1L,
                // Frames exist after the last one RETURNED only if the byte budget cut the tail
                // short of the end — which it did not: the walk consumed the newest frames first.
                // The frames before the window were skipped on purpose, and "skipped by the caller"
                // is not what this field claims.
                moreFrames = false,
            ),
        )
    }

    /**
     * The trailing frames of [runId] whose bytes cover [tailBytes], oldest first, with the oldest
     * one narrowed to the exact boundary.
     *
     * Walks BACKWARDS in batches that double, so the cost is proportional to the bytes asked for
     * rather than to the length of the run: a 64 KiB tail of 4 KiB frames examines a few dozen
     * frames, and a run that never wrote more than 64 KiB stops at the beginning instead of
     * rescanning. Ordinals are dense per run — the index assigns them from a per-run counter — so
     * the last `n` ordinals really are the last `n` frames.
     */
    private fun tailFrames(runId: String, last: Long, tailBytes: Long): List<OutputFrame> {
        var requested = INITIAL_TAIL_BATCH
        while (true) {
            val after = maxOf(-1L, last - requested)
            val window = index.framesOfRun(runId, after, requested)

            var covered = 0L
            var keep = window.size
            while (keep > 0 && covered + window[keep - 1].length <= tailBytes) {
                covered += window[keep - 1].length
                keep--
            }
            val reachedFirstFrame = after <= -1L || window.size < requested

            // The budget landed EXACTLY on a frame edge. Nothing is half-included, and `window[keep]`
            // — the frame the loop refused to consume — is NOT part of the answer, so it must not
            // be narrowed either: doing so produced `from == to` and the frame's own `init` refused
            // it. That is the shape a "tail that ended neatly" bug takes.
            if (covered >= tailBytes) return window.drop(keep)

            // The window is exhausted and the budget is not met. Everything in it is included whole.
            if (keep == 0) {
                if (reachedFirstFrame) return window
                requested *= 2
                continue
            }

            // The budget lands INSIDE `window[keep - 1]`, so that frame is narrowed and the rest of
            // the window is whole. Narrowing it is what makes the tail return the bytes that were
            // asked for instead of a whole frame more or a whole frame less.
            if (reachedFirstFrame) {
                val includeFrom = keep - 1
                val overhang = covered + window[includeFrom].length - tailBytes
                return window.drop(includeFrom).mapIndexed { position, frame ->
                    // Strictly less than the frame's length, because the loop stopped on the frame
                    // that would overshoot — so `to > from` still holds.
                    if (position == 0) frame.copy(from = frame.from + overhang) else frame
                }
            }
        }
    }

    private sealed interface ReadOutcome {
        /** The frame's raw bytes, and their decoded view. Both are bounded by the frame's range. */
        data class Bytes(val bytes: ByteArray, val text: String) : ReadOutcome
        data class Refused(val reason: OutputRefusal) : ReadOutcome
    }

    private fun readFrameBytes(frame: OutputFrame): ReadOutcome {
        val length = frame.length
        // A frame is a commit window, never a stream. Refusing the impossible is better than an
        // Int overflow that would silently ask for the wrong number of bytes.
        require(length <= Int.MAX_VALUE) { "frame ${frame.ordinal} names $length bytes, beyond one window" }

        return when (val read = bytes.read(frame.stream, OutputCursor(frame.stream, frame.from), length.toInt())) {
            is OutputReadResult.Refused -> ReadOutcome.Refused(read.reason)
            is OutputReadResult.Page -> ReadOutcome.Bytes(read.page.bytes, decodeWindow(read.page.bytes))
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

/** Frames examined on the first backward batch. Doubles from here; small enough to be cheap. */
private const val INITIAL_TAIL_BATCH = 8
