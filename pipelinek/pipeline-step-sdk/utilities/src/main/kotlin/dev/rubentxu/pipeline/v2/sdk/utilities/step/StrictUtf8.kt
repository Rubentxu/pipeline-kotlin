package dev.rubentxu.pipeline.v2.sdk.utilities.step

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The text of some bytes, when the bytes ARE text.
 *
 * ## The two answers
 *
 * [Text] is the text of bytes that are UTF-8. [Malformed] says the bytes are
 * not, and WHERE they stop being so.
 *
 * ## Why the offset is part of the answer
 *
 * Because a refusal that says only "this is not UTF-8" sends the operator
 * looking for the file, and the file is fine: what is broken is one byte in
 * the middle of it. The offset names that byte.
 */
internal sealed interface Utf8Decoding {

    /** The bytes are UTF-8, and this is their text. */
    data class Text(val value: String) : Utf8Decoding

    /**
     * The bytes are not UTF-8, and this is the offset of the first byte that
     * does not belong to a valid sequence.
     */
    data class Malformed(val offset: Int) : Utf8Decoding
}

/**
 * Decodes bytes as UTF-8 without replacing what it cannot read.
 *
 * ## Why not `ByteArray.toString(Charsets.UTF_8)`
 *
 * Because that conversion does not fail: every byte it cannot read becomes
 * U+FFFD, and the document that reaches the parser is a document the bytes do
 * not contain. A Step that reads a file has to be able to say "this file is
 * not text", and a conversion that cannot fail cannot say it.
 *
 * ## What it refuses
 *
 * A byte that is not part of a valid UTF-8 sequence. Nothing else: the
 * decoder is the JDK's own, so what UTF-8 accepts is what this accepts, and
 * a valid document is decoded exactly as it was written.
 */
internal object StrictUtf8 {

    /**
     * The text of [bytes], or the place where they stop being UTF-8.
     *
     * Measured on the JDK 21: after a malformed error the position of the
     * input buffer stays at the FIRST byte of the malformed sequence, which
     * is the offset this reports.
     */
    fun decode(bytes: ByteArray): Utf8Decoding {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        // A character takes LESS than a byte in UTF-8, so the room for the
        // text is always enough: there is no input whose text does not fit.
        val output = CharBuffer.allocate(bytes.size.coerceAtLeast(1))
        val result = decoder.decode(input, output, true)
        if (result.isMalformed || result.isUnmappable) {
            return Utf8Decoding.Malformed(input.position())
        }
        output.flip()
        return Utf8Decoding.Text(output.toString())
    }
}
