package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * DEBT-CLI-SCRIPT-NOT-FOUND: the CLI must reject a missing script with a typed,
 * actionable diagnostic instead of a raw `NoSuchFileException` stacktrace.
 *
 * The rejection is fail-closed at the CLI boundary and happens BEFORE any store,
 * journal or process is created, so no durable state is written for a run that
 * could never start. Exit code 2 matches the other input/usage rejections
 * (e.g. an invalid `--control-root`).
 */
@Timeout(120)
class CliMissingScriptRejectionTest {

    @Test
    fun `run rejects a missing script with a typed diagnostic and no durable state`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("absent.pipeline.kts")
        val database = tempDir.resolve("journal.db")
        val controlRoot = tempDir.resolve("control")

        val result = runCli(
            tempDir,
            listOf(
                "run",
                "--db", database.toString(),
                "--control-root", controlRoot.toString(),
                missing.toString(),
            ),
        )

        assertEquals(2, result.exitCode, "missing script is an input rejection (exit 2). Output:\n${result.output}")
        assertTrue(
            "not found or not readable" in result.output,
            "the diagnostic must name the cause. Output:\n${result.output}",
        )
        assertTrue(
            missing.toAbsolutePath().toString() in result.output,
            "the diagnostic must carry the resolved path. Output:\n${result.output}",
        )
        assertFalse(
            "Exception" in result.output || "\tat " in result.output,
            "no raw stacktrace may reach the user. Output:\n${result.output}",
        )
        assertFalse(
            Files.exists(database),
            "fail-closed: no durable database may be created for a run that never started",
        )
    }

    @Test
    fun `validate rejects a missing script with a typed diagnostic`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("absent.pipeline.kts")

        val result = runCli(tempDir, listOf("validate", missing.toString()))

        assertEquals(2, result.exitCode, "missing script is an input rejection (exit 2). Output:\n${result.output}")
        assertTrue(
            "not found or not readable" in result.output,
            "the diagnostic must name the cause. Output:\n${result.output}",
        )
        assertFalse(
            "Exception" in result.output || "\tat " in result.output,
            "no raw stacktrace may reach the user. Output:\n${result.output}",
        )
    }

    @Test
    fun `a directory in place of a script is rejected, not read as a script`(@TempDir tempDir: Path) {
        val asDirectory = Files.createDirectories(tempDir.resolve("looks-like.pipeline.kts"))

        val result = runCli(tempDir, listOf("run", asDirectory.toString()))

        assertEquals(2, result.exitCode, "a directory is not a pipeline script (exit 2). Output:\n${result.output}")
        assertTrue(
            "not found or not readable" in result.output,
            "the diagnostic must name the cause. Output:\n${result.output}",
        )
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "pipeline-missing-script-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *arguments.toTypedArray(),
        )
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        val text = Files.readString(output)
        assertTrue(completed, "CLI did not finish within 60s: $text")
        process.destroyForcibly()
        return CliResult(process.exitValue(), text)
    }

    private data class CliResult(val exitCode: Int, val output: String)
}
