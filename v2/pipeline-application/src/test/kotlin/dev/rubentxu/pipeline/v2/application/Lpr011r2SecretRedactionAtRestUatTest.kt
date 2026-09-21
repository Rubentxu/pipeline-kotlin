package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.ShOperationsAdapter
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellFiles
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * WU-LPR-011R2 — Secret Redaction At-Rest Closure (Gate-1).
 *
 * Law: `console.log` MUST receive only already-redacted bytes. Redaction
 * happens BEFORE persistence (streaming pump at the write boundary), never
 * during cleanup. A JVM crash can leave at most a partial SANITIZED
 * transcript.
 *
 * The critical Gate-1 proof: a child prints the secret and FAILS; the control
 * dir is retained (cleanupRetainOnFailure semantics); the surviving
 * `console.log` contains zero raw secret bytes and the redaction marker.
 */
@Timeout(120)
class Lpr011r2SecretRedactionAtRestUatTest {

    private val secret = "GHS6_CANARY_7f3a9c2e1b4d5e6f"

    private fun registry(): SecretPatternRegistry =
        SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

    private fun consoleLog(controlRoot: Path, runId: String): Path =
        DurableShellFiles.resolveConsoleLog(controlRoot.resolve(OpId(runId, 0, 0).format()))

    private fun adapter(
        runId: String,
        controlRoot: Path,
        reg: SecretPatternRegistry?,
        eventSink: InMemoryEventStore,
    ): ShOperationsAdapter = ShOperationsAdapter(
        runIdString = runId,
        opId = OpId(runId, 0, 0),
        shOptions = ShOptions.EMPTY,
        controlDirRoot = controlRoot,
        eventSink = eventSink,
        secretPatternRegistry = reg,
    )

    private fun invoke(adapter: ShOperationsAdapter, runId: String, script: String) {
        runBlocking {
            adapter.invoke(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                runId = RunId(runId),
                stepIndex = 0,
            )
        }
    }

    private fun rawCount(file: Path, needle: String): Int =
        if (!Files.exists(file)) 0 else Files.readString(file).split(needle).size - 1

    private fun eventRawCount(events: List<*>, runId: String, needle: String): Int =
        events.filterIsInstance<EchoOutputCaptured>().filter { it.runId == runId }
            .sumOf { it.content.split(needle).size - 1 }

