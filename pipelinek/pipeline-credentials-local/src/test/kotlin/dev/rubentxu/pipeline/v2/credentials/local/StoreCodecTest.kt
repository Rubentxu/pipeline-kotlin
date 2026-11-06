package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.StoreHeader
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [StoreCodec] is the authority over the shape of the store file.
 *
 * ## The guarantee
 * The store file has ONE authority: the codec. What an entry is, with what
 * AAD it is sealed, and what a body that does not fit its version means are
 * all decided there, and the numbers that go on the wire -- the kind of each
 * credential -- are decided there too.
 *
 * ## Why this test is a UNIT test
 * Every property here is a property of BYTES: the layout of an entry, the
 * layout of an AAD, the numbers on the wire and the answer the reader gives
 * to a body that does not fit. None of them needs a file, a lock or a
 * passphrase, so there is nothing here that a deeper layer could see and
 * that this layer cannot.
 *
 * The facts that only exist when a REAL store writes a REAL file and the
 * REAL reader opens it again -- that the header a store is published with and
 * the header its entries are sealed under are the same header -- are not
 * here: they are in the integration lane, where the store itself is the
 * subject.
 *
 * ## What is faked
 * Nothing. There is no clock, no subprocess, no network and no filesystem
 * here: the bytes are made in memory and the answers are read from them.
 */
class StoreCodecTest {

    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val id = CredentialsId.from("aws-prod")
    private val plaintext = "very secret payload".toByteArray()
    private val key = ByteArray(32) { (it xor 0x5A).toByte() }

    /** The header of a store of [version], with a salt this test owns. */
    private fun header(version: Short): StoreHeader = StoreHeader(
        magic = StoreCodec.magic(),
        version = version,
        kdfM = 19_456,
        kdfT = 2,
        kdfP = 1,
        kdfSalt = salt,
        wrappedDek = ByteArray(40),
    )

    /** The bytes of a store file of [version] whose body is [body]. */
    private fun fileBytes(version: Short, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        StoreCodec.writeHeader(out, header(version))
        out.write(body)
        return out.toByteArray()
    }

    /** The bytes of an entry of the current format, sealed under its own AAD. */
    private fun currentEntry(kindId: Short): ByteArray {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val sealed = AeadCipher.encrypt(key, plaintext, format.aad(header(StoreCodec.VERSION_CURRENT), id, kindId))
        return format.encode(StoreEntry(id, sealed, kindId))
    }

    // --- The AAD of each version ----------------------------------------------

    @Test
    fun `the legacy AAD carries the magic, the legacy version and the credential id`() {
        val aad = StoreCodec.of(StoreCodec.VERSION_LEGACY).aad(header(StoreCodec.VERSION_LEGACY), id, CredentialKinds.SECRET_TEXT)

        // The AAD is the source of the credential's binding: a tampered magic
        // or version must be detected at decrypt time. Pinning the bytes is
        // the cheapest way to assert the binding.
        assertArrayEquals(
            byteArrayOf(0x50, 0x4B, 0x43, 0x52), // "PKCR"
            aad.copyOfRange(0, 4),
            "magic must be the first four bytes, in the on-disk order",
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            aad.copyOfRange(4, 6),
            "the legacy version is little-endian, and it is the version the header carries",
        )
        assertArrayEquals(
            salt,
            aad.copyOfRange(18, 34),
            "the salt of the header is the salt the entry is sealed under",
        )
        val idBytes = id.value.toByteArray(Charsets.UTF_8)
        assertEquals(42, aad.size, "header prefix + salt + id")
        assertArrayEquals(
            idBytes,
            aad.copyOfRange(aad.size - idBytes.size, aad.size),
            "the id is the last segment of the AAD, in UTF-8",
        )
    }

    @Test
    fun `the legacy AAD carries no kind`() {
        val format = StoreCodec.of(StoreCodec.VERSION_LEGACY)
        val legacy = header(StoreCodec.VERSION_LEGACY)

        val withSecretText = format.aad(legacy, id, CredentialKinds.SECRET_TEXT)
        val withZip = format.aad(legacy, id, CredentialKinds.ZIP)

        assertArrayEquals(
            withSecretText,
            withZip,
            "a legacy entry holds one secret, and its seal says nothing about which kind that secret is",
        )
    }

