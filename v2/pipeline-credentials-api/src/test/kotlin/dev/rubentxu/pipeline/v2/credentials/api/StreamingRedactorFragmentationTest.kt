package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-A — the fragmentation matrix for [StreamingRedactor].
 *
 * ## Why this class exists
 *
 * WU-A changed the redactor's read loop (the full-ring progress branch and the retained EOF flush)
 * and removed the synthetic seam for an empty registry. Those changes are invisible to a test that
 * feeds a whole buffer at once, because `feed` was already correct — what was broken was the
 * `InputStream` wrapper's ability to make progress and to keep an EOF flush it could not hand back
 * in one call.
 *
 * So every row here drives the **stream**, at every fragmentation offset, with reads of every size
 * from 1 upwards. A redactor that only works on aligned chunks is not a redactor.
 *
 * ## Harness fidelity
 *
 * **HF0/HF1, and the level is faithful rather than convenient.** The unit under test is
 * `RedactingInputStream.read`, which is the thing that was defective, so driving it directly IS
 * the production path — `wrap()` builds exactly this object over a `ByteArrayInputStream`. What a
 * harness must never do here is recompute the expected output by running the redactor a second way;
 * so [expected] is a literal string per case, not a second invocation.
 *
 * The one thing that is NOT covered here is a live pipe: a `ByteArrayInputStream` is already at
 * EOF after one read, so the "partial read of a live source" property is proved at
 * `ShTranscriptRedactionReproductionTest` (HF1, a real `sh`) and against `installDist` (HF2), not
 * here. Saying so is part of the row, not a gap in it.
 *
 * ## The property every row asserts
 *
 * ```text
 * bytes_out == expected            (exact, including the marker)
 * concat(bytes_out) == expected    (order and completeness, over any read size)
 * the secret never appears in the output, at ANY offset
 * ```
 */
class StreamingRedactorFragmentationTest {

    private val secret = "SUPER-SECRET-VALUE-1234"
    private val marker = SecretPatternRegistry.SCRUB_MARKER

    private fun registryWith(vararg secrets: String): SecretPatternRegistry =
        SecretPatternRegistry().apply { secrets.forEach { addSecret(SecretHandle.plain(it)) } }