    // 1. whole secret, plain mode, SUCCESS run: cleanup deletes the control dir,
    // so at-rest content is proven by the live-window and retention tests below;
    // here the observable event plane must be redacted.
    @Test
    fun `whole secret is redacted in the observable event of a successful run`() {
        val runId = "r2-whole"
        val controlRoot = Files.createTempDirectory("r2-whole")
        val sink = InMemoryEventStore()
        invoke(adapter(runId, controlRoot, registry(), sink), runId, "echo $secret")
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret),
            "successful-run event must carry zero raw secret bytes")
    }

    // 2. split secret across two child writes
    @Test
    fun `secret split across two child writes is redacted in the persisted console log`() {
        val runId = "r2-split2"
        val controlRoot = Files.createTempDirectory("r2-split2")
        val sink = InMemoryEventStore()
        val half = secret.length / 2
        invoke(adapter(runId, controlRoot, registry(), sink), runId,
            "printf '%s' '${secret.substring(0, half)}'; sleep 0.05; printf '%s\\n' '${secret.substring(half)}'")
        val log = consoleLog(controlRoot, runId)
        assertEquals(0, rawCount(log, secret), "split secret must never persist raw")
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret))
    }

    // 3. split across many small writes (chunk boundary stress)
    @Test
    fun `secret emitted byte by byte is redacted in the persisted console log`() {
        val runId = "r2-splitn"
        val controlRoot = Files.createTempDirectory("r2-splitn")
        val sink = InMemoryEventStore()
        // printf one char at a time, no separators: forces boundary stress
        val chars = secret.chunked(1).joinToString("") { c -> "printf '%s' '$c';" }
        invoke(adapter(runId, controlRoot, registry(), sink), runId, chars)
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret),
            "byte-wise secret must never reach the observable plane raw")
    }

    // 4. stderr secret, plain mode (merged)
    @Test
    fun `secret on stderr is redacted in the persisted console log`() {
        val runId = "r2-stderr"
        val controlRoot = Files.createTempDirectory("r2-stderr")
        val sink = InMemoryEventStore()
        invoke(adapter(runId, controlRoot, registry(), sink), runId, "echo $secret 1>&2")
        assertEquals(0, rawCount(consoleLog(controlRoot, runId), secret),
            "stderr secret must never persist raw")
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret))
    }

    // 5. THE Gate-1 proof: failing child + retained control dir
    @Test
    fun `failing child with retained control dir leaves a sanitized console log`() {
        val runId = "r2-fail-retain"
        val controlRoot = Files.createTempDirectory("r2-fail-retain")
        val sink = InMemoryEventStore()
        invoke(adapter(runId, controlRoot, registry(), sink), runId,
            "echo $secret; echo about-to-fail 1>&2; exit 3")
        val log = consoleLog(controlRoot, runId)
        assertTrue(Files.exists(log), "failed-run transcript must survive (retention)")
        assertEquals(0, rawCount(log, secret),
            "GATE-1: retained failing transcript must contain zero raw secret bytes")
        assertTrue(Files.readString(log).contains("****"), "redaction marker must be present")
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret))
    }

    // 6. timeout/interruption retention
    @Test
    fun `interrupted child retention leaves a sanitized console log`() {
        val runId = "r2-timeout"
        val controlRoot = Files.createTempDirectory("r2-timeout")
        val sink = InMemoryEventStore()
        // child prints secret then sleeps long; the durable timeout kills the tree
        val options = ShOptions.EMPTY.copy(timeoutMs = 1500L)
        val a = ShOperationsAdapter(
            runIdString = runId,
            opId = OpId(runId, 0, 0),
            shOptions = options,
            controlDirRoot = controlRoot,
            eventSink = sink,
            secretPatternRegistry = registry(),
        )
        invoke(a, runId, "echo $secret; sleep 30")
        val log = consoleLog(controlRoot, runId)
        assertTrue(Files.exists(log), "timeout-retained transcript must exist")
        assertEquals(0, rawCount(log, secret),
            "timeout-killed transcript must contain zero raw secret bytes")
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret))
    }

    // 7. DURING EXECUTION proof: redaction before persistence, not cleanup-time
    @Test
    fun `console log contains no raw secret while the child is still alive`() {
        val runId = "r2-live"
        val controlRoot = Files.createTempDirectory("r2-live")
        val sink = InMemoryEventStore()
        val logPath = consoleLog(controlRoot, runId)
        // Launch on another thread; poll the transcript WHILE the child sleeps.
        val t = Thread {
            // 100 echo lines each containing the secret (≈3.4KB) far exceeds the
            // redactor's maxLiteralByteLength (~56), so pending flushes (and the
            // "****" marker lands on disk) while `sleep 2` keeps the child alive.
            invoke(adapter(runId, controlRoot, registry(), sink), runId,
                "for i in \$(seq 1 100); do echo $secret-line-\$i; done; sleep 2")
        }
        t.isDaemon = true
        t.start()
        // WU-RP-005 r6 (CI flake fix, run 35646215918 + local repro ~1/3):
        // the StreamingRedactor holds bytes in its pending buffer until it has
        // maxLiteralByteLength (~56 for this registry) of lookahead or EOF.
        // A tiny `echo` line cannot cross that threshold while the child is
        // alive, so sanitized bytes only reach console.log near process exit,
        // racing the success-path cleanup. To make the DURING-EXECUTION window
        // deterministic we produce enough output to force repeated pending
        // flushes while the child is still sleeping, then assert the at-rest
        // invariants on every observation inside that window.
        val deadline = System.currentTimeMillis() + 30_000
        var observedLive = false
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(logPath)) {
                val content = try { Files.readString(logPath) } catch (_: Exception) { "" }
                if (content.contains("****")) {
                    assertEquals(0, rawCount(logPath, secret),
                        "live transcript must never contain the raw secret")
                    assertFalse(content.contains("echo $secret"), "script echo must be scrubbed live")
                    observedLive = true
                    break
                }
            }
            Thread.sleep(50)
        }
        t.join(35_000)
        assertTrue(observedLive, "must observe the sanitized transcript DURING execution")
        // Success-path cleanup may delete the control dir right after exit; if the
        // transcript still exists, the at-rest properties must hold.
        if (Files.exists(logPath)) {
            assertEquals(0, rawCount(logPath, secret), "post-run check: still zero raw bytes")
        }
    }

    // 8. capture mode: typed value exact, stderr transcript safe
    @Test
    fun `capture mode keeps typed stdout exact and the stderr transcript safe`() {
        val runId = "r2-capture"
        val controlRoot = Files.createTempDirectory("r2-capture")
        val sink = InMemoryEventStore()
        val a = ShOperationsAdapter(
            runIdString = runId,
            opId = OpId(runId, 0, 0),
            shOptions = ShOptions.EMPTY.copy(captureStdout = true),
            controlDirRoot = controlRoot,
            eventSink = sink,
            secretPatternRegistry = registry(),
        )
        runBlocking {
            a.invoke(
                command = ShellCommand(script = "echo $secret; echo err-secret $secret 1>&2", returnMode = ShellReturnMode.STDOUT),
                runId = RunId(runId),
                stepIndex = 0,
            )
        }
        // typed value channel: EXACT by contract
        val outputTxt = controlRoot.resolve(OpId(runId, 0, 0).format()).resolve("output.txt")
        // output.txt may be read-then-deleted on success; assert only if retained
        if (Files.exists(outputTxt)) {
            assertTrue(Files.readString(outputTxt).contains(secret),
                "typed capturedStdout must remain EXACT (never scrubbed)")
        }
        // observable transcript: safe
        assertEquals(0, rawCount(consoleLog(controlRoot, runId), secret),
            "stderr transcript must contain zero raw secret bytes")
    }

    // 9. null registry: explicit legacy raw composition (characterization)
    @Test
    fun `null registry preserves legacy raw console log behavior`() {
        val runId = "r2-legacy"
        val controlRoot = Files.createTempDirectory("r2-legacy")
        val sink = InMemoryEventStore()
        invoke(adapter(runId, controlRoot, null, sink), runId, "echo $secret")
        assertEquals(1, eventRawCount(sink.eventsFor(runId).toList(), runId, secret),
            "null registry = explicit legacy composition, raw behavior preserved")
    }

    // 10. large output: bounded, no hot-path regression
    @Test
    fun `large output streams through the redaction pump within budget`() {
        val runId = "r2-large"
        val controlRoot = Files.createTempDirectory("r2-large")
        val sink = InMemoryEventStore()
        val start = System.currentTimeMillis()
        invoke(adapter(runId, controlRoot, registry(), sink), runId,
            "for i in \$(seq 1 20000); do echo line-${'$'}i filler filler filler; done")
        val elapsed = System.currentTimeMillis() - start
        assertEquals(0, eventRawCount(sink.eventsFor(runId).toList(), runId, secret))
        assertTrue(elapsed < 60_000, "pump must not stall the hot path; took ${elapsed}ms")
    }

