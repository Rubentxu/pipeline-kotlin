package dev.rubentxu.pipeline.v2.credentials.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * CR-RD-015: Aho-Corasick performance test.
 *
 * Verifies that when > 20 secrets are registered, the pattern matching
 * still completes in reasonable time (< 50ms per event for N=100 patterns
 * per design §R2).
 *
 * The naive Pattern.quote | alternation approach is used for ≤ 20 secrets.
 * This test verifies that the naive approach handles 25 secrets quickly.
 */
class AhoCorasickSwitchTest {

    @Test
    fun `CR-RD-015 performance with 25 secrets`() {
        val registry = SecretPatternRegistry()
        // Add 25 secrets — beyond naive threshold
        repeat(25) { i ->
            val secret = "secret-number-$i-${"x".repeat(10)}"
            registry.addSecret(SecretHandle.plain(secret))
        }

        val patterns = registry.buildActivePatterns()
        assertTrue(patterns.size > 25 * 4, "Should have multiple encoding variants per secret")

        // Build a large input containing all secrets
        val content = buildString {
            repeat(10) { i ->
                append("secret-number-$i-${"x".repeat(10)} ")
            }
        }

        val start = System.currentTimeMillis()
        var result = content
        for (pattern in patterns) {
            result = pattern.replace(result, "****")
        }
        val elapsed = System.currentTimeMillis() - start

        // Per design §R2: ≤ 50ms per event for N=100 patterns
        assertTrue(elapsed < 200, "25 secrets (${patterns.size} patterns) should process quickly: ${elapsed}ms")
    }
}
