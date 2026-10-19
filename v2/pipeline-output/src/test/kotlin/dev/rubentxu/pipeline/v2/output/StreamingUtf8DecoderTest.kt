package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * The Output Plane frames bytes wherever a write happens, so a multi-byte code point can straddle a
 * page boundary. `StreamingUtf8Decoder` exists to hold that half code point and emit it once the
 * next page completes it.
 * Its own KDoc names the guarantee twice, and both are the contract these tests pin:
 * - "`String(bytes, UTF_8)` is the reference: a single-page decode is byte-for-byte equivalent."
 * - "Invalid byte sequences are replaced with U+FFFD — but ONLY when the decoder has confirmed
 *   that no continuation can follow (next page starts with an ASCII byte, …)."
 * The second clause is what makes this more than a string-equality exercise: a decoder that holds
 * a leader byte must NOT also swallow the ASCII byte sitting behind it, because that byte is
 * already in the page and is not part of the incomplete sequence.
 * Every test builds a fresh decoder: the class carries state across calls by design, so sharing an
 * instance between tests would let one test's held bytes explain another's output.
*/
class StreamingUtf8DecoderTest {

    // --- the named reference: one page in, one page out ---------------------

    @Test
    fun `a single page of ASCII decodes to its text`() {
        assertEquals("abc", StreamingUtf8Decoder().decode("abc".toByteArray()))
    }

