package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [EnvValue] sealed interface and [SecretRef] type alias.
 *
 * Covers:
 * - [EnvValue.Plain] and [EnvValue.Secret] construction and equality
 * - [SecretRef] is a runtime alias for [CredentialsId]
 * - Sealed interface exhaustiveness in when expressions
 * - Equality and identity semantics
 */
@DisplayName("EnvValue contract tests")
class EnvValueTest {

    // ---------------------------------------------------------------------------
    // EnvValue.Plain
    // ---------------------------------------------------------------------------

    @Test
    fun `Plain wraps a non-blank string value`() {
        val plain = EnvValue.Plain("hello-world")
        assertEquals("hello-world", plain.value)
    }

    @Test
    fun `Plain equality - same value yields equal instances`() {
        val a = EnvValue.Plain("secret-value")
        val b = EnvValue.Plain("secret-value")
        assertEquals(a, b)
    }

    @Test
    fun `Plain equality - different values are not equal`() {
        val a = EnvValue.Plain("value-a")
        val b = EnvValue.Plain("value-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `Plain toString contains the value`() {
        val plain = EnvValue.Plain("test-value")
        assertTrue(plain.toString().contains("test-value"))
    }

    // ---------------------------------------------------------------------------
    // EnvValue.Secret
    // ---------------------------------------------------------------------------

    @Test
    fun `Secret wraps a CredentialsId via typealias`() {
        val credId = CredentialsId("github-token")
        val secret = EnvValue.Secret(credId)
        assertEquals(credId.value, secret.ref.value)
    }

    @Test
    fun `Secret equality - same ref yields equal instances`() {
        val credId = CredentialsId("my-secret-id")
        val a = EnvValue.Secret(credId)
        val b = EnvValue.Secret(credId)
        assertEquals(a, b)
    }

    @Test
    fun `Secret equality - different refs are not equal`() {
        val credA = CredentialsId("secret-a")
        val credB = CredentialsId("secret-b")
        val a = EnvValue.Secret(credA)
        val b = EnvValue.Secret(credB)
        assertNotEquals(a, b)
    }

    @Test
    fun `Secret toString contains the ref value`() {
        val credId = CredentialsId("cred-123")
        val secret = EnvValue.Secret(credId)
        assertTrue(secret.toString().contains("cred-123"))
    }

    // ---------------------------------------------------------------------------
    // SecretRef = CredentialsId type alias
    // ---------------------------------------------------------------------------

    @Test
    fun `SecretRef is the same runtime type as CredentialsId`() {
        val credId = CredentialsId("pipeline-secret")
        // SecretRef and CredentialsId are the same type (typealias)
        val ref: CredentialsId = credId
        assertEquals("pipeline-secret", ref.value)
    }

    @Test
    fun `SecretRef can be constructed via CredentialsId factory`() {
        val ref = CredentialsId.from("direct-construct")
        assertEquals("direct-construct", ref.value)
    }

    @Test
    fun `SecretRef requires non-blank value`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialsId("")
        }
    }

    @Test
    fun `SecretRef value is accessible`() {
        val credId = CredentialsId("test-ref")
        val ref: SecretRef = credId
        assertEquals("test-ref", ref.value)
    }

    // ---------------------------------------------------------------------------
    // Sealed interface exhaustiveness
    // ---------------------------------------------------------------------------

    @Test
    fun `when expression is exhaustive for EnvValue`() {
        val plain = EnvValue.Plain("test")
        val secret = EnvValue.Secret(CredentialsId("id"))

        fun describe(env: EnvValue): String = when (env) {
            is EnvValue.Plain -> "plain:${env.value}"
            is EnvValue.Secret -> "secret:${env.ref.value}"
        }

        assertEquals("plain:test", describe(plain))
        assertEquals("secret:id", describe(secret))
    }

    // ---------------------------------------------------------------------------
    // EnvValue as a value object
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("EnvValue is a value object")
    inner class ValueObjectSemantics {

        @Test
        fun `Plain is a data class with value semantics`() {
            val original = EnvValue.Plain("original")
            val copy = original.copy()
            assertEquals(original, copy)
            assertNotSame(original, copy)
        }

        @Test
        fun `Secret is a data class with value semantics`() {
            val credId = CredentialsId("ref")
            val original = EnvValue.Secret(credId)
            val copy = original.copy()
            assertEquals(original, copy)
            assertNotSame(original, copy)
        }

        @Test
        fun `Plain value can be updated via copy`() {
            val original = EnvValue.Plain("old")
            val updated = original.copy(value = "new")
            assertEquals("old", original.value)
            assertEquals("new", updated.value)
        }

        @Test
        fun `Secret ref can be updated via copy`() {
            val credA = CredentialsId("a")
            val credB = CredentialsId("b")
            val original = EnvValue.Secret(credA)
            val updated = original.copy(ref = credB)
            assertEquals("a", original.ref.value)
            assertEquals("b", updated.ref.value)
        }
    }
}
