package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * `firstMissingOffset` decides whether a read's requested bytes are all present, given only the
 * sealed segments' EXTENTS and the current segment's base. It never opens a file: a `SealedSegment`
 * carries a `Path` but nothing here reads it, which is what keeps this a unit test under
 * AGENTS.md section 2.
 *
 * The property is the one the two read loops used to get wrong by accident. Both derived each
 * chunk's offset as `(cursor - seg.base).coerceAtLeast(0)`, so a segment starting ABOVE the cursor
 * was read from its own offset 0 and copied into the middle of the answer. The page came back the
 * requested length, so the only remaining guard -- `written == target.size` -- passed: a substituted
 * answer satisfies it perfectly. Every case below is one a reader must distinguish.
 */
class FirstMissingOffsetTest {

    /** A sealed segment by extent. The [Path] is a name this test never opens. */
    private fun seg(base: Long, length: Long) =
        SegmentReader.SealedSegment(base, length, Path.of(SegmentReader.sealedName(base, length)))

    /** Two contiguous sealed segments covering [0, 2000) with the current segment starting at 2000. */
    private val contiguous = listOf(seg(0, 1000), seg(1000, 1000))

    // --- a whole range that is present ---------------------------------------

    @Test
    fun `a range wholly inside contiguous segments has no gap`() {
        assertNull(firstMissingOffset(contiguous, 0L, 500L, currentBase = 2000L))
        assertNull(firstMissingOffset(contiguous, 500L, 1500L, currentBase = 2000L))
    }

    @Test
    fun `a range spanning several contiguous segments has no gap`() {
        assertNull(firstMissingOffset(contiguous, 0L, 2000L, currentBase = 2000L))
    }

    @Test
    fun `a range that runs into the current segment has no gap`() {
        // [1500, 2500) needs the sealed tail plus the first 500 bytes of the current segment.
        assertNull(firstMissingOffset(contiguous, 1500L, 2500L, currentBase = 2000L))
    }

    @Test
    fun `a range behind every segment is not a gap, it is the caller's mistake`() {
        // Entirely below offset 0 is impossible for a valid cursor, and entirely behind a base that
        // starts above `from` IS a gap. This case is the degenerate one: nothing is missing because
        // nothing was asked for.
        assertNull(firstMissingOffset(listOf(seg(1000, 1000)), 1000L, 1500L, currentBase = 2000L))
    }

    // --- a gap in the middle ---------------------------------------------------

    @Test
    fun `a range starting below the first surviving segment is a gap at from`() {
        // The exact shape the coercion bridged: ask for [500, 1500) when nothing below 1000
        // survives. The old reader answered with the content of [1000, 1500).
        assertEquals(500L, firstMissingOffset(listOf(seg(1000, 1000)), 500L, 1500L, currentBase = 2000L))
    }

    @Test
    fun `a range crossing a hole between two surviving segments is a gap at the hole`() {
        val holed = listOf(seg(0, 500), seg(1000, 1000))

        assertEquals(500L, firstMissingOffset(holed, 0L, 1500L, currentBase = 2000L))
    }

    @Test
    fun `the gap is reported even when the range ends inside the hole`() {
        // The caller never reaches a surviving byte, so no amount of reading could have served it.
        assertEquals(500L, firstMissingOffset(listOf(seg(0, 500), seg(1000, 1000)), 400L, 700L, currentBase = 2000L))
    }

    @Test
    fun `only the FIRST gap is reported, so the consumer resumes from the truth`() {
        // Two holes. Reporting the second would claim the first was served.
        val twice = listOf(seg(0, 100), seg(200, 100), seg(400, 100))

        assertEquals(100L, firstMissingOffset(twice, 0L, 500L, currentBase = 500L))
    }

    @Test
    fun `the hole between the last sealed segment and the current base is a gap`() {
        // Sealed segments stop at 500 but the current segment starts at 1000: [500, 1000) is gone
        // even though no individual sealed segment "starts after" the cursor.
        assertEquals(500L, firstMissingOffset(listOf(seg(0, 500)), 0L, 1500L, currentBase = 1000L))
    }

    // --- boundaries ------------------------------------------------------------

    @Test
    fun `an empty range is not a gap`() {
        assertNull(firstMissingOffset(contiguous, 0L, 0L, currentBase = 2000L))
        assertNull(firstMissingOffset(contiguous, 500L, 500L, currentBase = 2000L))
    }

    @Test
    fun `a stream with no sealed segments reads from the current base`() {
        assertNull(firstMissingOffset(emptyList(), 2000L, 2500L, currentBase = 2000L))
        assertEquals(0L, firstMissingOffset(emptyList(), 0L, 100L, currentBase = 2000L))
    }

    @Test
    fun `a range that ends exactly at the current base has no gap`() {
        // [1000, 2000) is the sealed tail and nothing more; it must not report 2000 as missing.
        assertNull(firstMissingOffset(contiguous, 1000L, 2000L, currentBase = 2000L))
    }

    @Test
    fun `segments are consulted in base order regardless of the order given`() {
        // `readRange` sorts before walking; this function must not depend on the caller having done
        // so, because a caller that forgets produces a different answer for the same store.
        val shuffled = listOf(seg(1000, 1000), seg(0, 1000))

        assertNull(firstMissingOffset(shuffled, 0L, 2000L, currentBase = 2000L))
    }

    @Test
    fun `an overlapping segment does not hide the gap behind it`() {
        // A segment that overlaps its predecessor covers [cursor, ...) but cannot move the cursor
        // BACK, so a later hole is still found. Walking in base order is what makes this hold.
        val overlapping = listOf(seg(0, 1000), seg(500, 1000), seg(2000, 1000))

        assertEquals(1500L, firstMissingOffset(overlapping, 0L, 2500L, currentBase = 3000L))
    }
}
