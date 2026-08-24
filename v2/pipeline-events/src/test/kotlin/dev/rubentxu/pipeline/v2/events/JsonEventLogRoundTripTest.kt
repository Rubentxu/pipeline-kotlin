package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
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
                stageIndex = 0,
                stageName = "Build",
            ),
            StepStarted(
                eventId = "id-ss-2",
                runId = runId,
                sequence = 6L,
                occurredAt = Instant.parse("2026-08-23T10:00:06Z"),
                stageIndex = 0,
                stepIndex = 0,
                stepName = "echo",
                stepType = "echo",
            ),
            StepFinished(
                eventId = "id-ss-3",
                runId = runId,
                sequence = 7L,
                occurredAt = Instant.parse("2026-08-23T10:00:07Z"),
                stageIndex = 0,
                stepIndex = 0,
                stepName = "echo",
                stepType = "echo",
            ),
            StageFinished(
                eventId = "id-ss-4",
                runId = runId,
                sequence = 8L,
                occurredAt = Instant.parse("2026-08-23T10:00:08Z"),
                stageIndex = 0,
                stageName = "Build",
                outcome = "success",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(4, decoded.size)

        assertEquals("StageStarted", decoded[0].kind)
        val ss0 = decoded[0] as StageStarted
        assertEquals("Build", ss0.stageName)
        assertEquals(0, ss0.stageIndex)
        assertEquals(runId, ss0.runId)
        assertEquals(5L, ss0.sequence)

        assertEquals("StepStarted", decoded[1].kind)
        val ss1 = decoded[1] as StepStarted
        assertEquals("echo", ss1.stepName)
        assertEquals("echo", ss1.stepType)
        assertEquals(0, ss1.stageIndex)
        assertEquals(0, ss1.stepIndex)
        assertEquals(6L, ss1.sequence)

        assertEquals("StepFinished", decoded[2].kind)
        val sf2 = decoded[2] as StepFinished
        assertEquals("echo", sf2.stepName)
        assertEquals("echo", sf2.stepType)
        assertEquals(0, sf2.stageIndex)
        assertEquals(0, sf2.stepIndex)
        assertEquals(7L, sf2.sequence)

        assertEquals("StageFinished", decoded[3].kind)
        val sf3 = decoded[3] as StageFinished
        assertEquals("Build", sf3.stageName)
        assertEquals(0, sf3.stageIndex)
        assertEquals("success", sf3.outcome)
        assertEquals(8L, sf3.sequence)
    }

    @Test
    fun `agent resolved event round-trips correctly`() {
        val runId = "agent-run"
        val events = listOf(
            AgentResolved(
                eventId = "id-ar-1",
                runId = runId,
                sequence = 9L,
                occurredAt = Instant.parse("2026-08-23T10:00:09Z"),
                agentLabel = "linux-agent",
                remoteUri = "grpc://agent.example.com:9090",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("AgentResolved", decoded[0].kind)
        val ar = decoded[0] as AgentResolved
        assertEquals("linux-agent", ar.agentLabel)
        assertEquals("grpc://agent.example.com:9090", ar.remoteUri)
        assertEquals(9L, ar.sequence)
    }

    @Test
    fun `parallel branch events round-trip correctly`() {
        val runId = "parallel-run"
        val events = listOf(
            ParallelBranchStarted(
                eventId = "id-pbs-1",
                runId = runId,
                sequence = 10L,
                occurredAt = Instant.parse("2026-08-23T10:00:10Z"),
                branchIndex = 0,
                branchName = "branch-a",
                parentStageIndex = 0,
            ),
            ParallelBranchFinished(
                eventId = "id-pbf-1",
                runId = runId,
                sequence = 11L,
                occurredAt = Instant.parse("2026-08-23T10:00:11Z"),
                branchIndex = 0,
                branchName = "branch-a",
                parentStageIndex = 0,
                outcome = "success",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals("ParallelBranchStarted", decoded[0].kind)
        val pbs = decoded[0] as ParallelBranchStarted
        assertEquals(0, pbs.branchIndex)
        assertEquals("branch-a", pbs.branchName)
        assertEquals(0, pbs.parentStageIndex)

        assertEquals("ParallelBranchFinished", decoded[1].kind)
        val pbf = decoded[1] as ParallelBranchFinished
        assertEquals("success", pbf.outcome)
    }

    @Test
    fun `retry attempt events round-trip correctly`() {
        val runId = "retry-run"
        val events = listOf(
            RetryAttemptStarted(
                eventId = "id-ras-1",
                runId = runId,
                sequence = 12L,
                occurredAt = Instant.parse("2026-08-23T10:00:12Z"),
                attemptNumber = 2,
                maxAttempts = 3,
                stepName = "sh",
                stepType = "sh",
                stageIndex = 0,
                stepIndex = 1,
            ),
            RetryAttemptFinished(
                eventId = "id-raf-1",
                runId = runId,
                sequence = 13L,
                occurredAt = Instant.parse("2026-08-23T10:00:13Z"),
                attemptNumber = 2,
                maxAttempts = 3,
                stepName = "sh",
                stepType = "sh",
                stageIndex = 0,
                stepIndex = 1,
                outcome = "success",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals("RetryAttemptStarted", decoded[0].kind)
        val ras = decoded[0] as RetryAttemptStarted
        assertEquals(2, ras.attemptNumber)
        assertEquals(3, ras.maxAttempts)

        assertEquals("RetryAttemptFinished", decoded[1].kind)
        val raf = decoded[1] as RetryAttemptFinished
        assertEquals("success", raf.outcome)
    }

    @Test
    fun `timeout scheduled event round-trips correctly`() {
        val runId = "timeout-run"
        val events = listOf(
            TimeoutScheduled(
                eventId = "id-ts-1",
                runId = runId,
                sequence = 14L,
                occurredAt = Instant.parse("2026-08-23T10:00:14Z"),
                timeoutSeconds = 300L,
                timeoutAction = "FAIL",
                stepName = "sh",
                stepType = "sh",
                stageIndex = 0,
                stepIndex = 1,
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("TimeoutScheduled", decoded[0].kind)
        val ts = decoded[0] as TimeoutScheduled
        assertEquals(300L, ts.timeoutSeconds)
        assertEquals("FAIL", ts.timeoutAction)
        assertEquals("sh", ts.stepName)
    }

    @Test
    fun `step failed event round-trips correctly`() {
        val runId = "step-failed-run"
        val events = listOf(
            StepFailed(
                eventId = "id-sf-1",
                runId = runId,
                sequence = 15L,
                occurredAt = Instant.parse("2026-08-23T10:00:15Z"),
                stepIndex = 2,
                stepName = "error",
                stepType = "error",
                failureKind = FailureKind.USER,
                message = "boom",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("StepFailed", decoded[0].kind)
        val sf = decoded[0] as StepFailed
        assertEquals("error", sf.stepName)
        assertEquals("error", sf.stepType)
        assertEquals(2, sf.stepIndex)
        assertEquals(FailureKind.USER, sf.failureKind)
        assertEquals("boom", sf.message)
        assertEquals(runId, sf.runId)
        assertEquals(15L, sf.sequence)
    }

    @Test
    fun `echo output captured event round-trips correctly`() {
        val runId = "echo-output-run"
        val events = listOf(
            EchoOutputCaptured(
                eventId = "id-eoc-1",
                runId = runId,
                sequence = 16L,
                occurredAt = Instant.parse("2026-08-23T10:00:16Z"),
                stepIndex = 0,
                content = "hello world",
            ),
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("EchoOutputCaptured", decoded[0].kind)
        val eoc = decoded[0] as EchoOutputCaptured
        assertEquals(0, eoc.stepIndex)
        assertEquals("hello world", eoc.content)
        assertEquals(runId, eoc.runId)
        assertEquals(16L, eoc.sequence)
    }
}
