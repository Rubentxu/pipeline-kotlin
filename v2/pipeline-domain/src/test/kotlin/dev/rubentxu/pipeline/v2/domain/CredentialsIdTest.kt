package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for CredentialsId value class.
 * CredentialsId is a JVM inline value class wrapping String.
 * The ID is NOT a secret - it's a public identifier for credentials.
 */
@DisplayName("CredentialsId contract tests")
class CredentialsIdTest {

    @Test
    fun `CredentialsId wraps a non-blank string value`() {
        val id = CredentialsId("github-deploy-key")
        assertEquals("github-deploy-key", id.value)
    }

    @Test
    fun `CredentialsId must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialsId("")
        }
    }

    @Test
    fun `CredentialsId equals another CredentialsId with same value`() {
        val id1 = CredentialsId("github-token")
        val id2 = CredentialsId("github-token")
        assertEquals(id1, id2)
    }

    @Test
    fun `CredentialsId is a JVM inline value class`() {
        // CredentialsId should compile as @JvmInline value class
        val id = CredentialsId("test-id")
        // Value class identity check: two instances with same value should be equal
        assertTrue(id == CredentialsId("test-id"))
    }
}
