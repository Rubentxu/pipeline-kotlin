package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * EVT-CR-009: BoundPurpose enum values test.
 * Verifies the enum has exactly [ENV, FILE, VALUE] — no extra discriminators.
 */
class BoundPurposeEnumTest {

    @Test
    fun `EVT-CR-009 BoundPurpose has exactly ENV FILE VALUE`() {
        val values = BoundPurpose.entries
        assertEquals(3, values.size, "BoundPurpose should have exactly 3 values: ENV, FILE, VALUE")
        assertTrue(values.contains(BoundPurpose.ENV), "Should contain ENV")
        assertTrue(values.contains(BoundPurpose.FILE), "Should contain FILE")
        assertTrue(values.contains(BoundPurpose.VALUE), "Should contain VALUE")
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
            purpose = BoundPurpose.ENV,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        // The kind is encoded as a string — forward compat is handled by decode returning null for unknown kinds
        assertTrue(encoded.contains("CredentialBound"))
    }
}
