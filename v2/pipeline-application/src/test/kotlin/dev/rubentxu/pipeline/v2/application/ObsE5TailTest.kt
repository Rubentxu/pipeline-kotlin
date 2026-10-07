package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSlice
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputReader
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * OBS-E5e — `--tail-bytes`: a bounded tail of the run's output, read in O(bytes) not O(run).
 *
 * ## The rule these rows defend
 *
 * A tail exists so that a reader which cannot afford the whole transcript pays for the part it
 * reads. That is the whole claim, and it is a claim about COST as much as about bytes: a tail that
 * had to walk the entire frame index to find its starting point would return 64 KiB after reading
 * 200 MB, which is the situation the flag is meant to solve rather than restate.
 *
 * So the two properties that matter are these: the bytes returned are the run's LAST ones in
 * observation order, and the work done to find them is proportional to those bytes.
 *
 * The oldest frame included is NARROWED, not dropped: its `from` moves forward and its ordinal stays,
 * so a reader resuming from `lastOrdinal` continues exactly where the tail ended and no range is
 * claimed that was not read.
 *
 * ```text
 * TAIL-1  the resolved tail is PRINTED, not merely used as a resume position
 * TAIL-2  the oldest included frame is narrowed and keeps its ordinal
 * TAIL-3  a budget larger than the run returns the whole run, not a truncated one
 * TAIL-4  the cost grows with the bytes asked for, not with the run's frames
 * TAIL-5  a run with no committed output yields an empty page and no invented position
 * TAIL-6  `--tail-bytes` on a view with no bytes is refused, not ignored
 * TAIL-7  `--tail-bytes` is a start position, so it composes with `--limit`
 * TAIL-8  `run --tail-bytes` is refused rather than silently truncating
 * ```
 *
 * Mutations:
 *
 * - `M-T1` (the tail reads from the beginning) → ask for everything. REDS TAIL-2 and TAIL-4. It
 *   was predicted to red TAIL-1 too and did not, because TAIL-1 is answered by a scripted lane and
 *   never reaches `readTail` — which is why that row now says what it really defends.
 * - `M-T2` (the boundary frame is dropped whole) → exclude the oldest frame instead of narrowing
 *   it. REDS TAIL-2 alone.
 * - `M-T3` (the backward walk stops at the first batch) → return the first batch as the tail
 *   instead of widening. REDS TAIL-4 alone, and it is the only row that can see the cost of the
 *   walk: every other row fits inside eight frames.
 *
 * TAIL-1, TAIL-3, TAIL-5, TAIL-6, TAIL-7 and TAIL-8 are not killed by any of these. TAIL-3/5/6/7/8
 * pin refusals and an identity, and TAIL-1 pins the CLI seam rather than the reader — which is the
 * defect its first version had, keeping only the resume position and printing nothing at all.
 */
