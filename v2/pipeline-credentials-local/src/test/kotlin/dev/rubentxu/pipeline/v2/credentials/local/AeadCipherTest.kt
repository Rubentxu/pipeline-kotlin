package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

/**
 * [AeadCipher] is the single layer between credential plaintexts and the bytes that hit the
 * disk. Three properties keep it sound: a ciphertext plus a non-matching AAD must fail, a
 * tampered ciphertext must fail, and a fresh encryption must not produce the same bytes twice.
 * Each test names the property it pins, and the encryption is over a single 32-byte key
 * generated inside the test. Nothing in the file touches the filesystem or the network.
*/
class AeadCipherTest {

    private val key: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `encrypt then decrypt returns the original plaintext`() {
        val plaintext = "the quick brown fox".toByteArray()
        val aad = "header-and-id".toByteArray()

        val sealed = AeadCipher.encrypt(key, plaintext, aad)
        val recovered = AeadCipher.decrypt(key, sealed, aad)

        assertArrayEquals(plaintext, recovered, "round-trip must be lossless")
    }

    @Test
    fun `every encryption under a fresh key uses a fresh nonce`() {
        // A repeated nonce under the same key is the textbook AEAD failure mode: it leaks
        // XOR of the plaintexts and lets an attacker forge authenticators. The test would
        // need ~2^48 trials to actually collide, so this is an honesty contract: if it
        // breaks, the failure is loud; if it does not, the implementation is at least
        // not reusing a nonce within a single call.
        val plaintext = "repeat".toByteArray()
        val aad = "aad".toByteArray()

        val first = AeadCipher.encrypt(key, plaintext, aad).raw
        val second = AeadCipher.encrypt(key, plaintext, aad).raw

        assertFalse(
            first.contentEquals(second),
            "two encryptions of the same plaintext under the same key must produce different bytes",
        )
    }

    @Test
    fun `a different AAD is rejected by the authentication tag`() {
        val plaintext = "the quick brown fox".toByteArray()
        val sealed = AeadCipher.encrypt(key, plaintext, aad = "header-A".toByteArray())

        val ex = assertThrows(
            AEADBadTagException::class.java,
        ) { AeadCipher.decrypt(key, sealed, aad = "header-B".toByteArray()) }

        assertTrue(
            ex.message?.contains("Tag") == true || ex.message?.contains("tag") == true,
            "the failure must name the tag check, not something else",
        )
    }

    @Test
    fun `a flipped bit in the ciphertext is rejected by the authentication tag`() {
        val plaintext = "the quick brown fox".toByteArray()
        val sealed = AeadCipher.encrypt(key, plaintext, aad = "aad".toByteArray())

        // Flip one bit somewhere in the ciphertext region (skip the nonce prefix).
        val tampered = sealed.raw.copyOf()
        tampered[AeadCipher.NONCE_SIZE_BYTES + 1] = (tampered[AeadCipher.NONCE_SIZE_BYTES + 1].toInt() xor 0x01).toByte()

        val rewrapped = AeadCipher.SealedBlob(tampered)

        assertThrows(
            AEADBadTagException::class.java,
        ) { AeadCipher.decrypt(key, rewrapped, aad = "aad".toByteArray()) }
    }

    @Test
    fun `a flipped bit in the nonce is rejected by the authentication tag`() {
        val plaintext = "the quick brown fox".toByteArray()
        val sealed = AeadCipher.encrypt(key, plaintext, aad = "aad".toByteArray())

        val tampered = sealed.raw.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        val rewrapped = AeadCipher.SealedBlob(tampered)

        assertThrows(
            AEADBadTagException::class.java,
        ) { AeadCipher.decrypt(key, rewrapped, aad = "aad".toByteArray()) }
    }

    @Test
    fun `a sealed blob decodes via the combined-byte-array overload too`() {
        val plaintext = "second path".toByteArray()
        val sealed = AeadCipher.encrypt(key, plaintext, aad = "aad".toByteArray())

        // The combined overload takes the raw bytes directly, skipping the SealedBlob
        // constructor. It is the public path the entry format uses.
        val recovered = AeadCipher.decrypt(key, sealed.raw, aad = "aad".toByteArray())

        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `a key that is not 32 bytes is rejected`() {
        val tooShort = ByteArray(31)
        val plaintext = "data".toByteArray()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            AeadCipher.encrypt(tooShort, plaintext, ByteArray(0))
        }
        assertTrue(ex.message?.contains("32") == true, "the message must name the expected size")
    }

    @Test
    fun `a SealedBlob shorter than nonce plus tag is rejected at construction`() {
        val tooShort = ByteArray(AeadCipher.NONCE_SIZE_BYTES + AeadCipher.TAG_SIZE_BYTES)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            AeadCipher.SealedBlob(tooShort)
        }

        assertTrue(
            ex.message?.contains("28") == true,
            "the message must name the minimum size (12 + 16 = 28 bytes)",
        )
    }

    @Test
    fun `a SealedBlob exactly one byte too short is rejected at construction`() {
        val minimum = AeadCipher.NONCE_SIZE_BYTES + AeadCipher.TAG_SIZE_BYTES
        val oneByteShort = ByteArray(minimum - 1)

        assertThrows(IllegalArgumentException::class.java) { AeadCipher.SealedBlob(oneByteShort) }
    }

    @Test
    fun `the SealedBlob is exactly nonce plus ciphertext plus tag, not more`() {
        val plaintext = ByteArray(64) { (it % 251).toByte() }
        val sealed = AeadCipher.encrypt(key, plaintext, aad = ByteArray(0))

        assertEquals(
            AeadCipher.NONCE_SIZE_BYTES + plaintext.size + AeadCipher.TAG_SIZE_BYTES,
            sealed.raw.size,
            "the format is nonce(12) || ciphertext(N) || tag(16); no padding, no extra framing",
        )
    }

    @Test
    fun `encrypting an empty plaintext is rejected at the SealedBlob boundary`() {
        // The SealedBlob init requires raw.size > NONCE + TAG, which is impossible for an
        // empty plaintext: the JCE produces nonce || tag (28 bytes) and no ciphertext, so
        // the result is exactly the boundary value and the init guard refuses it. The
        // boundary is the smallest possible AAD-binding witness, and a successful encrypt
        // here would mean the init guard is the wrong side of the bound.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            AeadCipher.encrypt(key, plaintext = ByteArray(0), aad = ByteArray(0))
        }

        assertTrue(ex.message?.contains("28") == true, "the message must name the minimum size, got: ${ex.message}")
    }

    @Test
    fun `two SealedBlobs with the same bytes are equal and hash equally`() {
        val bytes = ByteArray(60) { (it + 1).toByte() }
        val a = AeadCipher.SealedBlob(bytes)
        val b = AeadCipher.SealedBlob(bytes.copyOf())

        assertEquals(a, b, "the equality is structural, not reference-based")
        assertEquals(a.hashCode(), b.hashCode(), "the hash follows the structural equality")
    }

    @Test
    fun `two SealedBlobs differing in one byte are not equal`() {
        val a = AeadCipher.SealedBlob(ByteArray(60) { (it + 1).toByte() })
        val b = AeadCipher.SealedBlob(ByteArray(60) { (it + 2).toByte() })

        assertNotEquals(a, b, "one byte is enough")
    }

    @Test
    fun `toByteArray is the raw bytes, not a copy with extra framing`() {
        val sealed = AeadCipher.SealedBlob(ByteArray(60) { it.toByte() })

        assertArrayEquals(
            sealed.raw,
            sealed.toByteArray(),
            "toByteArray() is the canonical byte form, used by the entry encoder",
        )
    }
}
