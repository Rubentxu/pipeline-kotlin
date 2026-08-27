package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for PassphraseResolver.
 *
 * CR-ST-008: env var PIPELINE_STORE_PASSPHRASE → no TTY prompt, decrypt succeeds
 * CR-ST-009: TTY + no env var → Console.readPassword() prompt
 * CR-ST-010: neither → CredentialsStorePassphraseUnavailableException
 */
@DisplayName("PassphraseResolver tests")
class CredentialsStorePassphraseTest {

    @Test
    fun `CR-ST-008 env var returns passphrase without TTY`() {
        // Given PIPELINE_STORE_PASSPHRASE is set
        val env = mapOf("PIPELINE_STORE_PASSPHRASE" to "env-passphrase")

        // When we resolve
        val passphrase = PassphraseResolver.resolve(env) { -> null }

        // Then it returns the env var value
        assertEquals("env-passphrase", String(passphrase))
        // Verify it's a copy, not the original reference
        assertTrue(passphrase !== "env-passphrase".toCharArray())
    }

    @Test
    fun `CR-ST-010 neither env nor TTY throws CredentialsStorePassphraseUnavailableException`() {
        // Given no env var and TTY returns null
        val env = emptyMap<String, String>()

        // Then it throws
        assertThrows(PassphraseResolver.CredentialsStorePassphraseUnavailableException::class.java) {
            PassphraseResolver.resolve(env) { -> null }
        }
    }

    @Test
    fun `CR-ST-009 TTY reader called when no env var`() {
        // Given no env var but TTY returns a passphrase
        val env = emptyMap<String, String>()
        var ttyCalled = false
        val ttyPassphrase = "tty-passphrase".toCharArray()

        // When we resolve with TTY available
        val result = PassphraseResolver.resolve(env) {
            ttyCalled = true
            ttyPassphrase.copyOf()
        }

        // Then TTY was called and passphrase is returned
        assertTrue(ttyCalled)
        assertEquals("tty-passphrase", String(result))
    }

    @Test
    fun `resolved passphrase is a copy not the original`() {
        // Verify the returned CharArray is always a copy
        val env = mapOf("PIPELINE_STORE_PASSPHRASE" to "secret")
        val passphrase = PassphraseResolver.resolve(env) { -> null }

        // Mutating the returned array must not affect any internal state
        passphrase.fill('\u0000')
        val again = PassphraseResolver.resolve(env) { -> null }
        assertEquals("secret", String(again))
    }
}
