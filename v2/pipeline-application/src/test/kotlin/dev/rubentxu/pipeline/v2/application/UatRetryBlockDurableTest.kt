package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * B13 / E-EM-11 retry laws (canonical spine only, no direct StepSpec execution).
 *
 * Laws under test:
 * 1. body attempt 1 fails -> recorded -> retry per contract -> body attempt 2 executes;
 * 2. body success -> no extra attempt;
 * 3. restart/replay -> completed attempts are not duplicated (deterministic
 *    attempt identity via the retry BlockSegment in the journal bodyPath).
 */
@Timeout(120)
class UatRetryBlockDurableTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun terminateProcesses() {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    private fun script(tempDir: Path, marker: Path, failFirst: Boolean, retries: Int = 2): Path {
        val script = tempDir.resolve("pipeline-retry.kts")
        val guard = if (failFirst) {
            // Attempt 1: marker absent -> fail and create marker. Attempt 2: marker present -> succeed.
            """
            if [ -f '${marker}' ]; then echo retry-ok >> '${marker}'; else echo attempt-1 >> '${marker}' && touch '${marker}' && exit 1; fi
            """.trimIndent()
        } else {
            "echo once >> '${marker}'"
        }
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("retry-stage") {
                        retry($retries) {
                            sh(""" + "\"\"\"" + guard + "\"\"\"" + """)
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        return script
    }

    @Test
    fun `WL-R1 failing body is retried and second attempt succeeds`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = script(tempDir, marker, failFirst = true)

        val invocation = listOf(
            "run",
            "--db", tempDir.resolve("journal.db").toString(),
            "--control-root", tempDir.resolve("control").toString(),
            script.toString(),
        )
        val result = runCli(tempDir, invocation)
        assertEquals(0, result.exitCode, "retry block must succeed once a body attempt succeeds; output:\n${result.output}")
        val lines = markerLines(marker)
        assertTrue(
            lines.contains("attempt-1") && lines.contains("retry-ok"),
            "expected one failed attempt then one successful attempt, got: $lines",
        )
    }

    @Test
    fun `WL-R2 successful body executes exactly one attempt`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = script(tempDir, marker, failFirst = false)

        val invocation = listOf(
            "run",
            "--db", tempDir.resolve("journal.db").toString(),
            "--control-root", tempDir.resolve("control").toString(),
            script.toString(),
        )
        val result = runCli(tempDir, invocation)
        assertEquals(0, result.exitCode, "output:\n${result.output}")
        assertEquals(listOf("once"), markerLines(marker), "a successful body must not be re-attempted")
    }

    @Test
    fun `WL-R3 rerun of a completed retry block does not duplicate attempts`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = script(tempDir, marker, failFirst = false)

        val invocation = listOf(
            "run",
            "--db", tempDir.resolve("journal.db").toString(),
            "--control-root", tempDir.resolve("control").toString(),
            script.toString(),
        )
        assertEquals(0, runCli(tempDir, invocation).exitCode)
        // Default (no --rerun) durable reuse: completed attempts must NOT re-execute.
        assertEquals(0, runCli(tempDir, invocation).exitCode)
        assertEquals(
            listOf("once"),
            markerLines(marker),
            "completed retry block must be reused on durable rerun (no attempt duplication)",
        )
    }

    // ---- helpers mirroring UatDurableDefaultReuseCliTest ----

    private fun runCli(workdir: Path, args: List<String>): CliResult {
        val output = Files.createTempFile(workdir, "pipeline-cli-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        processes.add(process)
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val text = Files.readString(output)
        if (!finished) error("CLI did not finish: $text")
        return CliResult(process.exitValue(), text)
    }

    private data class CliResult(val exitCode: Int, val output: String)

    private fun markerLines(marker: Path): List<String> =
        if (Files.exists(marker)) Files.readAllLines(marker).filter { it.isNotBlank() } else emptyList()
}
