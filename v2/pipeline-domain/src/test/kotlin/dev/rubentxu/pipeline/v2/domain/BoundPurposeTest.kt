package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for BoundPurpose enum (ML-R6).
 * BoundPurpose records how a credential is bound to a step.
 * Maps to Jenkins credentials-binding kinds per JENKINS_FAMILIARITY_CATALOG.md §1.6.
 */
@DisplayName("BoundPurpose enum contract tests (ML-R6)")
class BoundPurposeTest {

    @Test
    fun `BoundPurpose has exactly 7 values`() {
        val values = BoundPurpose.entries
        assertEquals(7, values.size)
    }

    @Test
    fun `BoundPurpose contains all expected Jenkins kinds`() {
        val values = BoundPurpose.entries.toSet()
        // All 7 Jenkins credential kinds
        assertTrue(BoundPurpose.API_KEY in values)
        assertTrue(BoundPurpose.USERNAME_PASSWORD in values)
        assertTrue(BoundPurpose.SSH_KEY in values)
        assertTrue(BoundPurpose.FILE in values)
        assertTrue(BoundPurpose.CERTIFICATE in values)
        assertTrue(BoundPurpose.ZIP in values)
        assertTrue(BoundPurpose.USERNAME_COLON_PASSWORD in values)
    }

    @Test
    fun `BoundPurpose enum has expected ordinal ordering`() {
        assertEquals(0, BoundPurpose.API_KEY.ordinal)
        assertEquals(1, BoundPurpose.USERNAME_PASSWORD.ordinal)
        assertEquals(2, BoundPurpose.SSH_KEY.ordinal)
        assertEquals(3, BoundPurpose.FILE.ordinal)
        assertEquals(4, BoundPurpose.CERTIFICATE.ordinal)
        assertEquals(5, BoundPurpose.ZIP.ordinal)
        assertEquals(6, BoundPurpose.USERNAME_COLON_PASSWORD.ordinal)
    }

    @Test
    fun `BoundPurpose API_KEY maps to string binding`() {
        assertEquals("API_KEY", BoundPurpose.API_KEY.name)
    }

    @Test
    fun `BoundPurpose USERNAME_PASSWORD maps to usernamePassword binding`() {
        assertEquals("USERNAME_PASSWORD", BoundPurpose.USERNAME_PASSWORD.name)
    }

    @Test
    fun `BoundPurpose SSH_KEY maps to sshUserPrivateKey binding`() {
        assertEquals("SSH_KEY", BoundPurpose.SSH_KEY.name)
    }
}
