package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GIT-CHK-032: GitCheckoutFailed.reason scrubbing test.
 *
 * When stderr contains registered canary values (e.g., __canary__, __git_canary__),
 * the GitCheckoutFailed.reason must NOT contain them.
 *
 * INV-L6-CR-013: GitCheckoutFailed.reason is scrubbed via SecretPatternRegistry.scrub()
 * before the event is emitted.
 *
 * @see <a href="ADR-0051">ADR-0051 — ML-R6 credentials parity</a>
 */
class ReasonScrubTest {

    /**
     * GIT-CHK-032: Reason is scrubbed when stderr contains canary.
     *
     * When a git command fails and stderr contains a registered secret pattern,
     * the GitCheckoutFailed.reason field must NOT contain the secret.
     *
     * This tests the SecretPatternRegistry.scrub() method directly since
     * the actual GitCheckoutExecutor path is tested in integration tests.
     */
    @Test
    fun `GIT_CHK_032_scrub_reason_with_canary`() {
        val registry = SecretPatternRegistry()

        // Register the ML-R4 canary
        registry.addSecret(SecretHandle.plain("GHS6_CANARY_7f3a9c2e1b4d5e6f"))

        // Register the ML-R5 git canary
        registry.addSecret(SecretHandle.plain("GIT_CANARY_b8c9d7e6f5a4b3c2d1e0"))

        // Register the ML-R6 SSH canary
        registry.addSecret(SecretHandle.plain("SSH_CANARY_9a8b7c6d5e4f3a2b"))

        // Simulate a stderr that contains the canary
        val stderrWithCanary = """
            fatal: Authentication failed for 'https://GHS6_CANARY_7f3a9c2e1b4d5e6f@github.com/owner/repo.git'
            remote: Invalid credentials
        """.trimIndent()

        val scrubbed = registry.scrub(stderrWithCanary)

        // The canary should be replaced with ****
        assertNotEquals(
            stderrWithCanary,
            scrubbed,
            "Scrubbing should modify the string containing canary"
        )
        assertTrue(
            scrubbed.contains("****"),
            "Scrubbed string should contain the scrub marker"
        )
        assertNotEquals(
            true,
            scrubbed.contains("GHS6_CANARY_7f3a9c2e1b4d5e6f"),
            "Scrubbed string should NOT contain the ML-R4 canary"
        )
    }

    /**
     * Verifies that scrubbing with no registered secrets returns the input unchanged.
     */
    @Test
    fun `scrub_with_no_registered_secrets_returns_input`() {
        val registry = SecretPatternRegistry()

        val input = "Some error message without secrets"
        val result = registry.scrub(input)

        assertEquals(input, result, "Scrubbing with no registered secrets should return input unchanged")
    }

    /**
     * Verifies that scrubbing with short secrets (< 8 chars) doesn't crash.
     * Short secrets are not registered per MIN_MASKABLE_LENGTH.
     */
    @Test
    fun `scrub_short_secret_not_registered`() {
        val registry = SecretPatternRegistry()

        // Short secret is not registered
        registry.addSecret(SecretHandle.plain("short"))

        val input = "Error with short secret: short"
        val result = registry.scrub(input)

        // Short secret should NOT be scrubbed (not registered)
        assertEquals(input, result, "Short secrets should not be registered for scrubbing")
    }

    /**
     * Verifies multi-encoding scrub: base64, hex variants.
     */
    @Test
    fun `scrub_removes_base64_encoding_of_secret`() {
        val registry = SecretPatternRegistry()

        val secret = "MY_SECRET_TOKEN_12345"
        registry.addSecret(SecretHandle.plain(secret))

        // Raw secret in error
        val rawScrubbed = registry.scrub("Token: $secret")
        assertNotEquals(true, rawScrubbed.contains(secret), "Raw secret should be scrubbed")

        // Base64 encoding of secret
        val encoded = java.util.Base64.getEncoder().encodeToString(secret.toByteArray())
        val base64Scrubbed = registry.scrub("Token: $encoded")
        assertNotEquals(true, base64Scrubbed.contains(encoded), "Base64 secret should be scrubbed")

        // Hex encoding
        val hexLower = secret.toByteArray().joinToString("") { "%02x".format(it) }
        val hexLowerScrubbed = registry.scrub("Token: $hexLower")
        assertNotEquals(true, hexLowerScrubbed.contains(hexLower), "Hex (lower) secret should be scrubbed")

        val hexUpper = secret.toByteArray().joinToString("") { "%02X".format(it) }
        val hexUpperScrubbed = registry.scrub("Token: $hexUpper")
        assertNotEquals(true, hexUpperScrubbed.contains(hexUpper), "Hex (upper) secret should be scrubbed")
    }
}