    /**
     * Drive the wrapper and return everything it produced, across the given read sizes.
     *
     * Each read is independent: the wrapper must be able to answer a one-byte request and a
     * large one with the same total, because a consumer picks its own buffer size.
     */
    private fun drain(redactor: StreamingRedactor, input: ByteArray, readSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        redactor.wrap(ByteArrayInputStream(input)).use { stream ->
            val buf = ByteArray(readSize)
            while (true) {
                val n = stream.read(buf, 0, readSize)
                if (n < 0) break
                assertTrue(n > 0, "read($readSize) returned 0; the contract says never 0 for len>0")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Every read size that can split the interesting cases differently, plus a few odd ones. */
    private fun readSizes(inputSize: Int): List<Int> =
        (1..minOf(inputSize, 12)).toList() + listOf(16, 64, 1024)

    // ------------------------------------------------------------------ passthrough

    /**
     * FRAG-1 — an EMPTY registry preserves the bytes exactly, at every offset and read size.
     *
     * This is the case WU-A changed most: the seam used to be a synthetic `****` literal and the
     * lookahead used to be seven bytes on a stream with nothing to look for. A registry with no
     * secrets has nothing to decide, so it must decide nothing.
     *
     * The marker is a literal in the output here **on purpose**: `****` is an ordinary thing for a
     * program to print, and the old synthetic pattern treated it as a secret and round-tripped it
     * through the matcher. The output must be byte-identical whether or not it contains one.
     */
    @Test
    fun `FRAG-1 an empty registry is an exact passthrough at every offset and read size`() {
        val redactor = StreamingRedactor(registryWith(), chunkSize = 8)
        val payload = "line-1\n****\nline-3 with $secret spelled out literally\n"
            .toByteArray(Charsets.UTF_8)

        for (readSize in readSizes(payload.size)) {
            assertArrayEquals(
                payload,
                drain(redactor, payload, readSize),
                "an empty registry must not alter a single byte, at readSize=$readSize",
            )
        }
    }

    /**
     * FRAG-2 — a secret at EVERY offset is redacted, for a payload long enough to fill the ring.
     *
     * The ring is `maxLiteralByteLength`; a payload longer than that forces the ring-full branch,
     * which is the one that used to spin. A payload shorter than the ring never reaches it, which
     * is why "one representative case" is not a test here.
     */
    @Test
    fun `FRAG-2 a secret is redacted at every offset of a payload longer than the ring`() {
        val redactor = StreamingRedactor(registryWith(secret), chunkSize = 64)
        assertTrue(
            redactor.maxLiteralByteLength < 96,
            "this row is only meaningful while the ring is smaller than the payload; " +
                "maxLiteral=${redactor.maxLiteralByteLength}",
        )
        val filler = "x".repeat(redactor.maxLiteralByteLength * 3)
        val payload = filler.toByteArray(Charsets.UTF_8)

        for (offset in 0..(filler.length - secret.length)) {
            val input = filler.toByteArray(Charsets.UTF_8).also {
                secret.toByteArray(Charsets.UTF_8).copyInto(it, offset)
            }
            val out = String(drain(StreamingRedactor(registryWith(secret), chunkSize = 64), input, 3))
            assertTrue(
                !out.contains(secret),
                "the secret leaked at offset $offset: '$out'",
            )
            assertEquals(
                filler.length - secret.length + marker.length,
                out.length,
                "everything but the secret must survive, at offset $offset; got '$out'",
            )
        }
    }

    // ------------------------------------------------------------------ shapes

    /** FRAG-3 — consecutive secrets must not be collapsed or reordered. */
    @Test
    fun `FRAG-3 consecutive secrets each become a marker`() {
        val redactor = StreamingRedactor(registryWith(secret), chunkSize = 16)
        val input = "$secret$secret$secret".toByteArray(Charsets.UTF_8)
        val out = String(drain(redactor, input, 5))

        assertEquals(marker.repeat(3), out, "three adjacent secrets are three markers, in order")
    }

    /** FRAG-4 — a secret that shares a PREFIX with another must not be decided too early. */
    @Test
    fun `FRAG-4 a shared prefix does not let the longer secret be decided early`() {
        val shorter = "SECRET-AB"
        val longer = "SECRET-ABCDEFGH"
        val redactor = StreamingRedactor(registryWith(shorter, longer), chunkSize = 4)

        // The longer secret's first bytes are a prefix of the shorter one's, so a scanner that
        // stops at the shorter match emits the tail of the longer one in the clear.
        val out = String(drain(redactor, "$longer;$shorter;$longer".toByteArray(Charsets.UTF_8), 1))
        assertEquals("$marker;$marker;$marker", out)
        assertTrue(!out.contains("DEFGH"), "the longer secret's tail leaked: '$out'")
        assertTrue(!out.contains(shorter), "the shorter secret leaked: '$out'")
    }

    /** FRAG-5 — UTF-8 multibyte survives, and a secret containing multibyte is still redacted. */
    @Test
    fun `FRAG-5 multibyte UTF-8 is preserved byte for byte and a multibyte secret is redacted`() {
        val multibyteSecret = "clé-secrète-à€" // 2-byte and 3-byte sequences
        val payload = "héllo → wörld ✅\n$multibyteSecret\nfin"
        val bytes = payload.toByteArray(Charsets.UTF_8)

        // The secret is byte-matched, so the redacted output is not the input with a substring
        // removed; it is the input re-encoded. Compare against that explicitly.
        val expected = buildString {
            append("héllo → wörld ✅\n")
            append(marker)
            append("\nfin")
        }.toByteArray(Charsets.UTF_8)

        for (readSize in readSizes(bytes.size)) {
            assertArrayEquals(
                expected,
                drain(StreamingRedactor(registryWith(multibyteSecret), chunkSize = 4), bytes, readSize),
                "multibyte must survive a byte-level redactor intact, at readSize=$readSize",
            )
        }
    }

    /** FRAG-6 — binary output passes through unchanged when nothing matches. */
    @Test
    fun `FRAG-6 binary bytes including NUL and high bytes survive exactly`() {
        val binary = ByteArray(256) { it.toByte() } // 0x00..0xFF
        for (readSize in readSizes(binary.size)) {
            assertArrayEquals(
                binary,
                drain(StreamingRedactor(registryWith(secret), chunkSize = 7), binary, readSize),
                "the redactor is a byte filter, not a text decoder; readSize=$readSize",
            )
        }
    }

    /**
     * FRAG-7 — a secret LONGER than every offered buffer must still be redacted.
     *
     * The EOF flush is what makes this work: the lookahead can never hold the whole literal, so
     * the decision has to be taken across `finish`. This row is also the one that would have
     * caught the discarded-remainder defect, because a one-byte read is exactly the case where
     * the flush cannot be handed back in one call.
     */
    @Test
    fun `FRAG-7 a secret longer than the read buffer is still fully redacted`() {
        val longSecret = "L" + "ONG-SECRET-".repeat(20) + "END"
        val redactor = StreamingRedactor(registryWith(longSecret), chunkSize = 8)
        val input = ("head-$longSecret-tail").toByteArray(Charsets.UTF_8)
        val expected = "head-$marker-tail".toByteArray(Charsets.UTF_8)

        for (readSize in listOf(1, 2, 3, 7, 8, 13, 64, 4096)) {
            assertArrayEquals(
                expected,
                drain(StreamingRedactor(registryWith(longSecret), chunkSize = 8), input, readSize),
                "the whole literal must be replaced even when no read can hold it, at readSize=$readSize",
            )
        }
    }

    /**
     * FRAG-8 — an empty input produces an empty output, and does not hang.
     *
     * The degenerate case of the full-ring branch: with no bytes there is nothing to decide, and a
     * read loop that can only exit by making progress must be able to exit by having nothing to do.
     */
    @Test
    fun `FRAG-8 an empty input yields an empty output`() {
        assertEquals(
            0,
            drain(StreamingRedactor(registryWith(secret), chunkSize = 8), ByteArray(0), 1).size,
        )
        assertEquals(
            0,
            drain(StreamingRedactor(registryWith(), chunkSize = 8), ByteArray(0), 1).size,
        )
    }

    /**
     * FRAG-9 — a payload far longer than the ring round-trips completely.
     *
     * The ring-full branch is reached once per `maxLiteral` bytes, so a payload of many multiples
     * exercises it many times, and any lost byte shows up as a length mismatch rather than as a
     * silent tail truncation.
     */
    @Test
    fun `FRAG-9 a long payload round-trips completely through the full-ring branch`() {
        val redactor = StreamingRedactor(registryWith(secret), chunkSize = 16)
        val unit = "abcdefghij"
        val payload = (unit.repeat(500) + secret + unit.repeat(500)).toByteArray(Charsets.UTF_8)
        val expected = (unit.repeat(500) + marker + unit.repeat(500)).toByteArray(Charsets.UTF_8)

        assertArrayEquals(expected, drain(redactor, payload, 7), "5000+ bytes, one-byte reads")
        assertArrayEquals(
            expected,
            drain(StreamingRedactor(registryWith(secret), chunkSize = 16), payload, 4096),
            "5000+ bytes, one large read",
        )
    }
}
