package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-DSL-005: Timeout Grammar — retry and timeout configuration test.
 *
 * Exercises the retry(count, delaySeconds) and timeout(seconds) DSL constructs
 * and validates that RetryAttemptStarted/RetryAttemptFinished and
 * TimeoutScheduled events are emitted.
 */
class UatDsl005TimeoutGrammarTest {

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

    private val timeoutRetryScript: Path by lazy {
        Paths.get(javaClass.getResource("/timeout-retry.pipeline.kts")!!.toURI())
    }

    @Test
    fun `timeout-retry script compiles and emits parseable JSON`() {
        val result = ProcessBuilder(appBin.toString(), "run", timeoutRetryScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
            .also { it.waitFor() }

        val stdout = result.inputStream.bufferedReader().readText().trim()
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        assertTrue(stdout.startsWith("["), "stdout must start with '['")
        assertTrue(stdout.endsWith("]"), "stdout must end with ']'")

        val events = JsonEventLog.decode(stdout)
        assertNotNull(events)
    }

    @Test
    fun `timeout-retry script emits retry attempt events`() {
        val (_, events) = runAndDecode()

        // Verify retry attempt events are emitted
        val retryStartedEvents = events.filter { it is RetryAttemptStarted }
        val retryFinishedEvents = events.filter { it is RetryAttemptFinished }

        assertTrue(retryStartedEvents.isNotEmpty(), "Must have RetryAttemptStarted events: $events")
        assertTrue(retryFinishedEvents.isNotEmpty(), "Must have RetryAttemptFinished events: $events")

        // Verify retry event structure
        val ras = retryStartedEvents.first() as RetryAttemptStarted
        assertTrue(ras.attemptNumber >= 1, "attemptNumber must be >= 1")
        assertTrue(ras.maxAttempts >= 1, "maxAttempts must be >= 1")
        assertEquals(ras.maxAttempts, (retryFinishedEvents.first() as RetryAttemptFinished).maxAttempts)
    }

    @Test
    fun `timeout-retry script emits timeout scheduled events`() {
        val (_, events) = runAndDecode()

        val timeoutEvents = events.filter { it is TimeoutScheduled }
        assertTrue(timeoutEvents.isNotEmpty(), "Must have TimeoutScheduled events: $events")

        val ts = timeoutEvents.first() as TimeoutScheduled
        assertTrue(ts.timeoutSeconds > 0, "timeoutSeconds must be positive")
        assertTrue(ts.timeoutAction.isNotEmpty(), "timeoutAction must not be empty")
    }

    @Test
    fun `timeout-retry script produces complete event timeline`() {
        val (_, events) = runAndDecode()

        // Verify RunStarted
        assertTrue(events.first() is RunStarted, "First event must be RunStarted")
        // Verify RunFinished
        assertTrue(events.last() is RunFinished, "Last event must be RunFinished")

        // Verify we have stage events (3 stages: RetryTest, TimeoutTest, ErrorHandling)
        val stageStartedEvents = events.filter { it is StageStarted }
        assertTrue(stageStartedEvents.size >= 3, "Must have at least 3 StageStarted events: ${stageStartedEvents.size}")

        // Verify we have step events
        assertTrue(events.any { it is StepStarted }, "Must have StepStarted event")
        assertTrue(events.any { it is StepFinished }, "Must have StepFinished event")

        // Verify compilation events
        assertTrue(events.any { it is CompilationStarted }, "Must have CompilationStarted event")
        assertTrue(events.any { it is CompilationFinished }, "Must have CompilationFinished event")
    }

    @Test
    fun `error step type is emitted`() {
        val (_, events) = runAndDecode()

        val stepStartedEvents = events.filter { it is StepStarted }
        val stepTypes = stepStartedEvents.map { (it as StepStarted).stepType }.distinct()

        assertTrue(stepTypes.contains("error"), "Must have error step type: $stepTypes")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", timeoutRetryScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("CLI exited with $exitCode. stderr: $stderr")
        }
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
