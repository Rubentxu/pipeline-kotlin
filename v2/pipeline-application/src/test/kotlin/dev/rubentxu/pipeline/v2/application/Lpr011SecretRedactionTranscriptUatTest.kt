package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.ShOperationsAdapter
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.TranscriptRedactor
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-LPR-011 secret-redaction slice — Gate-1: chunk-boundary-safe redaction of
 * the observable shell output BEFORE it reaches the durable event plane.
 *
 * Characterization replaced (P5, WU-LPR-040): the transcript seam used to emit
 * raw log bytes; a secret straddling a chunk boundary survived redaction. The
 * seam now routes through [StreamingRedactor] (via [TranscriptRedactor]), so a
 * secret split across arbitrary process-output chunk boundaries is still
 * scrubbed before the [EchoOutputCaptured] event leaves the substrate.
 */
@Timeout(60)
class Lpr011SecretRedactionTranscriptUatTest {

    private val secret = "GHS6_CANARY_7f3a9c2e1b4d5e6f"

    private fun registry(): SecretPatternRegistry =
        SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

    private fun capturedOutputs(events: List<*>, runId: String): List<String> =
        events.filterIsInstance<EchoOutputCaptured>()
            .filter { it.runId == runId }
            .map { it.content }

    // ------------------------------------------------------------------
    // L1: the redaction component itself (credentials-api unit seam)
    // ------------------------------------------------------------------

    @Test
    fun `transcript redactor scrubs a secret split across single byte reads`() {
        val content = "before $secret after"
        val redactor = TranscriptRedactor(registry(), chunkSize = 4)
        val input = object : java.io.InputStream() {
            val bytes = content.toByteArray(Charsets.UTF_8)
            var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() else -1
        }
        val out = redactor.redactStream(input)
        assertFalse(out.contains(secret), "split secret must not survive: [$out]")
        assertTrue(out.contains("before"), "surrounding content must survive")
        assertTrue(out.contains("after"), "surrounding content must survive")
    }

    @Test
    fun `transcript redactor returns null for absent file`() {
        val missing = Files.createTempDirectory("lpr011").resolve("nope.log")
        assertEquals(null, TranscriptRedactor(registry()).redactFile(missing))
    }

    @Test
    fun `transcript redactor redacts a whole file via the file seam`() {
        val dir = Files.createTempDirectory("lpr011-file")
        val log = dir.resolve("console.log")
        Files.writeString(log, "token=$secret end")
        val out = TranscriptRedactor(registry(), chunkSize = 3).redactFile(log)
        assertNotNull(out, "existing file must yield content")
        assertFalse(out!!.contains(secret), "file-seam secret must not survive: [$out]")
    }

    // ------------------------------------------------------------------
    // L2: the durable shell substrate (canonical ShOperationsAdapter path)
    // ------------------------------------------------------------------

    @Test
    fun `non durable adapter path redacts a split secret from the observable event`() = runBlocking {
        val runIdString = "lpr011-redact-nondurable"
        val eventSink = InMemoryEventStore()
        // Print the secret in two halves from the child so the seam receives it
        // split across two process writes (the P5 characterization shape).
        val half = secret.length / 2
        val script = "printf '%s' '${secret.substring(0, half)}'; sleep 0.05; printf '%s\\n' '${secret.substring(half)}'"
        val adapter = ShOperationsAdapter(
            runIdString = runIdString,
            opId = OpId(runIdString, 0, 0),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = eventSink,
            secretPatternRegistry = registry(),
        )
        val result = adapter.invoke(
            command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
            runId = RunId(runIdString),
            stepIndex = 0,
        )
        assertTrue(
            result is ShellInvocationResult.UnitValue || result is ShellInvocationResult.Stdout ||
                result is ShellInvocationResult.Status && (result as ShellInvocationResult.Status).exitCode == 0,
            "subprocess must succeed; got $result",
        )
        val contents = capturedOutputs(eventSink.eventsFor(runIdString).toList(), runIdString)
        assertTrue(contents.isNotEmpty(), "exactly one observable EchoOutputCaptured expected")
        for (c in contents) {
            assertFalse(c.contains(secret), "observable event leaked the raw secret: [$c]")
        }
        // The redacted event must still carry the benign framing text.
        assertTrue(contents.any { it.contains("GHS6_CANARY") || it.contains("****") },
            "redacted marker or surrounding text must remain: $contents")
    }

    @Test
    fun `non durable adapter path without registry keeps legacy unredacted behavior`() = runBlocking {
        val runIdString = "lpr011-unredacted-legacy"
        val eventSink = InMemoryEventStore()
        val adapter = ShOperationsAdapter(
            runIdString = runIdString,
            opId = OpId(runIdString, 0, 0),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = eventSink,
            secretPatternRegistry = null,
        )
        adapter.invoke(
            command = ShellCommand(script = "echo $secret", returnMode = ShellReturnMode.NONE),
            runId = RunId(runIdString),
            stepIndex = 0,
        )
        val contents = capturedOutputs(eventSink.eventsFor(runIdString).toList(), runIdString)
        // Null registry = explicit legacy composition: raw behavior preserved.
        assertTrue(contents.any { it.contains(secret) },
            "null registry must preserve legacy raw transcript: $contents")
    }

    @Test
    fun `durable adapter path redacts a whole secret from the observable event`() = runBlocking {
        val runIdString = "lpr011-redact-durable"
        val eventSink = InMemoryEventStore()
        val controlRoot = Files.createTempDirectory("lpr011-durable")
        val adapter = ShOperationsAdapter(
            runIdString = runIdString,
            opId = OpId(runIdString, 0, 0),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = controlRoot,
            eventSink = eventSink,
            secretPatternRegistry = registry(),
        )
        val result = adapter.invoke(
            command = ShellCommand(script = "echo $secret", returnMode = ShellReturnMode.NONE),
            runId = RunId(runIdString),
            stepIndex = 0,
        )
        assertTrue(
            result is ShellInvocationResult.UnitValue || result is ShellInvocationResult.Stdout ||
                result is ShellInvocationResult.Status && (result as ShellInvocationResult.Status).exitCode == 0,
            "subprocess must succeed; got $result",
        )
        val contents = capturedOutputs(eventSink.eventsFor(runIdString).toList(), runIdString)
        for (c in contents) {
            assertFalse(c.contains(secret), "durable observable event leaked the raw secret: [$c]")
        }
    }
}
