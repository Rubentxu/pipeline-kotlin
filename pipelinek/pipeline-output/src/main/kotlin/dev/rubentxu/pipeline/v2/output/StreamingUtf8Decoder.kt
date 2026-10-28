package dev.rubentxu.pipeline.v2.output

/**
 * Incremental UTF-8 decoder that holds half-codepoint state across page reads.
 * ## Why this exists
 * The Output Plane frames bytes in [page] entries whose boundaries fall wherever a write happens,
 * Not wherever a code-point ends. The read pipeline concatenates page bytes into a transcript
 * with [java.nio.charset.CharsetDecoder] re-decoded PER PAGE — which means a multi-byte codepoint
 * straddling two pages produces two replacement characters (or one `null` + one base64) instead of
 * One correctly emitted character. That is the defect this decoder exists to
 * fix: decoding by frame without character state.
 * ## The contract
 * `decode(page)` accepts the next byte page, holds any partial codepoint between calls, and returns
 * the text THIS page contributed. It is not an accumulator: the returned `String` starts empty on
 * every call and never repeats an earlier page. A caller that loops over pages therefore CONCATENATES
 * the returns to see one continuous text — the half-codepoint at a page boundary is never visible to
 * it. (An earlier version of this KDoc promised "the complete `CharSequence` so far", which the
 * implementation has never done; a caller written against that sentence would silently drop every
 * page but the last.)
 * `String(bytes, UTF_8)` is the reference for which bytes are valid, but not for how many U+FFFD a
 * malformed run produces: the JDK reports a malformed LENGTH per structural step, so it may group a
 * bad run differently than this decoder does. What is guaranteed is that a valid sequence decodes to
 * the same characters, and that an invalid one never yields a lone surrogate, an overlong spelling of
 * a real character, or a code point past U+10FFFF.
 * ## Errors
 * Invalid byte sequences are replaced with U+FFFD — but ONLY when the decoder has confirmed that no
 * continuation can follow (next page starts with an ASCII byte, the leader is a leader that the
 * next page cannot complete, or `reset` is called). A trailing leader at end-of-input is held over;
 * the caller decides what to do with it (typically `reset()` to drop it).
*/
class StreamingUtf8Decoder {

    /**
     * Held-over bytes from the previous page that form an incomplete sequence. Empty when
     * the decoder is in sync with the page boundary.
*/
    private var pendingBytes: ByteArray = EMPTY

    /**
     * Consume the next page of bytes and return the text THIS page contributed.
     * Not an accumulator: the returned `String` starts empty on every call and never repeats an
     * earlier page. A held-over half code point is not part of it — that text arrives with a later
     * page's return.
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

        // Walk the combined bytes, decoding each COMPLETE sequence and holding any
        // incomplete trailing sequence for the next call. Each branch reports how many bytes it
        // consumed, or HOLD when the sequence continues onto the next page.
        val out = StringBuilder(combined.size)
        var i = 0
        while (i < combined.size) {
            val b0 = combined[i].toInt() and 0xff
            val consumed = when {
                b0 and 0b10000000 == 0 -> {
                    // 0xxxxxxx — ASCII single byte.
                    out.append(b0.toChar())
                    1
                }
                b0 and 0b11100000 == 0b11000000 -> twoBytes(combined, i, out)
                b0 and 0b11110000 == 0b11100000 -> threeBytes(combined, i, out)
                b0 and 0b11111000 == 0b11110000 -> fourBytes(combined, i, out)
                else -> {
                    // Continuation byte (10xxxxxx) at the start of a page, or a malformed
                    // 5/6-byte leader. Either way, replace with U+FFFD and skip.
                    out.append(REPLACEMENT)
                    1
                }
            }
            if (consumed == HOLD) break
            i += consumed
        }

        // Anything that didn't fit a complete sequence becomes the next pending bytes. The
        // `break` above jumps here when we run out of bytes mid-sequence.
        if (i < combined.size) {
            pendingBytes = combined.copyOfRange(i, combined.size)
        } else {
            pendingBytes = EMPTY
        }
        return out.toString()
    }

    /**
     * 110xxxxx — a 2-byte sequence needing 1 continuation byte.
     * The branch admits leaders..DF, but and can only ever spell U+0000..U+007F, which a
     * single ASCII byte already spells. Accepting them would let a filter on decoded text be
     * bypassed by writing the character in a longer form, so a 2-byte sequence must reach U+0080.
*/
    private fun twoBytes(combined: ByteArray, i: Int, out: StringBuilder): Int {
        if (i + 1 >= combined.size) return HOLD
        val b1 = combined[i + 1].toInt() and 0xff
        if (!isContinuation(b1)) {
            // The byte behind the leader cannot continue it, so the leader is malformed NOW and
            // only the leader is consumed. The byte itself is decoded on the next iteration.
            out.append(REPLACEMENT)
            return 1
        }
        val code = ((combined[i].toInt() and 0b00011111) shl 6) or (b1 and 0b00111111)
        if (code < 0x80) {
            out.append(REPLACEMENT)
            return 1
        }
        out.append(code.toChar())
        return 2
    }

