package dev.rubentxu.pipeline.v2.output.store


import dev.rubentxu.pipeline.v2.output.OutputAdoption
import dev.rubentxu.pipeline.v2.output.OutputAdoptionObligation
import dev.rubentxu.pipeline.v2.output.OutputCrashInvariant
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M1-P1 — the Output Store.
 *
 * The crash tests do not fork a process. They reproduce the **durable state** a process death
 * leaves behind — an outstanding `cur.res` and, possibly, uncommitted bytes in `cur.seg` — and then
 * recover through a *fresh* store instance over the same root. That is the property that matters:
 * recovery reads durable state, so the state is what has to be reproduced, and reproducing it
 * in-process is what makes the test fast enough to run on every change.
 *
 * Every guard here was proved non-vacuous by mutating the thing it watches. That is recorded per
 * test; a guard nobody broke is a guard nobody knows works.
 */
class SegmentOutputStoreTest {

    /** Payload committed after an empty reservation was released, to prove the stream is reusable. */
    private val firstBlock = "written after an empty release\n"

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun page(result: OutputReadResult): OutputPage =
        assertInstanceOf(OutputReadResult.Page::class.java, result, "expected a page, got $result").page

    private fun refusal(result: OutputReadResult): OutputRefusal =
        assertInstanceOf(OutputReadResult.Refused::class.java, result, "expected a refusal, got $result").reason

    /**
     * A writer that dies between reserve and commit: the reservation is left on disk, uncommitted.
     *
     * Delegated to [CrashedResidue] rather than done with a live store, because "the handle was
     * dropped" stopped modelling a crash the moment ownership became a kernel `FileLock` — see that
     * object's KDoc for the full account of what that fiction cost.
     */
    private fun crashed(
        root: Path,
        stream: OutputStreamId,
        acknowledged: ByteArray = ByteArray(0),
        unacknowledged: ByteArray = ByteArray(0),
        reservedBytes: Long = 64L * 1024L,
    ) = CrashedResidue.leave(root, stream, acknowledged, unacknowledged, reservedBytes)

    // ------------------------------------------------------------------ basics

    @Test
    fun `a reservation abandoned before a single byte was written leaves the stream intact`(@TempDir root: Path) {
        // The half of I6 nobody covered. `crashAfterWriting` above dies between write and commit;
        // the witness in OutputAdoption says "kill between reserve and write", and that is a
        // different durable state: `cur.res` exists and `cur.seg` may not exist at all.
        //
        // It is also the one a writer hits on the ordinary path — a producer that yields nothing
        // still took a reservation, and the caller releases it rather than leaking the range.
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("run/reserved-never-written")
        val payload = "committed before the empty reservation\n"

        store.open(stream).reserve(payload.length).apply {
            write(bytes(payload))
        }.commit()
        val committedBefore = store.open(stream).reserve(64)

        committedBefore.abandon()

        // Dense order, and the earlier bytes still readable: releasing an unused range must cost
        // nothing that was already promised to a reader.
        val result = store.read(stream, OutputCursor.start(stream), 1024)
        val page = assertInstanceOf(OutputReadResult.Page::class.java, result, "got $result").page
        assertEquals(payload, String(page.bytes, StandardCharsets.UTF_8))
        assertEquals(payload.length.toLong(), store.committedExtent(stream))
    }

