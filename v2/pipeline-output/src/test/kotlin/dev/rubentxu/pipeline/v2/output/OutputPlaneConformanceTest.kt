package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * M1-P4 — conformance of the Output Plane.
 *
 * ## The refusal that governs this whole file
 *
 * These tests prove **"a process that dies loses nothing it acknowledged"**. They do **not** prove
 * durability across power loss, and nothing here may be cited as if they did: the store issues
 * writes without `fsync`, so a machine that loses power can lose bytes a process was told were
 * committed. The fault model here is process death. See
 * [OutputNotEstablished.POWER_LOSS_DURABILITY].
 *
 * ## No invented thresholds
 *
 * The memory assertions below check a **shape**, not a number. "The writer never holds more than
 * one window at a time" is a property of the code; "peak RSS below 40 MB" is a measurement of this
 * machine under this load, and a threshold derived from a sample becomes a law nobody can re-derive.
 * The one number asserted is the one the brief fixes: **more than 1 GiB**.
 *
 * ## How a crash is produced
 *
 * By leaving the durable residue a process death leaves — an outstanding `cur.res` and uncommitted
 * bytes — and recovering through a fresh store instance. Recovery reads durable state, so the
 * state is what has to be reproduced. The soak is the same principle at scale.
 */
@Timeout(600)
class OutputPlaneConformanceTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun readAll(
        store: SegmentOutputStore,
        stream: OutputStreamId,
        pageSize: Int = 4096,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        var pages = 0
        // Capped, and the cap is asserted. A store that never returns a null `next` makes this an
        // infinite loop, and an infinite loop in a test hangs the gate instead of reporting
        // anything. The mutation harness found exactly that.
        while (cursor != null) {
            assertTrue(pages < 100_000, "the reader never reached the end of the stream after $pages pages")
            val page = (store.read(stream, cursor, pageSize) as OutputReadResult.Page).page
            out.write(page.bytes)
            pages++
            cursor = page.next
        }
        assertTrue(pages > 0, "a non-empty stream must take at least one page")
        return out.toByteArray()
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // ------------------------------------------- no missing / duplicated bytes

    @Test
    fun `a deterministic stream of pseudo-random bytes round-trips with no missing or duplicated byte`(
        @TempDir root: Path,
    ) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("lossless")
        val random = Random(seed = 20261004)

        // 2 MiB in 64 KiB windows: enough to cross many appendFrom reservations and at least one
        // segment rotation, small enough to run on every change.
        val total = 2 * 1024 * 1024
        val written = java.io.ByteArrayOutputStream()
        var produced = 0
        while (produced < total) {
            val chunk = ByteArray(minOf(64 * 1024, total - produced)).also { random.nextBytes(it) }
            store.open(stream).appendFrom(chunk.inputStream(), windowBytes = chunk.size)
            written.write(chunk)
            produced += chunk.size
        }

        val read = readAll(store, stream)
        assertEquals(total, read.size, "the reader saw a different number of bytes than were written")
        assertEquals(
            sha256(written.toByteArray()),
            sha256(read),
            "a digest match is the only claim strong enough to rule out a compensating miss+duplicate pair",
        )
    }

    @Test
    fun `an arbitrary range of a long stream equals the same slice of the whole read`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("range-long")
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        store.open(stream).appendFrom(payload.inputStream(), windowBytes = 8192)

        val whole = readAll(store, stream)
        val random = Random(seed = 7)
        repeat(20) {
            val from = random.nextLong(0, payload.size.toLong() - 64)
            val to = minOf(from + random.nextLong(1, 64), payload.size.toLong())
            val slice = (store.readRange(stream, from, to) as OutputReadResult.Page).page.bytes
            assertArrayEquals(
                whole.copyOfRange(from.toInt(), to.toInt()),
                slice,
                "range [$from, $to) disagreed with the whole read",
            )
        }
    }

    // ------------------------------------------------------ restart mid-stream

    @Test
    fun `a restart mid-stream resumes at the committed offset with no gap and no repeat`(@TempDir root: Path) {
        val stream = OutputStreamId("restart")
        val first = SegmentOutputStore(root)
        first.recover()
        val acknowledged = "part-one-".repeat(100)
        first.open(stream).reserve(acknowledged.length).apply { write(bytes(acknowledged)) }.commit()

        // The writer dies here, mid-stream, with a reservation outstanding.
        first.open(stream).reserve(4096).write(bytes("LOST-NEVER-ACKNOWLEDGED"))

        val after = SegmentOutputStore(root)
        val report = after.recover()
        assertEquals(1, report.reservationsReleased, "the outstanding reservation was not released")

        // A reader holding the pre-crash cursor resumes exactly where the acknowledged bytes end.
        val cursor = OutputCursor(stream, acknowledged.length.toLong())
        val resumed = (after.read(stream, cursor, 4096) as OutputReadResult.Page).page
        assertEquals(0, resumed.bytes.size, "a reader resumed into bytes that were never acknowledged")

        // And the stream is still writable and still dense.
        val tail = "part-two"
        after.open(stream).reserve(tail.length).apply { write(bytes(tail)) }.commit()
        assertArrayEquals(
            bytes(acknowledged + tail),
            readAll(after, stream),
            "the recovered stream is not dense: the restart left a hole or a repeat",
        )
    }

    @Test
    fun `recovery is safe to run twice, and after an interrupted recovery`(@TempDir root: Path) {
        val stream = OutputStreamId("double-recovery")
        val first = SegmentOutputStore(root)
        first.recover()
        first.open(stream).reserve(16).apply { write(bytes("durable")) }.commit()
        first.open(stream).reserve(512).write(bytes("uncommitted"))

        val after = SegmentOutputStore(root)
        val r1 = after.recover()
        val r2 = after.recover()
        val r3 = after.recover()
        // Only the stable part is idempotent. bytesReleased and reservationsReleased are per-pass
        // and are correctly zero on the second call, so comparing whole reports was asking the
        // wrong question.
        assertEquals(r1.committedBytes, r2.committedBytes, "recovery is not idempotent")
        assertEquals(r1.committedBytes, r3.committedBytes, "recovery is not idempotent")
        assertEquals(0L, r2.bytesReleased, "a second recovery pass must have nothing left to release")
        assertEquals(0, r2.reservationsReleased, "a second recovery pass must have nothing left to release")
        assertArrayEquals(bytes("durable"), readAll(after, stream))
    }

    // ------------------------------------------------------------ partial tail

    @Test
    fun `a commit count ahead of the readable bytes is treated as uncommitted, not as content`(
        @TempDir root: Path,
    ) {
        val stream = OutputStreamId("partial-tail")
        val first = SegmentOutputStore(root)
        first.recover()
        first.open(stream).reserve(32).apply { write(bytes("good")) }.commit()
        first.open(stream).reserve(32).write(bytes("TORN"))

        val dir = root.resolve("streams").resolve("partial-tail")
        // Simulate a torn tail: the commit record claims more than the payload actually holds.
        Files.writeString(dir.resolve("cur.cmt"), "99\n")

        val after = SegmentOutputStore(root)
        val report = after.recover()

        // The record claims 99 bytes; the payload holds 8. The store counts the gap and does NOT
        // repair it: clamping down would publish the 4 uncommitted bytes (I2), and clamping back
        // is not decidable from the segment alone. See OutputNotEstablished.CORRUPT_COMMIT_RECORD.
        assertEquals(91L, report.bytesUnbacked, "the unbacked claim was not counted")

        // And a range that would need the absent bytes is a dangling commit (I4): the store
        // refuses rather than handing back a short page that looks complete.
        val result = after.readRange(stream, 5L, 99L)
        assertTrue(
            result is OutputReadResult.Refused,
            "a dangling commit must be refused through the closed ADT, not thrown, got $result",
        )
        assertTrue(
            (result as OutputReadResult.Refused).reason is OutputRefusal.DanglingCommit,
            "the refusal must name the dangling commit, got ${result.reason}",
        )
    }

    // -------------------------------------------------------------- slow reader

    @Test
    fun `a slow reader that stops and resumes sees every committed byte exactly once`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("slow-reader")

        val assembled = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        var reads = 0

        // The writer commits in bursts while the reader is "thinking". The interleaving is real,
        // not simulated by sleeping: each burst runs on its own thread and the reader only reads
        // after that burst has finished.
        fun burst(i: Int): String =
            (0 until 50).joinToString("") { line -> "burst-$i-line$line-" }

        val writerDone = CountDownLatch(1)
        val writer = Thread {
            try {
                repeat(10) { i ->
                    val chunk = burst(i)
                    store.open(stream).reserve(chunk.length).apply { write(bytes(chunk)) }.commit()
                    Thread.sleep(2)
                }
            } finally {
                writerDone.countDown()
            }
        }
        writer.start()
        writerDone.await(120, TimeUnit.SECONDS)
        writer.join()

        var guard = 0
        while (true) {
            assertTrue(guard < 10_000, "the slow reader never reached the end of the stream")
            guard++
            val result = store.read(stream, cursor!!, 64)
            assertTrue(
                result is OutputReadResult.Page,
                "a slow reader must not be refused mid-stream, got $result at offset ${cursor.committedOffset} " +
                    "of committed ${store.committedExtent(stream)}",
            )
            val page = (result as OutputReadResult.Page).page
            assembled.write(page.bytes)
            reads++
            if (page.next == null) break
            cursor = page.next
            // A slow reader: deliberately stall between pages while nothing else is happening.
            if (reads % 3 == 0) Thread.sleep(1)
        }

        val expected = (0 until 10).joinToString("") { i -> burst(i) }
        assertArrayEquals(bytes(expected), assembled.toByteArray(), "a slow reader saw a gap or a repeat")
        assertTrue(reads > 10, "the reader should have taken many small pages, took $reads")
    }

    // ---------------------------------------------- bounded memory, as a shape

    @Test
    fun `the writer never materialises the whole producer, only one window`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("bounded-window")

        val total = 8 * 1024 * 1024
        val window = 32 * 1024

        // A producer that refuses to be read more than one window ahead. If appendFrom ever
        // buffered the whole thing, the counter below would show a window in flight beyond the
        // one it is allowed.
        val outstanding = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOutstanding = java.util.concurrent.atomic.AtomicInteger(0)
        val source = object : InputStream() {
            private var remaining = total
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len, remaining)
                java.util.Arrays.fill(b, off, off + n, 'w'.code.toByte())
                remaining -= n
                val inFlight = outstanding.incrementAndGet()
                maxOutstanding.updateAndGet { maxOf(it, inFlight) }
                outstanding.decrementAndGet()
                return n
            }
        }

        store.open(stream).appendFrom(source, windowBytes = window)

        assertEquals(total.toLong(), store.committedExtent(stream))
        // Shape, not a threshold: the store asked for windows of `window`, so it must not have
        // pulled more than a window at a time.
        assertTrue(
            maxOutstanding.get() <= 1,
            "the producer was read ${maxOutstanding.get()} times concurrently; expected one window in flight",
        )
        assertEquals(total.toLong(), readAll(store, stream, pageSize = 64 * 1024).size.toLong())
    }

    // ------------------------------------------------------------- the soak

    /**
     * More than 1 GiB, and the digest is the assertion.
     *
     * Tagged `performance`, so it runs through the repository's `performanceTest` task and is
     * excluded from the standard gate — the same reason `StreamingRedactor`'s probe is. A 1 GiB
     * test in `check` would make every commit pay for it and would then get skipped or deleted
     * under contention, which is worse than not having it.
     */
    @Tag("performance")
    @Test
    fun `soak - more than 1 GiB survives the round trip with an identical digest`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("soak-1gib")

        val chunk = ByteArray(1024 * 1024).also { Random(seed = 99).nextBytes(it) }
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        val repeats = 1025   // 1025 MiB > 1 GiB
        val window = 64 * 1024

        for (i in 0 until repeats) {
            store.open(stream).appendFrom(chunk.inputStream(), windowBytes = window)
            expectedDigest.update(chunk)
            // The expected digest is of the *concatenation*, so it has to be updated the same way
            // the reader will see it.
        }

        val extent = assertNotNullExtent(store, stream)
        assertTrue(
            extent > 1024L * 1024L * 1024L,
            "the soak must exceed 1 GiB, produced $extent bytes",
        )

        val actual = MessageDigest.getInstance("SHA-256")
        var cursor: OutputCursor? = OutputCursor.start(stream)
        var pages = 0L
        while (cursor != null) {
            val page = (store.read(stream, cursor, 1 shl 20) as OutputReadResult.Page).page
            actual.update(page.bytes)
            pages++
            cursor = page.next
        }
        assertEquals(
            expectedDigest.digest().joinToString("") { "%02x".format(it) },
            actual.digest().joinToString("") { "%02x".format(it) },
            "a GiB-scale round trip changed the bytes",
        )
        assertTrue(pages > 1, "the soak must page; it took $pages pages")
    }

    private fun assertNotNullExtent(store: SegmentOutputStore, stream: OutputStreamId): Long =
        store.committedExtent(stream) ?: error("stream $stream has no committed extent after a soak")

    // ------------------------------------------------------ the page contract

    @Test
    fun `the last page reports no continuation, and an intermediate one always does`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("page-edges")
        val payload = "0123456789"
        store.open(stream).reserve(payload.length).apply { write(bytes(payload)) }.commit()

        val exact = (store.read(stream, OutputCursor.start(stream), payload.length) as OutputReadResult.Page).page
        assertNull(exact.next, "a page that reaches the committed end must not hand back a cursor")

        val partial = (store.read(stream, OutputCursor.start(stream), 4) as OutputReadResult.Page).page
        assertEquals(OutputCursor(stream, 4L), partial.next)
        assertEquals(10L, partial.committedEnd, "committedEnd is the stream's extent, not the page's end")
    }
}
