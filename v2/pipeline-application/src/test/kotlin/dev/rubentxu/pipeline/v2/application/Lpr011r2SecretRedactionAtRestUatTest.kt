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
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
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

    /**
     * Raw occurrences of [needle] in the transcript held by the OUTPUT PLANE.
     *
     * This replaces counting occurrences inside `EchoOutputCaptured`, and the replacement is not
     * cosmetic. M1 made the plane the owner of process bytes, so that channel stopped carrying
     * them — and every `assertEquals(0, eventRawCount(...))` in this file became an assertion
     * about an empty channel. Zero read from a channel that never had the bytes proves nothing
     * about redaction; it proves the reader was looking somewhere the secret never went. For a
     * suite whose whole law is "zero raw secret bytes may reach an observable surface", that is
     * the one thing it must not be able to claim, so the count is taken where the bytes now are.
     *
     * [needle] is counted in the plane only. The durable `console.log` file is a SEPARATE
     * surface with its own retention rules, and the rows below that assert on it keep doing so
     * deliberately: a transcript can be retained on disk for post-mortem after a failed run.
     */
    private fun planeRawCount(controlRoot: Path, runId: String, needle: String): Int =
        ConsolePlaneProbe
            .transcript(controlRoot, runId, stageIndex = 0, stepIndex = 0)
            .split(needle).size - 1

    // 1. whole secret, plain mode, SUCCESS run: cleanup deletes the control dir,
    // so at-rest content is proven by the live-window and retention tests below;
    // here the observable event plane must be redacted.
    @Test
    fun `whole secret is redacted in the observable event of a successful run`() {
        val runId = "r2-whole"
        val controlRoot = Files.createTempDirectory("r2-whole")
        val sink = InMemoryEventStore()
        invoke(adapter(runId, controlRoot, registry(), sink), runId, "echo $secret")
        assertEquals(0, planeRawCount(controlRoot, runId, secret),
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret))
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret),
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret))
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret))
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret))
    }

    // 7. DURING EXECUTION proof: redaction before persistence, not cleanup-time
    @Test
    fun `console log contains no raw secret while the child is still alive`() {
        val runId = "r2-live"
        val controlRoot = Files.createTempDirectory("r2-live")
        val sink = InMemoryEventStore()
        val logPath = consoleLog(controlRoot, runId)
        // Launch on another thread; poll the transcript WHILE the child sleeps.
        // WU-RP-101: determinism fix for the DURING-EXECUTION live window.
        // Earlier rounds (r6..r12) targeted a 100-line × 34-byte payload that
        // crossed StreamingRedactor.maxLiteralByteLength once per line. Under
        // modern JVM BufferedWriter buffers + kernel pipe coalescing, the
        // single observation window (sleep 2) was not enough to (a) fill
        // the executor's stream buffer and (b) force a flush to console.log
        // before the child exited. The fix widens BOTH the volume (8 KiB of
        // matched bytes ⇒ multiple match-and-emit cycles) and the live
        // observation window (sleep 10). The contract under test is
        // unchanged: bytes reaching console.log DURING execution must be
        // sanitized (the **** marker) and zero raw secret bytes may appear
        // there. We do NOT relax any assertion.
        val t = Thread {
            // 1000 echo lines × ~34 bytes ≈ 34 KiB, well above the typical
            // 8 KiB pipe buffer; sleep 10 keeps the child alive long enough
            // for at least one flush to reach disk while the
            // StreamingRedactor continues to emit **** markers.
            invoke(adapter(runId, controlRoot, registry(), sink), runId,
                "for i in \$(seq 1 1000); do echo $secret-line-\$i; done; sleep 10")
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
        // WU-RP-101: polling tightened. Files.getLastModifiedTime-driven
        // polling keeps the live read window open for the FULL 30-second
        // budget instead of breaking on the first **** sighting. We keep
        // the early-out on **** for fast paths; if no **** appears we still
        // reach the join() and fail loud (the contract demands a sanitized
        // live transcript).
        val deadline = System.currentTimeMillis() + 30_000
        var observedLive = false
        var lastModified = 0L
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(logPath)) {
                lastModified = try {
                    Files.getLastModifiedTime(logPath).toMillis()
                } catch (_: Exception) { 0L }
                val content = try { Files.readString(logPath) } catch (_: Exception) { "" }
                if (content.contains("****")) {
                    assertEquals(0, rawCount(logPath, secret),
                        "live transcript must never contain the raw secret")
                    assertFalse(content.contains("echo $secret"), "script echo must be scrubbed live")
                    observedLive = true
                    break
                }
            }
            Thread.sleep(20)
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
        // The NEGATIVE control for every row above, so the zeros mean something: with no
        // registry the redaction pump is absent and the secret must survive RAW. If this ever
        // reads 0, either redaction became unconditional (and the "explicit" in the
        // composition is a lie) or the reader lost the stream — which is why it is counted on
        // the plane rather than inferred from an empty event channel.
        assertEquals(1, planeRawCount(controlRoot, runId, secret),
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
        assertEquals(0, planeRawCount(controlRoot, runId, secret))
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
    // The observable surface: the Output Plane transcript (stderr-only in capture mode) must be
    // sanitized. It used to assert over EchoOutputCaptured contents, which after M1 is a list
    // that is always empty for a `sh` step — so the loop below proved nothing at all. The typed
    // stdout value is EXACT by contract and is deliberately NOT read here: the law is that its
    // exactness must not become a leak into any observable surface, which is only checkable
    // against the surface itself.
    val planeTranscript = ConsolePlaneProbe
        .transcript(controlRoot, runId, stageIndex = 0, stepIndex = 0)
    assertFalse(planeTranscript.contains(secret),
        "the observable transcript leaked the raw secret (typed-value boundary violation): " +
            "[$planeTranscript]")
    // Retained-file at-rest check as well.
    assertEquals(0, rawCount(consoleLog(controlRoot, runId), secret),
        "stderr transcript on disk must contain zero raw secret bytes")
}

}
