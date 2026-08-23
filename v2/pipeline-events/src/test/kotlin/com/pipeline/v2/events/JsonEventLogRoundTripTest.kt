package com.pipeline.v2.events

import com.pipeline.v2.events.StageStarted
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StepStarted
import com.pipeline.v2.events.StepFinished
import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.ScriptingDiagnostic
import com.pipeline.v2.scripting.ScriptDiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * JSON encode/decode round-trip: encode 4 events → decode → structural equality ignoring eventId/occurredAt.
 */
class JsonEventLogRoundTripTest {

    @Test
    fun `encode then decode preserves event structure`() {
        val runId = "round-trip-run"
        val events = listOf(
            RunStarted(
                eventId = "id-1",
                runId = runId,
                sequence = 1L,
                occurredAt = Instant.parse("2026-08-23T10:00:00Z"),
                scriptPath = "test.pipeline.kts",
            ),
            CompilationStarted(
                eventId = "id-2",
                runId = runId,
                sequence = 2L,
                occurredAt = Instant.parse("2026-08-23T10:00:01Z"),
            ),
            CompilationFinished(
                eventId = "id-3",
                runId = runId,
                sequence = 3L,
                occurredAt = Instant.parse("2026-08-23T10:00:02Z"),
                cacheKey = CacheKey("a".repeat(64), "v1"),
                diagnostics = listOf(
                    ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, "test error", 1, 1, "test.pipeline.kts")
                ),
            ),
            RunFinished(
                eventId = "id-4",
                runId = runId,
                sequence = 4L,
                occurredAt = Instant.parse("2026-08-23T10:00:03Z"),
                outcome = "success",
                diagnostics = emptyList(),
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(4, decoded.size)

        assertEquals("RunStarted", decoded[0].kind)
        assertEquals(runId, decoded[0].runId)
        assertEquals(1L, decoded[0].sequence)
        val rs0 = decoded[0] as RunStarted
        assertEquals("test.pipeline.kts", rs0.scriptPath)

        assertEquals("CompilationStarted", decoded[1].kind)
        assertEquals(2L, decoded[1].sequence)

        assertEquals("CompilationFinished", decoded[2].kind)
        assertEquals(3L, decoded[2].sequence)
        val cf2 = decoded[2] as CompilationFinished
        assertEquals("v1", cf2.cacheKey.version)
        assertEquals(64, cf2.cacheKey.value.length)
        assertEquals(1, cf2.diagnostics.size)

        assertEquals("RunFinished", decoded[3].kind)
        assertEquals(4L, decoded[3].sequence)
        val rf3 = decoded[3] as RunFinished
        assertEquals("success", rf3.outcome)
    }

    @Test
    fun `stage and step events round-trip correctly`() {
        val runId = "stage-step-run"
        val events = listOf(
            StageStarted(
                eventId = "id-ss-1",
                runId = runId,
                sequence = 5L,
                occurredAt = Instant.parse("2026-08-23T10:00:05Z"),
                stageName = "Build",
            ),
            StepStarted(
                eventId = "id-ss-2",
                runId = runId,
                sequence = 6L,
                occurredAt = Instant.parse("2026-08-23T10:00:06Z"),
                stepName = "echo",
                stepType = "echo",
            ),
            StepFinished(
                eventId = "id-ss-3",
                runId = runId,
                sequence = 7L,
                occurredAt = Instant.parse("2026-08-23T10:00:07Z"),
                stepName = "echo",
                stepType = "echo",
            ),
            StageFinished(
                eventId = "id-ss-4",
                runId = runId,
                sequence = 8L,
                occurredAt = Instant.parse("2026-08-23T10:00:08Z"),
                stageName = "Build",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(4, decoded.size)

        assertEquals("StageStarted", decoded[0].kind)
        val ss0 = decoded[0] as StageStarted
        assertEquals("Build", ss0.stageName)
        assertEquals(runId, ss0.runId)
        assertEquals(5L, ss0.sequence)

        assertEquals("StepStarted", decoded[1].kind)
        val ss1 = decoded[1] as StepStarted
        assertEquals("echo", ss1.stepName)
        assertEquals("echo", ss1.stepType)
        assertEquals(6L, ss1.sequence)

        assertEquals("StepFinished", decoded[2].kind)
        val sf2 = decoded[2] as StepFinished
        assertEquals("echo", sf2.stepName)
        assertEquals("echo", sf2.stepType)
        assertEquals(7L, sf2.sequence)

        assertEquals("StageFinished", decoded[3].kind)
        val sf3 = decoded[3] as StageFinished
        assertEquals("Build", sf3.stageName)
        assertEquals(8L, sf3.sequence)
    }
}
