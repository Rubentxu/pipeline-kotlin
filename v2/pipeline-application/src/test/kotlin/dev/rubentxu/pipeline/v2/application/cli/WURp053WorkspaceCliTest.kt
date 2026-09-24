package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Timeout(10, unit = TimeUnit.MINUTES)
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
