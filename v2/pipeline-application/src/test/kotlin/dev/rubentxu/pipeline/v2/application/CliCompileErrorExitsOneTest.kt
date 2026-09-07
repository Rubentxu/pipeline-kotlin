package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Regression test for INC-021: CLI exits non-zero on Kotlin script compilation failure.
 *
 * Verifies that `pipeline run` and `pipeline validate` correctly report FAILURE
 * when the script fails to compile, rather than exiting 0 with "SUCCESS".
 *
 * Uses the existing `99-broken-compilation.pipeline.kts` fixture.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CliCompileErrorExitsOneTest {

    private fun brokenFixture(): Path {
        val userDir = java.io.File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { java.io.File(it, "v2/pipeline-application/src/test/resources/broken") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate broken/ test resources via directory walk")
        val fixture = candidate.resolve("99-broken-compilation.pipeline.kts")
        assertTrue(fixture.isFile) { "Broken fixture not found: $fixture" }
        return fixture.toPath()
    }

    /**
     * `pipeline run` with broken compilation exits 1 and prints FAILURE.
     */
    @Test
    fun `run exits one with FAILURE on broken compilation`() {
        val appBin = AppBinSupport.discover()
        val fixture = brokenFixture()

        val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        // Exit code must be 1
        assertEquals(1, exitCode, "run with broken compilation must exit 1, but got $exitCode. stderr: $stderr")

        // stderr must contain FAILURE (not SUCCESS)
        assertTrue(stderr.contains("Pipeline finished with FAILURE"),
            "stderr must contain 'Pipeline finished with FAILURE', got: $stderr")

        // stdout must be valid JSON events
        assertTrue(stdout.startsWith("["), "stdout must be JSON array, got: ${stdout.take(100)}")
        assertTrue(stdout.endsWith("]"), "stdout must end with ']'")

        val events = JsonEventLog.decode(stdout)

        // Must have CompilationStarted and CompilationFinished
        val eventKinds = events.map { it.kind }
        assertTrue(eventKinds.contains("CompilationStarted"), "Events must contain CompilationStarted: $eventKinds")
        assertTrue(eventKinds.contains("CompilationFinished"), "Events must contain CompilationFinished: $eventKinds")

        // Must NOT have RunStarted, StepStarted, StepFinished (no execution happened)
        assertFalse(eventKinds.contains("RunStarted"), "Must NOT have RunStarted (no execution): $eventKinds")
        assertFalse(eventKinds.contains("StepStarted"), "Must NOT have StepStarted (no execution): $eventKinds")
        assertFalse(eventKinds.contains("StepFinished"), "Must NOT have StepFinished (no execution): $eventKinds")

        // CompilationFinished must have ERROR diagnostics
        val compilationFinished = events.filterIsInstance<CompilationFinished>().firstOrNull()
        assertTrue(compilationFinished != null, "CompilationFinished event must exist")
        assertTrue(compilationFinished!!.diagnostics.isNotEmpty(), "CompilationFinished must have diagnostics")
        val errors = compilationFinished.diagnostics.filter { it.severity == ScriptDiagnosticSeverity.ERROR }
        assertTrue(errors.isNotEmpty(), "Must have at least one ERROR diagnostic, got: ${compilationFinished.diagnostics}")
    }

    /**
     * `pipeline validate` with broken compilation exits 1 and prints VALIDATION FAILED.
     */
    @Test
    fun `validate exits one with VALIDATION FAILED on broken compilation`() {
        val appBin = AppBinSupport.discover()
        val fixture = brokenFixture()

        val pb = ProcessBuilder(appBin.toString(), "validate", fixture.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        // Exit code must be 1
        assertEquals(1, exitCode, "validate with broken compilation must exit 1, but got $exitCode. stderr: $stderr")

        // stderr must contain VALIDATION FAILED
        assertTrue(stderr.contains("VALIDATION FAILED"),
            "stderr must contain 'VALIDATION FAILED', got: $stderr")
    }

    /**
     * `pipeline run` with valid script (01-basic) exits 0 and prints SUCCESS.
     * Verifies the fix does not break the happy path.
     */
    @Test
    fun `run exits zero with SUCCESS on valid hello script`() {
        val appBin = AppBinSupport.discover()

        // Find 01-basic.pipeline.kts
        val userDir = java.io.File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { java.io.File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk")
        val fixture = candidate.resolve("01-basic.pipeline.kts").toPath()

        val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        // Valid script should exit 0
        assertEquals(0, exitCode, "Valid script must exit 0, but got $exitCode. stderr: $stderr")

        // stderr should contain SUCCESS
        assertTrue(stderr.contains("Pipeline finished with SUCCESS"),
            "stderr must contain 'Pipeline finished with SUCCESS', got: $stderr")

        // stdout should have valid events including RunStarted and RunFinished
        val events = JsonEventLog.decode(stdout)
        val eventKinds = events.map { it.kind }
        assertTrue(eventKinds.contains("RunStarted"), "Events must contain RunStarted: $eventKinds")
        assertTrue(eventKinds.contains("RunFinished"), "Events must contain RunFinished: $eventKinds")
    }
}
