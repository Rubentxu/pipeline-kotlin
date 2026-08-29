package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Regression test for INC-R8-ARC-001 (StepFailed not emitted on sh non-zero exit)
 * and INC-R8-ARC-002 (duplicate StepFinished on failure path).
 *
 * Tests:
 * (a) sh failure (exit != 0) emits EXACTLY ONE StepFinished
 * (b) sh failure emits StepFailed with failureKind=FailureKind.SCRIPT
 * (c) sh success (exit=0) emits exactly one StepFinished and NO StepFailed
 *
 * @see <a href="spec:step-finished-dedupe-on-failure">step-finished-dedupe-on-failure spec</a>
 * @see <a href="spec:step-sdk-sh">step-sdk-sh spec</a>
 */
@Timeout(120)
class UatStep001ShFailureStepFinishedCountTest {

    private val processes = mutableListOf<Process>()

    @Test
    fun `sh failure emits exactly one StepFinished`() {
        val (exitCode, events) = runPipelineWithFailingSh()

        // (a) StepFinished count for (runId, stepIndex=0) must be exactly 1
        // BEFORE D2 fix: count == 2 (duplicate emission at PipelineRun.kt:647)
        // AFTER D2 fix: count == 1
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()
        assertTrue(stepFinishedEvents.isNotEmpty(), "Must have at least one StepFinished event")

        val stepIndex0Finished = stepFinishedEvents.filter { it.stepIndex == 0 }
        assertEquals(
            1,
            stepIndex0Finished.size,
            "StepFinished count for stepIndex=0 must be exactly 1, got ${stepIndex0Finished.size}. " +
                "Events: ${events.map { it::class.simpleName }}"
        )
    }

    @Test
    fun `sh failure emits StepFailed with failureKind SCRIPT`() {
        val (exitCode, events) = runPipelineWithFailingSh()

        // (b) StepFailed must be emitted on sh non-zero exit
        val stepFailedEvents = events.filterIsInstance<StepFailed>()
        assertTrue(
            stepFailedEvents.any { it.stepIndex == 0 && it.failureKind == dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT },
            "StepFailed with failureKind=SCRIPT must be emitted for sh exit != 0. " +
                "StepFailed events: ${stepFailedEvents.map { "stepIndex=${it.stepIndex}, kind=${it.failureKind}, msg=${it.message}" }}"
        )
    }

    @Test
    fun `sh failure RunFinished outcome is failure`() {
        val (exitCode, events) = runPipelineWithFailingSh()

        // RunFinished.outcome must be "failure"
        val runFinished = events.filterIsInstance<RunFinished>().firstOrNull()
        assertNotNull(runFinished, "RunFinished must be present")
        assertEquals("failure", runFinished!!.outcome, "RunFinished.outcome must be 'failure'")
    }

    @Test
    fun `sh success emits one StepFinished and no StepFailed`() {
        val (exitCode, events) = runPipelineWithSuccessfulSh()

        // (c) sh success emits exactly one StepFinished and NO StepFailed
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()
        val stepFailedEvents = events.filterIsInstance<StepFailed>()

        val stepIndex0Finished = stepFinishedEvents.filter { it.stepIndex == 0 }
        assertEquals(
            1,
            stepIndex0Finished.size,
            "StepFinished count for stepIndex=0 must be exactly 1, got ${stepIndex0Finished.size}"
        )

        assertTrue(
            stepFailedEvents.none { it.stepIndex == 0 },
            "StepFailed must NOT be emitted for successful sh (exit=0). " +
                "StepFailed events: ${stepFailedEvents.map { "stepIndex=${it.stepIndex}, kind=${it.failureKind}" }}"
        )
    }

    private fun runPipelineWithFailingSh(): Pair<Int, List<DomainEvent>> {
        val tempDir = Files.createTempDirectory("pipeline-test")
        val script = tempDir.resolve("failing.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("failing") {
                        sh("exit 42")
                    }
                }
            }
        """.trimIndent())

        return runPipeline(script).let { it.exitCode to it.events }
    }

    private fun runPipelineWithSuccessfulSh(): Pair<Int, List<DomainEvent>> {
        val tempDir = Files.createTempDirectory("pipeline-test")
        val script = tempDir.resolve("success.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("success") {
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent())

        return runPipeline(script).let { it.exitCode to it.events }
    }

    private fun runPipeline(scriptPath: Path): L7Result {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val dbPath = Files.createTempDirectory("pipeline-test-journal").resolve("journal.db").toAbsolutePath()
        val controlRoot = Files.createTempDirectory("pipeline-test-ctrl").toAbsolutePath()

        val args = listOf(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toAbsolutePath().toString()
        )

        val pb = ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        processes.add(process)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(300, TimeUnit.SECONDS)
        val exitCode = if (exited) process.exitValue() else -1
        val combined = if (stderr.isNotBlank()) "$stdout\nSTDERR:\n$stderr" else stdout

        return L7Result(
            stdout = combined,
            exitCode = exitCode,
            events = try { JsonEventLog.decode(stdout) } catch (_: Exception) { emptyList() }
        )
    }

    data class L7Result(
        val stdout: String,
        val exitCode: Int,
        val events: List<DomainEvent>
    )
}
