package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Proves the durable CLI reuses completed work unless the user requests --rerun. */
@Timeout(60)
class UatDurableDefaultReuseCliTest {
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

    @Test
    fun `same durable invocation reuses completed shell and rerun starts it again`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val database = tempDir.resolve("journal.db")
        val controlRoot = tempDir.resolve("control")
        val script = tempDir.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("marker") {
                        sh("echo executed >> '${marker}'")
                    }
                }
            }
            """.trimIndent(),
        )

        val invocation = listOf(
            "run",
            "--db", database.toString(),
            "--control-root", controlRoot.toString(),
            script.toString(),
        )

        assertEquals(0, runCli(tempDir, invocation).exitCode)
        assertEquals(0, runCli(tempDir, invocation).exitCode)
        assertEquals(listOf("executed"), markerLines(marker))

        assertEquals(0, runCli(tempDir, invocation.drop(1).let { listOf("run", "--rerun") + it }).exitCode)
        assertEquals(listOf("executed", "executed"), markerLines(marker))
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "pipeline-cli-", ".log")
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
        processes += process

        val completed = process.waitFor(45, TimeUnit.SECONDS)
        assertEquals(true, completed, "CLI did not finish: ${Files.readString(output)}")
        return CliResult(process.exitValue(), Files.readString(output))
    }

    private fun markerLines(marker: Path): List<String> = Files.readAllLines(marker).filter { it.isNotBlank() }

    private data class CliResult(val exitCode: Int, val output: String)
}
