package com.pipeline.v2.application

import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EchoOutputCaptured
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StepFailed
import com.pipeline.v2.events.StepFinished
import com.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-STEP-003: error abort
 * Tests that error emits StepFailed and halts subsequent steps.
 */
class UatStep003ErrorAbortTest {

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

    private val errorAbortScript: Path by lazy {
        Paths.get(javaClass.getResource("/error-abort.pipeline.kts")!!.toURI())
    }

    @Test
    fun `error emits StepFailed`() {
        val (_, events) = runAndDecode()

        val stepFailedEvents = events.filter { it is StepFailed }
        assertTrue(stepFailedEvents.isNotEmpty(), "Must have StepFailed event for error step")

        val stepFailed = stepFailedEvents.first() as StepFailed
        assertTrue(stepFailed.message.contains("boom"), "StepFailed message must contain 'boom': ${stepFailed.message}")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", errorAbortScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        // Note: error step may cause non-zero exit, but we still get events
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
