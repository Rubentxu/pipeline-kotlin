package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * UAT-STEP-004: sleep timing
 * Tests that sleep blocks for at least N seconds and emits timing.
 */
@Timeout(120)
class UatStep004SleepTimingTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val sleepTimingScript: Path by lazy {
        Paths.get(javaClass.getResource("/sleep-timing.pipeline.kts")!!.toURI())
    }

    @Test
    fun `sleep blocks for at least 950ms`() {
        val (_, events) = runAndDecode()

        val stepStartedEvents = events.filter { it is StepStarted }
        val stepFinishedEvents = events.filter { it is StepFinished }

        val sleepStarted = stepStartedEvents.find { (it as StepStarted).stepType == "sleep" }
        val sleepFinished = stepFinishedEvents.find { (it as StepFinished).stepType == "sleep" }

        assertTrue(sleepStarted != null, "Must have StepStarted for sleep")
        assertTrue(sleepFinished != null, "Must have StepFinished for sleep")

        // Measure actual sleep duration from event timestamps, not wall-clock time
        val sleepStartedAt = (sleepStarted as StepStarted).occurredAt
        val sleepFinishedAt = (sleepFinished as StepFinished).occurredAt
        val sleepDurationMs = java.time.Duration.between(sleepStartedAt, sleepFinishedAt).toMillis()

        assertTrue(sleepDurationMs >= 950, "Sleep must block for at least 950ms, but only blocked for ${sleepDurationMs}ms")
        assertTrue(sleepDurationMs <= 5000, "Sleep must block for at most 5000ms, but blocked for ${sleepDurationMs}ms")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", sleepTimingScript.toString())
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
