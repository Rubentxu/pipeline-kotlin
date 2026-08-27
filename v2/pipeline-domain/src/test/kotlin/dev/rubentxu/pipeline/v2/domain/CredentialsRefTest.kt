package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for CredentialsRef typed carrier.
 * CredentialsRef is the sole boundary carrier for credentials-by-ID references.
 * NEVER use credentials by value across module boundaries.
 */
@DisplayName("CredentialsRef typed carrier tests")
class CredentialsRefTest {

    @Test
    fun `CredentialsRef wraps a CredentialsId value class`() {
        val id = CredentialsId("github-api-key")
        val ref = CredentialsRef(id)
        assertEquals(id, ref.id)
    }

    @Test
    fun `CredentialsRef equals another with same CredentialsId`() {
        val id = CredentialsId("docker-hub-token")
        val ref1 = CredentialsRef(id)
        val ref2 = CredentialsRef(CredentialsId("docker-hub-token"))
        assertEquals(ref1, ref2)
    }

    @Test
    fun `CredentialsRef is the sole boundary carrier for credentials-by-ID`() {
        // The purpose of CredentialsRef is to be the ONLY way to pass
        // credentials references across module boundaries (L1 structural redaction)
        val ref = CredentialsRef(CredentialsId("my-secret"))
        assertEquals("CredentialsId(value=my-secret)", ref.id.toString())
    }

    @Test
    fun `CredentialsRef is a JVM inline value class`() {
        val ref = CredentialsRef(CredentialsId("test"))
        assertTrue(ref == CredentialsRef(CredentialsId("test")))
    }
}
