package dev.rubentxu.pipeline.v2.credentials.api

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The redactor over a secret that the flow delivers in pieces.
 *
 * ## The guarantee
 * A secret that arrives whole is masked, and a secret that arrives in pieces is masked
 * piece by piece: the bytes of the secret leave the redactor as a marker, and the bytes
 * that were not part of it leave it as they went in.
 *
 * ## The capacity this serves
 * A step's transcript is a stream, and a value in it is not always a contiguous string:
 * a credential can be handed to a child process that prints half of it, then something
 * else, then the other half. The redactor that only knows whole values cannot see that,
 * because the value it is looking for is not a string that exists in the stream. So the
 * redactor knows the PIECES of the value instead, and a piece that is long enough to be
 * worth hiding is hidden wherever it appears.
 *
 * ## What the threshold buys
 * A piece of the value is only worth hiding when it is long enough to be a piece of
 * something and not a coincidence. The threshold is the length of that promise, and it is
 * a promise in both directions: a piece above it is masked, and a piece below it is not.
 * A law that only asserted the first half would leave the threshold unbounded, and a
 * redactor that masks everything destroys the console it is meant to protect.
 *
 * ## Why these laws assert a shape and not a text
 * The value of a row is what it forbids, and a row that asserted one fixed string would
 * only forbid that string. The rows below assert the shape of the answer: how many
 * markers it holds, where the bytes that are not part of the secret are, and whether any
 * piece of the secret survives in it. The generative row sweeps the cut point, the noise
 * and the position, because one case of a secret that arrives in pieces is a sample, not
 * a guarantee.
 *
 * ## What the neighbouring file already holds
 * The laws of a value that arrives WHOLE live in [StreamingRedactorTest]: the split of
 * the reads, the tail of a secret that shares a prefix with another one, and the memory
 * of a finished pass. These rows are the same capacity over the pieces of a value, and
 * they extend those laws instead of repeating them.
 */
class StreamingRedactorFragmentTest {

    @Test
    @DisplayName("a value cut in half by noise leaves no half in the clear")
    fun `a value cut in half by noise leaves no half in the clear`() {
        val registry = registryOf(SECRET)
        val cut = SECRET.length / 2
        val head = SECRET.substring(0, cut)
        val tail = SECRET.substring(cut)

        for (noiseLength in NOISE_LENGTHS) {
            val noise = NOISE_BYTE.repeat(noiseLength)
            val transcript = redact(registry, head + noise + tail, pieceSize = 7)

            // The shape: one marker for each half of the value, and the bytes that were
            // not part of it, untouched and in their place.
            assertEquals(
                "$MARKER$noise$MARKER",
                transcript,
                "the halves of a value cut by $noiseLength bytes of noise must be masked " +
                    "and the noise must survive byte for byte",
            )
            assertFalse(
                transcript.contains(head) || transcript.contains(tail),
                "a piece of the value reached the transcript",
            )
        }
    }

    @Test
    @DisplayName("every cut point, every noise and every position leaves no half in the clear")
    fun `every cut point and every noise leaves no half in the clear`() {
        val registry = registryOf(SECRET)

        for (cut in 1 until SECRET.length) {
            for (noiseLength in NOISE_LENGTHS) {
                for (position in POSITIONS) {
                    val head = SECRET.substring(0, cut)
                    val tail = SECRET.substring(cut)
                    val noise = NOISE_BYTE.repeat(noiseLength)
                    val input = position.first + head + noise + tail + position.second

                    val transcript = redact(registry, input, pieceSize = 5)

                    // The shape: each half of the value is a marker when it is long
                    // enough to be worth hiding, and it is in the clear when it is not.
                    val expectedHead = maskedOrPlain(head)
                    val expectedTail = maskedOrPlain(tail)
                    assertEquals(
                        position.first + expectedHead + noise + expectedTail + position.second,
                        transcript,
                        "the value cut at $cut, with $noiseLength bytes of noise, at a " +
                            "position of ${position.first.length}/${position.second.length} " +
                            "bytes, did not come out masked",
                    )
                    assertFalse(
                        transcript.contains(head) && head.length >= THRESHOLD,
                        "a half of the value that is long enough to be hidden reached the transcript",
                    )
                    assertFalse(
                        transcript.contains(tail) && tail.length >= THRESHOLD,
                        "a half of the value that is long enough to be hidden reached the transcript",
                    )
                }
            }
        }
    }

    @Test
    @DisplayName("a piece shorter than the threshold is not masked, and that is the promise")
    fun `a piece shorter than the threshold is not masked`() {
        val registry = registryOf(SECRET)

        for (length in 1 until THRESHOLD) {
            val piece = SECRET.substring(0, length)
            val transcript = redact(registry, piece, pieceSize = 3)

            // The other half of the promise: the threshold bounds what is hidden.
            assertEquals(
                piece,
                transcript,
                "a piece of $length bytes is below the threshold of $THRESHOLD and must " +
                    "not be masked: a redactor that hides every coincidence destroys the console",
            )
        }
    }