    @Test
    fun `the current AAD carries the magic, the current version, the id, a colon and the kind`() {
        val aad = StoreCodec.of(StoreCodec.VERSION_CURRENT).aad(header(StoreCodec.VERSION_CURRENT), id, CredentialKinds.USERNAME_COLON_PASSWORD)

        assertArrayEquals(
            byteArrayOf(0x50, 0x4B, 0x43, 0x52), // "PKCR"
            aad.copyOfRange(0, 4),
            "magic must be the first four bytes, in the on-disk order",
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            aad.copyOfRange(4, 6),
            "the current version is little-endian, and it is the version the header carries",
        )
        assertArrayEquals(
            salt,
            aad.copyOfRange(18, 34),
            "the salt of the header is the salt the entry is sealed under",
        )
        val idBytes = id.value.toByteArray(Charsets.UTF_8)
        assertEquals(45, aad.size, "header prefix + salt + id + ':' + kind")
        assertArrayEquals(
            idBytes,
            aad.copyOfRange(34, 34 + idBytes.size),
            "the id follows the salt, in UTF-8",
        )
        assertArrayEquals(
            byteArrayOf(0x3A, 0x07, 0x00),
            aad.copyOfRange(42, 45),
            "the separator is a literal colon, and the kind is little-endian",
        )
    }

    @Test
    fun `the AAD differs when the credential id differs`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val current = header(StoreCodec.VERSION_CURRENT)

        val a = format.aad(current, CredentialsId.from("one"), CredentialKinds.SECRET_TEXT)
        val b = format.aad(current, CredentialsId.from("two"), CredentialKinds.SECRET_TEXT)

