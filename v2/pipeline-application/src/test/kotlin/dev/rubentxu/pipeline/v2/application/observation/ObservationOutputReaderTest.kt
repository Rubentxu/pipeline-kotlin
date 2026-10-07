package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * OBS-D2: the output lane of the read model, over the two real Output Plane authorities.
 *
 * ## Harness fidelity
 *
 * HF2 — a real [SegmentOutputStore] on a real filesystem, driven through the same
 * `declareStream` → `reserve`/`write`/`commit` → `append` order `ShExecution` uses, read back
 * through the production [dev.rubentxu.pipeline.v2.output.OutputFrameIndex]. The harness crosses the
 * productive authority: it does not reimplement the store, and it does not build the frames it
 * asserts on.
 *
 * The one exception is [REFUSE-1], which uses a read-port double. That is stated in the row rather
 * than hidden: the store's own refusal taxonomy is already certified by `OutputPlaneConformanceTest`
 * and `SegmentOutputStoreTest`, and what this row adds is the reader's obligation to PROPAGATE a
 * refusal instead of reporting an empty page.
 *
 * ## What this pins
 *
 * ```text
 * LANE-1   frames arrive in observation order, each carrying its channel
 * LANE-2   a frame's bytes are its OWN range: later writes do not grow it
 * LANE-3   moreFrames reports truncation by the limit, never "more will arrive"
 * LANE-4   an empty page resumes from where the caller already was
 * CONSERVE every committed byte appears exactly once across the page
 * SPLIT-1  a character split across frames is lost at the boundary, deterministically
 * REFUSE-1 a refusal is propagated, not answered with an empty page
 * ```
 *
 * ## Mutation
 *
 * - `M-D2` (frames are read at their own range) → read `maxBytes` from the stream's current
 *   committed end instead of from `frame.from`.
 * - `M-D3` (moreFrames means truncation) → always report `false`.
 * - `M-D4` (empty page keeps the resume position) → advance `lastOrdinal` by one on an empty page.
 * - `M-D5` (refusal propagates) → return an empty page instead of `Refused`.
 */
class ObservationOutputReaderTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: SegmentOutputStore
    private lateinit var reader: ObservationOutputReader

    private val runId = "run-obsd2"
    private val stdout = OutputStreamId("$runId/build/sh-0/stdout")
    private val stderr = OutputStreamId("$runId/build/sh-0/stderr")

    @BeforeEach
    fun setUp() {
        store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
        reader = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)
    }

    /** The production order: declare, write, commit, THEN append the frame. */
    private fun publish(stream: OutputStreamId, channel: OutputChannel, text: String): Long {
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
        store.frameIndex().append(stream, channel, from, from + payload.size)
        return from
    }

    private fun page(result: ObservationOutputRead): ObservationOutputPage =
        assertInstanceOf(
            ObservationOutputRead.Page::class.java,
            result,
            "expected a page, got $result",
        ).page

    @Test
    fun `LANE-1 frames arrive in observation order, each carrying its channel`() {
        publish(stdout, OutputChannel.STDOUT, "compiling A\n")
        publish(stderr, OutputChannel.STDERR, "warning B\n")
        publish(stdout, OutputChannel.STDOUT, "compiling C\n")

        val page = page(reader.readOutput(runId, -1, frameLimit = 10))

        assertEquals(3, page.records.size, "got ${page.records.map { it.frame.ordinal }}")
        assertEquals(
            listOf(OutputChannel.STDOUT, OutputChannel.STDERR, OutputChannel.STDOUT),
            page.records.map { it.channel },
            "the lanes must interleave in OBSERVATION order, which is the only order the index can " +
                "speak about — and it is not stdout-then-stderr, which is what a fixed-order " +
                "concat would have produced",
        )
        assertEquals("compiling A\nwarning B\ncompiling C\n", page.text)
    }

    @Test
    fun `LANE-2 a frame's bytes are its own range and later writes do not grow it`() {
        val from = publish(stdout, OutputChannel.STDOUT, "first\n")
        // A second write lands AFTER the frame the reader is about to read.
        publish(stdout, OutputChannel.STDOUT, "second\n")

        val page = page(reader.readOutput(runId, -1, frameLimit = 1))

        assertEquals(1, page.records.size)
        assertEquals("first\n", page.records.single().text)
        assertEquals(
            from,
            page.records.single().frame.from,
            "the frame names the range it published; reading past it would make a replay see a " +
                "different window than the first time",
        )
    }

    @Test
    fun `LANE-3 moreFrames reports truncation by the limit and not more than that`() {
        repeat(3) { publish(stdout, OutputChannel.STDOUT, "line $it\n") }

        val truncated = page(reader.readOutput(runId, -1, frameLimit = 2))
        assertTrue(truncated.moreFrames, "the index had a frame left after the two it returned")
        assertEquals(2, truncated.records.size)

        val complete = page(reader.readOutput(runId, truncated.lastOrdinal, frameLimit = 10))
        assertFalse(
            complete.moreFrames,
            "the index is exhausted. This says nothing about whether more bytes will ever arrive — " +
                "that is OutputTailState's question, and answering it here is the next == null mistake",
        )
        assertEquals(1, complete.records.size)
    }

    @Test
    fun `LANE-4 an empty page resumes from where the caller already was`() {
        publish(stdout, OutputChannel.STDOUT, "only line\n")

        val first = page(reader.readOutput(runId, -1, frameLimit = 10))
        val exhausted = page(reader.readOutput(runId, first.lastOrdinal, frameLimit = 10))

        assertTrue(exhausted.records.isEmpty())
        assertEquals(
            first.lastOrdinal,
            exhausted.lastOrdinal,
            "an empty page must hand back the position it was given, so a caller can always resume " +
                "from the value on the page it just received instead of tracking what it asked for",
        )
    }

    @Test
    fun `CONSERVE every committed byte appears exactly once across the page`() {
        publish(stdout, OutputChannel.STDOUT, "alpha\n")
        publish(stderr, OutputChannel.STDERR, "beta\n")
        publish(stdout, OutputChannel.STDOUT, "gamma\n")

        val page = page(reader.readOutput(runId, -1, frameLimit = 10))

        val committed = listOf(stdout, stderr).sumOf { store.committedExtent(it) ?: 0L }
        val delivered = page.records.sumOf { it.frame.length }
        assertEquals(
            committed,
            delivered,
            "the page must carry every committed byte exactly once: no frame may be skipped and no " +
                "range may be counted twice",
        )
        assertEquals(
            listOf(0L, 6L),
            page.records.filter { it.frame.stream == stdout }.map { it.frame.from },
            "stdout frames must be contiguous in THEIR stream: alpha at 0, gamma at 6",
        )
        assertEquals(
            listOf(0L),
            page.records.filter { it.frame.stream == stderr }.map { it.frame.from },
            "stderr has its own byte space, which is exactly why OBS-C2.3 gave each channel a " +
                "stream instead of interleaving them into one offset line",
        )
    }

    @Test
    fun `SPLIT-1 a character split across frames is lost at the boundary, deterministically`() {
        // The euro sign is three bytes; the first two are published alone.
        val euro = "€".toByteArray(Charsets.UTF_8)
        assertEquals(3, euro.size, "premise broken: the character is not multi-byte")

        store.frameIndex().declareStream(stdout, OutputChannel.STDOUT)
        val handle = store.open(stdout)
        val first = handle.reserve(2)
        first.write(euro.copyOfRange(0, 2))
        first.commit()
        store.frameIndex().append(stdout, OutputChannel.STDOUT, 0, 2)

        val handle2 = store.open(stdout)
        val second = handle2.reserve(1)
        second.write(euro.copyOfRange(2, 3))
        second.commit()
        store.frameIndex().append(stdout, OutputChannel.STDOUT, 2, 3)

        val text = page(reader.readOutput(runId, -1, frameLimit = 10)).text

        assertFalse(
            text.contains('€'),
            "the character must NOT survive the split. Expected got: '$text'",
        )
        assertTrue(
            text.all { it == '�' },
            "every byte of the broken sequences becomes a replacement, and nothing else does: the " +
                "split costs the character in exactly the frames it straddles, deterministically. " +
                "A reader-local carry-over would instead make these same bytes decode differently " +
                "depending on where the reader resumed, and the Output Plane keeps nothing to " +
                "rebuild the missing half. Got '$text'",
        )
        // Determinism is the actual claim: the same bytes must decode the same way every time.
        assertEquals(text, page(reader.readOutput(runId, -1, frameLimit = 10)).text)
    }

    @Test
    fun `REFUSE-1 a refusal is propagated rather than answered with an empty page`() {
        val refusing = FrameIndexedObservationOutputReader(
            store.frameIndex(),
            object : OutputReadPort {
                override fun committedExtent(stream: OutputStreamId): Long? = 0L

                override fun read(
                    stream: OutputStreamId,
                    cursor: OutputCursor,
                    maxBytes: Int,
                ): OutputReadResult = OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))

                override fun readRange(
                    stream: OutputStreamId,
                    from: Long,
                    to: Long,
                ): OutputReadResult = OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
            },
            store,
        )
        publish(stdout, OutputChannel.STDOUT, "text that exists but cannot be read\n")

        val result = refusing.readOutput(runId, -1, frameLimit = 10)

        val refused = assertInstanceOf(
            ObservationOutputRead.Refused::class.java,
            result,
            "a store that cannot answer must NOT look like a run that produced no output: a --follow " +
                "consumer reading an empty page here would poll a broken store forever, and one " +
                "reading it as 'the step was silent' would report truncation as silence. Got $result",
        )
        assertInstanceOf(OutputRefusal.UnknownStream::class.java, refused.reason)
    }

    @Test
    fun `BOUND-1 a non-positive window is refused rather than silently read as everything`() {
        val failure = runCatching { reader.readOutput(runId, -1, frameLimit = 0) }

        assertTrue(
            failure.isFailure,
            "frameLimit = 0 must not quietly become 'unbounded'; an unbounded read is the mechanism " +
                "by which a follow loop turns a large build log into memory pressure",
        )
    }
}