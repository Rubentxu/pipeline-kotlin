package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * `sealedName` writes a sealed segment's file name and `parseSealed` reads it back.
 * The two are one contract: a name the writer can produce must be readable, and a name the writer
 * can never produce must be rejected rather than misread.
 * `parseSealed` reads only `Path.fileName`, so it is a pure function over a string — no filesystem
 * access, no clock. The tests build the `Path` values by hand and never touch a disk.
*/
class SegmentReaderTest {

    // --- the writer ---------------------------------------------------------

    @Test
    fun `sealedName is the base, a dash, the length and the suffix`() {
        assertEquals("0-10.seg", SegmentReader.sealedName(base = 0L, length = 10L))
        assertEquals("128-4096.seg", SegmentReader.sealedName(base = 128L, length = 4096L))
    }

    @Test
    fun `sealedName round-trips through parseSealed`() {
        for ((base, length) in listOf(0L to 1L, 0L to 4096L, 128L to 1L, 1_000_000L to 65_536L)) {
            val parsed = SegmentReader.parseSealed(Path.of(SegmentReader.sealedName(base, length)))

            assertEquals(base, parsed?.base, "base survives the round-trip")
            assertEquals(length, parsed?.length, "length survives the round-trip")
        }
    }

    // --- the reader rejects what the writer cannot emit ---------------------

    @Test
    fun `a negative length behind a double dash is rejected`() {
        // sealedName splits at the FIRST dash, so "10--5" parses as base=10 and length="-5".
        // toLongOrNull accepts the sign, so without a guard this reaches currentBase with
        // base+length = 5 and the read path re-serves bytes from the live segment.
        val parsed = SegmentReader.parseSealed(Path.of("10--5.seg"))

        assertNull(parsed, "a negative length is not a segment this store can have written")
    }

    @Test
    fun `a negative base is rejected`() {
        assertNull(SegmentReader.parseSealed(Path.of("-1-10.seg")), "base must be non-negative")
    }

    @Test
    fun `a name that is not the sealed suffix is rejected`() {
        assertNull(SegmentReader.parseSealed(Path.of("0-10.txt")), "only .seg files are segments")
        assertNull(SegmentReader.parseSealed(Path.of("0-10")), "a bare name is not a segment")
    }

    @Test
    fun `a name with no dash or an empty base is rejected`() {
        assertNull(SegmentReader.parseSealed(Path.of("nosep.seg")), "no dash means no base/length split")
        assertNull(SegmentReader.parseSealed(Path.of("-10.seg")), "the dash is at index 0, so there is no base")
    }

    @Test
    fun `a name whose parts are not numbers is rejected`() {
        assertNull(SegmentReader.parseSealed(Path.of("a-b.seg")), "a non-numeric base is not a segment")
        assertNull(SegmentReader.parseSealed(Path.of("0-b.seg")), "a non-numeric length is not a segment")
        assertNull(SegmentReader.parseSealed(Path.of("0-1.5.seg")), "a fractional length is not a segment")
    }

    @Test
    fun `an overlong number is rejected instead of wrapping`() {
        // A Long that does not fit must fail the parse, not truncate into a plausible offset.
        assertNull(
            SegmentReader.parseSealed(Path.of("0-99999999999999999999.seg")),
            "a length beyond Long does not become a smaller valid one",
        )
    }

    @Test
    fun `only the first dash separates, so a name with trailing text after the length is rejected`() {
        assertNull(
            SegmentReader.parseSealed(Path.of("0-10-20.seg")),
            "the length segment is parsed whole, so '10-20' is not a number",
        )
    }

    // --- the value type guards its own range -------------------------------

    @Test
    fun `SealedSegment refuses a negative base at construction`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SegmentReader.SealedSegment(base = -1L, length = 10L, file = Path.of("x"))
        }
        assertTrue(ex.message?.contains("base") == true, "the message names the field: ${ex.message}")
    }

    @Test
    fun `SealedSegment refuses a negative length at construction`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SegmentReader.SealedSegment(base = 0L, length = -1L, file = Path.of("x"))
        }
        assertTrue(ex.message?.contains("length") == true, "the message names the field: ${ex.message}")
    }

    @Test
    fun `SealedSegment accepts a zero-length segment`() {
        // rotateLocked refuses to seal an empty segment, but a zero extent is not an invalid
        // extent: it names a range that simply contributes no bytes. The reader must not reject it.
        val segment = SegmentReader.SealedSegment(base = 0L, length = 0L, file = Path.of("0-0.seg"))

        assertEquals(0L, segment.base)
        assertEquals(0L, segment.length)
    }
}
