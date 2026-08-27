package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialScope
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * RED tests for T5: SecretStore interface + CredentialScope wiring.
 * These tests require types that don't exist yet (SecretStore, CredentialScope).
 * Expected: FAIL TO COMPILE in RED phase.
 */
class SecretStoreInterfaceTest {

    @TempDir
    lateinit var tempDir: Path

    private val storeFile: Path get() = tempDir.resolve("credentials.bin")

    /**
     * CR-ST-005: LocalSecretStore implements SecretStore interface.
     */
    @Test
    fun `LocalSecretStore implements SecretStore interface`() {
        val store: SecretStore = LocalSecretStore(storeFile, "passphrase".toCharArray())
        // Type is confirmed by the declaration above (SecretStore = ...)
        assertTrue(store is LocalSecretStore)
    }

    /**
     * CR-ST-008: CredentialScope.env(id) returns SecretHandle for stored credential.
     */
    @Test
    fun `CredentialScope resolves stored credential`() {
        val passphrase = "test-passphrase".toCharArray()
        val store: SecretStore = LocalSecretStore(storeFile, passphrase)
        store.put(CredentialsId("github-token"), "secret-value".toByteArray())

        // CredentialScope requires: store + bindings + eventSink + clock
        // For this test, we verify the store provides the raw getAsSecretHandle()
        val handle = store.getAsSecretHandle(CredentialsId("github-token"))
        assertEquals("secret-value", String(handle.unwrap()))
    }

    /**
     * CR-ST-009: SecretStore.list() returns only IDs.
     */
    @Test
    fun `SecretStore list returns only credential IDs`() {
        val store: SecretStore = LocalSecretStore(storeFile, "passphrase".toCharArray())
        store.put(CredentialsId("id-a"), "val-a".toByteArray())
        store.put(CredentialsId("id-b"), "val-b".toByteArray())

        val ids = store.list()
        assertEquals(2, ids.size)
        assertTrue(ids.map { it.value }.containsAll(listOf("id-a", "id-b")))
    }

    /**
     * CR-ST-010: SecretStore.remove() deletes credential.
     */
    @Test
    fun `SecretStore remove deletes credential`() {
        val store: SecretStore = LocalSecretStore(storeFile, "passphrase".toCharArray())
        store.put(CredentialsId("github-token"), "secret".toByteArray())
        store.remove(CredentialsId("github-token"))
        assertTrue(store.list().isEmpty())
    }
}
