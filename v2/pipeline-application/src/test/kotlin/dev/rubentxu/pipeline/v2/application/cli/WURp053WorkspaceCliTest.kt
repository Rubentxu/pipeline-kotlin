package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * WU-RP-053 cut5 CLI integration tests. Disabled in WU-RP-053-MERGE because
 * the cut5 model + PROJECT mode default (workspace = script's parent) breaks
 * 7 existing UAT tests (SB-S-001/SB-006/SB-008/SC-011-04/UAT-L7-TC-004/fixture10/
 * corpus) which assume the legacy per-stage workspace layout.
 *
 * The cut5 model IS integrated (M1: WorkspaceOperationsAdapter /
 * StashOperationsAdapter carry effectiveWorkingDirectory) but the CLI
 * default behaviour change (resolveCliWorkspace → scriptPath.parent) is
 * NOT adopted in this WU. The Main.kt change was attempted (commit 3e9fc4aa)
 * and reverted (commit e08b063e) once the regression surface was measured.
 *
 * These CLI tests are kept here as the canonical proof of the cut5 CLI
 * behaviour. Re-enable when cut5's PROJECT mode default is promoted behind
 * an opt-in flag (e.g. `--workspace-mode=project`) so existing UATs can
 * pass --workspace explicitly to maintain the legacy layout.
 */
@Timeout(10, unit = TimeUnit.MINUTES)
@Disabled("Disabled in WU-RP-053-MERGE — see class-level docs. Re-enable when cut5 PROJECT mode default is promoted behind --workspace-mode opt-in flag.")
class WURp053WorkspaceCliTest {
    private val binary = AppBinSupport.discover().toAbsolutePath().toString()

    private fun invoke(directory: Path, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(binary, *args)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .apply { environment().remove("REPO_ROOT") }
            .start()
        try {
            assertTrue(process.waitFor(4, TimeUnit.MINUTES), "CLI timed out: ${args.toList()}")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `bare and nested relative scripts use their own directory for canonical durable workspace`() {
        val project = Files.createTempDirectory("rp053-project-")
        val state = Files.createTempDirectory("rp053-state-")
        val nested = Files.createDirectories(project.resolve("nested"))
        val script = """
            |pipeline {
            |  stages {
            |    stage("workspace") {
            |      writeFile("step-output.txt", "step-data")
            |      sh("test -f marker.txt && test -f step-output.txt")
            |    }
            |  }
            |}
        """.trimMargin()
        Files.writeString(project.resolve("bare.pipeline.kts"), script)
        Files.writeString(nested.resolve("nested.pipeline.kts"), script)
        Files.writeString(project.resolve("marker.txt"), "here")
        Files.writeString(nested.resolve("marker.txt"), "here")

        listOf("bare.pipeline.kts", "nested/nested.pipeline.kts").forEachIndexed { index, name ->
            val (exit, output) = invoke(project, "run", "--db", state.resolve("$index.sqlite").toString(), name)
            assertEquals(0, exit, "$name: ${output.takeLast(1500)}")
            assertTrue(output.contains("Pipeline finished with SUCCESS"), output.takeLast(1500))
            val expectedRoot = if (index == 0) project else nested
            assertEquals("step-data", Files.readString(expectedRoot.resolve("step-output.txt")))
            assertFalse(Files.exists(state.resolve("step-output.txt")))
            assertFalse(Files.exists(state.resolve("durable-shell/step-output.txt")))
        }
        assertFalse(Files.exists(project.resolve("durable-shell")))
        assertFalse(Files.exists(nested.resolve("durable-shell")))
    }

    @Test
    fun `explicit workspace wins for canonical and scripted durable bare filename`() {
        val project = Files.createTempDirectory("rp053-script-")
        val workspace = Files.createTempDirectory("rp053-workspace-")
        val state = Files.createTempDirectory("rp053-state-")
        Files.writeString(workspace.resolve("marker.txt"), "explicit")
        Files.writeString(project.resolve("explicit.pipeline.kts"),
            """pipeline { stages { stage("explicit") { sh("test -f marker.txt") } } }""")
        val (exit, output) = invoke(project, "run", "--db", state.resolve("explicit.sqlite").toString(),
            "--workspace", workspace.toString(), "explicit.pipeline.kts")
        assertEquals(0, exit, output.takeLast(1500))
        assertFalse(Files.exists(project.resolve("marker.txt")))
        assertFalse(Files.exists(project.resolve("durable-shell")))
        assertTrue(Files.exists(state.resolve("durable-shell")))

        Files.writeString(project.resolve("scripted.pipeline.kts"), """
            pipeline {
                stages {
                    stage("scripted") {
                        val current = pwd()
                        if (current.isNotEmpty()) sh("test -f marker.txt")
                    }
                }
            }
        """.trimIndent())
        val (scriptedExit, scriptedOutput) = invoke(project, "run", "--db",
            state.resolve("scripted.sqlite").toString(), "--workspace", workspace.toString(), "scripted.pipeline.kts")
        assertEquals(0, scriptedExit, scriptedOutput.takeLast(1500))
        assertFalse(Files.exists(project.resolve("durable-shell")))
    }
}
