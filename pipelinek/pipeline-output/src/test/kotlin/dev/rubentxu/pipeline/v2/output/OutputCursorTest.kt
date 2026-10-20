package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * A cursor is a RESUMABLE POSITION, so its wire form has to survive a round trip through a journal
 * and still name the same byte of the same stream. `OutputPage` is the other half: it is the only
 * thing that hands a caller the cursor to resume from, so the two have to agree on where the page
 * ended.
*/
class OutputCursorTest {

    // --- the token round-trip ------------------------------------------------

    @Test
    fun `an encoded cursor decodes back to itself`() {
        for (offset in listOf(0L, 1L, 4096L, 1_000_000L)) {
            val cursor = OutputCursor(OutputStreamId("run-7/step.out/stdout"), offset)

            assertEquals(cursor, OutputCursor.decode(cursor.encode()),
                "offset $offset must survive the round-trip")
        }
    }

    @Test
    fun `a stream id with characters a token cannot carry still round-trips`() {
        // The stream value is URL-encoded, so spaces, colons and slashes are escaped rather than
        // mistaken for the token's own separators.
        val cursor = OutputCursor(OutputStreamId("run 7/step:out/stdout"), 5L)

        assertEquals(cursor, OutputCursor.decode(cursor.encode()))
    }

    @Test
    fun `start is the cursor at offset zero`() {
        val stream = OutputStreamId("run-7/op/stdout")

        assertEquals(OutputCursor(stream, 0L), OutputCursor.start(stream))
    }

    @Test
    fun `a negative offset is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputCursor(OutputStreamId("s"), -1L)
        }
    }

    @Test
    fun `a blank stream id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OutputStreamId(" ") }
        assertThrows(IllegalArgumentException::class.java) { OutputStreamId("") }
    }

    // --- what decode refuses -------------------------------------------------

    @Test
    fun `a token with the wrong shape is refused`() {
        assertNull(OutputCursor.decode(""), "empty")
        assertNull(OutputCursor.decode("out-cursor-v1:stream"), "two segments")
        assertNull(OutputCursor.decode("out-cursor-v1:s:0:extra"), "four segments")
    }

    @Test
    fun `a token with a foreign or mis-cased prefix is refused`() {
        assertNull(OutputCursor.decode("evt-cursor-v1:s:0"), "another cursor's namespace")
        assertNull(OutputCursor.decode("OUT-CURSOR-V1:s:0"), "the prefix is compared exactly")
        assertNull(OutputCursor.decode("out-cursor-v2:s:0"), "an unknown version")
    }

    @Test
    fun `a token whose offset is not a non-negative Long is refused`() {
        assertNull(OutputCursor.decode("out-cursor-v1:s:abc"), "not a number")
        assertNull(OutputCursor.decode("out-cursor-v1:s:"), "empty")
        assertNull(OutputCursor.decode("out-cursor-v1:s:1.5"), "not an integer")
        assertNull(OutputCursor.decode("out-cursor-v1:s:-1"), "negative")
        assertNull(OutputCursor.decode("out-cursor-v1:s:99999999999999999999"), "beyond Long")
    }

    @Test
    fun `a token with a malformed escape or a blank stream is refused without throwing`() {
        assertNull(OutputCursor.decode("out-cursor-v1:%ZZ:0"), "a stray percent is not a valid escape")
        assertNull(OutputCursor.decode("out-cursor-v1:%20:0"), "decodes to whitespace, which is blank")
    }

    @Test
    fun `a plus decodes to a space even though encode never writes one`() {
        // URLEncoder spells a space as '+' and URLDecoder reads it back as one, so this token is
        // decodable but is not one encode produces. It is pinned so that a future switch to a
        // different encoder is a deliberate change with a visible test diff, not a silent one.
        val decoded = OutputCursor.decode("out-cursor-v1:a+b:0")

        assertEquals(OutputCursor(OutputStreamId("a b"), 0L), decoded)
    }

    // --- the page and the cursor it hands back -------------------------------

    @Test
    fun `a page's next cursor is the position immediately after its bytes`() {
        val stream = OutputStreamId("run-7/op/stdout")
        val page = OutputPage(
            bytes = ByteArray(10),
            stream = stream,
            from = 100L,
            next = OutputCursor(stream, 110L),
            committedEnd = 500L,
        )

        assertEquals(110L, page.end)
    }

    @Test
    fun `a next cursor that skips or repeats bytes is refused`() {
        val stream = OutputStreamId("run-7/op/stdout")

        assertThrows(IllegalArgumentException::class.java) {
            OutputPage(ByteArray(10), stream, 100L, OutputCursor(stream, 111L), 500L)
        }
    }

    @Test
    fun `a next cursor pointing at another stream is refused`() {
        val stream = OutputStreamId("run-7/op/stdout")

        assertThrows(IllegalArgumentException::class.java) {
            // Same offset, wrong stream: a reader resuming here would read the right byte of the
            // wrong output, which is the failure a per-stream cursor exists to make impossible.
            OutputPage(ByteArray(10), stream, 100L, OutputCursor(OutputStreamId("run-7/op/stderr"), 110L), 500L)
        }
    }

    @Test
    fun `the last page of a stream carries no next cursor`() {
        val stream = OutputStreamId("run-7/op/stdout")

        val page = OutputPage(ByteArray(4), stream, 100L, null, 104L)

        assertEquals(104L, page.end)
        assertEquals(104L, page.committedEnd)
    }

    @Test
    fun `a page cannot end past the committed extent`() {
        val stream = OutputStreamId("run-7/op/stdout")

        assertThrows(IllegalArgumentException::class.java) {
            OutputPage(ByteArray(10), stream, 100L, null, 105L)
        }
    }

    @Test
    fun `an empty page in mid-stream is legal and resumes at the same offset`() {
        val stream = OutputStreamId("run-7/op/stdout")

        val page = OutputPage(ByteArray(0), stream, 100L, OutputCursor(stream, 100L), 500L)

        assertEquals(100L, page.end)
    }

    @Test
    fun `pages compare by their bytes, not by array identity`() {
        val stream = OutputStreamId("run-7/op/stdout")
        val bytes = "abc".toByteArray()

        val a = OutputPage(bytes, stream, 0L, null, 3L)
        val b = OutputPage("abc".toByteArray(), stream, 0L, null, 3L)

        assertEquals(a, b, "two pages with equal content are equal")
        assertEquals(a.hashCode(), b.hashCode(), "equal pages hash equally")
        assertNotEquals(a, OutputPage("abd".toByteArray(), stream, 0L, null, 3L))
    }

    @Test
    fun `the start cursor and an offset-zero page agree`() {
        val stream = OutputStreamId("run-7/op/stdout")

        assertEquals(OutputCursor.start(stream), OutputPage(ByteArray(0), stream, 0L, OutputCursor(stream, 0L), 9L).next)
    }

    @Test
    fun `a page keeps the byte array it was given, so a later mutation is visible`() {
        // Not a defect and not a contract to keep: this pins the CURRENT aliasing so that adding a
        // defensive copy would show up as a failing test rather than as a silent cost on every
        // page the Output Plane serves.
        val stream = OutputStreamId("run-7/op/stdout")
        val bytes = "abc".toByteArray()
        val page = OutputPage(bytes, stream, 0L, null, 3L)

        bytes[0] = 'z'.code.toByte()

        assertEquals('z'.code.toByte(), page.bytes[0], "the page aliases the array it was handed")
        assertFalse(page.bytes.contentEquals("abc".toByteArray()))
        assertArrayEquals("zbc".toByteArray(), page.bytes)
    }
}
