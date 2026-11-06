package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStorePassphraseMismatchException
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The key derivation and the key wrapping of the local secret store.
 *
 * Every operation here is a function of its arguments only: no file handles,
 * no locks and no cached state. The passphrase is passed in explicitly rather
 * than read from an instance field, which is what makes this object safe to
 * extract from [LocalSecretStore] without changing the derived key material.
 *
 * The framing of the store file is NOT here. What an entry looks like, with
 * what AAD it is sealed and what its bytes mean once they are open are
 * properties of the VERSION of the format, and [StoreCodec] is where the
 * version lives.
 */
internal object CredentialCrypto {

    /** The Argon2id memory cost, in KiB. The floor the store derives at. */
    const val OWASP_M = 19456

    /** The Argon2id iteration count. */
    const val OWASP_T = 2

    /** The Argon2id parallelism. */
    const val OWASP_P = 1

    /** The size of the data key, in bytes. */
    const val DEK_SIZE = 32

    /** The size of the wrapped data key, in bytes (RFC 3394 output for a 32-byte DEK). */
    const val WRAPPED_DEK_SIZE = DEK_SIZE + 8

    /**
     * The key that wraps the data key, derived from the passphrase.
     *
     * @param passphrase the passphrase of the store
     * @param salt the salt the header carries
     * @return the key-encryption key
     */
    fun deriveKek(passphrase: CharArray, salt: ByteArray): ByteArray {
        // Argon2id = Argon2Parameters.ARGON2_id = 2 (BouncyCastle convention)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withMemoryAsKB(OWASP_M)
            .withIterations(OWASP_T)
            .withParallelism(OWASP_P)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val kek = ByteArray(DEK_SIZE)
        generator.generateBytes(passphrase, kek)
        return kek
    }

    /**
     * Wraps the data key under the key-encryption key.
     *
     * @param kek the key-encryption key
     * @param dek the data key
     * @return the wrapped data key
     */
    fun wrapDek(kek: ByteArray, dek: ByteArray): ByteArray {
        // (RFC 3394) - wrap returns the ciphertext directly
        val kwp = AESWrapEngine()
        kwp.init(true, KeyParameter(kek))
        return kwp.wrap(dek, 0, dek.size)
    }

    /**
     * Unwraps the data key under the key-encryption key.
     *
     * @param kek the key-encryption key
     * @param wrapped the wrapped data key
     * @return the data key
     * @throws SecretStorePassphraseMismatchException when the key does not
     *   open the wrapped data key
     */
    fun unwrapDek(kek: ByteArray, wrapped: ByteArray): ByteArray {
        val kwp = AESWrapEngine()
        kwp.init(false, KeyParameter(kek))
        return try {
            kwp.unwrap(wrapped, 0, wrapped.size)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw SecretStorePassphraseMismatchException()
        }
    }
}
