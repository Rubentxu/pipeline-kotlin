package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S2-B: the `post` decision event must round-trip through the JSON event log
 * byte-for-byte in VALUE, including names containing quotes and non-ASCII,
 * because the durable journal is the only record of which finalizers fired.
 */
class PostConditionSelectedEventRoundTripTest {

    private val event = PostConditionSelected(
        eventId = "evt-post-001",
        runId = "run-post-001",
        sequence = 7L,
        occurredAt = Instant.parse("2026-09-30T12:00:00Z"),
        stageIndex = 2,
        stageName = "stage \"cuota\" ≥ 50%",
        stageOutcome = "failed",
        selectedConditions = listOf("ALWAYS", "FAILURE", "CLEANUP"),
        skippedConditions = listOf("SUCCESS", "UNSTABLE", "ABORTED", "UNSUCCESSFUL"),
    )

    @Test
    fun `encode declares the kind and both condition lists`() {
        val json = EventJsonWriter.encodeEvent(event)

        assertTrue(json.contains("\"kind\":\"PostConditionSelected\""), "kind: $json")
        assertTrue(json.contains("\"stageOutcome\":\"failed\""), "stageOutcome: $json")
        assertTrue(json.contains("\"selectedConditions\":[\"ALWAYS\",\"FAILURE\",\"CLEANUP\"]"), "selected: $json")
        assertTrue(
            json.contains("\"skippedConditions\":[\"SUCCESS\",\"UNSTABLE\",\"ABORTED\",\"UNSUCCESSFUL\"]"),
            "skipped: $json",
        )
    }

    @Test
    fun `decode reverses encode with exact value equality`() {
        val json = EventJsonWriter.encodeEvent(event)
        val decoded = JsonEventLog.decode("[$json]").single()

        assertEquals(event, decoded, "round-trip must preserve every field")
    }

    @Test
    fun `an empty selection list round-trips as an empty list`() {
        val inert = event.copy(selectedConditions = emptyList())
        val json = EventJsonWriter.encodeEvent(inert)
        val decoded = JsonEventLog.decode("[$json]").single() as PostConditionSelected

        assertEquals(emptyList<String>(), decoded.selectedConditions)
        assertTrue(json.contains("\"selectedConditions\":[]"), "empty list is an explicit empty array: $json")
    }
}
