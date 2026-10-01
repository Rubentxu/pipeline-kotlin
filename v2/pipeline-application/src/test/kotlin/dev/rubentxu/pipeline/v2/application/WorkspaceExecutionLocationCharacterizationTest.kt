package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * RP034-A — Execution-location characterisation freeze.
 *
 * **This class asserts CURRENT observable reality on the real CLI process
 * boundary. It is not a target-state test suite.**
 *
 * It exists to freeze the pre-WU-RP-034 semantics so the migration slices
 * (RP034-B..H) can prove what changed and what did not. The
 * [Characterisation] cases below are the ones that are EXPECTED TO INVERT when
 * the CLI default flips in RP034-H; they are marked so a future reader cannot
 * mistake a drift for a regression.
 *
 * The three authorities that WU-RP-034 must separate are observable here:
 * the invocation directory, the workspace root, and the control root.
 */
@Timeout(120)
@DisplayName("RP034-A characterisation: execution location authorities")
class WorkspaceExecutionLocationCharacterizationTest {

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

    // ---------------------------------------------------------------------
    // Characterisation: the no-flag default is a synthetic per-stage scratch.
    // INVERTS in RP034-H (becomes the invocation directory).
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Characterisation: no --workspace runs sh in a synthetic per-stage scratch, NOT the invocation directory")
    fun `no workspace flag runs shell in synthetic per-stage scratch`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val observed = tempDir.resolve("pwd.txt")
        val script = writePwdPipeline(invocationDirectory, observed)

        val result = runCli(
            invocationDirectory,
            listOf(
                "run",
                "--db", tempDir.resolve("durable.db").toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(observed), "shell did not record its CWD: ${result.output}")

        val reported = Files.readString(observed).trim()
        // The current scratch is <controlRoot>/workspace/<stage>-<n>.
        assertTrue(
            reported.startsWith(controlRoot.toRealPath().toString()),
            "expected the shell CWD to live under the control root, got: $reported",
        )
        // And it is emphatically NOT the invocation directory.
        assertFalse(
            reported == invocationDirectory.toRealPath().toString(),
            "RP034-A expected the invocation directory NOT to be the shell CWD; " +
                "if this now fails, the CLI default has already flipped and this " +
                "characterisation must be inverted in the RP034-H receipt",
        )
    }

    @Test
    @DisplayName("Characterisation: a relative path in the project does not resolve in a no-flag run (the WU-RP-034 defect)")
    fun `relative project path is unreachable without workspace flag`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        // A "project file" that only exists relative to the invocation directory.
        Files.writeString(invocationDirectory.resolve("build-marker.txt"), "present")
        val probe = tempDir.resolve("probe.txt")
        val script = invocationDirectory.resolve("pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("relative") {
                        sh("cat build-marker.txt > '${probe}' 2>/dev/null || echo UNREACHABLE > '${probe}'")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(
            invocationDirectory,
            listOf(
                "run",
                "--db", tempDir.resolve("rel.db").toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(probe), "probe did not run: ${result.output}")
        // This is the defect the whole WU exists to fix. INVERTS in RP034-H.
        assertEquals(
            "UNREACHABLE",
            Files.readString(probe).trim(),
            "RP034-A expected the relative project path to be unreachable; " +
                "if this now fails, the local-first default has already landed",
        )
    }

    // ---------------------------------------------------------------------
    // Stable behaviour: these must survive every migration slice.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Stable: an explicit --workspace makes the shell CWD exactly that workspace")
    fun `explicit workspace is the shell working directory`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val observed = tempDir.resolve("explicit-pwd.txt")
        val script = writePwdPipeline(invocationDirectory, observed)

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
        assertTrue(Files.exists(observed), "shell did not record its CWD: ${result.output}")
        assertEquals(
            workspace.toRealPath().toString(),
            Files.readString(observed).trim(),
            "an explicit --workspace must remain the effective shell CWD",
        )
    }

    @Test
    @DisplayName("Stable: workspace root and control root are distinct authorities")
    fun `workspace root is not the control root`(@TempDir tempDir: Path) {
        val invocationDirectory = Files.createDirectory(tempDir.resolve("invocation"))
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val observed = tempDir.resolve("separation.txt")
        val script = writePwdPipeline(invocationDirectory, observed)

        val result = runCli(
            invocationDirectory,
            listOf(
                "run",
                "--db", tempDir.resolve("sep.db").toString(),
                "--control-root", controlRoot.toString(),
                "--workspace", workspace.toString(),
                script.toString(),
            ),
        )

        assertEquals(0, result.exitCode, result.output)
        val reported = Files.readString(observed).trim()
        assertEquals(workspace.toRealPath().toString(), reported)
        assertFalse(
            reported.startsWith(controlRoot.toRealPath().toString()),
            "the workspace root must not collapse into the control root (INV-WS-004)",
        )
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

        val completed = process.waitFor(90, TimeUnit.SECONDS)
        assertTrue(completed, "CLI did not finish: ${Files.readString(output)}")
        return CliResult(process.exitValue(), Files.readString(output))
    }

    private data class CliResult(val exitCode: Int, val output: String)
}
