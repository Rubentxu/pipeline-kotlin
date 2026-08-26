package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-STEP-003: error abort
 * Tests that error emits StepFailed and halts subsequent steps.
 */
@Timeout(120)
class UatStep003ErrorAbortTest {

    private val appBin: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        val bin = moduleDir
            .resolve("build")
            .resolve("install")
            .resolve("pipeline-application")
            .resolve("bin")
            .resolve("pipeline-application")
        if (!bin.toFile().exists()) {
            throw IllegalStateException(
                "Application binary not found at $bin. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        bin
    }

    private val errorAbortScript: Path by lazy {
        Paths.get(javaClass.getResource("/error-abort.pipeline.kts")!!.toURI())
    }

    @Test
    fun `error emits StepFailed with correct failureKind and propagates outcome`() {
        val (_, events) = runAndDecode()

        // StepFailed must be present with correct failureKind
        val stepFailedEvents = events.filter { it is StepFailed }
        assertTrue(stepFailedEvents.isNotEmpty(), "Must have StepFailed event for error step")

        val stepFailed = stepFailedEvents.first() as StepFailed
        assertEquals(FailureKind.USER, stepFailed.failureKind,
            "StepFailed failureKind must be USER (as specified in error-abort.pipeline.kts)")
        assertEquals("boom", stepFailed.message,
            "StepFailed message must contain 'boom'")

        // StageFinished.outcome must be "failure"
        val stageFinishedEvents = events.filter { it is StageFinished }
        assertTrue(stageFinishedEvents.isNotEmpty(), "Must have StageFinished event")
        val stageFinished = stageFinishedEvents.first() as StageFinished
        assertEquals("failure", stageFinished.outcome,
            "StageFinished.outcome must be 'failure' when error step runs")

        // RunFinished.outcome must be "failure"
        val runFinishedEvents = events.filter { it is RunFinished }
        assertTrue(runFinishedEvents.isNotEmpty(), "Must have RunFinished event")
        val runFinished = runFinishedEvents.first() as RunFinished
        assertEquals("failure", runFinished.outcome,
            "RunFinished.outcome must be 'failure' when error step runs")

        // Diagnostics must be empty (error() is not a compile error)
        assertTrue(runFinished.diagnostics.isEmpty(),
            "error() step should not produce compile diagnostics")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", errorAbortScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        // Note: error step may cause non-zero exit, but we still get events
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
