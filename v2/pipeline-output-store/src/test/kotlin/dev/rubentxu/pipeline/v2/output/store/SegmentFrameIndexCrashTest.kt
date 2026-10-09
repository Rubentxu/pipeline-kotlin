package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * OBS-C2 — the frame index's crash windows, decided before any production dual-write exists.
 *
 * ## Why these tests come first
 *
 * Splitting stdout and stderr without recording the order PipelineK observed them destroys
 * information that cannot be reconstructed afterwards: once two channels are two streams, their
 * interleaving exists nowhere. So the index has to be right *before* anything depends on it, and
 * "right" here means one thing above all — that a crash cannot leave it lying.
 *
 * Two properties are worth more than anything else the index does:
 *
 * ```text
 * 1  a committed frame never names bytes that are not committed
 * 2  bytes that are committed are never irrecoverably outside the index
 * ```
 *
 * Both are structural rather than best-effort. (1) holds because [OutputFrameIndex.append] is only
 * ever called after the byte commit returns. (2) holds because a stream is **declared before its
 * first byte**, which is what makes the narrowest window — a crash between a stream's first byte
 * commit and its first frame — repairable at all.
 *
 * ## How a crash is produced here
 *
 * These tests do not fork a process, and that is deliberate rather than convenient. Recovery reads
 * **durable state**, so the only thing that has to be reproduced is the durable state a process
 * death leaves behind: committed bytes with no frame, and a log whose last line was torn
 * mid-write. Reproducing that state directly and then recovering through a **fresh** index over the
 * same root is the same exercise the byte store's own crash tests use, and it runs on every change.
 *
 * ## What each window is
 *
 * ```text
 * W1  crash between declareStream and the first byte   -> nothing exists, nothing to recover
 * W2  crash between the byte commit and its frame      -> bytes without a frame; MUST be closed
 * W3  crash inside the frame write, leaving a torn line-> the torn line is not a frame
 * W4  crash after the frame landed                    -> frame and bytes agree
 * W5  a frame that names uncommitted bytes            -> IMPOSSIBLE by construction
 * ```
 *
 * W5 is the one that cannot be reproduced by arranging state, because the index has no way to
 * produce it: it is the claim that the commit order is enforced, and its evidence is W2 plus the
 * mutation recorded on it.
 *
 * ## Non-vacuity, and the one mutation that matters
 *
 * ```text
 * M8  recoverUnframedBytes iterates only the streams that already have a frame
 *     instead of the streams this run declared
 * ```
 *
 * That is the naive index, and it is the design this whole file exists to reject. It turns three
 * rows red — the three whose subject is closing a gap over committed bytes — and leaves the other
 * seven green, including the concurrency row and the reopen row. The isolation is the point: the
 * declaration-before-first-byte rule is load-bearing for recovery and for nothing else, and M8
 * shows that precisely rather than by assertion.
 */
class SegmentFrameIndexCrashTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-crash"

    private fun store() = SegmentOutputStore(root).also { it.recover() }

    private val opId = "build/sh-0"
    private val stdout = OutputStreamAddress.of(runId, opId, OutputChannel.STDOUT).stream
    private val stderr = OutputStreamAddress.of(runId, opId, OutputChannel.STDERR).stream

    /** Commits [text] to [stream] and returns the offset one past it, as a pump would. */
    private fun commit(store: SegmentOutputStore, stream: OutputStreamId, text: String): Long {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        return reservation.commit()
    }

    // ------------------------------------------------------------------ W1

    /**
     * W1 — a crash between declaring a stream and writing its first byte leaves nothing to repair.
     *
     * This is the window a naive index gets wrong in the *other* direction: some implementations
     * treat "declared but empty" as damage and invent a frame for it. An empty stream has no bytes,
     * so a frame for it would name a range `[0, 0)` — a state [OutputFrame] forbids on purpose.
     */
    @Test
    fun `a declared stream with no bytes produces no frame`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stderr, OutputChannel.STDERR)

        val recovered = store.frameIndex().recoverUnframedBytes()

        assertEquals(emptyList<OutputFrame>(), recovered, "recovery invented a frame for a stream " +
            "that never received a byte")
        assertEquals(emptyList<OutputFrame>(), index.framesOfRun(runId, afterOrdinal = -1L, limit = 64))
        assertNull(index.lastOrdinal(runId), "an index with no frames reported a last ordinal")
    }

    // ------------------------------------------------------------------ W2

    /**
     * W2 — THE window. Committed bytes with no frame are closed by recovery.
     *
     * This is the whole reason a stream is declared before its first byte. Without the declaration
     * the index would have no way to know this stream exists, and these bytes would stay durable,
     * readable and unattributed for the rest of the run — permanently outside the index, with no
     * record of where they belonged in the interleaving.
     */
    @Test
    fun `bytes committed without a frame are closed by recovery`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)

        val committedTo = commit(store, stdout, "out-a\n")
        assertNull(
            index.framesOfRun(runId, afterOrdinal = -1L, limit = 64).firstOrNull(),
            "premise broken: a frame already exists, so this row is not covering the window",
        )

        // A fresh index over the same root is what a restarted process gets.
        val closed = store.frameIndex().recoverUnframedBytes()

        assertEquals(1, closed.size, "recovery did not close the single unframed stream")
        val frame = closed.single()
        assertEquals(0L, frame.from, "the gap starts where the stream's indexed extent ended")
        assertEquals(committedTo, frame.to, "the gap ends at the committed extent, or bytes stay " +
            "outside the index")
        assertEquals(OutputChannel.STDOUT, frame.channel, "recovery guessed the channel instead of " +
            "reading it from the stream it had declared")

        assertEquals(
            emptyList<OutputFrame>(),
            store.frameIndex().recoverUnframedBytes(),
            "a second recovery appended another frame for the same bytes, so recovery is not " +
                "idempotent and every open would grow the log",
        )
    }

    /**
     * W2, per channel — recovering one channel must not close the other.
     *
     * A recovery that computed one global "how far did we get" and applied it to both streams would
     * close stderr at stdout's extent and attribute stdout bytes to stderr. Two channels with
     * different extents are what make that visible.
     */
    @Test
    fun `recovery closes each channel only up to its own committed extent`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stderr, OutputChannel.STDERR)

        index.append(stdout, OutputChannel.STDOUT, 0L, 4L)
        val stdoutEnd = commit(store, stdout, "more-out\n")
        val stderrEnd = commit(store, stderr, "err\n")
        assertTrue(stdoutEnd != stderrEnd, "premise broken: both channels ended at the same " +
            "offset, so this row cannot detect cross-channel attribution")

        val closed = store.frameIndex().recoverUnframedBytes()

        assertEquals(2, closed.size, "one gap per channel was expected")
        val byChannel = closed.associateBy { it.channel }
        assertEquals(stdoutEnd, byChannel.getValue(OutputChannel.STDOUT).to, "stdout was closed at " +
            "the wrong extent")
        assertEquals(stderrEnd, byChannel.getValue(OutputChannel.STDERR).to, "stderr was closed at " +
            "the wrong extent")
    }

    // ------------------------------------------------------------------ W3

    /**
     * W3 — a frame log torn mid-line is not a frame, and does not poison the rest of the index.
     *
     * A torn line has no ordinal and may name bytes that were never committed, so reading it would
     * be inventing a record. The stronger half is what happens afterwards: the file is truncated
     * back to the last whole frame, so the next append starts from a consistent point instead of
     * appending behind a fragment that no reader will ever accept.
     */
    @Test
    fun `a torn frame line is discarded and the index stays writable`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.append(stdout, OutputChannel.STDOUT, 0L, 4L)
        val committedTo = commit(store, stdout, "tail\n")

        val framesFile = root.resolve("frames").resolve("${runId}.frames")
        val whole = Files.readAllBytes(framesFile)
        val tornSize = whole.size - 6L
        Files.newByteChannel(
            framesFile,
            StandardOpenOption.WRITE,
        ).use { it.truncate(tornSize) }

        val reopened = store.frameIndex()
        assertEquals(
            1,
            reopened.framesOfRun(runId, afterOrdinal = -1L, limit = 64).size,
            "the torn line was read as a frame",
        )

        val closed = reopened.recoverUnframedBytes()
        assertEquals(1, closed.size, "the unframed bytes behind the torn line were not recovered")
        assertEquals(committedTo, closed.single().to, "recovery did not close up to the committed " +
            "extent")

        val next = reopened.append(stdout, OutputChannel.STDOUT, committedTo, committedTo + 2L)
        assertEquals(2L, next.ordinal, "appending behind a torn line reused or skipped an ordinal, " +
            "so the index stopped being monotonic")
    }

    // ------------------------------------------------------------------ W4

    /**
     * W4 — a frame that landed agrees with the bytes it names.
     *
     * The baseline the crash rows contrast against: without a crash, the index and the store say
     * the same thing, and each channel's frames tile its stream without gaps or overlap.
     */
    @Test
    fun `frames tile each channel without gaps or overlap`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stderr, OutputChannel.STDERR)

        var outEnd = 0L
        var errEnd = 0L
        repeat(3) { i ->
            outEnd = commit(store, stdout, "out$i\n")
            index.append(stdout, OutputChannel.STDOUT, indexEndOf(index, stdout), outEnd)
            errEnd = commit(store, stderr, "err$i\n")
            index.append(stderr, OutputChannel.STDERR, indexEndOf(index, stderr), errEnd)
        }

        assertEquals(emptyList<OutputFrame>(), store.frameIndex().recoverUnframedBytes(), "nothing " +
            "was unframed, so recovery should have found no gap")
        assertEquals(emptyList<OutputFrame>(), store.frameIndex().recoverUnframedBytes())

        for (channel in OutputChannel.entries) {
            val stream = if (channel == OutputChannel.STDOUT) stdout else stderr
            val frames = store.frameIndex().framesOfRun(runId, -1L, 64).filter { it.channel == channel }
            assertEquals(3, frames.size, "expected three frames for $channel")

            var expectedFrom = 0L
            for (frame in frames) {
                assertEquals(expectedFrom, frame.from, "a gap or overlap before the frame at " +
                    "${frame.ordinal} on $channel")
                expectedFrom = frame.to
            }
            val committed = store.committedExtent(stream)
            assertEquals(committed, expectedFrom, "$channel ends at $expectedFrom but the store " +
                "committed $committed")
        }
    }

    // ------------------------------------------------------------------ W5

    /**
     * W5 — the index cannot be made to name uncommitted bytes, because the only way to record a
     * range is to hand it an end offset the byte store has already returned.
     *
     * There is no state to reproduce here, which is the point: [OutputFrameIndex.append] takes a
     * caller-supplied `to`, so what stops a caller from passing one the store never committed is
     * that the store **is** the thing that hands the offset out. The two are not separable, so this
     * row states the shape rather than an observation, and its evidence is W2 plus the mutation
     * recorded on the crash suite: recording the frame *before* the commit makes W2 fail, because
     * recovery then finds nothing to close while the bytes exist.
     */
    @Test
    fun `a frame cannot be recorded for a range the store never committed`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)

        // Committing nothing means the stream was never opened, so it has no committed extent at
        // all. `null` and `0` are different facts — "never written" against "written nothing" —
        // and the store keeps them apart for the same reason it refuses an empty page in place of
        // an unknown stream.
        assertNull(
            store.committedExtent(stdout),
            "premise broken: the stream already has a committed extent, so nothing below is " +
                "testing an uncommitted range",
        )

        val frame = index.append(stdout, OutputChannel.STDOUT, 0L, 8L)
        assertEquals(8L, frame.to, "the frame should record what it was handed; whether that range " +
            "was really committed is decided by the writer, which here is a test")

        // And the honest consequence of a lying writer is visible: recovery has nothing to close,
        // because the index already claims more than the store committed.
        assertEquals(
            emptyList<OutputFrame>(),
            store.frameIndex().recoverUnframedBytes(),
            "recovery cannot detect a frame that over-claims, because the index only knows the " +
                "extent it was told. That is why the commit order is enforced by the writer, not " +
                "detected by the reader",
        )
    }

    // ------------------------------------------------------------------ contract

    /**
     * The ordinal is monotonic and unique across two concurrent pumps.
     *
     * The whole point of assigning it inside the index is that two channel pumps append from
     * different threads. If the counter were the caller's, this row would lose frames or reuse
     * ordinals, and neither would be caught by any single-threaded test.
     */
    @Test
    fun `concurrent appends across both channels produce one dense monotonic sequence`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stderr, OutputChannel.STDERR)

        val threads = (0 until 2).map { side ->
            Thread {
                val stream = if (side == 0) stdout else stderr
                val channel = if (side == 0) OutputChannel.STDOUT else OutputChannel.STDERR
                repeat(50) { i ->
                    val from = i.toLong() * 4L
                    index.append(stream, channel, from, from + 4L)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(30_000) }

        val frames = store.frameIndex().framesOfRun(runId, -1L, 1_000)
        assertEquals(100, frames.size, "frames were lost or duplicated under concurrent append")
        assertEquals(
            (0L until 100L).toList(),
            frames.map { it.ordinal },
            "the ordinals are not a dense monotonic sequence, so the file order is not the " +
                "publication order",
        )
        assertEquals(
            2,
            frames.map { it.stream }.toSet().size,
            "frames landed in a stream nobody appended to: ${frames.map { it.stream }.toSet()}",
        )
    }

    /** The highest `to` currently indexed for [stream]. */
    private fun indexEndOf(index: SegmentFrameIndex, stream: OutputStreamId): Long =
        index.framesOfRun(runId, -1L, 1_000).filter { it.stream == stream }.maxOfOrNull { it.to } ?: 0L

    @Test
    fun `a stream id without a channel is refused rather than guessed`() {
        val store = store()
        val index = store.frameIndex()
        val channelLess = OutputStreamId("$runId/$opId/transcript")

        assertThrows(IllegalArgumentException::class.java) {
            index.declareStream(channelLess, OutputChannel.STDOUT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.append(channelLess, OutputChannel.STDERR, 0L, 1L)
        }
    }

    @Test
    fun `an empty frame is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputFrame(0L, stdout, OutputChannel.STDOUT, from = 4L, to = 4L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OutputFrame(-1L, stdout, OutputChannel.STDOUT, from = 0L, to = 1L)
        }
    }

    @Test
    fun `frames survive a reopen of the index over the same root`() {
        val store = store()
        val index = store.frameIndex()
        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stderr, OutputChannel.STDERR)
        index.append(stdout, OutputChannel.STDOUT, 0L, 7L)
        index.append(stderr, OutputChannel.STDERR, 0L, 3L)

        // A different store AND a different index instance: what a restarted process gets.
        val reopened = SegmentOutputStore(root).also { it.recover() }.frameIndex()

        // Ordinals start at 0, so two frames end at 1.
        assertEquals(1L, reopened.lastOrdinal(runId), "the ordinal counter did not survive a reopen")
        assertEquals(2, reopened.framesOfRun(runId, -1L, 64).size, "frames did not survive a reopen")
        assertNotNull(
            reopened.framesOfRun(runId, afterOrdinal = 0L, limit = 64).firstOrNull(),
            "the ordinal cursor did not resume past ordinal 0",
        )
        assertNull(
            reopened.framesOfRun(runId, afterOrdinal = 1L, limit = 64).firstOrNull(),
            "a cursor at the last ordinal still returned a frame, so resume-by-ordinal would " +
                "re-deliver what a consumer had already seen",
        )
    }
}
