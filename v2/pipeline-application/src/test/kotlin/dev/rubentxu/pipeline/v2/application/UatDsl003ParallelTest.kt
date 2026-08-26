package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-DSL-003: Parallel — parallel branch execution grammar test.
 *
 * Exercises the parallel { branch("name") { ... } } DSL construct
 * and validates that ParallelBranchStarted/ParallelBranchFinished events
 * are emitted for each branch.
 */
@Timeout(120)
class UatDsl003ParallelTest {

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

    private val parallelScript: Path by lazy {
        Paths.get(javaClass.getResource("/parallel.pipeline.kts")!!.toURI())
    }

    @Test
    fun `parallel script compiles and emits parseable JSON`() {
        val result = ProcessBuilder(appBin.toString(), "run", parallelScript.toString())
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
    fun `parallel script emits parallel branch events`() {
        val (_, events) = runAndDecode()

        // Should have exactly 3 parallel branches
        val branchStartedEvents = events.filter { it is ParallelBranchStarted }
        val branchFinishedEvents = events.filter { it is ParallelBranchFinished }

        assertEquals(3, branchStartedEvents.size, "Expected 3 ParallelBranchStarted events: $events")
        assertEquals(3, branchFinishedEvents.size, "Expected 3 ParallelBranchFinished events: $events")

        // Verify branch names
        val branchNames = branchStartedEvents.map { (it as ParallelBranchStarted).branchName }
        assertTrue(branchNames.contains("branch-a"), "Must have branch-a: $branchNames")
        assertTrue(branchNames.contains("branch-b"), "Must have branch-b: $branchNames")
        assertTrue(branchNames.contains("branch-c"), "Must have branch-c: $branchNames")
    }

    @Test
    fun `parallel branches have correct parent stage index`() {
        val (_, events) = runAndDecode()

        val branchStartedEvents = events.filter { it is ParallelBranchStarted }
        for (event in branchStartedEvents) {
            val pbs = event as ParallelBranchStarted
            assertTrue(pbs.branchIndex >= 0, "branchIndex must be non-negative")
            assertTrue(pbs.parentStageIndex >= 0, "parentStageIndex must be non-negative")
        }
    }

    @Test
    fun `parallel script produces complete event timeline`() {
        val (_, events) = runAndDecode()

        // Verify RunStarted
        assertTrue(events.first() is RunStarted, "First event must be RunStarted")
        // Verify RunFinished
        assertTrue(events.last() is RunFinished, "Last event must be RunFinished")

        // Verify we have stage events
        assertTrue(events.any { it is StageStarted }, "Must have StageStarted event")
        assertTrue(events.any { it is StageFinished }, "Must have StageFinished event")

        // Verify we have step events
        assertTrue(events.any { it is StepStarted }, "Must have StepStarted event")
        assertTrue(events.any { it is StepFinished }, "Must have StepFinished event")

        // Verify compilation events
        assertTrue(events.any { it is CompilationStarted }, "Must have CompilationStarted event")
        assertTrue(events.any { it is CompilationFinished }, "Must have CompilationFinished event")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", parallelScript.toString())
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
