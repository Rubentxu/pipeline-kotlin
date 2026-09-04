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
import kotlin.concurrent.thread

@Timeout(20)
class CanonicalInMemoryCliTest {

    private val processes = mutableListOf<Process>()

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        processes.forEach { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        processes.clear()
    }

    @Test
    fun `run without database executes canonical echo payload`() {
        val script = tempDir.resolve("canonical.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("canonical") {
                        echo("canonical CLI output")
                    }
                }
            }
        """.trimIndent())

        val result = run(script)

        assertEquals(0, result.exitCode, "CLI failed: ${result.stderr}")
        assertTrue(
            result.stdout.contains("\"kind\":\"EchoOutputCaptured\"") &&
                result.stdout.contains("\"content\":\"canonical CLI output\\n\""),
            "The canonical echo output must be emitted. stdout: ${result.stdout}",
        )
    }

    private fun run(script: Path): CliResult {
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            script.toString(),
        )
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        processes += process

        var stdout = ""
        var stderr = ""
        val stdoutReader = thread { stdout = process.inputStream.bufferedReader().use { it.readText() } }
        val stderrReader = thread { stderr = process.errorStream.bufferedReader().use { it.readText() } }

        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI process did not finish")
        stdoutReader.join()
        stderrReader.join()
        return CliResult(process.exitValue(), stdout, stderr)
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
