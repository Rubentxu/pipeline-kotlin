package dev.rubentxu.pipeline.v2.output

/**
 * R4 / WU-R4.4 — incremental UTF-8 decoder that holds half-codepoint state across page reads.
 *
 * ## Why this exists
 *
 * The Output Plane frames bytes in [page] entries whose boundaries fall wherever a write happens,
 * not wherever a code-point ends. The OBS-E5 read pipeline concatenates page bytes into a transcript
 * with [java.nio.charset.CharsetDecoder] re-decoded PER PAGE — which means a multi-byte codepoint
 * straddling two pages produces two replacement characters (or one `null` + one base64) instead of
 * one correctly emitted character. PC-08 (OBS_PROGRESSIVE_CONSOLE_ROADMAP §3) names this exact defect:
 *
 * > Decodificación UTF-8 por frame sin estado de caracteres.
 *
 * ## The contract
 *
 * `decode(page)` accepts the next byte page, holds any partial codepoint between calls, and returns
 * the complete `CharSequence` so far. A caller that loops over pages sees one continuous text — the
 * half-codepoint at a page boundary is never visible to it.
 *
 * `String(bytes, UTF_8)` is the reference: a single-page decode is byte-for-byte equivalent, and a
 * multi-page decode assembles partial codepoints without ever emitting a replacement character for
 * a sequence that simply continues onto the next page.
 *
 * ## Errors
 *
 * Invalid byte sequences are replaced with U+FFFD — but ONLY when the decoder has confirmed that no
 * continuation can follow (next page starts with an ASCII byte, the leader is a leader that the
 * next page cannot complete, or `reset` is called). A trailing leader at end-of-input is held over;
 * the caller decides what to do with it (typically `reset()` to drop it).
 */
class StreamingUtf8Decoder {

    /**
     * Held-over bytes from the previous page that form an incomplete UTF-8 sequence. Empty when
     * the decoder is in sync with the page boundary.
     */
    private var pendingBytes: ByteArray = EMPTY

    /**
     * Consume the next page of bytes and return the complete `CharSequence` decoded so far.
     *
     * The returned `String` is freshly allocated; `decode` is not a view, and the caller may keep
     * the reference past the next call without aliasing surprises.
     */
    fun decode(page: ByteArray): String {
        val combined: ByteArray = if (pendingBytes.isEmpty()) {
            page
        } else {
            ByteArray(pendingBytes.size + page.size).also {
                System.arraycopy(pendingBytes, 0, it, 0, pendingBytes.size)
                System.arraycopy(page, 0, it, pendingBytes.size, page.size)
            }
        }

        // Walk the combined bytes, decoding each COMPLETE UTF-8 sequence to a char, and holding
        // any incomplete trailing sequence for the next call.
        val out = StringBuilder(combined.size)
        var i = 0
        while (i < combined.size) {
            val b0 = combined[i].toInt() and 0xff
            when {
                b0 and 0b10000000 == 0 -> {
                    // 0xxxxxxx — ASCII single byte.
                    out.append(b0.toChar())
                    i++
                }
                b0 and 0b11100000 == 0b11000000 -> {
                    // 110xxxxx — 2-byte sequence. Needs 1 continuation byte.
                    if (i + 1 >= combined.size) break
                    val b1 = combined[i + 1].toInt() and 0xff
                    if (b1 and 0b11000000 != 0b10000000) {
                        // Malformed continuation. Replace with U+FFFD and skip the leader.
                        out.append('�')
                        i++
                    } else {
                        val code = ((b0 and 0b00011111) shl 6) or (b1 and 0b00111111)
                        out.append(code.toChar())
                        i += 2
                    }
                }
                b0 and 0b11110000 == 0b11100000 -> {
                    // 1110xxxx — 3-byte sequence. Needs 2 continuation bytes.
                    if (i + 2 >= combined.size) break
                    val b1 = combined[i + 1].toInt() and 0xff
                    val b2 = combined[i + 2].toInt() and 0xff
                    if (b1 and 0b11000000 != 0b10000000 || b2 and 0b11000000 != 0b10000000) {
                        out.append('�')
                        i++
                    } else {
                        val code = ((b0 and 0b00001111) shl 12) or
                            ((b1 and 0b00111111) shl 6) or
                            (b2 and 0b00111111)
                        out.append(code.toChar())
                        i += 3
                    }
                }
                b0 and 0b11111000 == 0b11110000 -> {
                    // 11110xxx — 4-byte sequence. Needs 3 continuation bytes.
                    if (i + 3 >= combined.size) break
                    val b1 = combined[i + 1].toInt() and 0xff
                    val b2 = combined[i + 2].toInt() and 0xff
                    val b3 = combined[i + 3].toInt() and 0xff
                    if (b1 and 0b11000000 != 0b10000000 ||
                        b2 and 0b11000000 != 0b10000000 ||
                        b3 and 0b11000000 != 0b10000000
                    ) {
                        out.append('�')
                        i++
                    } else {
                        // 4-byte sequences encode code-points U+10000..U+10FFFF, which
                        // require a UTF-16 surrogate PAIR (two `Char`s). `Int.toChar()` would
                        // truncate the low 16 bits and emit a low-surrogate character on its
                        // own, which is exactly the pre-fix defect for codepoints like 🚀.
                        val code = ((b0 and 0b00000111) shl 18) or
                            ((b1 and 0b00111111) shl 12) or
                            ((b2 and 0b00111111) shl 6) or
                            (b3 and 0b00111111)
                        val shifted = code - 0x10000
                        val high = 0xD800 + (shifted shr 10)
                        val low = 0xDC00 + (shifted and 0x3FF)
                        out.append(high.toChar())
                        out.append(low.toChar())
                        i += 4
                    }
                }
                else -> {
                    // Continuation byte (10xxxxxx) at the start of a page, or a malformed
                    // 5/6-byte leader. Either way, replace with U+FFFD and skip.
                    out.append('�')
                    i++
                }
            }
        }

        // Anything that didn't fit a complete sequence becomes the next pending bytes. The
        // `break` statements above jump here when we run out of bytes mid-sequence.
        if (i < combined.size) {
            pendingBytes = combined.copyOfRange(i, combined.size)
        } else {
            pendingBytes = EMPTY
        }
        return out.toString()
    }

    /**
     * Drop any held-over partial codepoint. Useful for tests and for callers that want to end a
     * decode session and discard a malformed trailing byte rather than carry it forever.
     */
    fun reset() {
        pendingBytes = EMPTY
    }

    private companion object {
        private val EMPTY = ByteArray(0)
    }
}