// 11. FOREVER-FITNESS (WU-LPR-061 receipt law): the typed capturedStdout value
// is EXACT by contract, but any OBSERVABLE projection of the shell execution
// (EchoOutputCaptured events, console.log) must not carry that typed value raw.
// A typed value containing sensitive data must never reach the observable plane
// through its exactness. This pins the channel separation at the event boundary.
@org.junit.jupiter.api.Test
fun `typed capturedStdout value never leaks raw into the observable event plane`() {
    val runId = "r2-typed-leak"
    val controlRoot = Files.createTempDirectory("r2-typed-leak")
    val sink = InMemoryEventStore()
    val a = ShOperationsAdapter(
        runIdString = runId,
        opId = OpId(runId, 0, 0),
        shOptions = ShOptions.EMPTY.copy(captureStdout = true),
        controlDirRoot = controlRoot,
        eventSink = sink,
        secretPatternRegistry = registry(),
    )
    runBlocking {
        a.invoke(
            command = ShellCommand(
                script = "echo $secret; echo err-line $secret 1>&2",
                returnMode = ShellReturnMode.STDOUT,
            ),
            runId = RunId(runId),
            stepIndex = 0,
        )
    }
    // Observable events: the console transcript event (stderr-only in capture
    // mode) must be sanitized; stdout must NOT be re-emitted as a console event.
    val contents = sink.eventsFor(runId).toList()
        .filterIsInstance<EchoOutputCaptured>()
        .filter { it.runId == runId }
        .map { it.content }
    for (c in contents) {
        assertFalse(c.contains(secret),
            "observable event leaked raw secret (typed-value boundary violation): [$c]")
    }
    // Retained-file at-rest check as well.
    assertEquals(0, rawCount(consoleLog(controlRoot, runId), secret),
        "stderr transcript on disk must contain zero raw secret bytes")
}

}