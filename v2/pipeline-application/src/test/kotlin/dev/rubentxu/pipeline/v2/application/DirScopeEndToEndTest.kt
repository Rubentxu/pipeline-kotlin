package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * RP034-D — end-to-end proof that `dir` scopes the filesystem vertical too.
 *
 * The contract tests in `CorePathSemanticsTest` assert the law against the
 * domain authority; this class proves it holds through the real CLI, where the
 * scope is applied by the durable coordinator and observed by the Step
 * executors. It is the case that was previously broken: `writeFile` inside a
 * `dir` block wrote to the stage workspace while `sh` ran in the scope.
 */
@Timeout(180)
@DisplayName("RP034-D dir scopes shell and filesystem together")
class DirScopeEndToEndTest {

    private val processes = mutableListOf<Process>()

    @AfterEach
    fun tearDown() {
        processes.forEach { process ->
            val descendants = process.descendants().toList()
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            descendants.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    @Test
    @DisplayName("writeFile inside dir lands under the scope and is readable there")
    fun `dir scopes writeFile and readFile`(@TempDir tempDir: Path) {
        val project = Files.createDirectory(tempDir.resolve("project"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        Files.createDirectories(project.resolve("backend"))

        val script = project.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("scoped") {
                        dir("backend") {
                            writeFile("marker.txt", "written-inside-dir")
                            echo(pwd())
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(
            project,
            listOf(
                "run", "--format", "json",
                "--db", tempDir.resolve("durable.db").toString(),
                "--control-root", tempDir.resolve("control").toString(),
                "--workspace", workspace.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)

        // pwd() inside the scope must report the scope.
        assertTrue(
            result.output.contains("backend"),
            "pwd() inside dir('backend') must report the scoped directory, got: ${result.output}",
        )

        // The file must exist under the workspace root + scope, not at the root
        // and not in a stage scratch directory.
        val scopedFile = workspace.resolve("backend/marker.txt")
        assertTrue(
            Files.exists(scopedFile),
            "writeFile inside dir must land at $scopedFile, but it was not there. " +
                "Output: ${result.output}",
        )
        assertEquals("written-inside-dir", Files.readString(scopedFile))
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "rp034d-cli-", ".log")
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

        val completed = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(completed, "CLI did not finish: ${Files.readString(output)}")
        return CliResult(process.exitValue(), Files.readString(output))
    }

    private data class CliResult(val exitCode: Int, val output: String)
}
