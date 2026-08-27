package dev.rubentxu.pipeline.v2.credentials.local

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AEAD cipher using AES-256-GCM.
 *
 * ## Format
 *
 * sealed = nonce(12) || ciphertext || tag(16)
 * Total overhead = 12 + 16 = 28 bytes
 *
 * ## Design (ADR-0049 D2)
 *
 * Uses Java JCE AES/GCM/NoPadding (backed by bouncycastle bcprov).
 * - AES-256 key (KEY_SIZE_BYTES = 32)
 * - GCM standard nonce (NONCE_SIZE_BYTES = 12)
 * - GCM authentication tag (TAG_SIZE_BYTES = 16)
 */
object AeadCipher {
    private const val KEY_SIZE_BYTES = 32  // AES-256
    internal const val NONCE_SIZE_BYTES = 12  // GCM standard nonce (96 bits)
    internal const val TAG_SIZE_BYTES = 16   // GCM authentication tag (128 bits)

    private val secureRandom = SecureRandom()

    /**
     * Sealed blob containing the encrypted data.
     *
     * The raw ciphertext stored includes the nonce prepended:
     * raw = nonce(12) || ciphertext || tag(16)
     *
     * This compact format simplifies encoding/decoding — no separate nonce field needed.
     */
    data class SealedBlob(
        /** Ciphertext with nonce prepended: nonce(12) || ciphertext || tag(16) */
        val raw: ByteArray,
    ) {
        init {
            require(raw.size > NONCE_SIZE_BYTES + TAG_SIZE_BYTES) {
                "Raw sealed must be > ${NONCE_SIZE_BYTES + TAG_SIZE_BYTES} bytes"
            }
        }

        /**
         * Returns the total sealed blob as a single byte array.
         * Format: nonce(12) || ciphertext || tag(16)
         */
        fun toByteArray(): ByteArray = raw

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SealedBlob
            return raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int = raw.contentHashCode()
    }

    /**
     * Encrypts a plaintext using AES-256-GCM.
     *
     * @param key 32-byte AES key
     * @param plaintext The data to encrypt
     * @param aad Additional authenticated data (authenticated but not encrypted)
     * @return SealedBlob with nonce || ciphertext || tag
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): SealedBlob {
        require(key.size == KEY_SIZE_BYTES) { "Key must be 32 bytes" }

        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BYTES * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
        cipher.updateAAD(aad)
        // doFinal encrypts plaintext and appends the 16-byte GCM tag
        // Returns: ciphertext || tag
        val ciphertextPlusTag = cipher.doFinal(plaintext)

        // Output format: nonce(12) || ciphertext || tag(16)
        val raw = nonce + ciphertextPlusTag
        return SealedBlob(raw)
    }

    /**
     * Decrypts a sealed blob and verifies GCM authentication tag.
     *
     * @param key 32-byte AES key
     * @param blob The sealed blob (nonce || ciphertext || tag)
     * @param aad Additional authenticated data
     * @return The plaintext bytes
     * @throws javax.crypto.AEADBadTagException if tag verification fails
     */
    fun decrypt(key: ByteArray, blob: SealedBlob, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "Key must be 32 bytes" }

        // Extract nonce from first 12 bytes of raw
        val nonce = blob.raw.copyOfRange(0, NONCE_SIZE_BYTES)
        // Extract ciphertext || tag from after nonce
        val ciphertextPlusTag = blob.raw.copyOfRange(NONCE_SIZE_BYTES, blob.raw.size)

        val gcmSpec = GCMParameterSpec(TAG_SIZE_BYTES * 8, nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
        cipher.updateAAD(aad)
        // Input to doFinal: ciphertext || tag (same as what encrypt's doFinal returned)
        return cipher.doFinal(ciphertextPlusTag)
    }

    /**
     * Decrypts from a combined byte array (nonce || ciphertext || tag).
     */
    fun decrypt(key: ByteArray, combined: ByteArray, aad: ByteArray): ByteArray {
        require(combined.size > NONCE_SIZE_BYTES + TAG_SIZE_BYTES) {
            "Combined blob too short: ${combined.size} bytes"
        }
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BYTES * 8, combined)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec)
        cipher.updateAAD(aad)
        val ciphertextPlusTag = combined.copyOfRange(NONCE_SIZE_BYTES, combined.size)
        return cipher.doFinal(ciphertextPlusTag)
    }
}
