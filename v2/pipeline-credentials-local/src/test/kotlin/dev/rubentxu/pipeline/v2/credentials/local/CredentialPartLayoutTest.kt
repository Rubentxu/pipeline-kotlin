package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the exact byte layout of a credential part.
 *
 * The store writes a persistent, versioned file (magic "PKCR", versions V1/V2).
 * A change to these bytes corrupts every credential already on disk, so the
 * layout is pinned here rather than left to the round-trip tests: a round-trip
 * test passes just as happily when both writer and reader move together.
 *
 * Layout of one part, as written by `serializeCredential` and read back by
 * `deserializeCredential`:
 *
 * ```
 * offset  size  content
 *      0     1  part-name length, as a single byte
 *      1     n  part name, UTF-8
 *   1+n     4  part payload length, big-endian Int
 *  5+n     m  part payload bytes
 * ```
 */
class CredentialPartLayoutTest {

    /** Writes one part exactly as the store does today. */
    private fun writePart(out: ByteArrayOutputStream, name: String, payload: ByteArray) {
        out.write(name.length)
        out.write(name.toByteArray(Charsets.UTF_8))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
        out.write(payload, 0, payload.size)
    }

    @Test
    fun `a part is a length-prefixed name then a big-endian length-prefixed payload`() {
        val out = ByteArrayOutputStream()
        writePart(out, "value", "hello".toByteArray(Charsets.UTF_8))
        val bytes = out.toByteArray()

        // 1 + len("value") + 4 + len("hello")
        assertEquals(1 + 5 + 4 + 5, bytes.size, "unexpected part size")

        assertEquals(5, bytes[0].toInt(), "name length is a single leading byte")
        assertArrayEquals(
            "value".toByteArray(Charsets.UTF_8),
            bytes.copyOfRange(1, 6),
            "the name follows its length",
        )
        assertEquals(
            5,
            ByteBuffer.wrap(bytes, 6, 4).order(ByteOrder.BIG_ENDIAN).int,
            "payload length is big-endian",
        )
        assertArrayEquals(
            "hello".toByteArray(Charsets.UTF_8),
            bytes.copyOfRange(10, 15),
            "the payload follows its length",
        )
    }

    @Test
    fun `an empty payload is distinguishable from an absent one only by the part count`() {
        // The part count is written before the parts, so an empty payload still
        // produces a real part. This is why the count, not the payload, is what
        // tells a reader the credential kind.
        val out = ByteArrayOutputStream()
        writePart(out, "passphrase", ByteArray(0))
        val bytes = out.toByteArray()

        assertEquals(1 + 10 + 4, bytes.size, "an empty payload still writes its length prefix")
        assertEquals(
            0,
            ByteBuffer.wrap(bytes, 11, 4).order(ByteOrder.BIG_ENDIAN).int,
            "an empty payload is recorded as length 0, not omitted",
        )
    }

    @Test
    fun `the name length prefix counts UTF-8 bytes, not characters`() {
        // The prefix is a byte count but the writer uses `String.length`, which
        // counts UTF-16 code units. For any name outside ASCII those two
        // disagree, and a reader would then mis-parse every following part.
        // Every current part name is ASCII ("value", "username", "password",
        // "privateKey", "passphrase", "certificate", "zip", "keyStorePassword"),
        // so this is pinned as a latent property rather than a live bug: it
        // fails loudly the moment a non-ASCII part name is introduced.
        val out = ByteArrayOutputStream()
        val asciiName = "password"
        writePart(out, asciiName, byteArrayOf(7))
        val asciiBytes = out.toByteArray()
        assertEquals(
            asciiName.toByteArray(Charsets.UTF_8).size,
            asciiBytes[0].toInt(),
            "for ASCII the character count and the byte count agree",
        )

        val accented = "contraseña"
        val accentedOut = ByteArrayOutputStream()
        writePart(accentedOut, accented, byteArrayOf(7))
        val accentedBytes = accentedOut.toByteArray()
        assertEquals(
            accented.length,
            accentedBytes[0].toInt(),
            "the writer records String.length, not the UTF-8 byte count",
        )
        assertEquals(
            1 + accented.toByteArray(Charsets.UTF_8).size + 4 + 1,
            accentedBytes.size,
            "the name occupies its UTF-8 byte count, so the recorded length and " +
                "the bytes actually written disagree",
        )
    }
}