    @Test
    fun `a single page with one multi-byte character matches the JDK decoder`() {
        // "café € 🚀" exercises 2-byte, 3-byte and 4-byte leaders in one page.
        val text = "café € 🚀"

        assertEquals(String(text.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
            StreamingUtf8Decoder().decode(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `a lone surrogate can never survive a decode`() {
        // ED 80 encodes U+, which is not a character: it is half of a pair. A
        // decoder that emits it alone hands its consumer a value that is illegal to re-encode.
        val decoded = StreamingUtf8Decoder().decode(bytes(0xED, 0xA0, 0x80))

        assertFalse(decoded.any { it in '\uD800'..'\uDFFF' },
            "a lone UTF-16 surrogate must not leave the decoder: $decoded")
    }

    @Test
    fun `an overlong encoding is replaced, not decoded to the character it smuggles`() {
        // 80 is the two-byte spelling of U+0000. Accepting it would let anything that filters
        // on decoded text be bypassed by writing the character in a longer form.
        val decoded = StreamingUtf8Decoder().decode(bytes(0xC0, 0x80))

        assertFalse(decoded.contains('\u0000'), "an overlong NUL must not decode to NUL: $decoded")
        // Byte-exact agreement with the JDK is deliberately NOT asserted here. The JDK reports a
        // malformed LENGTH per structural step, so it groups an overlong sequence differently than
        // any other correct grouping might; the guarantee that matters is the one below -- the
        // smuggled character never appears, whatever the replacement count turns out to be.
        assertFalse(decoded.any { it != '\uFFFD' },
            "an overlong encoding yields only replacements: $decoded")
    }

    @Test
    fun `a code point above the Unicode maximum is replaced`() {
        // BF BF BF is the largest sequence the 4-byte branch accepts: U+1FFFFF, which is
        // above U+10FFFF and therefore not a character at all.
        val decoded = StreamingUtf8Decoder().decode(bytes(0xF7, 0xBF, 0xBF, 0xBF))

        assertFalse(decoded.any { it in '\uD800'..'\uDFFF' },
            "a code point past U+10FFFF must not decode to a surrogate pair: $decoded")
    }

    // --- the swallow regression ---------------------------------------------

    @Test
    fun `an ASCII byte behind an incomplete leader is decoded, not held over`() {
        // Is a 3-byte leader with only ONE byte after it in this page, and that byte is 'A'
        // which cannot be a continuation. The leader is malformed NOW: the decoder must emit
        // U+FFFD for it and decode the 'A' in the same page. Holding both would delay the 'A'
        // by a page and lose it entirely if the caller resets.
        val decoder = StreamingUtf8Decoder()

        val decoded = decoder.decode(bytes(0xE2, 0x41))

        assertEquals(String(bytes(0xE2, 0x41), StandardCharsets.UTF_8), decoded,
            "the JDK emits U+FFFD then 'A'; a holder that swallows the 'A' emits nothing")
    }

    @Test
    fun `an ASCII byte behind a 4-byte leader is decoded, not held over`() {
        // Is a 4-byte leader with three bytes after it, the second of which is 'A'. Only the
        // first continuation is plausible; 'A' cannot continue it.
        val page = bytes(0xF0, 0x9F, 0x41, 0x80)

        assertEquals(String(page, StandardCharsets.UTF_8), StreamingUtf8Decoder().decode(page),
            "a 4-byte leader with a non-continuation in the middle decodes as the JDK decodes it")
    }

    @Test
    fun `an ASCII byte behind a 2-byte leader is decoded, not held over`() {
        // The 2-byte branch checks its single continuation before holding, so this one already
        // behaved; the test keeps the 3- and 4-byte fixes from regressing into a narrower shape.
        val page = bytes(0xC3, 0x41)

        assertEquals(String(page, StandardCharsets.UTF_8), StreamingUtf8Decoder().decode(page))
    }

    // --- holding a real half code point across pages -----------------------

    @Test
    fun `a code point split across two pages is emitted once, by the second`() {
        val decoder = StreamingUtf8Decoder()
        val rocket = "🚀".toByteArray(StandardCharsets.UTF_8)

        val first = decoder.decode(rocket.copyOfRange(0, 2))
        val second = decoder.decode(rocket.copyOfRange(2, 4))

        assertEquals("", first, "the first page cannot yet name the character")
        assertEquals("🚀", second, "the completing page emits the whole character")
    }

    @Test
    fun `a code point split one byte at a time is still emitted whole`() {
        val decoder = StreamingUtf8Decoder()
        val rocket = "🚀".toByteArray(StandardCharsets.UTF_8)

        val decoded = rocket.map { decoder.decode(byteArrayOf(it)) }.joinToString("")

        assertEquals("🚀", decoded)
    }

    @Test
    fun `a page of complete text followed by a half code point yields both`() {
        val decoder = StreamingUtf8Decoder()
        val rocket = "🚀".toByteArray(StandardCharsets.UTF_8)

        val first = decoder.decode("hi ".toByteArray() + rocket.copyOfRange(0, 2))
        val second = decoder.decode(rocket.copyOfRange(2, 4))

        assertEquals("hi ", first)
        assertEquals("🚀", second)
    }

    @Test
    fun `arbitrary byte-level page splits concatenate to the whole decode`() {
        // Split the ENCODED bytes, not the string: cutting the String at a character boundary
        // would never exercise the holder, and cutting it mid-character via a literal like
        // "🚀" would encode a lone surrogate rather than the rocket's continuation bytes.
        val all = "café 🚀!".toByteArray(StandardCharsets.UTF_8)
        val decoder = StreamingUtf8Decoder()

        val joined = all.toList().chunked(3).map { decoder.decode(it.toByteArray()) }.joinToString("")

        assertEquals("café 🚀!", joined,
            "a page split that lands inside a code point must still produce the whole text")
    }

    // --- reset ---------------------------------------------------------------

    @Test
    fun `reset drops a held leader without emitting it`() {
        val decoder = StreamingUtf8Decoder()

        decoder.decode(bytes(0xE2, 0x82)) // a complete 2-byte sequence, nothing held
        decoder.decode(bytes(0xF0, 0x9F)) // a 4-byte leader with one continuation: held
        decoder.reset()
        val afterReset = decoder.decode("ok".toByteArray())

        assertEquals("ok", afterReset, "after reset the decoder carries nothing from before")
    }

    @Test
    fun `an empty page decodes to nothing and keeps what was held`() {
        val decoder = StreamingUtf8Decoder()
        val rocket = "🚀".toByteArray(StandardCharsets.UTF_8)
        decoder.decode(rocket.copyOfRange(0, 2))

        assertEquals("", decoder.decode(ByteArray(0)), "an empty page adds no text")
        assertEquals("🚀", decoder.decode(rocket.copyOfRange(2, 4)),
            "an empty page must not discard the held bytes")
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}