class ObsE5TailTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: SegmentOutputStore
    private lateinit var reader: ObservationOutputReader

    private val runId = "run-e5tail"
    private val stdout = OutputStreamId("$runId/build/sh-0/stdout")

    @BeforeEach
    fun setUp() {
        store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
        reader = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)
    }

    /** The production order: declare, write, commit, THEN append the frame. */
    private fun publish(stream: OutputStreamId, channel: OutputChannel, text: String) {
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
        store.frameIndex().append(stream, channel, from, from + payload.size)
    }

    private fun page(result: ObservationOutputRead): dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage =
        assertInstanceOf(ObservationOutputRead.Page::class.java, result, "expected a page, got $result").page

    private fun parsed(vararg args: String): ObservationParseResult.Parsed {
        val result = CliParser.parseObservation(arrayOf(*args))
        assertTrue(result is ObservationParseResult.Parsed, "expected admission, got: $result")
        return result as ObservationParseResult.Parsed
    }

    private class Sink {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, "UTF-8")
        val diagnostics = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
        fun stdout(): String = bytes.toString("UTF-8")
    }

    /** A lane whose only answer is a page the test scripted, so the CLI loop is not what is measured. */
    private class Scripted(private val tail: ObservationOutputRead?, private val pages: List<dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage>) : ObserveLanes {
        var tailBytes: Long? = null
            private set

        override val hasEventStore = false
        override val hasOutputPlane = true
        override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
            val next = pages.indexOfFirst { it.lastOrdinal > afterOrdinal }
            return ObservationOutputRead.Page(
                if (next < 0) dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage(emptyList(), afterOrdinal, false)
                else pages[next],
            )
        }

        override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? {
            this.tailBytes = tailBytes
            return tail
        }

        override fun eventSliceOf(runId: String, after: EventCursor?, limit: Int): EventSlice? = null
        override fun outputTailsOf(runId: String): List<OutputTailState?> = listOf(OutputTailState.Sealed(1_000))
    }

    private fun window(ordinal: Long, text: String): dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output {
        val payload = text.toByteArray()
        return dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output(
            dev.rubentxu.pipeline.v2.output.OutputFrame(
                ordinal,
                dev.rubentxu.pipeline.v2.output.OutputStreamId("$runId/s0-0/stdout"),
                OutputChannel.STDOUT,
                0,
                payload.size.toLong(),
            ),
            payload,
            text,
        )
    }

    private fun page(vararg records: dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output) =
        dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage(
            records.toList(),
            records.lastOrNull()?.frame?.ordinal ?: -1L,
            false,
        )

    @Test
    fun `TAIL-1 the resolved tail is printed, not just used as a resume position`() {
        val records = listOf(window(0, "oldest\n"), window(1, "middle\n"), window(2, "newest\n"))
        val sink = Sink()

        val outcome = MainObserveCli.replay(
            Scripted(ObservationOutputRead.Page(page(records[1], records[2])), emptyList()),
            runId,
            parsed("--view", "console", "--tail-bytes", "8"),
            sink.out,
            sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, outcome)
        assertEquals("middle\nnewest\n", sink.stdout(), "the tail the lane resolved is what the caller sees; keeping only its position prints nothing")
    }

    @Test
    fun `TAIL-2 the boundary frame is narrowed and keeps its ordinal`() {
        // Three 4-byte frames. A 6-byte tail must start inside the OLDEST frame it includes, so that
        // frame has to be narrowed rather than dropped — dropping it would return four bytes and
        // claim the caller asked for six.
        publish(stdout, OutputChannel.STDOUT, "AAAA")
        publish(stdout, OutputChannel.STDOUT, "BBBB")
        publish(stdout, OutputChannel.STDOUT, "CCCC")

        val tail = page(reader.readTail(runId, 6L))

        // The last SIX bytes of "AAAABBBBCCCC" are "BBCCCC": the newest frame whole, plus two bytes
        // of the one before it. Written out because the first version of this row expected eight
        // bytes and was simply wrong about which six are last.
        assertEquals("BBCCCC", tail.records.joinToString("") { it.text }, "got ${tail.records.map { it.text }}")
        assertEquals(2, tail.records.size, "two frames cover six bytes at four bytes each")
        assertEquals(2L, tail.records[1].frame.ordinal, "the newest keeps its own ordinal")
        assertEquals(6L, tail.records[0].frame.from, "the oldest is narrowed to where the budget started")
        assertEquals(8L, tail.records[0].frame.to)
        assertEquals(2L, tail.records[0].frame.length, "two of its four bytes are in the tail")
        assertEquals(2L, tail.lastOrdinal, "a reader resumes from exactly here")
        assertTrue(!tail.moreFrames, "the walk consumed the newest frames; none remain after them")
    }

    @Test
    fun `TAIL-3 a budget larger than the run returns the whole run`() {
        repeat(3) { publish(stdout, OutputChannel.STDOUT, "ABCD") }

        val tail = page(reader.readTail(runId, 1_000_000L))

        assertEquals(3, tail.records.size, "everything, not a truncation of everything")
        assertEquals("ABCDABCDABCD", tail.records.joinToString("") { it.text })
    }

    @Test
    fun `TAIL-4 the cost grows with the bytes asked for, not with the run`() {
        // 400 frames of 4 bytes. Far more than the first backward batch, so a reader that stopped at
        // that batch would return the newest 32 bytes no matter how much it was asked for — which
        // is the defect this row exists to see, because the other rows all fit in one batch.
        repeat(400) { publish(stdout, OutputChannel.STDOUT, "ABCD") }

        val small = page(reader.readTail(runId, 40L))
        val large = page(reader.readTail(runId, 400L))

        assertEquals(10, small.records.size, "40 bytes of 4-byte frames")
        assertEquals(100, large.records.size, "400 bytes of 4-byte frames")
        assertEquals(40L, small.records.sumOf { it.frame.length }, "every returned record names real bytes")
    }

    @Test
    fun `TAIL-5 a run with no committed output yields an empty page`() {
        val tail = page(reader.readTail(runId, 4_096L))

        assertTrue(tail.records.isEmpty())
        assertEquals(-1L, tail.lastOrdinal, "no invented resume position")
        assertTrue(!tail.moreFrames)
    }

    @Test
    fun `TAIL-6 the flag is refused on a view that carries no bytes`() {
        for (view in listOf("events", "normal", "quiet")) {
            val result = CliParser.parseObservation(arrayOf("--view", view, "--tail-bytes", "64"))
            assertTrue(
                result is ObservationParseResult.Rejected && result.error is CliError.TailNeedsTheOutputLane,
                "--view $view shows no bytes, so --tail-bytes would bound nothing. got: $result",
            )
        }
        assertTrue(
            CliParser.parseObservation(arrayOf("--view", "console", "--tail-bytes", "64")) is
                ObservationParseResult.Parsed,
        )
        for (bad in listOf("abc", "0", "-1")) {
            val result = CliParser.parseObservation(arrayOf("--view", "console", "--tail-bytes", bad))
            assertTrue(
                result is ObservationParseResult.Rejected && result.error is CliError.InvalidTailBytes,
                "--tail-bytes '$bad' must be refused, never clamped. got: $result",
            )
        }
    }

    @Test
    fun `TAIL-7 the tail is a start position, so it composes with --limit`() {
        val records = listOf(window(5, "a\n"), window(6, "b\n"), window(7, "c\n"))
        val lanes = Scripted(ObservationOutputRead.Page(page(records[1])), listOf(page(records[1], records[2])))
        val sink = Sink()

        val outcome = MainObserveCli.replay(
            lanes, runId, parsed("--view", "console", "--tail-bytes", "4", "--limit", "1"), sink.out, sink.diagnostics,
        )

        assertEquals(ObserveOutcome.Replayed, outcome)
        assertEquals(4L, lanes.tailBytes, "the tail is resolved once, from the caller's bytes")
        assertEquals("b\n", sink.stdout(), "the tail chose where to start and the limit chose how much")
    }

    @Test
    fun `TAIL-8 run refuses --tail-bytes`() {
        val result = CliParser.parse(arrayOf("run", "--tail-bytes", "64", "pipeline.kts"))

        assertTrue(
            result is CliParseResult.Rejected && result.error is CliError.OptionBelongsToObserve,
            "run cannot report that what it printed was only the end of the transcript. got: $result",
        )
    }
}