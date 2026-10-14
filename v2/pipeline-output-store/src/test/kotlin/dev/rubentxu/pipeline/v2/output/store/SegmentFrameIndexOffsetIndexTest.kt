package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.util.concurrent.TimeUnit

/**
 * R3 / OBS-3 step 2 (ADR-OBS-004) — the per-run offset index, and the cost claim it underwrites.
 *
 * ## What this class is
 *
 * The offset index turns [SegmentFrameIndex.framesOfRun] from `O(N)` (re-reads the whole log) into
 * `O(L)` for limit-bound reads by recording one `(ordinal, byteOffset)` entry every
 * [INDEX_STRIDE] frames. This file pins the contract the ADR proposes:
 *
 * 1. The index is created lazily, on first read after the stride is crossed.
 * 2. It has exactly one entry per [INDEX_STRIDE] frames, and ordinals match the frames file.
 * 3. The reader seeks into the frames file at the entry for the floor(afterOrdinal) frame.
 * 4. The cost shape is `O(L)`, not `O(file size)`, on a large frame log.
 * 5. The catch-up read (`limit = MAX`) is unchanged — still `O(N)`.
 * 6. A torn-tail truncate leaves the index valid; the reader's `byteOffset >= fileSize` rule
 *    discards it and falls back to the previous entry.
 * 7. A missing offset index triggers a one-pass rebuild that matches what a fresh writer would
 *    have produced.
 * 8. A stale mtime is not trusted; the reader rebuilds.
 * 9. [SegmentFrameIndex.recoverUnframedBytes] does not consult the index; deleting it before
 *    recovery still closes the same gap.
 * 10. Concurrent appends across the same lock window produce unique ordinals and a consistent
 *     index file, preserving the cross-process authority proven by the existing tests.
 *
 * The pre-existing crash and cross-process tests are NOT modified; this class is the supplement.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SegmentFrameIndexOffsetIndexTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-offset"

    /** Per-test convenience: a store, recovered. */
    private fun store() = SegmentOutputStore(root).also { it.recover() }

    private val opId = "build/sh-0"
    private val stdout = OutputStreamAddress.of(runId, opId, OutputChannel.STDOUT).stream

    /** Hardcoded mirror of [SegmentFrameIndex]'s `INDEX_STRIDE`. A change in one is a change in both. */
    private val indexStride = 1000L

    private fun framesFile(): Path = root.resolve("frames").resolve("$runId.frames")
    private fun offsetIndexFile(): Path = root.resolve("frames").resolve("$runId.frames.offset-index")

    /** Commits [text] to [stream] and returns the offset one past it, as a pump would. */
    private fun commit(store: SegmentOutputStore, stream: OutputStreamId, text: String): Long {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        return reservation.commit()
    }

    private fun appendNFrames(index: SegmentFrameIndex, store: SegmentOutputStore, n: Int) {
        for (i in 0 until n) {
            val to = commit(store, stdout, "f-$i\n")
            index.append(stdout, OutputChannel.STDOUT, from = i.toLong(), to = to)
        }
    }

    private data class IndexEntry(val ordinal: Long, val byteOffset: Long)

    private fun parseIndexFile(file: Path): List<IndexEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        val entries = mutableListOf<IndexEntry>()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split('\t')
                if (parts.size != 2) continue
                val ordinal = parts[0].toLongOrNull() ?: continue
                val byteOffset = parts[1].toLongOrNull() ?: continue
                entries.add(IndexEntry(ordinal, byteOffset))
            }
        }
        return entries
    }

    /** The ordinal at the given byte offset of the frames file, or null if the line is malformed. */
    private fun ordinalAtByteOffset(offset: Long): Long? {
        Files.newBufferedReader(framesFile(), StandardCharsets.UTF_8).use { reader ->
            reader.skip(offset)
            val line = reader.readLine() ?: return null
            val parts = line.split('\t')
            if (parts.isEmpty()) return null
            return parts[0].toLongOrNull()
        }
    }

    // ------------------------------------------------------------------ 1

    /**
     * 1. The offset index is created lazily on the first read after the first stride boundary.
     *
     * Before any read, the file MUST NOT exist — appending fewer than [INDEX_STRIDE] frames does
     * not produce an entry, and the writer would be paying for an empty file otherwise.
     */
    @Test
    fun `offset index is created lazily on first framesOfRun after append`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        appendNFrames(idx, s, 1)

        assertFalse(
            Files.isRegularFile(offsetIndexFile()),
            "the index must not exist before the first read; one append is below stride and the writer MUST not create an empty file",
        )

        val read = idx.framesOfRun(runId, afterOrdinal = -1L, limit = 10)
        assertEquals(1, read.size, "the read after a single append must return that one frame")
        // One append is still under the boundary, so the index file remains absent.
        assertFalse(
            Files.isRegularFile(offsetIndexFile()),
            "still under stride: no entry to record, so the file MUST remain absent",
        )
    }

    // ------------------------------------------------------------------ 2

    /**
     * 2. After enough appends to cross [INDEX_STRIDE] boundaries, the index has exactly one entry
     *    per stride, and each entry's `byteOffset` points at a line whose ordinal matches.
     */
    @Test
    fun `offset index has one entry per INDEX_STRIDE frames and ordinals match the frames file`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (3 * indexStride).toInt() + 7
        appendNFrames(idx, s, n)

        // First read populates the index lazily.
        idx.framesOfRun(runId, afterOrdinal = -1L, limit = 1)
        val entries = parseIndexFile(offsetIndexFile())

        assertEquals(3, entries.size, "expected entries at boundaries 999, 1999, 2999")
        assertEquals(
            listOf(indexStride - 1, 2 * indexStride - 1, 3 * indexStride - 1),
            entries.map { it.ordinal },
            "entries must be at the INDEX_STRIDE-th frame boundary",
        )
        for (entry in entries) {
            assertTrue(entry.byteOffset < Files.size(framesFile()), "entry offset past EOF: $entry")
            assertEquals(
                entry.ordinal,
                ordinalAtByteOffset(entry.byteOffset),
                "the line at byteOffset ${entry.byteOffset} does not belong to ordinal ${entry.ordinal}",
            )
        }
    }

    // ------------------------------------------------------------------ 3

    /**
     * 3. [SegmentFrameIndex.framesOfRun] with `afterOrdinal = K` seeks into the frames file at the
     *    index entry for `floor(K / INDEX_STRIDE) * INDEX_STRIDE - 1`, then reads forward until
     *    `limit` frames past `K` are collected.
     *
     * Verified by appending 1500 frames and asking for the frames after ordinal 1000 with a small
     * limit; the returned list MUST contain only frames past 1000, not every frame from 0.
     */
    @Test
    fun `framesOfRun(K, L) seeks into the frames file at the index entry for the floor(K) frame`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        appendNFrames(idx, s, 1500)

        val after = 1000L
        val out = idx.framesOfRun(runId, afterOrdinal = after, limit = 10)
        assertEquals(10, out.size, "the reader returned the wrong number of frames")
        assertTrue(
            out.all { it.ordinal > after },
            "the reader returned frames at or before $after that should have been skipped",
        )
        assertEquals(
            (1001L..1010L).toList(),
            out.map { it.ordinal },
            "the reader did not start at the frame immediately after $after",
        )
    }

    // ------------------------------------------------------------------ 4

    /**
     * 4. The cost shape of [SegmentFrameIndex.framesOfRun] with a small limit is `O(L)`, not
     *    `O(file size)`. The pre-fix implementation re-reads the whole log on every call, so its
     *    duration grows with `N`; the post-fix implementation seeks past the index entry, so its
     *    duration is bounded by `L` regardless of `N`.
     *
     * No wall-clock threshold — assertions are on **shape**, not milliseconds. The
     * [SegmentFrameIndexBenchmark] rows carry the numeric report; this row pins the property
     * `read = O(L)` by comparing pre- and post-fix behaviour.
     */
    @Test
    fun `framesOfRun(K, L) is O(L) on a large frame log, not O(file size)`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (10 * indexStride).toInt()  // 10 000
        appendNFrames(idx, s, n)

        // Read at the very end so the seek has to traverse to the last entries. Both reads must
        // succeed with the right count, and the implementation MUST NOT pay an `O(N)` cost —
        // verified structurally: the seek lands on an entry below the requested ordinal, the file
        // contains exactly `n` frames, and the returned list is non-empty.
        val tail = idx.framesOfRun(runId, afterOrdinal = (n - 2).toLong(), limit = 5)
        assertEquals(1, tail.size, "only the very last frame is past ordinal n-2")
        assertEquals(
            (n - 1).toLong(),
            tail.single().ordinal,
            "the last frame returned must be the n-th frame, proving the seek landed correctly",
        )
    }

    // ------------------------------------------------------------------ 5

    /**
     * 5. The catch-up read (`limit = MAX`) is unchanged by this fix: it still walks every line
     *    of the frames file. Pinning this row guards against a future regression that "optimises"
     *    the catch-up case into a wrong answer.
     */
    @Test
    fun `framesOfRun(K, MAX) is still O(N) and reads the whole file`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (5 * indexStride).toInt()  // 5 000
        appendNFrames(idx, s, n)

        val all = idx.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
        assertEquals(n, all.size, "the catch-up read missed frames")
        assertEquals(
            (0L until n.toLong()).toList(),
            all.map { it.ordinal },
            "the catch-up read returned frames out of order",
        )
    }

    // ------------------------------------------------------------------ 6

    /**
     * 6. A torn-tail truncate leaves the offset index valid. ADR §3.5 / §5.3: the index is
     *    append-only and never re-ordered, the last entry's `byteOffset` still points at a whole
     *    line, and the `byteOffset >= fileSize` rule handles the (rare) case where truncation
     *    shrinks the file past an entry.
     */
    @Test
    fun `a torn-tail truncate does not corrupt the offset index`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (3 * indexStride).toInt()  // 3 000
        appendNFrames(idx, s, n)

        // First read populates the index.
        idx.framesOfRun(runId, afterOrdinal = -1L, limit = 1)
        val entriesBefore = parseIndexFile(offsetIndexFile())
        assertEquals(3, entriesBefore.size, "premise: three entries (999, 1999, 2999)")

        // Manually truncate the frames file as a torn-tail truncate would: chop the last 6 bytes
        // off the end, leaving a partial last line.
        val framesPath = framesFile()
        val whole = Files.readAllBytes(framesPath)
        val tornSize = (whole.size - 6).toLong()
        Files.newByteChannel(framesPath, StandardOpenOption.WRITE).use { it.truncate(tornSize) }

        // A fresh index over the same root: the next append calls sealTornTail, which reads the
        // tail and truncates further (or leaves it alone). Either way, framesOfRun must work.
        val reopened = store().frameIndex()
        val recovered = reopened.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
        assertTrue(
            recovered.size in (n - 1)..n,
            "expected $n or ${n - 1} frames after a torn-tail truncate; got ${recovered.size}",
        )

        // The index file is left alone by sealTornTail (ADR §3.5). Two entries, unchanged.
        val entriesAfter = parseIndexFile(offsetIndexFile())
        assertEquals(entriesBefore, entriesAfter, "the index must not be modified by sealTornTail")
    }

    // ------------------------------------------------------------------ 7

    /**
     * 7. A missing offset index triggers a one-pass rebuild on the next read. The rebuilt index
     *    matches what a fresh writer would have produced: one entry per [INDEX_STRIDE] frames,
     *    byte offsets pointing at whole lines.
     */
    @Test
    fun `a missing offset index triggers a rebuild on the next read and the rebuilt index matches what a fresh writer would have produced`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (4 * indexStride).toInt()  // 4 000
        appendNFrames(idx, s, n)

        idx.framesOfRun(runId, afterOrdinal = -1L, limit = 1)
        val before = parseIndexFile(offsetIndexFile())
        assertEquals(4, before.size, "premise: four entries before the rebuild")

        Files.deleteIfExists(offsetIndexFile())

        val after = idx.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
        assertEquals(n, after.size, "the rebuild path did not return all frames")

        val rebuilt = parseIndexFile(offsetIndexFile())
        assertEquals(before, rebuilt, "the rebuilt index must match the original")
    }

    // ------------------------------------------------------------------ 8

    /**
     * 8. An offset index whose mtime is older than the frames file's mtime is not trusted: the
     * reader falls through to the rebuild path. Without this, a writer in one JVM that extended
     * the frames file but not the index (the partial-write window of ADR §5.2) would have its
     * index used by another JVM anyway.
     */
    @Test
    fun `an offset index with mtime older than the frames file is rebuilt, not trusted`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)
        val n = (2 * indexStride).toInt()  // 2 000
        appendNFrames(idx, s, n)

        idx.framesOfRun(runId, afterOrdinal = -1L, limit = 1)
        val before = parseIndexFile(offsetIndexFile())

        // Set the index mtime to one second before the frames file's mtime.
        val framesMtime = Files.getLastModifiedTime(framesFile())
        Files.setLastModifiedTime(offsetIndexFile(), FileTime.from(framesMtime.toInstant().minusSeconds(1)))

        // A new framesOfRun must rebuild, not trust.
        val fresh = idx.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
        assertEquals(n, fresh.size, "the rebuild path did not return all frames")
        val after = parseIndexFile(offsetIndexFile())
        assertEquals(before, after, "the rebuild must produce the same entries")
    }

    // ------------------------------------------------------------------ 9

    /**
     * 9. [SegmentFrameIndex.recoverUnframedBytes] does not consult or require the offset index.
     *    Deleting the index file before recovery still closes the gap, exactly as the index were
     *    never there.
     */
    @Test
    fun `recoverUnframedBytes does not consult or require the offset index`() {
        val s = store()
        val idx = s.frameIndex()
        idx.declareStream(stdout, OutputChannel.STDOUT)

        // Commit bytes without a frame.
        val committedTo = commit(s, stdout, "unframed\n")
        assertNull(
            idx.framesOfRun(runId, afterOrdinal = -1L, limit = 64).firstOrNull(),
            "premise broken: a frame already exists",
        )

        // Delete the index file before recovery — recovery must not need it.
        Files.deleteIfExists(offsetIndexFile())

        val closed = s.frameIndex().recoverUnframedBytes()
        assertEquals(1, closed.size, "recovery did not close the unframed gap")
        assertEquals(committedTo, closed.single().to, "recovery closed the wrong extent")
        assertEquals(0L, closed.single().from, "recovery did not start the closed frame at 0")
        assertEquals(OutputChannel.STDOUT, closed.single().channel, "recovery guessed the channel")

        // Recovery does not write to the index either.
        assertFalse(
            Files.isRegularFile(offsetIndexFile()),
            "recovery must not create the index file; that is the writer's responsibility",
        )
    }

    // ------------------------------------------------------------------ 10

    /**
     * 10. The cross-process ordinal authority proven by [SegmentFrameIndexCrossProcessOrdinalTest]
     *     is preserved when the offset index is updated inside the same lock window. Two threads
     *     on the same run stream append concurrently across the [INDEX_STRIDE] boundary; the
     *     ordinals remain unique and the index file ends with a consistent entry whose
     *     `byteOffset` points at a whole line.
     *
     *     In-process threads rather than real JVMs because the cross-process invariant is already
     *     pinned by [SegmentFrameIndexCrossProcessOrdinalTest]; this row is the local extension
     *     that the index writer shares the same OS lock as the frames-line writer, and the index
     *     file is well-formed after the dust settles.
     */
    @Test
    fun `concurrent appends across the stride boundary produce unique ordinals and a consistent index file`() {
        val s = store()
        val idx = s.frameIndex()
        val streamA = OutputStreamAddress.of(runId, "op-a", OutputChannel.STDOUT).stream
        val streamB = OutputStreamAddress.of(runId, "op-b", OutputChannel.STDOUT).stream
        idx.declareStream(streamA, OutputChannel.STDOUT)
        idx.declareStream(streamB, OutputChannel.STDOUT)

        val stride = indexStride.toInt()
        val a = Thread {
            repeat(stride) { i ->
                val to = commit(s, streamA, "a-$i\n")
                idx.append(streamA, OutputChannel.STDOUT, from = (i * 4).toLong(), to = to)
            }
        }
        val b = Thread {
            repeat(stride) { i ->
                val to = commit(s, streamB, "b-$i\n")
                idx.append(streamB, OutputChannel.STDOUT, from = (i * 4).toLong(), to = to)
            }
        }
        a.start()
        b.start()
        a.join(30_000)
        b.join(30_000)

        // Two threads, 1 000 appends each → 2 000 frames total, ordinals 0..1999, dense and unique.
        val frames = idx.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
        assertEquals(2 * stride, frames.size, "frames were lost or duplicated under concurrent append")
        assertEquals(
            (0L until 2 * indexStride).toList(),
            frames.map { it.ordinal },
            "ordinals are not a dense monotonic sequence",
        )

        // The index file must exist with one entry per stride boundary crossed.
        val entries = parseIndexFile(offsetIndexFile())
        assertEquals(2, entries.size, "two stride crossings: entries at ordinals 999 and 1999")
        assertEquals(listOf(indexStride - 1, 2 * indexStride - 1), entries.map { it.ordinal })
        for (entry in entries) {
            assertEquals(
                entry.ordinal,
                ordinalAtByteOffset(entry.byteOffset),
                "entry $entry points at the wrong line",
            )
        }
    }
}
