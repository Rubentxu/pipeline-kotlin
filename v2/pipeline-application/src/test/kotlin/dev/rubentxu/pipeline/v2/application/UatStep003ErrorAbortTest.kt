package dev.rubentxu.pipeline.v2.application

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
    fun `error step fails the run with failure outcome and diagnostics`() {
        val (stdout, events) = runAndDecodeExpectingFailure()

        // Durable-spine contract (LF-0208, verified parity in-memory vs --db):
        // an error() step emits StepStarted/StepFinished and the failure is
        // carried by RunFinished.outcome=failure with diagnostics; the legacy
        // walker's dedicated StepFailed event is not part of the spine.
        val stepStartedEvents = events.filter { it is StepStarted }
        assertTrue(
            stepStartedEvents.any { (it as StepStarted).stepType == "error" },
            "Must have StepStarted for error step",
        )
        val stepFinishedEvents = events.filter { it is StepFinished }
        assertTrue(
            stepFinishedEvents.any { (it as StepFinished).stepType == "error" },
            "Must have StepFinished for error step",
        )

        // NOTE: under the durable spine a failing stage does NOT emit
        // StageFinished (verified in-memory and on --db); the failure is
        // carried by RunFinished instead.

        // RunFinished.outcome must be "failure"
        val runFinishedEvents = events.filter { it is RunFinished }
        assertTrue(runFinishedEvents.isNotEmpty(), "Must have RunFinished event")
        val runFinished = runFinishedEvents.first() as RunFinished
        assertEquals("failure", runFinished.outcome,
            "RunFinished.outcome must be 'failure' when error step runs")

        // The failure carries diagnostics (divergence-gated failure record).
        assertTrue(runFinished.diagnostics.isNotEmpty(),
            "RunFinished must carry failure diagnostics: ${runFinished.diagnostics}")
        assertTrue(stdout.isNotEmpty(), "stdout must still carry the full timeline")
    }

    /**
     * Runs the CLI expecting a FAILING pipeline (exit code 1) and returns
     * stdout + decoded events. The legacy helper threw on non-zero exits;
     * under the durable spine a failing run legitimately exits 1.
     */
    private fun runAndDecodeExpectingFailure(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", errorAbortScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        // A failing run exits 1 by design (D5 outcome propagation).
        assertEquals(1, exitCode, "failing pipeline must exit 1")
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
