package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class CorpusNormalizerTest {

    @Test
    fun normalizeEventStripsNonDeterministicFields() {
        val runStarted = RunStarted(
            eventId = "random-id-1",
            runId = "random-run-id",
            occurredAt = Instant.now(),
            sequence = 0L,
            scriptPath = "test.pipeline.kts",
        )

        val normalized = CorpusNormalizer.normalizeEvent(runStarted)
        assertEquals("RunStarted", normalized["kind"])
        assertEquals(null, normalized["eventId"])
        assertEquals(null, normalized["runId"])
        assertEquals(null, normalized["occurredAt"])
    }

    @Test
    fun normalizeEventPreservesStructuralFields() {
        val stageStarted = StageStarted(
            eventId = "random-id-2",
            runId = "random-run-id",
            occurredAt = Instant.now(),
            sequence = 1L,
            stageIndex = 0,
            stageName = "build",
        )

        val normalized = CorpusNormalizer.normalizeEvent(stageStarted)
        assertEquals("StageStarted", normalized["kind"])
        assertEquals("build", normalized["stageName"])
        assertEquals(0, normalized["stageIndex"])
    }

    @Test
    fun normalizePreservesEchoOutputCapturedContent() {
        val echo = EchoOutputCaptured(
            eventId = "random-id",
            runId = "random-run",
            occurredAt = Instant.now(),
            sequence = 1L,
            stepIndex = 0,
            content = "hello world\n",
        )

        val normalized = CorpusNormalizer.normalizeEvent(echo)
        assertEquals("EchoOutputCaptured", normalized["kind"])
        assertEquals("hello world\n", normalized["content"])
    }

    @Test
    fun normalizeDiagnosticStripsStackTraces() {
        val diag = ScriptingDiagnostic(
            severity = ScriptDiagnosticSeverity.ERROR,
            message = "Type error",
            line = 10,
            column = 5,
            path = "test.pipeline.kts",
        )

        val normalized = CorpusNormalizer.normalizeDiagnostic(diag)
        assertEquals("ERROR", normalized["severity"])
        assertEquals("Type error", normalized["message"])
        assertEquals("test.pipeline.kts", normalized["path"])
    }
}
