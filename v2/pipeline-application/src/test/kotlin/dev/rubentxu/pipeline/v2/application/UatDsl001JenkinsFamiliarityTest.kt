package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.support.CliContractTestKit
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import java.time.Duration

/**
 * UAT-DSL-001: Jenkins Familiarity — full grammar DSL exercising
 * environment, options, steps, parallel, retry, timeout, script +
 * error/sleep step types. (agent/post/whenCondition are fail-closed:
 * removed or rejected because the compiled path had no interpreter.)
 *
 * This test validates that the full DSL grammar produces a parseable
 * event stream with all M2-R1 event kinds.
 */
@Timeout(120)
class UatDsl001JenkinsFamiliarityTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val grammarFullScript: Path by lazy {
        Paths.get(javaClass.getResource("/grammar-full.pipeline.kts")!!.toURI())
    }

    @Test
    fun `full grammar script compiles and emits parseable JSON`() {
        // S6-PRE: this was `.also { it.waitFor() }` and only then a stdout read, which is the
        // deadlock `fixture12-error-handling` proved — this script emits the same full event log
        // through Main.kt:431, so the pipe filled and the class-level `@Timeout(120)` fired.
        val result = OwnedSubprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", grammarFullScript.toString()),
            timeout = GRAMMAR_DEADLINE,
        )
        assertTrue(result is CliRun.Completed) {
            "the grammar script did not finish within ${GRAMMAR_DEADLINE.seconds}s; pid=" +
                "${(result as? CliRun.TimedOut)?.diagnostics?.pid}. That is an ENVIRONMENT signal, " +
                "not a verdict about the script."
        }

        val stdout = (result as CliRun.Completed).stdout
        val stderr = (result as CliRun.Completed).stderr
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        // PK-NEXT R0.3: contract is decoded via the testkit. Line 1 is
        // the LaunchReceipt, line 2 is the JSON event document. The
        // "starts with '['" / "ends with ']'" assertions are wrong
        // against this contract and the testkit replaces them.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = stderr,
            format = ObservationFormat.JSON,
        )
        assertNull(contract.eventDocument.decodeError) {
            "the JSON event document failed to decode: ${contract.eventDocument.decodeError}"
        }
        val events = contract.eventDocument.events
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

        // Z1 decision (E-EM-11): AgentResolved is NOT asserted here. It remains a supported
        // DomainEvent/schema variant for compatibility and future real agent allocation
        // (requested constraints -> resolver/scheduler -> resolved target -> AgentResolved).
        // Configured agent metadata in the DSL is NOT a resolution transition; emitting it
        // from stage metadata would fabricate a transition that did not occur.
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

        // catchError is the DSL wrapper for error() — catchError step type confirms error-handling is exercised
        assertTrue(stepTypes.contains("catchError") || stepTypes.contains("error"),
            "Must have error-handling step type (catchError or error): $stepTypes")
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
        // S6-PRE: same migration as the grammar test. This helper read stderr ONLY on the failure
        // path, so a run that failed loudly was the one most likely to hang on a full stdout pipe.
        val result = OwnedSubprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", script.toString()),
            timeout = GRAMMAR_DEADLINE,
        )
        assertTrue(result is CliRun.Completed) {
            "the CLI did not finish within ${GRAMMAR_DEADLINE.seconds}s; pid=" +
                "${(result as? CliRun.TimedOut)?.diagnostics?.pid}. That is an ENVIRONMENT signal, " +
                "not a verdict about the script."
        }
        val completed = result as CliRun.Completed
        if (completed.exitCode != 0) {
            throw IllegalStateException("CLI exited with ${completed.exitCode}. stderr: ${completed.stderr}")
        }
        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = completed.stdout,
            stderr = completed.stderr,
            format = ObservationFormat.JSON,
        )
        if (contract.eventDocument.decodeError != null) {
            throw IllegalStateException(
                "JSON event document failed to decode: ${contract.eventDocument.decodeError}",
            )
        }
        val events = contract.eventDocument.events
        return completed.stdout.trim() to events
    }

    /**
     * S6-PRE: the SUBPROCESS's contract, separate from the class-level `@Timeout(120)`. A grammar
     * run takes seconds on an idle box, so 120 s is a wide margin that cannot be tripped by load.
     */
    private companion object {
        val GRAMMAR_DEADLINE: Duration = Duration.ofSeconds(120)
    }
}
