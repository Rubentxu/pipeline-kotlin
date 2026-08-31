package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Golden test for LocalCredentialProvider — verifies byte-for-byte equivalence
 * with LocalSecretStore.getAsSecretHandle.
 *
 * Design (design §6.1 R-3):
 * - Verifies LocalCredentialProvider.resolve returns byte-identical result
 *   to LocalSecretStore.getAsSecretHandle
 * - Uses real POSIX filesystem for LocalSecretStore setup
 */
@DisplayName("LocalCredentialProvider golden test")
@Timeout(120)
class LocalCredentialProviderGoldenTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storeFile: Path
    private lateinit var store: LocalSecretStore
    private lateinit var provider: LocalCredentialProvider

    @BeforeEach
    fun setUp() {
        storeFile = tempDir.resolve("credentials.bin")
        val passphrase = "test-passphrase".toCharArray()
        store = LocalSecretStore(storeFile, passphrase)
        provider = LocalCredentialProvider(store)
    }

    @Test
    fun `resolve returns byte-identical SecretHandle to LocalSecretStore`() {
        // Given a stored credential
        val secretBytes = "test-secret-value".toByteArray()
        val id = CredentialsId("test-credential")
        store.put(id, secretBytes)

        // When we resolve via provider and directly via store
        val viaProvider = provider.resolve(id)
        val viaStore = store.getAsSecretHandle(id)

        // Then the bytes are byte-identical
        assertArrayEquals(viaStore.unwrap(), viaProvider.unwrap()) {
            "SecretHandle bytes must be byte-identical"
        }
        assertEquals(viaStore.sizeBytes, viaProvider.sizeBytes) {
            "SecretHandle size must match"
        }
    }

    @Test
    fun `resolve returns correct providerId`() {
        assertEquals("local", provider.providerId) {
            "providerId must be 'local'"
        }
    }

    @Test
    fun `close closes the underlying store`() {
        // Given a stored credential
        val id = CredentialsId("test-credential")
        store.put(id, "secret".toByteArray())

        // When we close the provider
        provider.close()

        // Then the store is closed and subsequent access throws
        assertThrows(Exception::class.java) {
            store.getAsSecretHandle(id)
        }
    }
}
