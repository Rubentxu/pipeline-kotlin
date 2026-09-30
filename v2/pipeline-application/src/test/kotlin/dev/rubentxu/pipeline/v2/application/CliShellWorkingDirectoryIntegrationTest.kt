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

/** Exercises the public CLI process boundary for shell working-directory selection. */
@Timeout(90)
class CliShellWorkingDirectoryIntegrationTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun terminateProcesses() {
        processes.forEach { process ->
            val descendants = process.descendants().toList()
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            descendants.forEach { descendant ->
                runCatching { descendant.onExit().get(5, TimeUnit.SECONDS) }
            }
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    @Test
    fun `default shell uses invocation directory when workspace is omitted`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val observedDirectory = tempDir.resolve("default-pwd.txt")
        val script = writePwdPipeline(invocationDirectory, observedDirectory)

        val result = runCli(
            invocationDirectory,
            listOf(
                "run",
                "--db", tempDir.resolve("default.db").toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(observedDirectory), "the shell command did not write its observed CWD")
        assertEquals(invocationDirectory.toRealPath().toString(), Files.readString(observedDirectory).trim())
    }

    @Test
    fun `default shell uses invocation directory in in-memory CLI mode`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation-in-memory"))
        val observedDirectory = tempDir.resolve("in-memory-pwd.txt")
        val script = writePwdPipeline(invocationDirectory, observedDirectory)

        val result = runCli(invocationDirectory, listOf("run", script.toString()))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(observedDirectory), "the shell command did not write its observed CWD")
        assertEquals(invocationDirectory.toRealPath().toString(), Files.readString(observedDirectory).trim())
    }

    @Test
    fun `explicit workspace overrides invocation directory for shell execution`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val observedDirectory = tempDir.resolve("explicit-pwd.txt")
        val script = writePwdPipeline(invocationDirectory, observedDirectory)

        val result = runCli(
            invocationDirectory,
            listOf(
                "run",
                "--db", tempDir.resolve("explicit.db").toString(),
                "--control-root", controlRoot.toString(),
                "--workspace", workspace.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(observedDirectory), "the shell command did not write its observed CWD")
        assertEquals(workspace.toRealPath().toString(), Files.readString(observedDirectory).trim())
    }

    private fun writePwdPipeline(invocationDirectory: Path, observedDirectory: Path): Path {
        val script = invocationDirectory.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("working-directory") {
                        sh("pwd > '${observedDirectory}'")
                    }
                }
            }
            """.trimIndent(),
        )
        return script
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "pipeline-cwd-cli-", ".log")
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

        val completed = process.waitFor(60, TimeUnit.SECONDS)
        assertTrue(completed, "CLI did not finish: ${Files.readString(output)}")
        return CliResult(process.exitValue(), Files.readString(output))
    }

    private data class CliResult(val exitCode: Int, val output: String)
}