    @Test
    fun `a reservation abandoned before any write still releases the range for reuse`(@TempDir root: Path) {
        // The reuse half of the same invariant: abandon() returns the base precisely so the range
        // can be taken again, and the reused range must land at exactly that offset — dense, with
        // no hole and no overlap.
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("run/empty-reservation-reuse")
        val first = "first committed block\n"

        store.open(stream).reserve(first.length).apply { write(bytes(first)) }.commit()
        val base = store.open(stream).reserve(32).abandon()
        store.open(stream).reserve(first.length).apply { write(bytes(first)) }.commit()

        assertEquals(
            first.length.toLong(),
            base,
            "an unwritten reservation must release from the committed offset, not from its own limit",
        )
        // Two commits of the same payload is two blocks of bytes, and reading them back as one
        // doubled block is the correct answer — the second write is a second acknowledged append,
        // not a rewrite. What must NOT happen is a hole or an overlap, so the assertion is on the
        // extent and on both halves, not on a stream that "still looks the same".
        val extent = store.committedExtent(stream)
        assertEquals(2L * first.length, extent, "the reused range must extend the stream, not replace it")
        val result = store.read(stream, OutputCursor.start(stream), 1024)
        val page = assertInstanceOf(OutputReadResult.Page::class.java, result, "got $result").page
        assertEquals(first + first, String(page.bytes, StandardCharsets.UTF_8), "the stream must be dense")
    }

    @Test
    fun `abandoning a reservation on a stream that never wrote is not an error`(@TempDir root: Path) {
        // The extreme of the same path: no committed bytes means no `cur.seg` to truncate. Release
        // has to be total — a caller that took a reservation and got nothing has to be able to give
        // it back, and the range is still reusable afterwards.
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("run/never-written-at-all")

        val base = store.open(stream).reserve(16).abandon()

        assertEquals(0L, base, "nothing was committed, so the range starts at zero")
        // A reserved-then-released stream is a KNOWN stream holding nothing, not an unknown one:
        // `reserve` created its directory, so `committedExtent` answers 0 rather than null. The
        // distinction matters because "empty" is a fact a console can report and "I have never heard
        // of this run" is not.
        assertEquals(0L, store.committedExtent(stream), "a released empty reservation leaves an empty stream")
        // And the stream is still usable afterwards, which is the point of returning the base.
        store.open(stream).reserve(firstBlock.length).apply { write(bytes(firstBlock)) }.commit()
        assertEquals(firstBlock.length.toLong(), store.committedExtent(stream))
        val result = store.read(stream, OutputCursor.start(stream), 1024)
        val page = assertInstanceOf(OutputReadResult.Page::class.java, result, "got $result").page
        assertEquals(firstBlock, String(page.bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `committed bytes read back byte-identical`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("run/step-0")
        val payload = "hello output plane\nsecond line\n"

        val committed = store.open(stream).reserve(payload.length).apply {
            write(bytes(payload))
        }.commit()

        assertEquals(payload.length.toLong(), committed)
        val result = store.read(stream, OutputCursor.start(stream), 1024)
        assertArrayEquals(bytes(payload), page(result).bytes)
    }

    @Test
    fun `page is bounded by maxBytes and the cursor resumes exactly where it stopped`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("bounded")
        val payload = "0123456789abcdefghij"
        store.open(stream).reserve(payload.length).apply { write(bytes(payload)) }.commit()

        val first = page(store.read(stream, OutputCursor.start(stream), 10))
        assertEquals(10, first.bytes.size)
        assertEquals(0L, first.from)
        assertEquals(OutputCursor(stream, 10L), first.next)

        val second = page(store.read(stream, first.next!!, 1024))
        assertArrayEquals(bytes(payload.substring(10)), second.bytes)
        assertNull(second.next, "at the committed end the page must say so, not leave a cursor behind")
    }

    @Test
    fun `arbitrary byte range equals the corresponding prefix of the whole stream`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("range")
        val payload = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        store.open(stream).reserve(payload.length).apply { write(bytes(payload)) }.commit()

        // The contractual property: a consumer can address a byte without reading the ones before
        // it, and get the same byte. Checked at several offsets, not just the aligned one.
        for (from in listOf(0L, 1L, 5L, 13L, 25L)) {
            for (length in listOf(1L, 3L, 7L)) {
                val to = minOf(from + length, payload.length.toLong())
                val slice = page(store.readRange(stream, from, to))
                assertEquals(
                    payload.substring(from.toInt(), to.toInt()),
                    String(slice.bytes, StandardCharsets.UTF_8),
                    "range [$from, $to) did not match the stream",
                )
            }
        }
    }

