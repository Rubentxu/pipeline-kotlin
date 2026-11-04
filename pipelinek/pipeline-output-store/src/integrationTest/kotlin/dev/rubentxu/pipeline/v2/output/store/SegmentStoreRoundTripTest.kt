package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputDigest
import dev.rubentxu.pipeline.v2.output.OutputReadDigestedResult
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The bytes that go in are the bytes that come out.
 *
 * ## The guarantee
 * A caller that commits bytes through the store and reads them back through
 * the same store receives exactly those bytes, and the digest that travels
 * with them is the digest of those bytes. A range past the committed end is
 * refused rather than padded, and a stream the store never saw is refused
 * rather than served as an empty page.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The unit suite of this module covers the pure half: the sealed-segment
 * name format, the extent of a sealed segment, the naming of a stream
 * directory and the lifetime of a per-stream exclusion. What it cannot cover
 * is the round trip, because the round trip IS the filesystem — the bytes are
 * written to a file, the file is read back at an offset, and the reader and
 * the writer have to agree about the name that file is sealed under.
 *
 * That agreement is the fact under trial. The writer and the reader are two
 * real components of this module, and the digest is a real component of
 * another one, and they are composed here exactly as the product wires them.
 *
 * ## What is faked, and what is not
 * The world outside the modules is faked: the Output Plane lives in a
 * temporary directory the test owns, and the pin substrate is a consult that
 * answers "nobody holds this directory" — a port the world outside answers,
 * not a module under test. Nothing that belongs to the store, to the reader
 * or to the digest is faked: they are the real ones, over a real filesystem.
 */
class SegmentStoreRoundTripTest {

    @TempDir
    lateinit var root: Path

    private val stream = OutputStreamId("run:stdout")

    /**
     * The store under test.
     *
     * ONE instance for the whole test, because the reconciled state belongs
     * to the store and not to the directory: a second store over the same
     * directory has not reconciled anything, and it refuses to serve a read
     * rather than guess. A test that built a store per call would be
     * measuring the refusal instead of the round trip.
     */
    private lateinit var store: SegmentOutputStore

    @BeforeEach
    fun reconcile() {
        store = SegmentOutputStore(
            root = root,
            pinConsult = object : DirectoryPinConsult {
                override fun pinsOfDirectory(directoryName: String): DirectoryPinAnswer =
                    DirectoryPinAnswer.Answered(emptyList())
            },
        )
        store.recover()
    }

    /**
     * Writes [bytes] through the real store and returns the committed end.
     *
     * The order is the product's order: a durable reservation, the bytes
     * inside it, and a commit that publishes them. A reservation that is
     * never committed is not visible to a reader, and the reader has to say
     * so — which is what makes the committed end the only end there is.
     */
    private fun commit(bytes: ByteArray): Long {
        val handle = store.open(stream)
        val reservation = handle.reserve(bytes.size)
        reservation.write(bytes)
        return reservation.commit()
    }

    @Test
    fun `the bytes the store committed are the bytes the reader serves`() {
        val payload = "the quick brown fox jumps over the lazy dog".toByteArray()
        val committed = commit(payload)

        val result = store.readRange(stream, 0L, committed)

        val page = assertInstanceOf(OutputReadResult.Page::class.java, result)
        assertArrayEquals(
            payload,
            page.page.bytes,
            "the store served bytes that are not the bytes it committed",
        )
    }

    @Test
    fun `the digest the store computes is the digest of the bytes it served`() {
        val payload = "a digest is a function of the bytes, not of the request".toByteArray()
        val committed = commit(payload)

        val result = store.readRangeDigested(stream, 0L, committed)

        val digested = assertInstanceOf(OutputReadDigestedResult.Digested::class.java, result)
        assertArrayEquals(
            payload,
            digested.page.bytes,
            "the digested page does not carry the bytes the store committed",
        )
        assertEquals(
            OutputDigest.sha256Of(payload).hex,
            digested.digest.hex,
            "the digest is not the digest of the bytes that travelled with it",
        )
    }

    @Test
    fun `a range past the committed end is refused and not padded`() {
        val payload = "only these bytes are committed".toByteArray()
        val committed = commit(payload)

        val result = store.readRange(stream, 0L, committed + 16L)

        val refused = assertInstanceOf(OutputReadResult.Refused::class.java, result)
        assertEquals(
            OutputRefusal.OffsetBeyondCommitted::class.simpleName,
            refused.reason::class.simpleName,
            "a range past the committed end must be refused, and not served with padding",
        )
    }

    @Test
    fun `a stream the store never saw is refused and not served as an empty page`() {
        val result = store.readRange(OutputStreamId("run:never-written"), 0L, 8L)

        val refused = assertInstanceOf(OutputReadResult.Refused::class.java, result)
        assertEquals(
            OutputRefusal.UnknownStream::class.simpleName,
            refused.reason::class.simpleName,
            "a stream the store never saw must be refused, not served as an empty page",
        )
    }

    @Test
    fun `a stream that rotated is read back whole and in order`() {
        // The store rotates the current segment when a NEW reservation finds
        // the current one already at its size ceiling, and the rotation MOVES
        // the file under a name the reader parses. The ceiling is the store's
        // own constant and it is private, so the test states it: the row
        // needs a stream that HAS rotated, and the only way to get one is to
        // write that many bytes and then ask for another reservation.
        val first = ByteArray(SEGMENT_CEILING.toInt()) { (it % 251).toByte() }
        val second = ByteArray(1024) { (it % 241).toByte() }

        val handle = store.open(stream)
        val opening = handle.reserve(first.size)
        opening.write(first)
        opening.commit()
        val afterRotation = handle.reserve(second.size)
        afterRotation.write(second)
        val committed = afterRotation.commit()

        val result = store.readRange(stream, 0L, committed)

        val page = assertInstanceOf(OutputReadResult.Page::class.java, result)
        assertArrayEquals(
            first + second,
            page.page.bytes,
            "a stream that rotated did not come back whole and in order",
        )

        // The digest has to travel the SAME two segments, because the digest
        // is computed while the bytes are read. A digest that covered only
        // the current segment would be the digest of half the stream, and a
        // digest of half the stream is a digest of nothing.
        val digested = assertInstanceOf(
            OutputReadDigestedResult.Digested::class.java,
            store.readRangeDigested(stream, 0L, committed),
        )
        assertEquals(
            OutputDigest.sha256Of(first + second).hex,
            digested.digest.hex,
            "the digest of a rotated stream does not cover the bytes of both segments",
        )

        val sealed = Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                // The segment that is still being written carries the same
                // suffix as a sealed one, so the suffix alone would find the
                // wrong file. The READER's own parser is what tells them
                // apart, and using it here is the point: the file this test
                // finds is a file the reader can actually read.
                .filter { SegmentReader.parseSealed(it) != null }
                .findFirst()
                .orElse(null)
        }
        assertTrue(
            sealed != null,
            "the store rotated the stream without leaving a sealed segment the reader recognises",
        )

        val parsed = requireNotNull(SegmentReader.parseSealed(sealed!!)) {
            "the reader cannot parse the name the writer sealed the segment under: ${sealed.fileName}"
        }
        assertEquals(
            SEGMENT_CEILING,
            parsed.length,
            "the sealed segment does not hold the bytes the store rotated out of the current one",
        )
    }

    private companion object {
        /**
         * The size at which the store rotates its current segment.
         *
         * It is the store's own ceiling and the store keeps it private, so
         * the test states it: the row needs a stream that HAS rotated, and
         * the only way to get one is to write that many bytes.
         */
        const val SEGMENT_CEILING = 8L * 1024L * 1024L
    }
}
