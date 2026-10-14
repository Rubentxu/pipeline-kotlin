package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * R4 / WU-R4.4 — `StreamingUtf8Decoder` contract.
 *
 * The decoder closes PC-08 (OBS_PROGRESSIVE_CONSOLE_ROADMAP §3). The pre-fix read pipeline
 * re-decodes bytes one page at a time with `String(bytes, UTF_8)`, which means a multi-byte
 * codepoint straddling two pages produces TWO U+FFFD replacement characters instead of one
 * correctly emitted character. These rows prove the fix on every shape the gap names.
 */
class StreamingUtf8DecoderTest {

    // ------------------------------------------------------------------ single-page equivalence

    @Test
    fun `decodes a single complete page byte-for-byte equal to String(_, UTF_8)`() {
        // Mix of 1/2/3-byte sequences.
        val bytes = "Hello, 世界! 🚀".toByteArray(Charsets.UTF_8)
        val decoder = StreamingUtf8Decoder()
        assertEquals(String(bytes, Charsets.UTF_8), decoder.decode(bytes))
    }

    @Test
    fun `passes ASCII through unchanged across many pages`() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(10)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val decoder = StreamingUtf8Decoder()
        val pageSize = 13
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val end = (i + pageSize).coerceAtMost(bytes.size)
            out.append(decoder.decode(bytes.copyOfRange(i, end)))
            i = end
        }
        assertEquals(text, out.toString())
    }

    // ------------------------------------------------------------------ codepoint split across pages

    @Test
    fun `assembles a 3-byte code-point split across two pages into one character`() {
        // "ñ" is U+00F1, encoded as 0xC3 0xB1 in UTF-8 (2 bytes). Split between pages.
        val bytes = "ñ".toByteArray(Charsets.UTF_8)
        assertEquals(2, bytes.size, "premise broken: ñ must be 2 bytes in UTF-8")
        val decoder = StreamingUtf8Decoder()
        val out = StringBuilder()
        out.append(decoder.decode(bytes.copyOfRange(0, 1))) // 0xC3 alone
        out.append(decoder.decode(bytes.copyOfRange(1, 2))) // 0xB1
        assertEquals("ñ", out.toString(), "split codepoint must reassemble into one character")
    }

    @Test
    fun `assembles a 4-byte code-point (surrogate pair) split across three pages`() {
        // "🚀" is U+1F680, encoded as F0 9F 9A 80 in UTF-8 (4 bytes). Split 1/1/2.
        val bytes = "🚀".toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)
        val decoder = StreamingUtf8Decoder()
        val out = StringBuilder()
        out.append(decoder.decode(bytes.copyOfRange(0, 1))) // F0 alone (4-byte leader)
        out.append(decoder.decode(bytes.copyOfRange(1, 2))) // 9F (continuation)
        out.append(decoder.decode(bytes.copyOfRange(2, 4))) // 9A 80 (continuation + last)
        assertEquals("🚀", out.toString())
    }

    @Test
    fun `split codepoint over 4 KiB page boundary yields same string as whole-input decode`() {
        // Pre-fix defect: a 4 KiB frame boundary that lands inside a multi-byte codepoint
        // produces U+FFFD at the boundary and a base64 entry on the next page.
        val text = buildString {
            repeat(500) { append("世界") }
            append("🚀")
            repeat(500) { append("Hola") }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val whole = String(bytes, Charsets.UTF_8)

        val decoder = StreamingUtf8Decoder()
        val out = StringBuilder()
        val pageSize = 4096
        var i = 0
        while (i < bytes.size) {
            val end = (i + pageSize).coerceAtMost(bytes.size)
            out.append(decoder.decode(bytes.copyOfRange(i, end)))
            i = end
        }
        assertEquals(
            whole,
            out.toString(),
            "4 KiB page boundaries must NOT split a character — pre-fix this lost every world emoji in the stream",
        )
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    fun `trailing malformed byte is replaced with U+FFFD on the next page`() {
        // 0xC3 alone is an incomplete 2-byte leader. On its own page it must be HELD (not
        // emitted as replacement); on the next page, if no continuation arrives, it becomes
        // U+FFFD.
        val decoder = StreamingUtf8Decoder()
        val first = decoder.decode(byteArrayOf(0xC3.toByte()))
        assertEquals("", first, "incomplete leader must NOT emit replacement on its own page")
        val second = decoder.decode("abc".toByteArray(Charsets.UTF_8))
        assertEquals("�abc", second)
    }

    @Test
    fun `reset discards the held-over partial codepoint`() {
        val decoder = StreamingUtf8Decoder()
        decoder.decode(byteArrayOf(0xC3.toByte()))
        decoder.reset()
        // After reset, the next decode is a clean page.
        assertEquals("ok", decoder.decode("ok".toByteArray(Charsets.UTF_8)))
    }
}
