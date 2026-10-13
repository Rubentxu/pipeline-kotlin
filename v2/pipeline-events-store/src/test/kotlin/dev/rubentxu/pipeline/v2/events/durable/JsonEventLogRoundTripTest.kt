package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted

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

    // === T7: Credential audit events ===

    @Test
    fun `EVT-CR-001 CredentialBound roundtrips correctly`() {
        val event = CredentialBound(
            eventId = "cred-bound-1",
            runId = "credential-run",
            sequence = 20L,
            occurredAt = Instant.parse("2026-08-27T10:00:00Z"),
            credentialsId = CredentialsId("github-token"),
            purpose = BoundPurpose.API_KEY,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as CredentialBound
        assertEquals(event.credentialsId, restored.credentialsId)
        assertEquals(event.purpose, restored.purpose)
        assertEquals(event.eventId, restored.eventId)
        assertEquals(event.runId, restored.runId)
        assertEquals(event.sequence, restored.sequence)
    }

    @Test
    fun `EVT-CR-002 CredentialUsed roundtrips with stepIndex`() {
        val event = CredentialUsed(
            eventId = "cred-used-1",
            runId = "credential-run",
            sequence = 21L,
            occurredAt = Instant.parse("2026-08-27T10:00:01Z"),
            credentialsId = CredentialsId("deploy-key"),
            purpose = BoundPurpose.API_KEY,
            stepIndex = 3,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as CredentialUsed
        assertEquals(event.credentialsId, restored.credentialsId)
        assertEquals(event.purpose, restored.purpose)
        assertEquals(event.stepIndex, restored.stepIndex)
    }

    @Test
    fun `EVT-CR-003 CredentialUnbound roundtrips correctly`() {
        val event = CredentialUnbound(
            eventId = "cred-unbound-1",
            runId = "credential-run",
            sequence = 25L,
            occurredAt = Instant.parse("2026-08-27T10:00:05Z"),
            credentialsId = CredentialsId("api-key"),
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as CredentialUnbound
        assertEquals(event.credentialsId, restored.credentialsId)
    }

    @Test
    fun `EVT-CR-004 new variants carry no secret field`() {
        val events = listOf(
            CredentialBound("cb-1", "cr", 1L, Instant.now(), CredentialsId("id"), BoundPurpose.API_KEY),
            CredentialUsed("cu-1", "cr", 2L, Instant.now(), CredentialsId("id"), BoundPurpose.API_KEY, 0),
            CredentialUnbound("cun-1", "cr", 3L, Instant.now(), CredentialsId("id")),
        )
        val encoded = JsonEventLog.encode(events)
        assertFalse(encoded.contains("\"secret\""), "JSON should not contain 'secret' field")
        assertFalse(encoded.contains("\"value\""), "JSON should not contain 'value' field")
        assertFalse(encoded.contains("\"bytes\""), "JSON should not contain 'bytes' field")
    }

    @Test
    fun `EVT-CR-005 mixed event roundtrip preserves all 3 new variants`() {
        val events = listOf(
            RunStarted("rs-1", "mixed-run", 1L, Instant.now(), "/path/to/script.kts"),
            CredentialBound("cb-1", "mixed-run", 2L, Instant.now(), CredentialsId("github"), BoundPurpose.API_KEY),
            CredentialUsed("cu-1", "mixed-run", 3L, Instant.now(), CredentialsId("github"), BoundPurpose.API_KEY, 0),
            StepFinished("sf-1", "mixed-run", 4L, Instant.now(), 0, 0, "build", "sh"),
            CredentialUnbound("cun-1", "mixed-run", 5L, Instant.now(), CredentialsId("github")),
            RunFinished("rf-1", "mixed-run", 6L, Instant.now(), "SUCCESS", emptyList()),
        )
        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(6, decoded.size)
        assertTrue(decoded[1] is CredentialBound, "Event at index 1 should be CredentialBound")
        assertTrue(decoded[2] is CredentialUsed, "Event at index 2 should be CredentialUsed")
        assertTrue(decoded[4] is CredentialUnbound, "Event at index 4 should be CredentialUnbound")
    }

    @Test
    fun `EVT-CR-008 forward compat - unknown kind returns null in decode`() {
        // New kinds should be skipped gracefully (forward compat).
        //
        // The purpose token here was `ENV`, which has not been a [BoundPurpose] member for some
        // time — the enum has API_KEY, USERNAME_PASSWORD, SSH_KEY, FILE, CERTIFICATE, ZIP and
        // USERNAME_COLON_PASSWORD. The row still decoded, and still decoded as `CredentialBound`,
        // because the decoder caught the unknown token and fell back to API_KEY. So this test
        // never checked forward compatibility: it asserted that an invented binding kind was
        // accepted. It passed for the wrong reason and would have passed with any spelling.
        //
        // `FILE` is a real member, so the row now exercises only what the test name claims: the
        // SECOND event carries an unknown `kind` and is skipped, while a known kind in the same
        // document survives. Credential-purpose strictness is covered separately, by
        // `CredentialPurposeRefusalTest`, which refuses an unknown token rather than defaulting.
        val encoded = "[{\"eventId\":\"e1\",\"runId\":\"r1\",\"sequence\":1,\"kind\":\"CredentialBound\"," +
            "\"occurredAt\":\"2026-08-27T10:00:00Z\",\"credentialsId\":\"github\",\"purpose\":\"FILE\"}," +
            "{\"eventId\":\"e2\",\"runId\":\"r1\",\"sequence\":2,\"kind\":\"FutureEventKind\"," +
            "\"occurredAt\":\"2026-08-27T10:00:00Z\",\"extra\":\"data\"}]"
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0] is CredentialBound)
    }

    // === ML-R9 workspace-cleanup events (T-05) ===

    @Test
    fun `ML-R9 T-05 DirDeleted roundtrips correctly`() {
        val runId = "dir-deleted-run"
        val event = DirDeleted(
            eventId = "dd-1",
            runId = runId,
            sequence = 30L,
            occurredAt = Instant.parse("2026-08-30T10:00:00Z"),
            path = "build/output",
            deletedCount = 42,
            sha256 = "abc123def456",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as DirDeleted
        assertEquals("DirDeleted", restored.kind)
        assertEquals("build/output", restored.path)
        assertEquals(42, restored.deletedCount)
        assertEquals("abc123def456", restored.sha256)
        assertEquals(runId, restored.runId)
        assertEquals(30L, restored.sequence)
    }

    @Test
    fun `ML-R9 T-05 WsCleaned roundtrips correctly`() {
        val runId = "ws-cleaned-run"
        val event = WsCleaned(
            eventId = "wc-1",
            runId = runId,
            sequence = 31L,
            occurredAt = Instant.parse("2026-08-30T10:00:01Z"),
            deletedFiles = 17,
            deletedDirs = 3,
            patterns = listOf("target/**", "*.tmp"),
            sha256 = "deadbeef1234",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as WsCleaned
        assertEquals("WsCleaned", restored.kind)
        assertEquals(17, restored.deletedFiles)
        assertEquals(3, restored.deletedDirs)
        assertEquals(listOf("target/**", "*.tmp"), restored.patterns)
        assertEquals("deadbeef1234", restored.sha256)
    }

    @Test
    fun `ML-R9 T-05 WsCleaned with null patterns roundtrips correctly`() {
        val runId = "ws-cleaned-null-run"
        val event = WsCleaned(
            eventId = "wcn-1",
            runId = runId,
            sequence = 32L,
            occurredAt = Instant.parse("2026-08-30T10:00:02Z"),
            deletedFiles = 5,
            deletedDirs = 1,
            patterns = emptyList(),
            sha256 = "feedface",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as WsCleaned
        assertEquals(emptyList<String>(), restored.patterns)
    }

    // === ML-R9 error-handling events (T-06) ===

    @Test
    fun `ML-R9 T-06 CatchErrorTriggered roundtrips correctly`() {
        val runId = "catch-error-run"
        val event = CatchErrorTriggered(
            eventId = "cet-1",
            runId = runId,
            sequence = 33L,
            occurredAt = Instant.parse("2026-08-30T10:00:03Z"),
            stageName = "Build",
            buildResult = CatchErrorBuildResult.Unstable,
            stageResult = CatchErrorBuildResult.Unstable,
            message = "tolerated failure",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as CatchErrorTriggered
        assertEquals("CatchErrorTriggered", restored.kind)
        assertEquals("Build", restored.stageName)
        // P3-E D3: this asserts the typed value. The WIRE spelling is asserted separately in
        // CatchErrorResultWireCompatibilityTest, because "the round trip preserved a value" and
        // "the round trip preserved the historical token" are two different claims.
        assertEquals(CatchErrorBuildResult.Unstable, restored.buildResult)
        assertEquals(CatchErrorBuildResult.Unstable, restored.stageResult)
        assertEquals("tolerated failure", restored.message)
    }

    @Test
    fun `ML-R9 T-06 CatchErrorTriggered with null buildResult roundtrips`() {
        val runId = "catch-error-null-run"
        val event = CatchErrorTriggered(
            eventId = "cetn-1",
            runId = runId,
            sequence = 34L,
            occurredAt = Instant.parse("2026-08-30T10:00:04Z"),
            stageName = "Test",
            buildResult = null,
            stageResult = CatchErrorBuildResult.Failure,
            message = null,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as CatchErrorTriggered
        assertEquals(null, restored.buildResult)
        assertEquals(CatchErrorBuildResult.Failure, restored.stageResult)
        assertEquals(null, restored.message)
    }

    @Test
    fun `ML-R9 T-06 StageMarkedUnstable roundtrips correctly`() {
        val runId = "stage-unstable-run"
        val event = StageMarkedUnstable(
            eventId = "smu-1",
            runId = runId,
            sequence = 35L,
            occurredAt = Instant.parse("2026-08-30T10:00:05Z"),
            stageName = "Integration",
            message = "flaky-network",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as StageMarkedUnstable
        assertEquals("StageMarkedUnstable", restored.kind)
        assertEquals("Integration", restored.stageName)
        assertEquals("flaky-network", restored.message)
        assertEquals(runId, restored.runId)
    }

    @Test
    fun `ML-R9 T-07 WorkflowLoaded roundtrips correctly`() {
        val runId = "workflow-loaded-run"
        val event = WorkflowLoaded(
            eventId = "wl-1",
            runId = runId,
            sequence = 40L,
            occurredAt = Instant.parse("2026-08-30T10:00:10Z"),
            path = "sub.pipeline.kts",
            stepCount = 3,
            sha256 = "abc123def456",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as WorkflowLoaded
        assertEquals("WorkflowLoaded", restored.kind)
        assertEquals("sub.pipeline.kts", restored.path)
        assertEquals(3, restored.stepCount)
        assertEquals("abc123def456", restored.sha256)
        assertEquals(runId, restored.runId)
    }

    @Test
    fun `ML-R9 T-07 WaitUntilPolled roundtrips correctly`() {
        val runId = "wait-until-run"
        val event = WaitUntilPolled(
            eventId = "wup-1",
            runId = runId,
            sequence = 41L,
            occurredAt = Instant.parse("2026-08-30T10:00:15Z"),
            attempt = 5,
            durationMs = 250L,
            conditionResult = true,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as WaitUntilPolled
        assertEquals("WaitUntilPolled", restored.kind)
        assertEquals(5, restored.attempt)
        assertEquals(250L, restored.durationMs)
        assertEquals(true, restored.conditionResult)
        assertEquals(runId, restored.runId)
    }

    @Test
    fun `ML-R9 T-07 WaitUntilCompleted roundtrips correctly`() {
        val runId = "wait-until-run"
        val event = WaitUntilCompleted(
            eventId = "wuc-1",
            runId = runId,
            sequence = 42L,
            occurredAt = Instant.parse("2026-08-30T10:00:20Z"),
            totalAttempts = 7,
            totalDurationMs = 500L,
            outcome = "completed",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as WaitUntilCompleted
        assertEquals("WaitUntilCompleted", restored.kind)
        assertEquals(7, restored.totalAttempts)
        assertEquals(500L, restored.totalDurationMs)
        assertEquals("completed", restored.outcome)
        assertEquals(runId, restored.runId)
    }

    // === ML-R9 T-05 Dir events (Entered/Exited) ===

    @Test
    fun `ML-R9 T-05 DirEntered roundtrips correctly`() {
        val runId = "dir-entered-run"
        val event = DirEntered(
            eventId = "de-1",
            runId = runId,
            sequence = 45L,
            occurredAt = Instant.parse("2026-08-30T10:00:15Z"),
            path = "build/output",
            previousPath = "/workspace",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as DirEntered
        assertEquals("DirEntered", restored.kind)
        assertEquals("build/output", restored.path)
        assertEquals("/workspace", restored.previousPath)
        assertEquals(runId, restored.runId)
        assertEquals(45L, restored.sequence)
    }

    @Test
    fun `ML-R9 T-05 DirExited roundtrips correctly`() {
        val runId = "dir-exited-run"
        val event = DirExited(
            eventId = "dex-1",
            runId = runId,
            sequence = 46L,
            occurredAt = Instant.parse("2026-08-30T10:00:20Z"),
            path = "build/output",
            restoredTo = "/workspace",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as DirExited
        assertEquals("DirExited", restored.kind)
        assertEquals("build/output", restored.path)
        assertEquals("/workspace", restored.restoredTo)
        assertEquals(runId, restored.runId)
    }

    // === ML-R9 T-10 timeout event ===

    @Test
    fun `ML-R9 T-10 TimeoutTriggered roundtrips correctly`() {
        val runId = "timeout-triggered-run"
        val event = TimeoutTriggered(
            eventId = "tt-1",
            runId = runId,
            sequence = 47L,
            occurredAt = Instant.parse("2026-08-30T10:00:25Z"),
            stageOrStep = "step:sh",
            action = "interrupt",
            durationMs = 30000L,
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as TimeoutTriggered
        assertEquals("TimeoutTriggered", restored.kind)
        assertEquals("step:sh", restored.stageOrStep)
        assertEquals("interrupt", restored.action)
        assertEquals(30000L, restored.durationMs)
        assertEquals(runId, restored.runId)
    }

    // === MILESTONE events ===

    @Test
    fun `ML-R9 T-09 MilestoneReached roundtrips correctly`() {
        val runId = "milestone-run"
        val event = MilestoneReached(
            eventId = "mr-1",
            runId = runId,
            sequence = 48L,
            occurredAt = Instant.parse("2026-08-30T10:00:30Z"),
            ordinal = 5,
            label = "build-milestone",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as MilestoneReached
        assertEquals("MilestoneReached", restored.kind)
        assertEquals(5, restored.ordinal)
        assertEquals("build-milestone", restored.label)
        assertEquals(runId, restored.runId)
    }

    @Test
    fun `ML-R9 T-09 MilestoneAborted roundtrips correctly`() {
        val runId = "milestone-aborted-run"
        val event = MilestoneAborted(
            eventId = "ma-1",
            runId = runId,
            sequence = 49L,
            occurredAt = Instant.parse("2026-08-30T10:00:35Z"),
            ordinal = 3,
            reason = "ordinal-already-reached",
        )
        val encoded = JsonEventLog.encode(listOf(event))
        val decoded = JsonEventLog.decode(encoded)
        assertEquals(1, decoded.size)
        val restored = decoded[0] as MilestoneAborted
        assertEquals("MilestoneAborted", restored.kind)
        assertEquals(3, restored.ordinal)
        assertEquals("ordinal-already-reached", restored.reason)
        assertEquals(runId, restored.runId)
    }

    /**
     * P3 slice 2 — the plugin carrier survives the durable round trip.
     *
     * F2 in `Rp030EventCodecsConnascenceFitnessTest` only proves a branch EXISTS in the source. It
     * cannot prove the branch is right: a branch that decoded every field to a default would also
     * satisfy the scan while turning every plugin event into the same indistinguishable event. So
     * this is the behavioural half, and it asserts the four fields that carry the whole meaning of
     * a contributed event.
     *
     * The second test is the negative half. `decodeEvent` does not default `registryKind` and
     * `schemaVersion` the way it defaults a `reason` or a `path`, and that asymmetry is the point:
     * a substituted kind would re-type this payload as ANOTHER plugin's event. A wrong observation
     * is worse than a dropped one, so a carrier without its identity decodes to nothing.
     */
    @Test
    fun `plugin event carrier round-trips with its identity intact`() {
        val runId = "plugin-run"
        val carrier = PluginEventEmitted(
            eventId = "id-pe-1",
            runId = runId,
            sequence = 4L,
            occurredAt = Instant.parse("2026-10-05T10:00:04Z"),
            registryKind = "acme.validated",
            schemaVersion = 2,
            payload = "v2#{\"ref\":\"abc\"}",
            emittedBy = "acme-plugin",
        )

        val decoded = JsonEventLog.decode(JsonEventLog.encode(listOf(carrier)))

        assertEquals(1, decoded.size, "the carrier must survive write then read, not decode to null")
        val back = decoded[0] as PluginEventEmitted
        assertEquals("PluginEventEmitted", back.kind)
        assertEquals("acme.validated", back.registryKind, "the kind is what the registry re-types by")
        assertEquals(2, back.schemaVersion, "a version that drifted would fail closed on decode")
        assertEquals("v2#{\"ref\":\"abc\"}", back.payload, "the payload is the contributor's own bytes")
        assertEquals("acme-plugin", back.emittedBy)
        assertEquals(4L, back.sequence, "the sequence is identity, and must not be reassigned on read")
    }

    @Test
    fun `a carrier without its schema version is dropped rather than defaulted`() {
        val line = """
            {"eventId":"id-pe-3","runId":"plugin-run","sequence":6,"kind":"PluginEventEmitted",
             "occurredAt":"2026-10-05T10:00:06Z","registryKind":"acme.validated","payload":"v2#x","emittedBy":"acme"}
        """.trimIndent().replace("\n", "")

        val decoded = JsonEventLog.decode(line)

        assertTrue(decoded.isEmpty()) {
            "a carrier with no schemaVersion must not decode: a substituted version would re-type " +
                "this payload under a shape its writer never promised. Decoded: $decoded"
        }
    }

    @Test
    fun `a carrier without its registry kind is dropped rather than re-typed`() {
        val line = """
            {"eventId":"id-pe-2","runId":"plugin-run","sequence":5,"kind":"PluginEventEmitted",
             "occurredAt":"2026-10-05T10:00:05Z","schemaVersion":1,"payload":"v1#x","emittedBy":"acme"}
        """.trimIndent().replace("\n", "")

        val decoded = JsonEventLog.decode(line)

        assertTrue(decoded.isEmpty()) {
            "a carrier with no registryKind must not decode: substituting a kind would re-type " +
                "this payload as another plugin's event. Decoded: $decoded"
        }
    }

    // PK-RC-RENDER-001 — the array is located by structure, not by position.
    //
    // Read DECODE-FIX-CORRECTION.md before reading these. The first version of this block was
    // seven tests that all passed against the ORIGINAL `decode`, which is how a wrong fix almost
    // shipped: they asserted what the codec should do, not what the old codec got wrong.
    //
    // What follows is the honest residue. Only `bracket in the receipt` and `escaped quote`
    // fail against the original code, so only those two are regressions in the strict sense.
    // The others pin behaviour that must survive the change — they would catch a future
    // over-eager scanner, which is the real risk in this helper, and they are marked as
    // guards rather than claimed as proof of the bug.
    private fun runStartedEvent(runId: String = "bounds-run") = RunStarted(
        eventId = "id-1",
        runId = runId,
        sequence = 1L,
        occurredAt = Instant.parse("2026-08-23T10:00:00Z"),
        scriptPath = "test.pipeline.kts",
    )

    /**
     * The regression that is real, copied from the failing case in `CliCompileErrorExitsOneTest`.
     *
     * `pipeline run --format json` writes the receipt line first and the event array after it, and
     * there is no `[PipelineK]` progress line in between, because the machine format streams no
     * live text. The payload therefore does NOT begin with `[`: it begins with the receipt's `{`.
     * Positional slicing stripped that `{` and the last line instead of the array's brackets, so
     * every event was lost and the caller reported `Events must contain RunStarted: []` for a run
     * that had exited 0. This is the case the other tests in this block failed to reproduce.
     */
    @Test
    fun `decode reads the array when the receipt is first and there is no progress line`() {
        val runId = "json-format-run"
        val document = JsonEventLog.encode(
            listOf(
                runStartedEvent(runId),
                EchoOutputCaptured(
                    eventId = "id-2",
                    runId = runId,
                    sequence = 2L,
                    occurredAt = Instant.parse("2026-08-23T10:00:01Z"),
                    stepIndex = 0,
                    content = "payload",
                ),
            ),
        )
        val stdout = "{\"schema\":\"pipelinek.launch/1\",\"runId\":\"$runId\"," +
            "\"correlationId\":\"corr-1\",\"ownerId\":\"cli-inmem-1\"," +
            "\"startedAtMs\":1791794694902,\"status\":\"Launched\"}\n" + document

        assertTrue(stdout.startsWith("{\""), "the payload really does not start with '['")

        val decoded = JsonEventLog.decode(stdout)

        assertEquals(2, decoded.size, "the array after the receipt must decode: got $decoded")
        assertEquals(runId, (decoded.first() as RunStarted).runId)
        assertEquals("EchoOutputCaptured", decoded[1].kind)
    }

    @Test
    fun `decode reads the array after the launch receipt line`() {
        val document = JsonEventLog.encode(
            listOf(
                runStartedEvent(),
                EchoOutputCaptured(
                    eventId = "id-2",
                    runId = "bounds-run",
                    sequence = 2L,
                    occurredAt = Instant.parse("2026-08-23T10:00:01Z"),
                    stepIndex = 0,
                    content = "payload",
                ),
            ),
        )
        // Shape copied from the recorded control run: a receipt line, four progress lines, the
        // event array, then the closing line. Both details are load-bearing and were found the
        // hard way. The trailing line matters because the real stream does not end at `]`, so a
        // fixture that stopped there let the old slicing survive by accident. TWO events matter
        // because with a single event the old code recovered it anyway: the pre-array text rode
        // along inside the one comma-separated segment and `decodeEvent` still read the fields.
        val stdout = "[PipelineK] Run correlated: bounds-run\n" +
            "{\"schema\":\"pipelinek.launch/1\",\"runId\":\"bounds-run\",\"ownerId\":\"cli-inmem-1\"}\n" +
            "[PipelineK] Compiling pipeline\n" +
            "[PipelineK] Compilation finished with 0 diagnostic(s)\n" +
            "Discovered external Step plugins: scm-git, http, junit, utilities\n" +
            document +
            "\nPipeline finished with SUCCESS"

        val decoded = JsonEventLog.decode(stdout)

        assertEquals(2, decoded.size, "the array after the receipt must still decode")
        assertEquals("bounds-run", (decoded.first() as RunStarted).runId)
    }

    @Test
    fun `decode is not confused by a bracket in the receipt text`() {
        val document = JsonEventLog.encode(listOf(runStartedEvent()))
        val stdout = "{\"schema\":\"pipelinek.launch/1\",\"note\":\"[truncated]\"}\n" + document

        val decoded = JsonEventLog.decode(stdout)

        assertEquals(1, decoded.size, "a bracket inside the receipt must not close the array early")
    }

    @Test
    fun `decode is not confused by an escaped quote before the array`() {
        val document = JsonEventLog.encode(listOf(runStartedEvent()))
        val stdout = "{\"schema\":\"pipelinek.launch/1\",\"note\":\"say \\\"hi\\\" [now]\"}\n" + document

        val decoded = JsonEventLog.decode(stdout)

        assertEquals(1, decoded.size, "an escaped quote must not end string tracking")
    }

    @Test
    fun `decode reads an event whose own text contains brackets`() {
        val runId = "brackets-in-event"
        val events = listOf(
            runStartedEvent(runId),
            EchoOutputCaptured(
                eventId = "id-2",
                runId = runId,
                sequence = 2L,
                occurredAt = Instant.parse("2026-08-23T10:00:01Z"),
                stepIndex = 0,
                content = "not an array [ ] { }",
            ),
        )

        val decoded = JsonEventLog.decode(JsonEventLog.encode(events))

        assertEquals(2, decoded.size, "brackets inside an event string must not truncate the document")
        assertEquals("not an array [ ] { }", (decoded[1] as EchoOutputCaptured).content)
    }

    @Test
    fun `decode still returns empty for a payload with no array`() {
        assertTrue(
            JsonEventLog.decode("[PipelineK] Run correlated: x\nno array here").isEmpty(),
            "text without an event array is still no events"
        )
    }

    @Test
    fun `decode still returns empty for an unterminated array`() {
        assertTrue(
            JsonEventLog.decode("""[{"kind":"RunStarted"}""").isEmpty(),
            "an array that never closes must not be read as a partial event list"
        )
    }

    @Test
    fun `decode of an untouched encode is unchanged`() {
        val decoded = JsonEventLog.decode(JsonEventLog.encode(listOf(runStartedEvent())))

        assertEquals(1, decoded.size, "the durable read path must be unaffected")
    }
}