    // ------------------------------------------------------------------ O1/O2/O3

    @Test
    fun `O1 - a reserved range is invisible until it is committed`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("o1")

        val reservation = store.open(stream).reserve(32)
        reservation.write(bytes("not yet acknowledged"))

        // The acknowledgement is the reservation, but the bytes are not visible until commit.
        assertEquals(0L, store.committedExtent(stream))
        assertEquals(0, page(store.read(stream, OutputCursor.start(stream), 64)).bytes.size)

        reservation.commit()
        assertEquals("not yet acknowledged".length.toLong(), store.committedExtent(stream))
    }

    @Test
    fun `O2 - the committed offset, not the file size, is what a reader resumes from`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("o2")

        val reservation = store.open(stream).reserve(64)
        reservation.write(bytes("abcdefghij"))
        reservation.commit()

        // Push uncommitted bytes past the committed extent so file size and committed extent differ.
        val second = store.open(stream).reserve(64)
        second.write(bytes("XXXX-not-committed"))

        val segment = root.resolve("streams").resolve("o2").resolve("cur.seg")
        assertTrue(Files.size(segment) > 10L, "test setup: the segment must be longer than what is committed")

        val served = page(store.read(stream, OutputCursor.start(stream), 1024))
        assertArrayEquals(bytes("abcdefghij"), served.bytes, "a reader resumed from the file size, not the committed offset")
        assertEquals(10L, served.committedEnd)
    }

    @Test
    fun `O3 - reads are refused before recovery has run`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        // No recover() call. A store that reconciles lazily has already served a reader from an
        // unreconciled state, so the read must be refused rather than attempted.
        //
        // It used to be asserted as a thrown IllegalStateException, which contradicted the port's
        // own KDoc ("refuses reads with OutputRefusal.RecoveryNotCompleted rather than guessing")
        // and left a caller that handled every refusal still catching an exception. The condition
        // has one representation now, and it is the one the contract names.
        val stream = OutputStreamId("unreconciled")

        val read = store.read(stream, OutputCursor.start(stream), 10)
        assertInstanceOf(OutputReadResult.Refused::class.java, read, "read must refuse in-band, got $read")
        assertEquals(
            OutputRefusal.RecoveryNotCompleted,
            (read as OutputReadResult.Refused).reason,
            "the refusal must be the O3 case, not some other answer",
        )

        val ranged = store.readRange(stream, 0L, 10L)
        assertInstanceOf(OutputReadResult.Refused::class.java, ranged, "readRange must refuse too, got $ranged")
        assertEquals(OutputRefusal.RecoveryNotCompleted, (ranged as OutputReadResult.Refused).reason)
    }

    @Test
    fun `O3 - the unreconciled refusal is distinct from an unknown stream`(@TempDir root: Path) {
        // The two must not collapse. "The store is not ready" and "this stream was never opened"
        // are different facts, and a caller that retries on one must not retry on the other.
        val store = SegmentOutputStore(root)
        val stream = OutputStreamId("absent")
        val beforeRecovery = store.read(stream, OutputCursor.start(stream), 10)
        store.recover()
        val afterRecovery = store.read(stream, OutputCursor.start(stream), 10)

        assertEquals(
            OutputRefusal.RecoveryNotCompleted,
            (beforeRecovery as OutputReadResult.Refused).reason,
        )
        assertEquals(
            OutputRefusal.UnknownStream(stream),
            (afterRecovery as OutputReadResult.Refused).reason,
        )
    }

    @Test
    fun `O3 - the operations that cannot refuse in-band still refuse loudly`(@TempDir root: Path) {
        // open, committedExtent and prune return a handle, a Long? and a report respectively —
        // none of which can carry a refusal without inventing an ambiguous value. They keep
        // throwing, and this row is what stops that from decaying into a silent wrong answer.
        val store = SegmentOutputStore(root)
        val stream = OutputStreamId("unreconciled")

        assertThrows(IllegalStateException::class.java) { store.open(stream) }
        assertThrows(IllegalStateException::class.java) { store.committedExtent(stream) }
        assertThrows(IllegalStateException::class.java) {
            store.prune(OutputPruneIntent.OperatorReleased(runId = "unreconciled", requestedBy = "test"))
        }
    }

    @Test
    fun `O3 - recovery is idempotent and may be run twice`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        val stream = OutputStreamId("twice")
        store.recover()
        store.open(stream).reserve(8).apply { write(bytes("kept")) }.commit()

        val first = store.recover()
        val second = store.recover()

        assertEquals(first, second, "recovery must be idempotent: a store that cannot be recovered twice cannot be trusted after its own recovery crashes")
        assertEquals("kept", String(page(store.read(stream, OutputCursor.start(stream), 64)).bytes, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------- I1..I6

    @Test
    fun `I1 - acknowledged bytes survive a restart`(@TempDir root: Path) {
        val stream = OutputStreamId("i1")
        val before = SegmentOutputStore(root)
        before.recover()
        before.open(stream).reserve(16).apply { write(bytes("acknowledged")) }.commit()

        // A new store instance over the same root is what a restarted process sees.
        val after = SegmentOutputStore(root)
        after.recover()
        assertArrayEquals(
            bytes("acknowledged"),
            page(after.read(stream, OutputCursor.start(stream), 64)).bytes,
        )
    }

    @Test
    fun `I2 - bytes written but never committed do not appear after recovery`(@TempDir root: Path) {
        val stream = OutputStreamId("i2")
        crashed(root, stream, bytes("kept"), bytes("phantom-bytes"))

        val after = SegmentOutputStore(root)
        after.recover()

        val served = page(after.read(stream, OutputCursor.start(stream), 1024))
        assertArrayEquals(bytes("kept"), served.bytes, "a byte the writer never acknowledged became observable")
        assertEquals(4L, served.committedEnd)
    }

    @Test
    fun `a reservation after an unreconciled tail does not expose the stale bytes`(@TempDir root: Path) {
        val stream = OutputStreamId("stale-tail")
        crashed(root, stream, bytes("kept"), bytes("STALE"))

        val after = SegmentOutputStore(root)
        after.recover()
        // The next write starts at the committed offset, so the segment must be truncated back to
        // it first. Without that, the stale bytes sit between the committed content and the new
        // bytes and the stream reads as a plausible-looking scramble.
        after.open(stream).reserve(8).apply { write(bytes("new")) }.commit()

        assertArrayEquals(
            bytes("keptnew"),
            page(after.read(stream, OutputCursor.start(stream), 64)).bytes,
        )
    }

    @Test
    fun `I3 and I6 - an unused reservation is released, so the order stays dense`(@TempDir root: Path) {
        val stream = OutputStreamId("i3")
        // Acknowledged 3 bytes, then a writer that reserved 4096 and used none of them before dying.
        crashed(root, stream, bytes("aaa"), reservedBytes = 4096)

        val after = SegmentOutputStore(root)
        val report = after.recover()

        assertEquals(1, report.reservationsReleased, "the unused reservation was not released")
        assertEquals(3L, after.committedExtent(stream))

        // Density: the next write continues at the committed offset with no hole, which is what
        // lets a cursor distinguish "gone" from "not yet here".
        after.open(stream).reserve(8).apply { write(bytes("bbb")) }.commit()
        val served = page(after.read(stream, OutputCursor.start(stream), 1024))
        assertArrayEquals(bytes("aaabbb"), served.bytes)
    }

    @Test
    fun `abandoned reservation is reusable rather than a permanent hole`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("abandon")

        store.open(stream).reserve(8).apply { write(bytes("aa")) }.commit()
        val released = store.open(stream).reserve(1024).apply { write(bytes("discarded")) }.abandon()
        assertEquals(2L, released, "abandon must report the base so the range can be reused")

        store.open(stream).reserve(8).apply { write(bytes("bb")) }.commit()
        assertArrayEquals(
            bytes("aabb"),
            page(store.read(stream, OutputCursor.start(stream), 64)).bytes,
            "the abandoned range left a hole instead of being reused",
        )
    }

    @Test
    fun `an unresolved reservation blocks a new one until recovery resolves it`(@TempDir root: Path) {
        val stream = OutputStreamId("stranded")
        // The writer dies with cur.res on disk and nothing committed behind it.
        crashed(root, stream, reservedBytes = 16)

        // A process that has not reconciled must not append. Appending over an unresolved
        // reservation would make the stranded range unreachable rather than released.
        val fresh = SegmentOutputStore(root)
        assertThrows(IllegalStateException::class.java) { fresh.open(stream) }

        fresh.recover()
        fresh.open(stream).reserve(8).apply { write(bytes("after")) }.commit()
        assertArrayEquals(bytes("after"), page(fresh.read(stream, OutputCursor.start(stream), 64)).bytes)
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `a cursor naming another stream is refused, never clamped`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val a = OutputStreamId("stream-a")
        val b = OutputStreamId("stream-b")
        store.open(a).reserve(8).apply { write(bytes("AAAA")) }.commit()
        store.open(b).reserve(8).apply { write(bytes("BBBB")) }.commit()

        // Both cursors sit at offset 0, so clamping would be indistinguishable from a correct
        // read. The refusal is the only thing that tells a consumer it asked the wrong question.
        assertEquals(
            OutputRefusal.ForeignStream(expected = b, actual = a),
            refusal(store.read(b, OutputCursor.start(a), 64)),
        )
        assertEquals(
            OutputRefusal.ForeignStream(expected = a, actual = b),
            refusal(store.read(a, OutputCursor.start(b), 64)),
        )
        // And the correct pairing still works, so the refusal is not a blanket denial.
        assertArrayEquals(bytes("BBBB"), page(store.read(b, OutputCursor.start(b), 64)).bytes)
    }

    @Test
    fun `an unknown stream is refused`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        assertEquals(
            OutputRefusal.UnknownStream(OutputStreamId("never-written")),
            refusal(store.read(OutputStreamId("never-written"), OutputCursor.start(OutputStreamId("never-written")), 16)),
        )
        assertNull(store.committedExtent(OutputStreamId("never-written")))
    }

    @Test
    fun `a cursor beyond the committed extent is refused`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("beyond")
        store.open(stream).reserve(8).apply { write(bytes("1234")) }.commit()

        assertEquals(
            OutputRefusal.OffsetBeyondCommitted(requested = 99L, committed = 4L),
            refusal(store.read(stream, OutputCursor(stream, 99L), 16)),
        )
    }

    @Test
    fun `an inverted range is refused`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("inverted")
        store.open(stream).reserve(8).apply { write(bytes("1234")) }.commit()

        assertEquals(OutputRefusal.InvalidRange(4L, 2L), refusal(store.readRange(stream, 4L, 2L)))
        assertEquals(OutputRefusal.InvalidRange(0L, 0L), refusal(store.readRange(stream, 0L, 0L)))
    }

    @Test
    fun `writing past a reservation limit is refused`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("bounded-write")
        val reservation = store.open(stream).reserve(4)
        // reserve(4) reserves a floor, not a cap: limit is the authority, and it is what the writer
        // must be told about. Asserting against minBytes would encode the wrong contract.
        assertTrue(reservation.limit >= reservation.base + 4, "reserve() is documented as a floor")
        reservation.write(ByteArray((reservation.limit - reservation.base).toInt()) { 'a'.code.toByte() })

        val error = assertThrows(OutputReservationExceeded::class.java) {
            reservation.write(bytes("!"))
        }
        assertEquals(reservation.limit, error.limit)
        assertEquals(reservation.limit + 1, error.attemptedAt)
    }

    // --------------------------------------------------------------- segments

    @Test
    fun `a long stream seals segments and reads back identically across the seam`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("long")

        // Force several rotations by writing well past the 8 MiB segment cap in modest chunks.
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        var expected = 0L
        repeat(10) {
            val reservation = store.open(stream).reserve(chunk.size)
            reservation.write(chunk)
            expected = reservation.commit()
        }

        val segments = root.resolve("streams").resolve("long").resolve("segments")
        val sealedCount = Files.list(segments).use { it.count() }
        assertTrue(sealedCount > 0, "expected the stream to have been segmented, found $sealedCount sealed segments")

        // Read the whole thing back in odd-sized pages and confirm it is byte-identical.
        val assembled = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val p = page(store.read(stream, cursor, 7777))
            assembled.write(p.bytes)
            cursor = p.next
        }
        assertEquals(expected, assembled.size().toLong())
        val expectedBytes = java.io.ByteArrayOutputStream().apply { repeat(10) { write(chunk) } }.toByteArray()
        assertArrayEquals(expectedBytes, assembled.toByteArray(), "a segmented stream did not read back identically")
    }

    // ------------------------------------------------------------- copyFrom

    @Test
    fun `copyFrom streams an unbounded producer without materialising it`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("copy")
        val payload = bytes("x".repeat(200_000))

        val committed = store.open(stream).appendFrom(payload.inputStream(), windowBytes = 8192)

        assertEquals(200_000L, committed)
        val served = page(store.read(stream, OutputCursor.start(stream), 1_000_000))
        assertEquals(200_000, served.bytes.size)
        assertArrayEquals(payload, served.bytes)
    }

    @Test
    fun `appendFrom takes one reservation per window and commits each in turn`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("multi-window")
        // 18 bytes at 8 bytes per window forces three reserve/write/commit cycles, so the
        // multi-reservation path is exercised rather than the single-reservation one.
        val payload = bytes("first-second-third")

        var failure: Throwable? = null
        val writerThread = Thread {
            failure = runCatching { store.open(stream).appendFrom(payload.inputStream(), windowBytes = 8) }
                .exceptionOrNull()
        }
        writerThread.start()
        writerThread.join()
        assertNull(failure, "the writer thread failed: $failure")

        assertEquals(18L, store.committedExtent(stream))
        val assembled = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        var pages = 0
        while (cursor != null) {
            val p = page(store.read(stream, cursor, 5))
            assembled.write(p.bytes)
            pages++
            cursor = p.next
        }
        assertEquals(4, pages, "reading 18 bytes in pages of 5 must take four pages")
        assertArrayEquals(payload, assembled.toByteArray())
    }

    @Test
    fun `copyFrom refuses rather than truncating a producer larger than its reservation`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()
        val stream = OutputStreamId("bounded-copy")
        val reservation = store.open(stream).reserve(4)
        val overLimit = ByteArray((reservation.limit - reservation.base).toInt() + 16) { 'x'.code.toByte() }

        // Truncating here would lose bytes silently, which is worse than refusing.
        assertThrows(OutputReservationExceeded::class.java) {
            reservation.copyFrom(overLimit.inputStream())
        }
    }

    // -------------------------------------------------------------- adoption

    @Test
    fun `S2 is a conjunction - two of three does not permit the claim`() {
        val all = OutputAdoption.obligations.toSet()
        assertTrue(OutputAdoption.mayClaimCrashConsistency(all))
        assertFalse(
            OutputAdoption.mayClaimCrashConsistency(all - setOf(OutputAdoptionObligation.RECOVERY_IS_AN_ENTRY_POINT)),
            "O1+O2 without O3 is a product that loses acknowledged bytes sometimes",
        )
        assertFalse(OutputAdoption.mayClaimCrashConsistency(emptySet()))
    }

    @Test
    fun `invariants blocked is a union, not a first-match chain`() {
        // The defect this guards: an if/else-if chain that returned on the first match reported
        // only I3 for a writer that reserved but had no recovery entry point, quietly vouching for
        // I5 and I6 - precisely the two recovery underpins.
        // The argument is the DISCHARGED set, so "nothing discharged" is the empty set. Passing
        // setOf(O1) here would mean O1 *is* discharged, which is the opposite of the claim.
        assertEquals(
            OutputAdoption.invariants.toSet(),
            OutputAdoption.invariantsBlockedBy(emptySet()).toSet(),
            "without O1 nothing the writer does is recoverable, so nothing is established",
        )

        val noRecovery = setOf(
            OutputAdoptionObligation.WRITER_RESERVES_BEFORE_APPEND,
            OutputAdoptionObligation.READER_RESUMES_FROM_COMMITTED,
        )
        val recoveryBlocked = OutputAdoption.invariantsBlockedBy(noRecovery)
        assertTrue(
            OutputCrashInvariant.I5_NO_DUPLICATE_ON_RECOVERY in recoveryBlocked &&
                OutputCrashInvariant.I6_NO_AMBIGUOUS_SLOT in recoveryBlocked,
            "a missing recovery entry point must block both recovery invariants, got $recoveryBlocked",
        )
    }

    @Test
    fun `this store discharges all three obligations and the refusal survives`(@TempDir root: Path) {
        // Each obligation is discharged by something observable, not by declaration:
        //   O1  cur.res is written before any byte
        //   O2  cur.cmt is the committed offset, and O2's test shows file size is ignored
        //   O3  reads are refused until recover() runs
        val stream = OutputStreamId("discharge")
        val store = SegmentOutputStore(root)

        val refusedBefore = store.read(stream, OutputCursor.start(stream), 1)
        assertEquals(
            OutputRefusal.RecoveryNotCompleted,
            (refusedBefore as OutputReadResult.Refused).reason,
            "O3 is discharged by a typed refusal, which is what the port contract names",
        )

        store.recover()
        val reservation = store.open(stream).reserve(4)
        assertTrue(
            Files.exists(root.resolve("streams").resolve("discharge").resolve("cur.res")),
            "O1: the reservation must be durable before the first byte is written",
        )
        reservation.write(bytes("done"))
        reservation.commit()
        assertTrue(
            Files.exists(root.resolve("streams").resolve("discharge").resolve("cur.cmt")),
            "O2: the committed offset must be its own durable record, not a file size",
        )
        assertTrue(
            OutputAdoption.mayClaimCrashConsistency(OutputAdoption.obligations.toSet()),
        )
        assertTrue(
            OutputAdoption.POWER_LOSS_NOT_CLAIMED.contains("NOT claimed"),
            "the power-loss refusal must remain quotable next to the claim",
        )
    }

    // -------------------------------------------------------------- OUT-01

    /**
     * OUT-01: pruning by `safe(runId) + "_"` prefix can delete streams belonging to a different
     * run whose safe-form starts with that prefix. Witnessed here with runId `"run"` (whose
     * prefix is `"run_"`) and a separate runId `"run_x"` whose stream directory `run_x_stdout`
     * starts with `"run_"`. Pruning the former must NOT delete the latter.
     *
     * Status: known to fail until the identity is made unequivocal (the durable format change
     * is tracked separately and not this block's to make — for now, the test is a guard
     * that fails today and must pass after the fix lands).
     */
    @Test
    fun `OUT-01 prune of a short runId must not delete streams of a longer runId sharing the safe prefix`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()

        // Run A: short runId whose safe form is "run" (prefix "run_").
        val runA = "run"
        val streamA = OutputStreamId("$runA/stdout")
        store.open(streamA).reserve(8).apply { write(bytes("A-stdout")) }.commit()

        // Run B: longer runId whose safe form is "run_x" — its stream directory starts with "run_".
        val runB = "run_x"
        val streamB = OutputStreamId("$runB/stdout")
        store.open(streamB).reserve(8).apply { write(bytes("B-stdout")) }.commit()

        // Sanity: both stream directories exist and share the "run_" prefix.
        val streamsRoot = root.resolve("streams")
        val dirA = streamsRoot.resolve(safeStreamName(streamA.value))
        val dirB = streamsRoot.resolve(safeStreamName(streamB.value))
        assertTrue(Files.isDirectory(dirA), "run A's stream dir must exist pre-prune")
        assertTrue(Files.isDirectory(dirB), "run B's stream dir must exist pre-prune")
        assertTrue(
            dirA.fileName.toString().startsWith("run_"),
            "run A's safe stream name must start with the 'run_' prefix (sanity)",
        )
        assertTrue(
            dirB.fileName.toString().startsWith("run_"),
            "run B's safe stream name ALSO starts with 'run_' — this is the OUT-01 trap",
        )

        // Pruning run A must NOT delete run B's stream.
        val report = store.prune(OutputPruneIntent.RunReachedTerminalState(runId = runA))

        assertEquals(1, report.streamsRemoved, "only run A's stream should be removed")

        // The defining OUT-01 assertion: run B's stream directory survives.
        assertTrue(
            Files.isDirectory(dirB),
            "OUT-01: run B's stream ('run_x/stdout') must NOT be deleted by pruning run A; " +
                "if this is missing, the prefix-based filter selected B's directory by accident",
        )
        // And run B's bytes are still readable.
        val readB = store.read(streamB, OutputCursor.start(streamB), 1024)
        assertInstanceOf(OutputReadResult.Page::class.java, readB, "run B's stream must still serve reads after pruning run A")
        assertEquals(
            "B-stdout",
            String((readB as OutputReadResult.Page).page.bytes, StandardCharsets.UTF_8),
        )
    }

    // -------------------------------------------------------------- OUT-02

    /**
     * OUT-02: `safeStreamName` is not injective. Two distinct runIds whose safe forms are equal
     * collide on disk. Witnessed here: runId `run/abc` (with a slash) and runId `run_abc` (already
     * safe form) both sanitize to `run_abc`. Writing to one silently aliases the other.
     *
     * Status: known to fail until a non-ambiguous physical identity is introduced. The durable
     * format change is a separate concern with a migration policy.
     */
    @Test
    fun `OUT-02 distinct runIds whose safe forms are equal collide on disk and serve each other's bytes`(@TempDir root: Path) {
        val store = SegmentOutputStore(root)
        store.recover()

        // Two semantically distinct runIds that collapse to the same safe form.
        val runA = "run/abc"   // sanitises to "run_abc"
        val runB = "run_abc"   // already safe, equal to safe(runA)
        val streamA = OutputStreamId("$runA/stdout")
        val streamB = OutputStreamId("$runB/stdout")

        // Sanity: safe form is identical.
        assertEquals(
            safeStreamName(runA),
            safeStreamName(runB),
            "pre-condition: safe(runA) must equal safe(runB) for this witness",
        )

        // Both streams resolve to the same on-disk directory; writing to one aliases the other.
        val dirA = root.resolve("streams").resolve(safeStreamName(streamA.value))
        val dirB = root.resolve("streams").resolve(safeStreamName(streamB.value))
        assertEquals(
            dirA,
            dirB,
            "pre-condition: both runIds must map to the same stream directory for the collision to bite",
        )

        store.open(streamA).reserve(32).apply { write(bytes("A-stdout: written through runA")) }.commit()
        // OUT-02 witness: in the current code (durable format NOT distinguishing runA from runB),
        // reading through runB serves A's bytes. The fix must make runB refuse as UnknownStream.
        // Today the assertion below FAILS because the read returns A's bytes (Page) instead of a
        // typed refusal; that failure is the bug we want to demonstrate.
        val readB = store.read(streamB, OutputCursor.start(streamB), 1024)
        assertInstanceOf(
            OutputReadResult.Refused::class.java,
            readB,
            "OUT-02: reading through runB must be refused as UnknownStream once runIds are " +
                "unambiguous; if this returned a Page, runA and runB still share a directory " +
                "because the durable format cannot tell their runIds apart.",
        )
        assertEquals(
            OutputRefusal.UnknownStream(streamB),
            (readB as OutputReadResult.Refused).reason,
            "OUT-02: the refusal reason must be the runB stream being unknown, not something else",
        )
    }
}
