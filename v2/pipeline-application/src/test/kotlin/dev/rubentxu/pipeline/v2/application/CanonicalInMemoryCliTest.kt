package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunStarted
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

    /**
     * `--control-root` must mean the same thing on BOTH execution paths.
     *
     * It used to be honoured only when `--db` selected the durable branch; without a database the
     * in-memory branch hard-coded a private temp directory and dropped the flag in silence. The
     * consequence was not a tidiness complaint: since M1 owns process output in the Output Plane
     * (ADR-M1 D2/D3), "where the plane is written" is the product question, and `pipeline console
     * --control-dir X` could not be aimed at a default-path run at all.
     *
     * This row certifies the whole chain a consumer actually walks, not just the flag: run the
     * default in-memory path with a control root, then read the transcript back through
     * [ConsolePlaneProbe] — which is [ConsoleReadService], the same reader the CLI serves. A
     * mutation that restores the unconditional temp directory fails here at the read, which is
     * the symptom the flag's contract is about, rather than at an incidental path assertion.
     */
    @Test
    fun `run without a database honours --control-root, so the Output Plane is reachable`() {
        val script = tempDir.resolve("planewitness.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("plane") {
                        sh("echo PLANE-WITNESS")
                    }
                }
            }
        """.trimIndent())

        val controlDir = tempDir.resolve("control")
        val result = run(script, "--control-root", controlDir.toString())

        assertEquals(0, result.exitCode, "CLI failed: ${result.stderr}")
        val runId = JsonEventLog.decode(result.stdout)
            .filterIsInstance<RunStarted>()
            .single()
            .runId
        val transcript = ConsolePlaneProbe.transcript(controlDir, runId, stageIndex = 0, stepIndex = 0)
        assertTrue(
            transcript.contains("PLANE-WITNESS"),
            "the process transcript must be readable from the requested control root; got [$transcript]. " +
                "stdout=${result.stdout} stderr=${result.stderr}",
        )
    }

    private fun run(script: Path, vararg options: String): CliResult {
        // Options precede the script path: CliParser stops consuming flags at the first non-flag
        // argument, so a trailing option would be dropped without a word.
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            *options,
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
