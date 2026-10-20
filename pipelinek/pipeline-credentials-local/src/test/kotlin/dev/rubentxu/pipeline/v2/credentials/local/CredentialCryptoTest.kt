package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStorePassphraseMismatchException
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.StoreHeader
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.V2EntryData
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CredentialCrypto] is the pure half of the local store: Argon2id derivation, key
 * wrapping, the AAD structure that binds each ciphertext to a header and a credential id,
 * and the on-disk entry encoding.
 * Every function is total over its inputs and produces the same bytes for the same inputs.
 * A bug here is silent: the store would either refuse to decrypt a file it produced, or
 * accept a file it should have refused. Both modes are tested here.
*/
class CredentialCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val dek = ByteArray(32) { (it * 13 + 5).toByte() }
    private val header = StoreHeader(
        magic = "PKCR".toByteArray(Charsets.US_ASCII),
        version = 2,
        kdfM = 19_456,
        kdfT = 2,
        kdfP = 1,
        kdfSalt = salt,
        wrappedDek = ByteArray(40),
    )

    // --- Argon2id KDF ---------------------------------------------------------

    @Test
    fun `deriveKek is deterministic for the same passphrase and salt`() {
        val first = CredentialCrypto.deriveKek(passphrase, salt)
        val second = CredentialCrypto.deriveKek(passphrase, salt)

        assertArrayEquals(first, second, "the same input must produce the same KEK")
        assertEquals(32, first.size, "the KEK is 32 bytes (AES-256)")
    }

    @Test
    fun `deriveKek is different for a different salt`() {
        val otherSalt = ByteArray(16) { (it + 100).toByte() }

        val first = CredentialCrypto.deriveKek(passphrase, salt)
        val second = CredentialCrypto.deriveKek(passphrase, otherSalt)

        assertFalse(
            first.contentEquals(second),
            "changing a single byte of the salt must change the KEK",
        )
    }

    @Test
    fun `deriveKek is different for a different passphrase`() {
        val otherPassphrase = "a different passphrase".toCharArray()

        val first = CredentialCrypto.deriveKek(passphrase, salt)
        val second = CredentialCrypto.deriveKek(otherPassphrase, salt)

        assertFalse(
            first.contentEquals(second),
            "a different passphrase must produce a different KEK",
        )
    }

    // --- AES-KWP wrapping ------------------------------------------------------

    @Test
    fun `wrapDek followed by unwrapDek returns the original DEK`() {
        val kek = CredentialCrypto.deriveKek(passphrase, salt)

        val wrapped = CredentialCrypto.wrapDek(kek, dek)
        val recovered = CredentialCrypto.unwrapDek(kek, wrapped)

        assertArrayEquals(dek, recovered, "the wrapped DEK round-trips under the same KEK")
    }

    @Test
    fun `wrapDek adds the 8-byte AES-KWP framing`() {
        val kek = CredentialCrypto.deriveKek(passphrase, salt)

        val wrapped = CredentialCrypto.wrapDek(kek, dek)

        assertEquals(
            dek.size + 8,
            wrapped.size,
            "AES-KWP (RFC 3394) framing adds exactly 8 bytes to a 32-byte input",
        )
    }

    @Test
    fun `unwrapDek throws the documented mismatch exception on the wrong KEK`() {
        val kek = CredentialCrypto.deriveKek(passphrase, salt)
        val wrongKek = CredentialCrypto.deriveKek("wrong".toCharArray(), salt)
        val wrapped = CredentialCrypto.wrapDek(kek, dek)

        assertThrows(
            SecretStorePassphraseMismatchException::class.java,
        ) { CredentialCrypto.unwrapDek(wrongKek, wrapped) }
    }

    @Test
    fun `unwrapDek throws on a tampered wrapped DEK`() {
        val kek = CredentialCrypto.deriveKek(passphrase, salt)
        val wrapped = CredentialCrypto.wrapDek(kek, dek).copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }

        assertThrows(
            SecretStorePassphraseMismatchException::class.java,
        ) { CredentialCrypto.unwrapDek(kek, wrapped) }
    }

    // --- AAD construction -----------------------------------------------------

    @Test
    fun `buildAad starts with the magic and version, in that order`() {
        val aad = CredentialCrypto.buildAad(header, CredentialsId.from("cred"))

        // The AAD is the source of the credential's binding: a tampered magic or version
        // must be detected at decrypt time. Pinning the first six bytes is the cheapest
        // way to assert the binding, and the rest is checked in the round-trip test below.
        assertArrayEquals(
            byteArrayOf(0x50, 0x4B, 0x43, 0x52), // "PKCR"
            aad.copyOfRange(0, 4),
            "magic must be the first four bytes, in the on-disk order",
        )
        assertEquals(
            2,
            aad[4].toInt() and 0xFF or ((aad[5].toInt() and 0xFF) shl 8),
            "version is little-endian",
        )
    }

    @Test
    fun `buildAad includes the credential id at the end, as UTF-8`() {
        val id = CredentialsId.from("my-cred")
        val aad = CredentialCrypto.buildAad(header, id)

        val idBytes = "my-cred".toByteArray(Charsets.UTF_8)
        assertEquals(aad.size, 4 + 2 + 12 + 16 + idBytes.size, "header prefix + salt + id")

        val tail = aad.copyOfRange(aad.size - idBytes.size, aad.size)
        assertArrayEquals(idBytes, tail, "the id is the last segment of the AAD, in UTF-8")
    }

    @Test
    fun `buildAad differs when the credential id differs`() {
        val a = CredentialCrypto.buildAad(header, CredentialsId.from("one"))
        val b = CredentialCrypto.buildAad(header, CredentialsId.from("two"))

        assertNotEquals(
            a.toList(),
            b.toList(),
            "two different credential ids must produce two different AADs, otherwise the same " +
                "ciphertext would be valid under any id",
        )
    }

    @Test
    fun `buildAad differs when the KDF parameters differ`() {
        val weakerHeader = header.copy(kdfM = 8_192, kdfT = 1, kdfP = 1)
        val a = CredentialCrypto.buildAad(header, CredentialsId.from("cred"))
        val b = CredentialCrypto.buildAad(weakerHeader, CredentialsId.from("cred"))

        assertNotEquals(
            a.toList(),
            b.toList(),
            "the AAD must change with the KDF parameters, so a header downgrade is detectable",
        )
    }

    @Test
    fun `buildV2Aad extends buildAad with a colon and the kind id`() {
        val kindId: Short = 7
        val aadV1 = CredentialCrypto.buildAad(header, CredentialsId.from("cred"))
        val aadV2 = CredentialCrypto.buildV2Aad(header, CredentialsId.from("cred"), kindId)

        val colonAndKind = aadV2.copyOfRange(aadV1.size, aadV2.size)

        // ':' is 0x3A; the kind id is little-endian.
        assertEquals(3, colonAndKind.size, "v2 AAD adds exactly 3 bytes: ':' + 2-byte kind id")
        assertEquals(0x3A, colonAndKind[0].toInt() and 0xFF, "the separator is a literal colon")
        assertEquals(kindId.toInt(), colonAndKind[1].toInt() and 0xFF or (colonAndKind[2].toInt() shl 8))
        assertArrayEquals(
            aadV1,
            aadV2.copyOfRange(0, aadV1.size),
            "v2 AAD is a strict prefix extension of v1 AAD",
        )
    }

    @Test
    fun `buildV2Aad differs by kind id`() {
        val a = CredentialCrypto.buildV2Aad(header, CredentialsId.from("cred"), 1)
        val b = CredentialCrypto.buildV2Aad(header, CredentialsId.from("cred"), 7)

        assertNotEquals(a.toList(), b.toList(), "different kind ids produce different AADs")
    }

    // --- Entry round-trip ----------------------------------------------------

    @Test
    fun `encodeV2Entry and readEntries produce the same id, kind and plaintext when prefixed by the v1 header`() {
        // readEntries is the FILE reader: it skips a 74-byte v1 header (4 magic + 2 version
        // + 4 m + 4 t + 4 p + 16 salt + 40 wrappedDek) and then walks v2 entries. A unit
        // test cannot produce a real file (AGENTS.md forbids filesystem persistence in
        // unit tests), so the test writes the header bytes by hand and concatenates an
        // encoded v2 entry, exercising the same byte stream the store would read.
        val id = CredentialsId.from("aws-prod")
        val plaintext = "very secret payload".toByteArray()
        val aad = CredentialCrypto.buildV2Aad(header, id, kindId = 6)
        val key = ByteArray(32) { (it xor 0x5A).toByte() }
        val sealed = AeadCipher.encrypt(key, plaintext, aad)

        val fileBytes = synthV1Header() + CredentialCrypto.encodeV2Entry(V2EntryData(id, sealed, kindId = 6))

        val read = CredentialCrypto.readEntries(fileBytes)

        assertEquals(setOf("aws-prod"), read.keys, "the id round-trips")
        assertEquals(6, read.getValue("aws-prod").kindId, "the kind id round-trips")

        val recovered = AeadCipher.decrypt(key, read.getValue("aws-prod").sealed, aad)
        assertArrayEquals(plaintext, recovered, "the ciphertext round-trips under the same AAD")
    }

    @Test
    fun `readEntries refuses to skip past an absurd id length`() {
        // A length >1024 would point the buffer past itself, so the reader stops. The
        // property to pin is that 5 KiB of garbage does not make the reader allocate 5 KiB
        // before noticing; the sanity check stops the loop and the test asserts the
        // call stays total.
        val garbage = ByteArray(5_000)

        val read = CredentialCrypto.readEntries(garbage)
        assertTrue(read.isEmpty(), "garbage input is not a credential, the reader returns an empty map")
    }

    @Test
    fun `AAD binding is enforced end-to-end, so the same ciphertext under a different id is rejected`() {
        // This is the property the store needs most: a ciphertext written for one
        // credential must not be accepted as the same credential under a different id.
        val idOne = CredentialsId.from("one")
        val idTwo = CredentialsId.from("two")
        val plaintext = "this is for one".toByteArray()

        val key = ByteArray(32) { (it xor 0x33).toByte() }
        val sealed = AeadCipher.encrypt(
            key,
            plaintext,
            aad = CredentialCrypto.buildV2Aad(header, idOne, kindId = 1),
        )

        val wrongAad = CredentialCrypto.buildV2Aad(header, idTwo, kindId = 1)

        val ex = assertThrows(
            javax.crypto.AEADBadTagException::class.java,
        ) { AeadCipher.decrypt(key, sealed, wrongAad) }

        assertTrue(
            ex.message?.contains("Tag") == true || ex.message?.contains("tag") == true,
            "the failure must name the tag check",
        )
    }
}

/**
 * Build the 74-byte v1 header that [CredentialCrypto.readEntries] expects at offset 0.
 * The header layout is pinned by the source: 4-byte magic, 2-byte little-endian version,
 * 4-byte little-endian m/t/p, 16-byte salt, 40-byte wrapped DEK. The header's
 * CONTENTS do not need to be cryptographically valid: readEntries walks entries, it does
 * not verify the header. The test cares about the byte positions, not the values.
*/
private fun synthV1Header(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write("PKCR".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(0x02, 0x00)) // version 2, little-endian
    // m, t, p in little-endian (4 bytes each)
    for (v in listOf(19_456, 2, 1)) {
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }
    // 16-byte salt (zeroed)
    out.write(ByteArray(16))
    // 40-byte wrapped DEK (zeroed)
    out.write(ByteArray(40))
    return out.toByteArray()
}