    /**
     * 1110xxxx — a 3-byte sequence needing 2 continuation bytes.
     * Each available byte is checked BEFORE deciding to hold the leader. A non-continuation byte
     * that is already in this page proves the leader malformed now, so the leader becomes U+FFFD
     * and the loop continues at the byte after it. The previous shape peeked only at
     * `i + 2 >= size`, so a page like [, 41] took the hold path and swallowed the 'A' — a valid
     * character in this very page — into pendingBytes, delaying it by a page and losing it
     * outright if the caller reset.
     * A malformed unit also consumes the continuations it already accepted: [, 9F, 41, 80] is
     * one bad unit, then 'A', then a stray continuation — three characters, not four.
*/
    private fun threeBytes(combined: ByteArray, i: Int, out: StringBuilder): Int {
        if (i + 1 >= combined.size) return HOLD
        val b1 = combined[i + 1].toInt() and 0xff
        if (!isContinuation(b1)) {
            out.append(REPLACEMENT)
            return 1
        }
        if (i + 2 >= combined.size) return HOLD
        val b2 = combined[i + 2].toInt() and 0xff
        if (!isContinuation(b2)) {
            out.append(REPLACEMENT)
            return 2
        }
        val code = ((combined[i].toInt() and 0b00001111) shl 12) or
            ((b1 and 0b00111111) shl 6) or
            (b2 and 0b00111111)
        // 3 bytes can spell anything from U+0000, so a code below U+0800 is an OVERLONG encoding
        // Of a character a shorter form already exists for, and U+..U+DFFF is not a character
        // At all — it is one half of a pair. Both yield U+FFFD, never the smuggled value
        if (code < 0x0800 || code in 0xD800..0xDFFF) {
            out.append(REPLACEMENT)
            return 3
        }
        out.append(code.toChar())
        return 3
    }

    /**
     * 11110xxx — a 4-byte sequence needing 3 continuation bytes. Each available byte is checked
     * before holding, for the same reason as the 3-byte branch: a byte that cannot continue the
     * leader must be decoded, not retained.
     * A valid sequence becomes a surrogate PAIR. `Int.toChar()` would truncate the low 16
     * bits and emit a lone low-surrogate, which is the pre-fix defect for code points like 🚀.
*/
    private fun fourBytes(combined: ByteArray, i: Int, out: StringBuilder): Int {
        if (i + 1 >= combined.size) return HOLD
        val b1 = combined[i + 1].toInt() and 0xff
        if (!isContinuation(b1)) {
            out.append(REPLACEMENT)
            return 1
        }
        if (i + 2 >= combined.size) return HOLD
        val b2 = combined[i + 2].toInt() and 0xff
        if (!isContinuation(b2)) {
            out.append(REPLACEMENT)
            return 2
        }
        if (i + 3 >= combined.size) return HOLD
        val b3 = combined[i + 3].toInt() and 0xff
        if (!isContinuation(b3)) {
            out.append(REPLACEMENT)
            return 3
        }
        val code = ((combined[i].toInt() and 0b00000111) shl 18) or
            ((b1 and 0b00111111) shl 12) or
            ((b2 and 0b00111111) shl 6) or
            (b3 and 0b00111111)
        // The branch admits leaders.., but.. cannot reach U+10FFFF. U+10000 is the
        // floor for this width, so anything under it is overlong, and anything past U+10FFFF is
        // not a code point at all.
        if (code < 0x10000 || code > 0x10FFFF) {
            out.append(REPLACEMENT)
            return 4
        }
        val shifted = code - 0x10000
        out.append((0xD800 + (shifted shr 10)).toChar())
        out.append((0xDC00 + (shifted and 0x3FF)).toChar())
        return 4
    }

    /** 10xxxxxx — the continuation byte every multi-byte leader after it must be. */
    private fun isContinuation(b: Int): Boolean = b and 0b11000000 == 0b10000000

    /**
     * Drop any held-over partial codepoint. Useful for tests and for callers that want to end a
     * decode session and discard a malformed trailing byte rather than carry it forever.
*/
    fun reset() {
        pendingBytes = EMPTY
    }

    private companion object {
        private val EMPTY = ByteArray(0)

        /** Returned by a branch that cannot finish its sequence: hold the bytes for the next page. */
        private const val HOLD = 0

        /** U+FFFD REPLACEMENT CHARACTER. */
        private const val REPLACEMENT = '�'
    }
}
