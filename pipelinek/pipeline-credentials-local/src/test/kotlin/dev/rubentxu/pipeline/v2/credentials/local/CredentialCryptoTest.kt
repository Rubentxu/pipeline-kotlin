package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStorePassphraseMismatchException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [CredentialCrypto] is the pure half of the local store: Argon2id derivation
 * and key wrapping.
 *
 * Every function here is total over its inputs and produces the same bytes for
 * the same inputs. A bug here is silent: the store would either refuse to
 * decrypt a file it produced, or accept a file it should have refused.
 *
 * The shape of the store FILE -- what an entry is, with what AAD it is sealed
 * -- is not here: it is a property of the version of the format, and
 * [StoreCodecTest] is where that is pinned.
 */
class CredentialCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val dek = ByteArray(32) { (it * 13 + 5).toByte() }

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
}
