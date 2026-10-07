package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * UAT-STEP-002: echo capture
 * Tests that echo emits EchoOutputCaptured event and preserves newlines.
 */
@Timeout(120)
class UatStep002EchoCaptureTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val echoCaptureScript: Path by lazy {
        Paths.get(javaClass.getResource("/echo-capture.pipeline.kts")!!.toURI())
    }

    @Test
    fun `echo emits captured event`() {
        val (_, events) = runAndDecode()

        val echoOutputEvents = events.filter { it is EchoOutputCaptured }
        assertTrue(echoOutputEvents.isNotEmpty(), "Must have EchoOutputCaptured event for echo")

        val capturedOutput = (echoOutputEvents.first() as EchoOutputCaptured).content
        assertTrue(capturedOutput.contains("payload"), "Captured content must contain 'payload': $capturedOutput")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", echoCaptureScript.toString()),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout.trim()
        if (exitCode != 0) {
            val stderr = cliRun.stderr
            throw IllegalStateException("CLI exited with $exitCode. stderr: $stderr")
        }
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
