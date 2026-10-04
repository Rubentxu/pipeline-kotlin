package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.GateEvaluated
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S2-C: the gate composition verdict must round-trip through the JSON event
 * log byte-for-byte in VALUE, including names containing quotes and non-ASCII,
 * because the durable journal is the only record of which gates were evaluated
 * and what they decided. A satisfied gate that leaves no trace is
 * indistinguishable from a stage with no gates at all.
 */
class GateEvaluatedEventRoundTripTest {

    private val event = GateEvaluated(
        eventId = "evt-gate-001",
        runId = "run-gate-001",
        sequence = 9L,
        occurredAt = Instant.parse("2026-09-30T12:00:00Z"),
        stageIndex = 1,
        stageName = "stage \"despliegue\" ≥ prod",
        directiveKeys = listOf("core.when", "acme.lock"),
        satisfied = true,
        reason = "",
    )

    @Test
    fun `encode declares the kind, all directive keys and the verdict`() {
        val json = EventJsonWriter.encodeEvent(event)

        assertTrue(json.contains("\"kind\":\"GateEvaluated\""), "kind: $json")
        assertTrue(json.contains("\"directiveKeys\":[\"core.when\",\"acme.lock\"]"), "keys: $json")
        assertTrue(json.contains("\"satisfied\":true"), "satisfied: $json")
        assertTrue(json.contains("\"reason\":\"\""), "reason: $json")
    }

    @Test
    fun `decode reverses encode with exact value equality`() {
        val json = EventJsonWriter.encodeEvent(event)
        val decoded = JsonEventLog.decode("[$json]").single()

        assertEquals(event, decoded, "round-trip must preserve every field")
    }

    @Test
    fun `a denied verdict carries its reason through the log`() {
        val denied = event.copy(satisfied = false, reason = "branch != main for stage \"despliegue\" ≥ prod")
        val json = EventJsonWriter.encodeEvent(denied)
        val decoded = JsonEventLog.decode("[$json]").single() as GateEvaluated

        assertEquals(denied, decoded, "reason with quotes and non-ASCII must survive")
        assertTrue(json.contains("\"satisfied\":false"), "verdict: $json")
    }

    @Test
    fun `an empty composition key list round-trips as an empty list`() {
        val inert = event.copy(directiveKeys = emptyList())
        val json = EventJsonWriter.encodeEvent(inert)
        val decoded = JsonEventLog.decode("[$json]").single() as GateEvaluated

        assertEquals(emptyList<String>(), decoded.directiveKeys)
        assertTrue(json.contains("\"directiveKeys\":[]"), "empty list is an explicit empty array: $json")
    }
}
