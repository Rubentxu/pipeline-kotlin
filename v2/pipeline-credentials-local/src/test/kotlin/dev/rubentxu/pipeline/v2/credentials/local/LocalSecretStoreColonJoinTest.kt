package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Path

/**
 * LocalSecretStore colon-join tests — CR-BD-022-A..D.
 *
 * ## Scenario Coverage
 *
 * | Scenario ID | Description | Test Method |
 * |------------|-------------|-------------|
 * | CR-BD-022-A | colon-join happy path: "value" part returns UTF-8 bytes "admin:secret123" | `colon_join_value_returns_joined_bytes` |
 * | CR-BD-022-B | username regression: "username" part byte-identical to baseline | `username_part_preserved` |
 * | CR-BD-022-C | password regression: "password" part byte-identical to baseline | `password_part_preserved` |
 * | CR-BD-022-D | AAD tamper detection preserved for all three partName values | `aad_tamper_detection_preserved` |
 */
@DisplayName("LocalSecretStore colon-join — CR-BD-022-A..D")
@Timeout(60)
class LocalSecretStoreColonJoinTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storeFile: Path

    @BeforeEach
    fun setUp() {
        storeFile = tempDir.resolve("credentials.bin")
    }

    // ─── CR-BD-022-A ───────────────────────────────────────────────────────────────

    @Test
    fun `CR-BD-022-A colon-join value returns joined UTF-8 bytes`() {
        // Given: a UsernameColonPassword credential stored with user="admin" pass="secret123"
        val passphrase = "colon-join-test".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        val id = CredentialsId("colon-creds")
        val original = UsernameColonPassword(id, CredentialScope.GLOBAL, "admin", "secret123".toByteArray())
        store.add(id, original)

        // When: we read the "value" part
        val valueHandle = store.getAsHandle(id, "value")

        // Then: returns UTF-8 bytes "admin:secret123"
        val joined = String(valueHandle.unwrap(), Charsets.UTF_8)
        assertEquals("admin:secret123", joined, "value part must return colon-joined username:password")
    }

    // ─── CR-BD-022-B ───────────────────────────────────────────────────────────────

    @Test
    fun `CR-BD-022-B username part byte-identical to baseline`() {
        // Given: a UsernameColonPassword credential
        val passphrase = "user-regression-test".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        val id = CredentialsId("user-regression-creds")
        val original = UsernameColonPassword(id, CredentialScope.GLOBAL, "admin", "secret123".toByteArray())
        store.add(id, original)

        // When: we read the "username" part
        val usernameHandle = store.getAsHandle(id, "username")

        // Then: returns exactly the username bytes
        assertTrue(usernameHandle.unwrap().contentEquals("admin".toByteArray()),
            "username part must return exact username bytes")
    }

    // ─── CR-BD-022-C ───────────────────────────────────────────────────────────────

    @Test
    fun `CR-BD-022-C password part byte-identical to baseline`() {
        // Given: a UsernameColonPassword credential
        val passphrase = "pass-regression-test".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        val id = CredentialsId("pass-regression-creds")
        val original = UsernameColonPassword(id, CredentialScope.GLOBAL, "admin", "secret123".toByteArray())
        store.add(id, original)

        // When: we read the "password" part
        val passwordHandle = store.getAsHandle(id, "password")

        // Then: returns exactly the password bytes
        assertTrue(passwordHandle.unwrap().contentEquals("secret123".toByteArray()),
            "password part must return exact password bytes")
    }

    // ─── CR-BD-022-D ───────────────────────────────────────────────────────────────

    @Test
    fun `CR-BD-022-D AAD tamper detection preserved for all partName values`() {
        // Given: a UsernameColonPassword credential
        val passphrase = "aad-tamper-test".toCharArray()
        val store = LocalSecretStore(storeFile, passphrase)
        val id = CredentialsId("aad-tamper-creds")
        val original = UsernameColonPassword(id, CredentialScope.GLOBAL, "admin", "secret123".toByteArray())
        store.add(id, original)

        // Tamper with one byte in the ciphertext region (past header, >100 bytes in)
        val fileBytes = java.nio.file.Files.readAllBytes(storeFile)
        val tamperedBytes = fileBytes.copyOf()
        if (tamperedBytes.size > 100) {
            tamperedBytes[100] = (tamperedBytes[100].toInt() xor 0x01).toByte()
        }
        java.nio.file.Files.write(storeFile, tamperedBytes)

        // Then: AAD tamper throws SecretStoreTamperException for "value" part
        val exValue = assertThrows(SecretStoreTamperException::class.java) {
            store.getAsHandle(id, "value")
        }
        assertNotNull(exValue, "AAD tamper must throw for value part")

        // Then: AAD tamper throws SecretStoreTamperException for "username" part
        val exUser = assertThrows(SecretStoreTamperException::class.java) {
            store.getAsHandle(id, "username")
        }
        assertNotNull(exUser, "AAD tamper must throw for username part")

        // Then: AAD tamper throws SecretStoreTamperException for "password" part
        val exPass = assertThrows(SecretStoreTamperException::class.java) {
            store.getAsHandle(id, "password")
        }
        assertNotNull(exPass, "AAD tamper must throw for password part")
    }
}
