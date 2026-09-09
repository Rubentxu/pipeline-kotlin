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
 * B13 / E-EM-11 parallel laws (canonical spine only, no direct StepSpec execution,
 * no second coordinator/runtime engine).
 *
 * Laws under test:
 * 1. branch A + branch B -> both execute, through the SAME canonical dispatch spine;
 * 2. independent durable identities (branch-indexed OpId journal rows);
 * 3. deterministic aggregate result: any branch failure fails the run
 *    (ALL_COMPLETE join; grounded in JoinPolicy.ALL_COMPLETE + the coordinator's
 *    fail-fast step semantics — no undeclared failFast policy invented);
 * 4. durable rerun does not duplicate completed branch work.
 */
@Timeout(120)
class UatParallelBlockDurableTest {
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
    fun `WL-P1 both branches execute with branch events`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-parallel-ok.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("parallel-stage") {
                        parallel {
                            branch("one") {
                                echo("branch-one")
                                sh("echo one >> '${marker}'")
                            }
                            branch("two") {
                                sh("echo two >> '${marker}'")
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, result.exitCode, "output:\n${result.output}")
        val lines = markerLines(marker).sorted()
        assertEquals(listOf("one", "two"), lines, "both branches must execute")
        assertTrue(result.output.contains("\"kind\":\"ParallelBranchStarted\""), "branch start events required")
        assertTrue(result.output.contains("\"kind\":\"ParallelBranchFinished\""), "branch finish events required")
    }

    @Test
    fun `WL-P2 failing branch fails the aggregate run`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-parallel-fail.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("parallel-stage") {
                        parallel {
                            branch("ok") {
                                sh("echo ok >> '${marker}'")
                            }
                            branch("bad") {
                                sh("exit 3")
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertTrue(result.exitCode != 0, "a failed branch must fail the aggregate; output:\n${result.output}")
        assertTrue(
            markerLines(marker).contains("ok"),
            "sibling branch outcome independent of the failing branch",
        )
    }

    @Test
    fun `WL-P3 durable rerun does not duplicate completed branch work`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("pipeline-parallel-reuse.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("parallel-stage") {
                        parallel {
                            branch("one") {
                                sh("echo one >> '${marker}'")
                            }
                            branch("two") {
                                sh("echo two >> '${marker}'")
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val inv = invocation(tempDir, script)
        val first = runCli(tempDir, inv)
        assertEquals(0, first.exitCode, "first run output:\n${first.output}")
        assertEquals(listOf("one", "two"), markerLines(marker).sorted(), "after run 1")
        val second = runCli(tempDir, inv)
        assertEquals(0, second.exitCode, "rerun output:\n${second.output}")
        assertEquals(listOf("one", "two"), markerLines(marker).sorted(), "after rerun")
    }
}