        assertNotEquals(
            a.toList(),
            b.toList(),
            "two different credential ids must produce two different AADs, otherwise the same " +
                "ciphertext would be valid under any id",
        )
    }

    @Test
    fun `the AAD differs when the KDF parameters differ`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val current = header(StoreCodec.VERSION_CURRENT)
        val weaker = current.copy(kdfM = 8_192, kdfT = 1, kdfP = 1)

        val a = format.aad(current, id, CredentialKinds.SECRET_TEXT)
        val b = format.aad(weaker, id, CredentialKinds.SECRET_TEXT)

        assertNotEquals(
            a.toList(),
            b.toList(),
            "the AAD must change with the KDF parameters, so a header downgrade is detectable",
        )
    }

    @Test
    fun `the AAD differs when the kind differs`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val current = header(StoreCodec.VERSION_CURRENT)

        val a = format.aad(current, id, CredentialKinds.SECRET_TEXT)
        val b = format.aad(current, id, CredentialKinds.ZIP)

        assertNotEquals(a.toList(), b.toList(), "different kind ids produce different AADs")
    }

    @Test
    fun `the AAD binding is enforced end-to-end, so the same ciphertext under a different id is rejected`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val current = header(StoreCodec.VERSION_CURRENT)
        val sealed = AeadCipher.encrypt(key, plaintext, format.aad(current, id, CredentialKinds.SECRET_TEXT))

        val wrongAad = format.aad(current, CredentialsId.from("another"), CredentialKinds.SECRET_TEXT)

        val ex = assertThrows<javax.crypto.AEADBadTagException> {
            AeadCipher.decrypt(key, sealed, wrongAad)
        }

        assertTrue(
            ex.message?.contains("Tag") == true || ex.message?.contains("tag") == true,
            "the failure must name the tag check",
        )
    }

    // --- The framing of an entry ----------------------------------------------

    @Test
    fun `the current entry frames the id, the kind and the sealed blob`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val sealed = AeadCipher.encrypt(key, plaintext, format.aad(header(StoreCodec.VERSION_CURRENT), id, CredentialKinds.ZIP))

        val encoded = format.encode(StoreEntry(id, sealed, CredentialKinds.ZIP))

        assertArrayEquals(
            byteArrayOf(0x08, 0x00),
            encoded.copyOfRange(0, 2),
            "the length of the id is little-endian, and it is 8 for 'aws-prod'",
        )
        assertArrayEquals(
            id.value.toByteArray(Charsets.UTF_8),
            encoded.copyOfRange(2, 10),
            "the id follows its own length",
        )
        assertArrayEquals(
            byteArrayOf(0x06, 0x00),
            encoded.copyOfRange(10, 12),
            "the kind is little-endian, right after the id",
        )
        assertArrayEquals(
            byteArrayOf(0x13, 0x00, 0x00, 0x00),
            encoded.copyOfRange(12, 16),
            "the length of the secret is little-endian, right after the kind",
        )
        assertEquals(
            16 + 12 + plaintext.size + 16,
            encoded.size,
            "the entry carries the nonce, the ciphertext and the tag of the seal",
        )
    }

    @Test
    fun `the legacy entry frames the id and the sealed blob, and carries no kind`() {
        val format = StoreCodec.of(StoreCodec.VERSION_LEGACY)
        val sealed = AeadCipher.encrypt(key, plaintext, format.aad(header(StoreCodec.VERSION_LEGACY), id, CredentialKinds.SECRET_TEXT))

        val encoded = format.encode(StoreEntry(id, sealed, CredentialKinds.SECRET_TEXT))

        assertArrayEquals(
            byteArrayOf(0x08, 0x00),
            encoded.copyOfRange(0, 2),
            "the length of the id is little-endian, and it is 8 for 'aws-prod'",
        )
        assertArrayEquals(
            id.value.toByteArray(Charsets.UTF_8),
            encoded.copyOfRange(2, 10),
            "the id follows its own length",
        )
        assertArrayEquals(
            byteArrayOf(0x13, 0x00, 0x00, 0x00),
            encoded.copyOfRange(10, 14),
            "the length of the secret is little-endian, and no kind stands between the id and it",
        )
        assertEquals(
            14 + 12 + plaintext.size + 16,
            encoded.size,
            "the entry carries the nonce, the ciphertext and the tag of the seal",
        )
    }

    @Test
    fun `the codec reads back the entry it wrote`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val body = currentEntry(CredentialKinds.ZIP)

        val read = format.read(fileBytes(StoreCodec.VERSION_CURRENT, body))

        assertEquals(setOf("aws-prod"), read.keys, "the id round-trips")
        val entry = read.getValue("aws-prod")
        assertEquals(CredentialKinds.ZIP, entry.kindId, "the kind id round-trips")
        assertArrayEquals(
            plaintext,
            AeadCipher.decrypt(key, entry.sealed, format.aad(header(StoreCodec.VERSION_CURRENT), id, CredentialKinds.ZIP)),
            "the ciphertext round-trips under the same AAD",
        )
    }

    @Test
    fun `the legacy format derives the kind of the entries it reads`() {
        val format = StoreCodec.of(StoreCodec.VERSION_LEGACY)
        val legacy = header(StoreCodec.VERSION_LEGACY)
        val sealed = AeadCipher.encrypt(key, plaintext, format.aad(legacy, id, CredentialKinds.SECRET_TEXT))
        val body = format.encode(StoreEntry(id, sealed, CredentialKinds.SECRET_TEXT))

        val read = format.read(fileBytes(StoreCodec.VERSION_LEGACY, body))

        assertEquals(
            CredentialKinds.SECRET_TEXT,
            read.getValue("aws-prod").kindId,
            "a legacy entry holds one secret, and the kind of that secret is derived, not read",
        )
    }

    // --- The refusal of a body that does not fit ------------------------------

    @Test
    fun `the reader refuses a body whose credential id length is not one a credential can have`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val body = currentEntry(CredentialKinds.ZIP).copyOf()
        // The length of the id is the first two bytes: make it one no
        // credential can have.
        body[0] = 0x00
        body[1] = 0x00

        val failure = assertThrows<SecretStoreTamperException> {
            format.read(fileBytes(StoreCodec.VERSION_CURRENT, body))
        }

        assertTrue(
            failure.message?.contains("not the body the version") == true,
            "the refusal must say that the body is not the body its version describes; it said: ${failure.message}",
        )
    }

    @Test
    fun `the reader refuses a body whose entry is cut short`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val entry = currentEntry(CredentialKinds.ZIP)
        val body = entry.copyOf(entry.size - 4)

        val failure = assertThrows<SecretStoreTamperException> {
            format.read(fileBytes(StoreCodec.VERSION_CURRENT, body))
        }

        assertTrue(
            failure.message?.contains("cut short") == true,
            "the refusal must say that the entry is cut short; it said: ${failure.message}",
        )
    }

    @Test
    fun `the reader refuses an entry whose kind names no credential`() {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val body = currentEntry(CredentialKinds.ZIP).copyOf()
        // The kind is the two bytes that follow the id: make it one that
        // names no credential.
        body[9] = 0x63.toByte()
        body[10] = 0x00

        val failure = assertThrows<SecretStoreTamperException> {
            format.read(fileBytes(StoreCodec.VERSION_CURRENT, body))
        }

        assertTrue(
            failure.message?.contains("names no kind of credential") == true,
            "the refusal must name the kind that names nothing; it said: ${failure.message}",
        )
    }

    // --- The versions ---------------------------------------------------------

    @Test
    fun `the codec refuses a version it does not know`() {
        val failure = assertThrows<SecretStoreTamperException> { StoreCodec.of(7) }

        assertTrue(
            failure.message?.contains("Unsupported store version") == true,
            "the refusal must name the version it does not know; it said: ${failure.message}",
        )
    }

    @Test
    fun `the header reader refuses a file whose magic is not the store's`() {
        val bytes = fileBytes(StoreCodec.VERSION_CURRENT, ByteArray(0))
        bytes[0] = 0x00

        val failure = assertThrows<SecretStoreTamperException> { StoreCodec.readHeader(bytes) }

        assertTrue(
            failure.message?.contains("Invalid store magic") == true,
            "the refusal must say that the magic is not the store's; it said: ${failure.message}",
        )
    }

    @Test
    fun `the header reader refuses a file whose version it does not know`() {
        val bytes = fileBytes(StoreCodec.VERSION_CURRENT, ByteArray(0))
        bytes[4] = 0x09

        val failure = assertThrows<SecretStoreTamperException> { StoreCodec.readHeader(bytes) }

        assertTrue(
            failure.message?.contains("Unsupported store version") == true,
            "the refusal must name the version it does not know; it said: ${failure.message}",
        )
    }

    @Test
    fun `the current format has parts by name, and the legacy one does not`() {
        assertNull(
            StoreCodec.of(StoreCodec.VERSION_CURRENT).partsRefusal(),
            "the current format holds a credential, and a credential has parts",
        )
        assertTrue(
            StoreCodec.of(StoreCodec.VERSION_LEGACY).partsRefusal()?.contains("no part by name") == true,
            "a legacy entry holds one secret, and it has no part by name",
        )
    }

    // --- The kinds ------------------------------------------------------------

    @Test
    fun `the kind ids are the ones the wire carries`() {
        assertEquals(1.toShort(), CredentialKinds.SECRET_TEXT, "the secret text is the first kind")
        assertEquals(2.toShort(), CredentialKinds.USERNAME_PASSWORD, "the username and password is the second kind")
        assertEquals(3.toShort(), CredentialKinds.SSH_PRIVATE_KEY, "the private key is the third kind")
        assertEquals(4.toShort(), CredentialKinds.SECRET_FILE, "the file is the fourth kind")
        assertEquals(5.toShort(), CredentialKinds.CERTIFICATE, "the certificate is the fifth kind")
        assertEquals(6.toShort(), CredentialKinds.ZIP, "the archive is the sixth kind")
        assertEquals(7.toShort(), CredentialKinds.USERNAME_COLON_PASSWORD, "the joined username and password is the seventh kind")
    }

    @Test
    fun `the name of a kind is the name of the credential, and an unknown kind has none`() {
        assertEquals("SecretText", CredentialKinds.nameOf(CredentialKinds.SECRET_TEXT))
        assertEquals("UsernamePassword", CredentialKinds.nameOf(CredentialKinds.USERNAME_PASSWORD))
        assertEquals("SshPrivateKey", CredentialKinds.nameOf(CredentialKinds.SSH_PRIVATE_KEY))
        assertEquals("SecretFile", CredentialKinds.nameOf(CredentialKinds.SECRET_FILE))
        assertEquals("Certificate", CredentialKinds.nameOf(CredentialKinds.CERTIFICATE))
        assertEquals("Zip", CredentialKinds.nameOf(CredentialKinds.ZIP))
        assertEquals("UsernameColonPassword", CredentialKinds.nameOf(CredentialKinds.USERNAME_COLON_PASSWORD))
        assertNull(CredentialKinds.nameOf(99), "a kind that names no credential has no name")
    }

    @Test
    fun `the kind of a credential is the kind it is written with`() {
        assertEquals(
            CredentialKinds.SECRET_TEXT,
            CredentialKinds.idOf(
                dev.rubentxu.pipeline.v2.domain.credentials.SecretText(
                    id,
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL,
                    plaintext,
                ),
            ),
        )
        assertEquals(
            CredentialKinds.ZIP,
            CredentialKinds.idOf(
                dev.rubentxu.pipeline.v2.domain.credentials.Zip(
                    id,
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL,
                    mapOf("a.txt" to plaintext),
                ),
            ),
        )
    }
}
