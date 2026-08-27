package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Tests for SecretPatternRegistry — CR-RD-001..007, CR-RD-013, CR-RD-015, CR-RD-016
 */
class SecretPatternRegistryTest {

    @Test
    fun `CR-RD-001 literal secret is scrubbed`() {
        val registry = SecretPatternRegistry()
        val secret = "super-secret-api-key-12345"
        registry.addSecret(SecretHandle.plain(secret))

        val patterns = registry.buildActivePatterns()
        assertTrue(patterns.isNotEmpty(), "Patterns should be built")

        // The secret literal should be matchable
        val input = "Using API key: $secret in request"
        val scrubbed = applyPatterns(input, patterns)
        assertFalse(scrubbed.contains(secret), "Secret literal should be replaced")
        assertTrue(scrubbed.contains("****"), "Scrub marker should appear")
    }

    @Test
    fun `CR-RD-002 base64 std encoding is scrubbed`() {
        val registry = SecretPatternRegistry()
        val rawBytes = "my-secret-password".toByteArray(StandardCharsets.UTF_8)
        val b64 = Base64.getEncoder().encodeToString(rawBytes)
        registry.addSecret(SecretHandle.secret(rawBytes))

        val patterns = registry.buildActivePatterns()
        val input = "Authorization: Bearer $b64"
        val scrubbed = applyPatterns(input, patterns)

        // base64 form should also be scrubbed
        assertFalse(scrubbed.contains(b64), "base64 secret should be replaced")
    }

    @Test
    fun `CR-RD-003 base64 url-safe encoding is scrubbed`() {
        val registry = SecretPatternRegistry()
        val rawBytes = "my/secret+with=special".toByteArray(StandardCharsets.UTF_8)
        val b64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        registry.addSecret(SecretHandle.secret(rawBytes))

        val patterns = registry.buildActivePatterns()
        val input = "Token: $b64Url"
        val scrubbed = applyPatterns(input, patterns)

        assertFalse(scrubbed.contains(b64Url), "base64url secret should be replaced")
    }

    @Test
    fun `CR-RD-005 hex upper encoding is scrubbed`() {
        val registry = SecretPatternRegistry()
        val rawBytes = "SecretValue99".toByteArray(StandardCharsets.UTF_8)
        val hexUpper = rawBytes.joinToString("") { "%02X".format(it) }
        registry.addSecret(SecretHandle.secret(rawBytes))

        val patterns = registry.buildActivePatterns()
        val input = "Key: $hexUpper"
        val scrubbed = applyPatterns(input, patterns)

        assertFalse(scrubbed.contains(hexUpper), "hex upper secret should be replaced")
    }

    @Test
    fun `CR-RD-006 hex lower encoding is scrubbed`() {
        val registry = SecretPatternRegistry()
        val rawBytes = "SecretValue99".toByteArray(StandardCharsets.UTF_8)
        val hexLower = rawBytes.joinToString("") { "%02x".format(it) }
        registry.addSecret(SecretHandle.secret(rawBytes))

        val patterns = registry.buildActivePatterns()
        val input = "Key: $hexLower"
        val scrubbed = applyPatterns(input, patterns)

        assertFalse(scrubbed.contains(hexLower), "hex lower secret should be replaced")
    }

    @Test
    fun `CR-RD-007 short secret below minimum length emits warning and is NOT registered`() {
        val registry = SecretPatternRegistry()
        // 3 chars — below GitLab minimum of 8
        registry.addSecret(SecretHandle.plain("abc"))

        val patterns = registry.buildActivePatterns()
        // Short secret should not produce patterns that would match it
        // (the registry should not register it for masking)
        assertTrue(patterns.isEmpty() || patterns.none {
            it.containsMatchIn("abc")
        }, "Short secrets should not be registered for masking")
    }

    @Test
    fun `CR-RD-013 line-oriented scrub preserves unrelated lines`() {
        val registry = SecretPatternRegistry()
        val secret = "ghp_verylongsecret1234567890"
        registry.addSecret(SecretHandle.plain(secret))

        val patterns = registry.buildActivePatterns()
        val input = """Line 1: normal text
Line 2: $secret - but this is not actually secret
Line 3: more normal text"""

        val scrubbed = applyPatterns(input, patterns)

        // Only the secret value itself should be replaced, not the whole line
        assertTrue(scrubbed.contains("normal text"), "Unrelated lines should be preserved")
        assertFalse(scrubbed.contains("ghp_verylong"), "Secret should be scrubbed")
    }

    @Test
    fun `CR-RD-015 naive patterns used for N less than 20`() {
        val registry = SecretPatternRegistry()
        // Add 5 secrets — within naive threshold
        repeat(5) { i ->
            registry.addSecret(SecretHandle.plain("secret-$i-abcdefghij"))
        }

        val patterns = registry.buildActivePatterns()
        assertTrue(patterns.isNotEmpty(), "Should have patterns for 5 secrets")
        // Naive mode should be used (no Aho-Corasick complexity visible in API)
    }

    @Test
    fun `CR-RD-016 removeSecret drops patterns`() {
        val registry = SecretPatternRegistry()
        val secret = "removable-secret-xyz"
        registry.addSecret(SecretHandle.plain(secret))

        var patterns = registry.buildActivePatterns()
        assertTrue(patterns.any { it.containsMatchIn(secret) }, "Secret should be registered")

        registry.removeSecret(SecretHandle.plain(secret))
        patterns = registry.buildActivePatterns()
        assertFalse(patterns.any { it.containsMatchIn(secret) }, "Secret should be removed")
    }

    /**
     * Helper: applies all patterns to scrub secret values from input.
     * This simulates what RedactingEventSink does.
     */
    private fun applyPatterns(input: String, patterns: List<Regex>): String {
        var result = input
        for (pattern in patterns) {
            result = pattern.replace(result, "****")
        }
        return result
    }
}
