package com.pipeline.v2.application

import com.pipeline.v2.events.AgentResolved
import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.CompilationStarted
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.ParallelBranchFinished
import com.pipeline.v2.events.ParallelBranchStarted
import com.pipeline.v2.events.RetryAttemptFinished
import com.pipeline.v2.events.RetryAttemptStarted
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.events.StageStarted
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StepStarted
import com.pipeline.v2.events.StepFinished
import com.pipeline.v2.events.TimeoutScheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-DSL-001: Jenkins Familiarity — full grammar DSL exercising
 * agent, environment, options, post, steps, parallel, retry, timeout,
 * whenCondition, script + error/sleep step types.
 *
 * This test validates that the full DSL grammar produces a parseable
 * event stream with all M2-R1 event kinds.
 */
class UatDsl001JenkinsFamiliarityTest {

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

    private val grammarFullScript: Path by lazy {
        Paths.get(javaClass.getResource("/grammar-full.pipeline.kts")!!.toURI())
    }

    @Test
    fun `full grammar script compiles and emits parseable JSON`() {
        val result = ProcessBuilder(appBin.toString(), "run", grammarFullScript.toString())
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
        assertTrue(events.isNotEmpty(), "events must not be empty")
    }

    @Test
    fun `full grammar script emits expected event kinds`() {
        val (stdout, events) = runAndDecode()

        assertTrue(events.isNotEmpty(), "Expected non-empty events from grammar-full fixture")

        // Verify we have RunStarted and RunFinished
        assertTrue(events.any { it is RunStarted }, "Must have RunStarted event")
        assertTrue(events.any { it is RunFinished }, "Must have RunFinished event")

        // Verify we have Stage events
        assertTrue(events.any { it is StageStarted }, "Must have StageStarted event")
        assertTrue(events.any { it is StageFinished }, "Must have StageFinished event")

        // Verify we have Step events
        assertTrue(events.any { it is StepStarted }, "Must have StepStarted event")
        assertTrue(events.any { it is StepFinished }, "Must have StepFinished event")

        // Verify Compilation events
        assertTrue(events.any { it is CompilationStarted }, "Must have CompilationStarted event")
        assertTrue(events.any { it is CompilationFinished }, "Must have CompilationFinished event")

        // Verify new M2-R1 event kinds
        assertTrue(events.any { it is AgentResolved }, "Must have AgentResolved event")
        assertTrue(events.any { it is ParallelBranchStarted }, "Must have ParallelBranchStarted event")
        assertTrue(events.any { it is ParallelBranchFinished }, "Must have ParallelBranchFinished event")
        assertTrue(events.any { it is RetryAttemptStarted }, "Must have RetryAttemptStarted event")
        assertTrue(events.any { it is RetryAttemptFinished }, "Must have RetryAttemptFinished event")
        assertTrue(events.any { it is TimeoutScheduled }, "Must have TimeoutScheduled event")
    }

    @Test
    fun `full grammar script contains error and sleep step types`() {
        val (_, events) = runAndDecode()

        val stepStartedEvents = events.filter { it is StepStarted }
        val stepTypes = stepStartedEvents.map { (it as StepStarted).stepType }.distinct()

        assertTrue(stepTypes.contains("error"), "Must have error step type: $stepTypes")
        assertTrue(stepTypes.contains("sleep"), "Must have sleep step type: $stepTypes")
    }

    @Test
    fun `mutating fixture yields updated event timeline`() {
        val helloScript: Path = Paths.get(javaClass.getResource("/hello.pipeline.kts")!!.toURI())

        // Run first script with stage name "hello"
        val (_, eventsBefore) = runAndDecode(helloScript)
        val jsonBefore = JsonEventLog.encode(eventsBefore)

        // Verify the first fixture has expected stage name "hello"
        assertTrue(eventsBefore.any { it is StageStarted && it.stageName == "hello" },
            "First fixture must have stage named 'hello'")

        // Create a second script with a different stage name "Renamed"
        val renamedScript = java.nio.file.Files.createTempFile("renamed-stage", ".pipeline.kts")
        try {
            renamedScript.toFile().writeText("""
                pipeline {
                    stages {
                        stage("Renamed") {
                            echo("hello from renamed stage")
                        }
                    }
                }
            """.trimIndent())

            // Run second script with different stage name
            val (_, eventsAfter) = runAndDecode(renamedScript)
            val jsonAfter = JsonEventLog.encode(eventsAfter)

            // Hard assertions per SUG-002
            assertTrue(eventsAfter.any { it is StageStarted && it.stageName == "Renamed" },
                "Second fixture must have stage named 'Renamed'")

            // Same shape (same number of events) but different content
            assertEquals(eventsBefore.size, eventsAfter.size,
                "Event timeline shapes must match")
            assertNotEquals(eventsBefore, eventsAfter,
                "Different script content must produce different event timeline")
            assertNotEquals(jsonBefore, jsonAfter,
                "Different script content must produce different JSON serialization")
        } finally {
            java.nio.file.Files.deleteIfExists(renamedScript)
        }
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        return runAndDecode(grammarFullScript)
    }

    private fun runAndDecode(script: Path): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", script.toString())
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