    @Test
    @DisplayName("the threshold is a parameter, and a piece below it is not masked")
    fun `the threshold is a parameter of the redactor`() {
        val registry = registryOf(SECRET)
        val piece = SECRET.substring(0, THRESHOLD)

        val below = redact(
            StreamingRedactor(registry, chunkSize = 64, fragmentThresholdBytes = 16),
            piece,
            pieceSize = 3,
        )
        assertEquals(
            piece,
            below,
            "a piece of ${piece.length} bytes is below the threshold of 16 and must not be masked",
        )

        val above = redact(StreamingRedactor(registry, chunkSize = 64), piece, pieceSize = 3)
        assertEquals(
            MARKER,
            above,
            "the same piece of ${piece.length} bytes IS above the default threshold of " +
                "$THRESHOLD and must be masked",
        )
    }

    @Test
    @DisplayName("text that holds no piece of the value comes back byte for byte")
    fun `text that holds no piece of the value comes back byte for byte`() {
        val registry = registryOf(SECRET)

        // A near miss: the text begins with the first bytes of the value and diverges
        // from it, so a matcher that looks at a prefix instead of at a whole piece
        // would hide it.
        val nearMiss = SECRET.substring(0, THRESHOLD - 1) + NOISE_BYTE.repeat(THRESHOLD)
        val innocent = "the build finished in 12 seconds and printed no credential at all"

        for (text in listOf(innocent, nearMiss)) {
            assertEquals(
                text,
                redact(registry, text, pieceSize = 5),
                "text that holds no piece of the value must survive byte for byte",
            )
        }
    }

    @Test
    @DisplayName("the encoded forms of a value are protected by piece too")
    fun `the encoded forms of a value are protected by piece too`() {
        val registry = registryOf(ENCODED_SECRET)
        val forms = listOf(
            "the literal form" to ENCODED_SECRET,
            "the base64 form" to Base64.getEncoder().encodeToString(ENCODED_SECRET.toByteArray()),
            "the hexadecimal form" to HexFormat.of().formatHex(ENCODED_SECRET.toByteArray()),
            "the URL-encoded form" to ENCODED_SECRET
                .replace("@", "%40").replace(" ", "%20"),
        )

        for ((name, form) in forms) {
            val cut = form.length / 2
            val head = form.substring(0, cut)
            val tail = form.substring(cut)
            val noise = NOISE_BYTE.repeat(7)
            val transcript = redact(registry, head + noise + tail, pieceSize = 4)

            assertEquals(
                "$MARKER$noise$MARKER",
                transcript,
                "$name of a value cut by noise must be masked piece by piece",
            )
        }
    }

    @Test
    @DisplayName("a value that is not ASCII is protected in bytes and in characters")
    fun `a value that is not ASCII is protected in bytes and in characters`() {
        val registry = registryOf(UNICODE_SECRET)
        val bytes = UNICODE_SECRET.toByteArray(Charsets.UTF_8)
        val noise = NOISE_BYTE.repeat(9).toByteArray(Charsets.UTF_8)

        // The cut in the middle of the BYTES and the cut in the middle of the CHARACTERS
        // are two different cuts of the same value, because a character that is not ASCII
        // is more than one byte. Both are the way a value can reach a stream.
        val cuts = listOf(
            bytes.size / 2,
            UNICODE_SECRET.substring(0, UNICODE_SECRET.length / 2).toByteArray(Charsets.UTF_8).size,
        )

        for (cut in cuts) {
            val head = bytes.copyOfRange(0, cut)
            val tail = bytes.copyOfRange(cut, bytes.size)
            val input = head + noise + tail

            val transcript = redactBytes(StreamingRedactor(registry), input, pieceSize = 6)

            assertArrayEquals(
                MARKER.toByteArray(Charsets.UTF_8) + noise + MARKER.toByteArray(Charsets.UTF_8),
                transcript,
                "a value that is not ASCII, cut at byte $cut, must be masked piece by piece",
            )
        }
    }

    @Test
    @DisplayName("the tail of a value that shares a prefix with another one does not leak")
    fun `the tail of a value that shares a prefix with another one does not leak`() {
        // The extension of the law that lives in [StreamingRedactorTest]: there, the two
        // values arrived whole and the tail of the longer one leaked, because the shorter
        // value matched while the longer one was still incomplete. Here the longer value
        // arrives in pieces, which is the way a value reaches a stream that is not a
        // document, and its tail is a piece of its own.
        val registry = registryOf("SECRET-AB", "SECRET-ABCDEFGHIJ")
        val head = "SECRET-AB"
        val tail = "CDEFGHIJ"
        val noise = NOISE_BYTE.repeat(11)

        val transcript = redact(registry, head + noise + tail, pieceSize = 4)

        assertEquals(
            "$MARKER$noise$MARKER",
            transcript,
            "the pieces of a value that shares a prefix with a shorter registered value " +
                "must be masked, and the tail of the longer one must not leak",
        )
        assertFalse(
            transcript.contains(tail),
            "the tail of a value that shares a prefix with another one reached the transcript",
        )
    }

