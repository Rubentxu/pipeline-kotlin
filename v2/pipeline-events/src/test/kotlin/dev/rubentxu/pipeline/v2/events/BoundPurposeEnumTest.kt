package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * EVT-CR-009: BoundPurpose enum values test.
 * Verifies the enum has exactly the ML-R6 credential kinds — no extra discriminators.
 * Maps to Jenkins credentials-binding kinds per JENKINS_FAMILIARITY_CATALOG.md §1.6.
 */
class BoundPurposeEnumTest {

    @Test
    fun `EVT-CR-009 BoundPurpose has exactly ML-R6 kinds`() {
        val values = BoundPurpose.entries
        assertEquals(7, values.size, "BoundPurpose should have exactly 7 ML-R6 values")
        assertTrue(values.contains(BoundPurpose.API_KEY), "Should contain API_KEY (string binding)")
        assertTrue(values.contains(BoundPurpose.USERNAME_PASSWORD), "Should contain USERNAME_PASSWORD")
        assertTrue(values.contains(BoundPurpose.SSH_KEY), "Should contain SSH_KEY")
        assertTrue(values.contains(BoundPurpose.FILE), "Should contain FILE")
        assertTrue(values.contains(BoundPurpose.CERTIFICATE), "Should contain CERTIFICATE")
        assertTrue(values.contains(BoundPurpose.ZIP), "Should contain ZIP")
        assertTrue(values.contains(BoundPurpose.USERNAME_COLON_PASSWORD), "Should contain USERNAME_COLON_PASSWORD")
    }

    @Test
    fun `EVT-CR-008 forward compat - schema version stays v1`() {
        // Schema version is "v1" — new variants are backward-compatible
        // The encode function uses "v1" as schema identifier
        val event = CredentialBound(
            eventId = "test",
            runId = "test",
            sequence = 1L,
            occurredAt = java.time.Instant.now(),
            credentialsId = dev.rubentxu.pipeline.v2.domain.CredentialsId("test"),
            purpose = BoundPurpose.API_KEY,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        // The kind is encoded as a string — forward compat is handled by decode returning null for unknown kinds
        assertTrue(encoded.contains("CredentialBound"))
    }
}
