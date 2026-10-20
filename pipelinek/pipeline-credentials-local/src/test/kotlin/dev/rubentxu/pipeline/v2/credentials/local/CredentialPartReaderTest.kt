package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [CredentialPartReader] consumes the bytes [CredentialSerializer] writes. The two
 * objects are paired: a reader edit that misaligns with the writer is a silent defect
 * (a part gets read as the next part's name), and the tests below pin the contract.
 */
class CredentialPartReaderTest {

    @Test
    fun `readPartHeader advances past the name and stops at the value`() {
        val out = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(out, "value", "abc".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())
        val valueLen = CredentialPartReader.readPartHeader(buf)

        assertEquals(3, valueLen, "the header reports the value length")
        // The buffer is positioned at the first value byte.
        assertEquals('a'.code, buf.get().toInt() and 0xFF)
        assertEquals('b'.code, buf.get().toInt() and 0xFF)
        assertEquals('c'.code, buf.get().toInt() and 0xFF)
    }

    @Test
    fun `readPartValue returns the payload and leaves the buffer past it`() {
        val out = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(out, "value", "payload".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())
        val value = CredentialPartReader.readPartValue(buf)

        assertArrayEquals("payload".toByteArray(), value)
        assertEquals(0, buf.remaining(), "the part is consumed")
    }

    @Test
    fun `skipPart advances past a part and returns its payload length`() {
        val first = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(first, "first", "AAAA".toByteArray())
        val second = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(second, "second", "BBBB".toByteArray())

        val combined = first.toByteArray() + second.toByteArray()
        val buf = CredentialPartReader.bufferOver(combined)

        val firstLen = CredentialPartReader.skipPart(buf)
        assertEquals(4, firstLen, "the first part is 4 bytes of payload")

        // After skipping, the buffer is positioned at the start of the second part.
        assertEquals(6, buf.get().toInt() and 0xFF, "the second part's name is 'second' (6 chars)")
    }

    @Test
    fun `readInlinePart skips a fixed number of preceding parts and returns the next`() {
        val out = java.io.ByteArrayOutputStream()
        // Three parts in order; the third's value is the target.
        CredentialSerializer.writePart(out, "a", "111".toByteArray())
        CredentialSerializer.writePart(out, "b", "222".toByteArray())
        CredentialSerializer.writePart(out, "c", "333".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())
        val c = CredentialPartReader.readInlinePart(buf, skipBefore = 2)

        assertArrayEquals("333".toByteArray(), c)
    }

    @Test
    fun `readLinkedRefPart returns the referenced id on a linked-ref part`() {
        val out = java.io.ByteArrayOutputStream()
        CredentialSerializer.writeRefPart(
            out,
            "passphrase",
            dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef(CredentialsId.from("linked")),
        )

        val buf = CredentialPartReader.bufferOver(out.toByteArray())
        val refId = CredentialPartReader.readLinkedRefPart(buf, "passphrase")

        assertEquals(CredentialsId.from("linked"), refId)
    }

    @Test
    fun `readLinkedRefPart refuses a part that is neither linked-ref nor absent`() {
        // A normal part name + inline payload is not a linked-ref. The reader must
        // not coerce it into a ref; the diagnostic names the credential label.
        val out = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(out, "passphrase", "oops".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())

        assertThrows(
            CredentialPartReader.MalformedEnvelopeException::class.java,
        ) { CredentialPartReader.readLinkedRefPart(buf, "passphrase") }
    }

    @Test
    fun `readPositionalPart returns the named part from a fixed-order credential`() {
        val out = java.io.ByteArrayOutputStream()
        // Three parts, in the order the serializer uses for SecretText: nothing
        // (SecretText only has a value part). The test lays a synthetic envelope with
        // two parts to exercise the path.
        CredentialSerializer.writePart(out, "name1", "v1".toByteArray())
        CredentialSerializer.writePart(out, "name2", "v2".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())
        val v2 = CredentialPartReader.readPositionalPart(
            buf,
            partName = "name2",
            credential = "Synthetic",
            orderedParts = arrayOf("name1" to 0, "name2" to 1),
        )

        assertArrayEquals("v2".toByteArray(), v2)
    }

    @Test
    fun `readPositionalPart throws when the part is not in the fixed order`() {
        val out = java.io.ByteArrayOutputStream()
        CredentialSerializer.writePart(out, "name1", "v1".toByteArray())

        val buf = CredentialPartReader.bufferOver(out.toByteArray())

        assertThrows(
            CredentialPartReader.MalformedEnvelopeException::class.java,
        ) {
            CredentialPartReader.readPositionalPart(
                buf,
                partName = "missing",
                credential = "Synthetic",
                orderedParts = arrayOf("name1" to 0),
            )
        }
    }

    @Test
    fun `readZipPart finds a named entry and ignores the rest`() {
        // Build a Zip credential, serialize it, and read back one entry by name.
        val zip = Zip(
            CredentialsId.from("z"),
            CredentialScope.GLOBAL,
            entries = linkedMapOf(
                "first" to "alpha".toByteArray(),
                "second" to "beta".toByteArray(),
                "third" to "gamma".toByteArray(),
            ),
        )
        val bytes = CredentialSerializer.serializeCredential(zip)

        val buf = CredentialPartReader.bufferOver(bytes)
        val second = CredentialPartReader.readZipPart(buf, "second")

        assertArrayEquals("beta".toByteArray(), second)
    }

    @Test
    fun `readZipPart throws when the requested entry is not present`() {
        val zip = Zip(
            CredentialsId.from("z"),
            CredentialScope.GLOBAL,
            entries = linkedMapOf("only" to "x".toByteArray()),
        )
        val bytes = CredentialSerializer.serializeCredential(zip)

        val buf = CredentialPartReader.bufferOver(bytes)

        assertThrows(
            CredentialPartReader.MalformedEnvelopeException::class.java,
        ) { CredentialPartReader.readZipPart(buf, "missing") }
    }

    @Test
    fun `notFound names the missing part and the credential`() {
        // The diagnostic must be actionable: it says which part is missing and in
        // which credential, so the operator can fix the writer rather than the reader.
        val ex = CredentialPartReader.notFound("foo", "SecretText")

        assertEquals(
            "Part 'foo' not found in SecretText credential",
            ex.message,
        )
    }

    @Test
    fun `round-trip across the serializer and the reader for a SecretText`() {
        // readPartValue expects the buffer to be POSITIONED AT a part header, not at
        // the credential's part-count byte. SecretText serializes a 1-byte part count
        // first, then the "value" part; the test consumes the part count and then hands
        // the buffer to the reader.
        val id = CredentialsId.from("rt")
        val credential = SecretText(id, CredentialScope.GLOBAL, "round-trip".toByteArray())

        val bytes = CredentialSerializer.serializeCredential(credential)
        val buf = CredentialPartReader.bufferOver(bytes)
        val partCount = buf.get().toInt() // consume the 1-byte part count
        assertEquals(1, partCount, "SecretText has exactly one part")

        val value = CredentialPartReader.readPartValue(buf)
        assertArrayEquals("round-trip".toByteArray(), value)
    }

    @Test
    fun `partOffset returns the index of a named part in a fixed order`() {
        assertEquals(
            0,
            CredentialPartReader.partOffset("a", "a" to 0, "b" to 1),
            "the first part of the order is at offset 0",
        )
        assertEquals(
            1,
            CredentialPartReader.partOffset("b", "a" to 0, "b" to 1),
            "the second part of the order is at offset 1",
        )
        assertNull(
            CredentialPartReader.partOffset("missing", "a" to 0, "b" to 1),
            "a name not in the order returns null",
        )
    }
}
