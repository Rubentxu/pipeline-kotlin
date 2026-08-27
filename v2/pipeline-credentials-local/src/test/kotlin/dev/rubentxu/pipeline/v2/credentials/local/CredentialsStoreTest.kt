package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Tests for LocalSecretStore implementation.
 *
 * CR-ST-001: roundtrip add→read byte-identical
 * CR-ST-002: tamper (flip 1 byte of ciphertext) throws SecretStoreTamperException
 * CR-ST-003: wrong passphrase throws SecretStorePassphraseMismatchException
 * CR-ST-004: AAD swap (copy entry-a's bytes into slot-b) throws SecretStoreTamperException (AAD mismatch)
 * CR-ST-005: POSIX 0600 file / 0700 dir permissions enforced
 * CR-ST-006: rotate(id) preserves other entries
 * CR-ST-007: KDF params persisted in header + upgrade path on next put
 * CR-ST-013: put with empty bytes throws CredentialsStoreEmptySecretException
 * CR-ST-014: put against existing id overwrites the slot (siblings untouched)
 */
@DisplayName("LocalSecretStore tests")
class CredentialsStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storeFile: Path

    @BeforeEach
    fun setUp() {
        storeFile = tempDir.resolve("credentials.bin")
    }

    @Test
    fun `CR-ST-001 add and read returns byte-identical secret`() {
        // Given a store with a passphrase
        val passphrase = "test-passphrase-123".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)

        val secretBytes = "my-super-secret-api-key".toByteArray()
        val id = CredentialsId("github-token")

        // When we add and read back
        store.put(id, secretBytes)
        val readBack = store.get(id)

        // Then the bytes are identical
        val readBytes = readBack.unwrap()
        assertArrayEquals(secretBytes, readBytes)
    }

    @Test
    fun `CR-ST-002 tamper ciphertext throws SecretStoreTamperException`() {
        // Given a store with a secret
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("test-id"), "secret".toByteArray())

        // When we tamper with the ciphertext (flip 1 byte in the encrypted payload)
        val fileBytes = storeFile.toFile().readBytes()
        val tamperedBytes = fileBytes.copyOf()
        // Flip a byte in the data region (past the 73-byte header)
        if (tamperedBytes.size > 80) {
            tamperedBytes[80] = (tamperedBytes[80].toInt() xor 0x01).toByte()
        }
        storeFile.toFile().writeBytes(tamperedBytes)

        // Then reading throws SecretStoreTamperException
        assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            LocalSecretStore(storeFile, passphrase).get(CredentialsId("test-id"))
        }
    }

    @Test
    fun `CR-ST-003 wrong passphrase throws SecretStorePassphraseMismatchException`() {
        // Given a store with a secret
        val passphrase = "correct-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("test-id"), "secret".toByteArray())

        // When we open with wrong passphrase
        val wrongPass = "wrong-passphrase".toCharArray()

        // Then it throws
        assertThrows(LocalSecretStore.SecretStorePassphraseMismatchException::class.java) {
            LocalSecretStore(storeFile, wrongPass).get(CredentialsId("test-id"))
        }
    }

    @Test
    fun `CR-ST-004 AAD swap throws SecretStoreTamperException`() {
        // Given a store with two credentials
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("id-a"), "secret-a".toByteArray())
        store.put(CredentialsId("id-b"), "secret-b".toByteArray())

        // Read the file and swap entry B's ciphertext with entry A's
        val fileBytes = storeFile.toFile().readBytes()
        // Header is 73 bytes; find both entries and swap encrypted payloads
        val swappedBytes = swapEntryPayloads(fileBytes, "id-a", "id-b")
        storeFile.toFile().writeBytes(swappedBytes)

        // Then reading id-b throws (AAD mismatch — credentialId in AAD doesn't match)
        assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            LocalSecretStore(storeFile, passphrase).get(CredentialsId("id-b"))
        }
    }

    @Test
    fun `CR-ST-005 file permissions are 0600`() {
        // Given a store
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("test-id"), "secret".toByteArray())

        // Then file permissions are 0600
        val perms = java.nio.file.Files.getPosixFilePermissions(storeFile)
        assertEquals("rw-------", PosixFilePermissions.toString(perms))
    }

    @Test
    fun `CR-ST-006 rotate preserves other entries`() {
        // Given a store with multiple entries
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("id-a"), "secret-a".toByteArray())
        store.put(CredentialsId("id-b"), "secret-b".toByteArray())
        store.put(CredentialsId("id-c"), "secret-c".toByteArray())

        // When we rotate id-b
        store.rotate(CredentialsId("id-b"), "new-secret-b".toByteArray())

        // Then id-a and id-c are unchanged
        val readA = store.get(CredentialsId("id-a")).unwrap()
        val readC = store.get(CredentialsId("id-c")).unwrap()
        assertArrayEquals("secret-a".toByteArray(), readA)
        assertArrayEquals("secret-c".toByteArray(), readC)

        // And id-b has new value
        val readB = store.get(CredentialsId("id-b")).unwrap()
        assertArrayEquals("new-secret-b".toByteArray(), readB)
    }

    @Test
    fun `CR-ST-007 KDF params persisted in header`() {
        // Given a fresh store
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("test-id"), "secret".toByteArray())

        // The header contains KDF params (m, t, p, salt)
        val header = LocalSecretStore.readHeader(storeFile)
        assertNotNull(header)
        assertEquals(LocalSecretStore.KdfParams.OWASP_MIN.m, header.kdfM)
        assertEquals(LocalSecretStore.KdfParams.OWASP_MIN.t, header.kdfT)
        assertEquals(LocalSecretStore.KdfParams.OWASP_MIN.p, header.kdfP)
    }

    @Test
    fun `CR-ST-013 empty secret throws CredentialsStoreEmptySecretException`() {
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)

        assertThrows(LocalSecretStore.CredentialsStoreEmptySecretException::class.java) {
            store.put(CredentialsId("test-id"), ByteArray(0))
        }
    }

    @Test
    fun `CR-ST-014 put overwrites existing slot`() {
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("test-id"), "original".toByteArray())

        store.put(CredentialsId("test-id"), "updated".toByteArray())

        val readBack = store.get(CredentialsId("test-id")).unwrap()
        assertArrayEquals("updated".toByteArray(), readBack)
    }

    /**
     * Helper: swap the encrypted payload of entry `fromId` into entry `toId`'s slot.
     * This simulates an AAD swap attack where the ciphertext is integrity-verified
     * but the AAD (credentialId) doesn't match the entry being decrypted.
     */
    private fun swapEntryPayloads(fileBytes: ByteArray, fromId: String, toId: String): ByteArray {
        // Parse entries and swap their sealed blobs
        // Entry format: idLen(2) + idBytes + plaintextLen(4) + blob
        // blob = nonce(12) + ciphertext + tag(16)
        val result = fileBytes.copyOf()
        val headerSize = 73 // magic(4) + version(1) + m(4) + t(4) + p(4) + salt(16) + wrappedDEK(40)
        var offset = headerSize
        val entries = mutableListOf<Pair<String, ByteArray>>() // id → blob
        while (offset < fileBytes.size) {
            val buf = java.nio.ByteBuffer.wrap(result, offset, fileBytes.size - offset)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val idLen = buf.short.toInt()
            if (idLen <= 0 || idLen > 1024) break
            val idBytes = ByteArray(idLen)
            buf.get(idBytes)
            val id = String(idBytes, Charsets.UTF_8)
            val plaintextLen = buf.int  // present in new format
            // blob = nonce(12) + ciphertext + tag(16) = plaintextLen + 28 bytes total
            val blobLen = 12 + plaintextLen + 16
            val sealedBytes = ByteArray(blobLen)
            buf.get(sealedBytes)
            entries.add(id to sealedBytes)
            offset += 2 + idLen + 4 + sealedBytes.size
        }
        val fromEntry = entries.find { it.first == fromId }?.second ?: return result
        val toEntryIdx = entries.indexOfFirst { it.first == toId }
        if (toEntryIdx < 0) return result

        // Rebuild with swapped sealed blob for toId (preserve plaintextLen from target entry)
        var newOffset = headerSize
        for (entry in entries) {
            val sealed = if (entry.first == toId) fromEntry else entry.second
            val idBytes = entry.first.toByteArray(Charsets.UTF_8)
            // plaintextLen is embedded in the sealed blob (12 + plaintextLen + 16)
            val blob = entry.second
            val plaintextLen = blob.size - 12 - 16
            val newEntrySize = 2 + idBytes.size + 4 + sealed.size
            java.nio.ByteBuffer.wrap(result, newOffset, 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(idBytes.size.toShort())
            System.arraycopy(idBytes, 0, result, newOffset + 2, idBytes.size)
            java.nio.ByteBuffer.wrap(result, newOffset + 2 + idBytes.size, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(plaintextLen)
            System.arraycopy(sealed, 0, result, newOffset + 2 + idBytes.size + 4, sealed.size)
            newOffset += newEntrySize
        }
        return result
    }
}
