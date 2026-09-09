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
 * B13 / E-EM-11 timeout laws (canonical spine only, no direct StepSpec execution).
 *
 * Laws under test:
 * 1. body completes before deadline -> Success;
 * 2. deadline expires -> cancellation propagated to the child subprocess
 *    (cookie-scan kill via the certified Sh watchdog seam) -> terminal
 *    timeout semantics (run fails, block does not silently succeed);
 * 3. durable rerun of a completed timeout block does not duplicate execution.
 *
 * The block deadline is projected from the `core.timeout` payload onto the
 * child shOptions (remaining-time budgeting), reusing the certified
 * DurableShellExecutor watchdog (flag-then-kill, FAILED_TIMEOUT).
 */
@Timeout(120)
class UatTimeoutBlockDurableTest {
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

    private fun invocation(tempDir: Path, script: Path) = listOf(
        "run",
        "--db", tempDir.resolve("journal.db").toString(),
        "--control-root", tempDir.resolve("control").toString(),
        script.toString(),
    )

    @Test
    fun `WL-T1 body finishing before deadline succeeds`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-timeout-ok.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("timeout-stage") {
                        timeout(30, "SECONDS") {
                            sh("echo done >> '${marker}'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, result.exitCode, "output:\n${result.output}")
        assertEquals(listOf("done"), markerLines(marker))
    }

    @Test
    fun `WL-T2 deadline expires cancels child process and fails the run`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-timeout-expired.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("timeout-stage") {
                        timeout(2, "SECONDS") {
                            sh("echo started >> '${marker}' && sleep 60")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertTrue(result.exitCode != 0, "a timed-out block must fail the run; output:\n${result.output}")
        assertTrue(
            result.output.contains("timed out") || result.output.contains("TIMEOUT") || result.output.contains("\"kind\":\"StepFailed\""),
            "timeout must surface as terminal timeout semantics; output:\n${result.output}",
        )
        // Child received cancellation: 'started' written, but the sleep was killed
        // (no hang; the run terminated within the @Timeout budget).
        assertEquals(listOf("started"), markerLines(marker))
    }

    @Test
    fun `WL-T3 rerun of a completed timeout block does not duplicate execution`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-timeout-reuse.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("timeout-stage") {
                        timeout(30, "SECONDS") {
                            sh("echo once >> '${marker}'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val inv = invocation(tempDir, script)
        assertEquals(0, runCli(tempDir, inv).exitCode)
        assertEquals(0, runCli(tempDir, inv).exitCode)
        assertEquals(
            listOf("once"),
            markerLines(marker),
            "completed timeout block must be reused on durable rerun",
        )
    }
}