    @Test
    @DisplayName("the pending state does not grow with the volume of the transcript")
    fun `the pending state does not grow with the volume of the transcript`() {
        val registry = registryOf(SECRET)
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()
        val out = ByteArrayOutputStream()

        // A megabyte of transcript that carries a value in every chunk: the state that
        // waits for the bytes it cannot decide must not grow with the volume.
        val chunk = (NOISE_BYTE.repeat(CHUNK_SIZE - SECRET.length) + SECRET).toByteArray()
        var fed = 0
        while (fed < 1024 * 1024) {
            redactor.feed(state, chunk, 0, chunk.size)
            fed += chunk.size
            assertTrue(
                state.ringCount <= state.maxLiteral,
                "the lookahead held ${state.ringCount} bytes for a window of ${state.maxLiteral}",
            )
            takeAll(redactor, state, out)
        }
        out.write(redactor.finish(state))

        // The shape: the bytes that were not part of the value are all there, and the
        // value is not.
        val chunks = 1024 * 1024 / chunk.size
        assertEquals(
            chunks * (chunk.size - SECRET.length),
            out.toString(Charsets.UTF_8.name()).count { it == NOISE_BYTE[0] },
            "the transcript must come back with every byte that was not part of the value",
        )
        assertFalse(
            out.toString(Charsets.UTF_8.name()).contains(SECRET),
            "a value reached the transcript",
        )
        assertTrue(
            out.size() <= fed,
            "the transcript cannot be longer than what went in",
        )
        assertTrue(
            state.ringCount <= state.maxLiteral,
            "the lookahead must stay inside its window at the end of the transcript",
        )
    }

    /**
     * The shape a piece of the value takes in the transcript: a marker when the piece is
     * long enough to be worth hiding, and the piece itself when it is not.
     *
     * The threshold counts bytes, and the values of this file are ASCII, so their
     * characters and their bytes are the same number.
     */
    private fun maskedOrPlain(piece: String): String =
        if (piece.length >= THRESHOLD) MARKER else piece

    private fun registryOf(vararg secrets: String): SecretPatternRegistry {
        val registry = SecretPatternRegistry()
        secrets.forEach { registry.addSecretValue(it) }
        return registry
    }

    /**
     * Feed [input] to the redactor in pieces of [pieceSize] bytes and return everything
     * it decided, in order.
     */
    private fun redact(registry: SecretPatternRegistry, input: String, pieceSize: Int): String =
        redact(StreamingRedactor(registry), input, pieceSize)

    /**
     * Feed [input] to the redactor in pieces of [pieceSize] bytes and return everything
     * it decided, in order.
     */
    private fun redact(redactor: StreamingRedactor, input: String, pieceSize: Int): String =
        String(redactBytes(redactor, input.toByteArray(Charsets.UTF_8), pieceSize), Charsets.UTF_8)

    /**
     * Feed [input] to the redactor in pieces of [pieceSize] bytes and return everything
     * it decided, in order.
     */
    private fun redactBytes(redactor: StreamingRedactor, input: ByteArray, pieceSize: Int): ByteArray {
        val state = redactor.initialState()
        val out = ByteArrayOutputStream()

        var offset = 0
        while (offset < input.size) {
            val len = minOf(pieceSize, input.size - offset)
            redactor.feed(state, input, offset, len)
            offset += len
            takeAll(redactor, state, out)
        }
        out.write(redactor.finish(state))
        return out.toByteArray()
    }

    /** Move everything the state has decided into [out]. */
    private fun takeAll(redactor: StreamingRedactor, state: RedactorState, out: ByteArrayOutputStream) {
        val chunk = ByteArray(64)
        while (true) {
            val n = redactor.take(state, chunk, 0, chunk.size)
            if (n <= 0) break
            out.write(chunk, 0, n)
        }
    }

    private companion object {
        /** The marker a masked value is replaced by. */
        val MARKER = SecretPatternRegistry.SCRUB_MARKER

        /** The number of bytes a piece of a value must have to be masked. */
        const val THRESHOLD = SecretPatternRegistry.MIN_MASKABLE_LENGTH

        /** A value of twenty-four bytes that is longer than the threshold in halves. */
        const val SECRET = "s3cret-value-fragment-24"

        /** A value that carries characters that an URL encoding changes. */
        const val ENCODED_SECRET = "p@ss w0rd-fragment"

        /** A value that is not ASCII, so its bytes and its characters do not add up. */
        const val UNICODE_SECRET = "abcdefghñijkl€mnopqrstuv"

        /** The bytes that are not part of any value: they must come back untouched. */
        const val NOISE_BYTE = "x"

        /** The size of the chunk the volume is fed in. */
        const val CHUNK_SIZE = 8192

        /** The lengths of the noise that are swept: one byte, many, and a round number. */
        val NOISE_LENGTHS = listOf(1, 60, 137)

        /** Where in the transcript the value sits: before it, after it, or on its own. */
        val POSITIONS = listOf(
            "" to "",
            "build ok: " to "",
            "" to " and the step finished",
            "line one\n" to "\nline two",
        )
    }
}
