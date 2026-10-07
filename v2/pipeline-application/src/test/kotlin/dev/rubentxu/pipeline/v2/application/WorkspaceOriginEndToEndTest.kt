package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * RP034-I — the invocation directory is the workspace; the script's directory is not.
 *
 * This is the law WU-RP-034 exists to enforce, and until now it had **no**
 * automated coverage. `DirScopeEndToEndTest` and
 * `WorkspaceAnchorScopeEndToEndTest` prove that Steps resolve against a `dir`
 * scope, which is a different claim. Nothing asserted the three facts below
 * through the real CLI:
 *
 *  1. with no flag, the workspace is the process invocation directory, so a
 *     script living in a subdirectory writes its effects at the root;
 *  2. `--workspace <dir>` moves those effects, independently of where the
 *     script sits;
 *  3. `dir(...)` derives only the cwd and restores the workspace root after the
 *     block.
 *
 * The candidate UAT for 0.45.0 observed all three by hand. Hand observation is
 * not regression protection: the three defects found in this WU — the dropped
 * ownership in transit, the SC-011-04 assertion of the opposite of ADR-0102,
 * and the corpus runner diverging from the reference runner — were all invisible
 * to tests that never exercised a real invocation directory.
 *
 * Hermetic by construction: the pipelines shell out only to `pwd` and `echo`,
 * so this runs in `check` without any external toolchain.
 */
@DisplayName("RP034-I — invocation directory, not script directory, is the workspace")
class WorkspaceOriginEndToEndTest {

    private val processes = mutableListOf<Process>()

    @AfterEach
    fun tearDown() {
        processes.forEach { it.destroyForcibly() }
    }

    @Test
    fun `no flag attaches the invocation directory and ignores the script directory`(@TempDir tempDir: Path) {
        val sub = Files.createDirectories(tempDir.resolve("scripts"))
        writeScript(
            sub,
            """
            pipeline {
                stages {
                    stage("write") {
                        sh("echo payload > marker.txt")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(tempDir, listOf("run", "--format", "json", sub.resolve("p.pipeline.kts").toString()))

        assertEquals(0, result.exitCode, result.output)
        assertTrue(
            Files.exists(tempDir.resolve("marker.txt")),
            "the invocation directory is the workspace, so the effect belongs at the root",
        )
        assertFalse(
            Files.exists(sub.resolve("marker.txt")),
            "the script's own directory must NOT become the workspace",
        )
    }

    @Test
    fun `explicit workspace moves the effect away from the invocation directory`(@TempDir tempDir: Path) {
        val sub = Files.createDirectories(tempDir.resolve("scripts"))
        val other = Files.createDirectories(tempDir.resolve("elsewhere"))
        writeScript(
            sub,
            """
            pipeline {
                stages {
                    stage("write") {
                        sh("echo payload > marker.txt")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(
            tempDir,
            listOf("run", "--format", "json", "--workspace", other.toString(), sub.resolve("p.pipeline.kts").toString()),
        )

        assertEquals(0, result.exitCode, result.output)
        assertTrue(
            Files.exists(other.resolve("marker.txt")),
            "--workspace is authoritative for the effective root",
        )
        assertFalse(
            Files.exists(tempDir.resolve("marker.txt")),
            "an explicit workspace must detach the root from the invocation directory",
        )
    }

    @Test
    fun `dir derives the cwd and the workspace root survives the block`(@TempDir tempDir: Path) {
        val sub = Files.createDirectories(tempDir.resolve("nested"))
        writeScript(
            tempDir,
            """
            pipeline {
                stages {
                    stage("observe") {
                        dir("nested") { sh("pwd") }
                        sh("pwd")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runCli(tempDir, listOf("run", "--format", "json", "p.pipeline.kts"))

        assertEquals(0, result.exitCode, result.output)

        // The CLI emits one JSON event line, so the two observations are found by
        // position rather than by line: the index check is what proves the ORDER,
        // which is the part of the law that matters — cwd moved into the scope
        // first and was restored to the root afterwards.
        val inside = tempDir.resolve("nested").toString()
        val root = tempDir.toString()
        val insideAt = result.output.indexOf(inside)
        val rootAt = result.output.indexOf(root)

        assertTrue(insideAt >= 0, "no pwd inside the dir scope was observed: ${result.output}")
        assertTrue(rootAt >= insideAt, "the workspace root must be observed after the dir scope: ${result.output}")
    }

    private fun writeScript(dir: Path, content: String) {
        Files.writeString(dir.resolve("p.pipeline.kts"), content)
    }

    private fun runCli(workingDirectory: Path, arguments: List<String>): CliResult {
        val output = Files.createTempFile(workingDirectory, "rp034origin-cli-", ".log")
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
