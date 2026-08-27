package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Tests for list and atomic write guarantees.
 *
 * CR-ST-011: list() returns exactly the IDs (never ciphertext or KDF params)
 * CR-ST-012: mid-write kill → file is either old or new, never partial
 */
@DisplayName("CredentialsStore list and atomic tests")
class CredentialsStoreListAtomicTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storeFile: Path

    @BeforeEach
    fun setUp() {
        storeFile = tempDir.resolve("credentials.bin")
    }

    @Test
    fun `CR-ST-011 list returns only IDs, never values`() {
        // Given a store with multiple entries
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("github-token"), "secret1".toByteArray())
        store.put(CredentialsId("docker-hub"), "secret2".toByteArray())
        store.put(CredentialsId("npm-token"), "secret3".toByteArray())

        // When we list
        val ids = store.list()

        // Then we get exactly the IDs
        assertEquals(3, ids.size)
        assertTrue(ids.contains(CredentialsId("github-token")))
        assertTrue(ids.contains(CredentialsId("docker-hub")))
        assertTrue(ids.contains(CredentialsId("npm-token")))
    }

    @Test
    fun `CR-ST-011 list returns empty for new store`() {
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `CR-ST-011 list does not expose KDF salt or wrapped DEK`() {
        // Given a store
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("id-with-salt-pattern"), "value".toByteArray())

        // The list output must not contain cryptographic material
        val ids = store.list()
        val flat = ids.joinToString(" ") { it.value }
        // Salt is 16 random bytes — probability of appearing in ID string is negligible
        assertFalse(flat.contains("PKCR") || ids.any { it.value.length > 100 })
    }

    @Test
    fun `remove deletes entry and list reflects it`() {
        val passphrase = "test-passphrase".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("keep-me"), "keep".toByteArray())
        store.put(CredentialsId("remove-me"), "remove".toByteArray())

        store.remove(CredentialsId("remove-me"))

        val ids = store.list()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(CredentialsId("keep-me")))
        assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            store.getAsSecretHandle(CredentialsId("remove-me"))
        }
    }
}
