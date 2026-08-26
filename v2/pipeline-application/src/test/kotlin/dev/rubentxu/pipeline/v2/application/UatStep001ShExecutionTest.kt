package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-STEP-001: sh execution
 * Tests that sh step runs successfully, respects argv list, and captures stdout.
 */
@Timeout(120)
class UatStep001ShExecutionTest {

    private val appBin: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        val bin = moduleDir
            .resolve("build")
            .resolve("install")
            .resolve("pipeline-application")
            .resolve("bin")
            .resolve("pipeline-application")
        if (!bin.toFile().exists()) {
            throw IllegalStateException(
                "Application binary not found at $bin. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        bin
    }

    private val shExecScript: Path by lazy {
        Paths.get(javaClass.getResource("/sh-exec.pipeline.kts")!!.toURI())
    }

    @Test
    fun `sh runs successfully`() {
        val (stdout, events) = runAndDecode()

        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")

        val stepStartedEvents = events.filter { it is StepStarted }
        val stepFinishedEvents = events.filter { it is StepFinished }

        assertTrue(stepStartedEvents.any { (it as StepStarted).stepType == "sh" }, "Must have StepStarted for sh")
        assertTrue(stepFinishedEvents.any { (it as StepFinished).stepType == "sh" }, "Must have StepFinished for sh")
        assertTrue(events.none { it is StepFailed }, "Must not have StepFailed event for successful sh")
    }

    @Test
    fun `sh captures stdout`() {
        val (_, events) = runAndDecode()

        val echoOutputEvents = events.filter { it is EchoOutputCaptured }
        assertTrue(echoOutputEvents.isNotEmpty(), "Must have EchoOutputCaptured event for sh stdout")

        val capturedOutput = (echoOutputEvents.first() as EchoOutputCaptured).content
        assertTrue(capturedOutput.contains("hello from sh"), "Captured stdout must contain 'hello from sh': $capturedOutput")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", shExecScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("CLI exited with $exitCode. stderr: $stderr")
        }
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
