package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.ShOperationsAdapter
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * WU-LPR-011R2 — Secret Redaction At-Rest Closure (Gate-1), on the Output Plane.
 *
 * ## The law, unchanged
 *
 * ```text
 * No unsanitized byte of process output may reach durable storage.
 * Sanitized bytes are persisted exactly once, in the Output Plane, which is also the only surface
 * for live observation, replay and post-mortem.
 * ```
 *
 * ## What changed, and what did not
 *
 * This suite used to prove the law by opening `console.log`. That file was a **staging buffer**:
 * `M1_P2_SINGLE_BYTE_AUTHORITY_RECEIPT.md` declared it would disappear "the moment the wrapper
 * grows a pipe protocol", and OBS-B2 is that moment. The pump now hands each sanitized chunk
 * straight to the Output Plane while the step runs.
 *
 * So the surface moved and the guarantees did not:
 *
 * | guarantee | disposition |
 * |---|---|
 * | redaction BEFORE durable persistence | PRESERVED — and now provable DURING the step |
 * | zero raw secret bytes at rest | PRESERVED, and checked on the physical segments too |
 * | redaction across chunk boundaries | PRESERVED — same redactor, same seam |
 * | a failing step keeps its transcript | PRESERVED — via the plane, not via a retained file |
 * | a timed-out step keeps its transcript | PRESERVED — same |
 * | `console.log` is the durable surface | SUPERSEDED — it is not written at all |
 *
 * The historical receipt is left alone. It records what was certified at the time; this file is
 * the superseding evidence.
 *
 * ## Why the DURING-execution row got STRONGER, not weaker
 *
 * The old version documented, in its own comments, that "sanitized bytes only reach `console.log`
 * near process exit" — and then widened the payload until that became true. It proved redaction
 * before persistence *eventually*. Now the plane is written during the step, so the row observes
 * the sanitized bytes while the child is provably still alive, with a sentinel file as the barrier
 * and no duration assertions at all.
 */
@Timeout(240)
class Lpr011r2SecretRedactionAtRestUatTest {

    private val secret = "GHS6_CANARY_7f3a9c2e1b4d5e6f"

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    private fun controlRoot(name: String): Path = Files.createDirectories(tempDir.resolve(name))

    private fun registry(): SecretPatternRegistry =
        SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

    private fun adapter(
        runId: String,
        controlRoot: Path,
        reg: SecretPatternRegistry?,
        eventSink: InMemoryEventStore,
        options: ShOptions = ShOptions.EMPTY,
    ): ShOperationsAdapter = ShOperationsAdapter(
        runIdString = runId,
        opId = OpId(runId, 0, 0),
        shOptions = options,
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

    /** Raw occurrences of [needle] in the committed transcript, read as a consumer would. */
    private fun planeRawCount(controlRoot: Path, runId: String, needle: String): Int =
        ConsolePlaneProbe
            .transcriptOrAbsent(controlRoot, runId, stageIndex = 0, stepIndex = 0)
            ?.split(needle)?.size?.minus(1)
            ?: 0

    private fun planeTranscriptOrNull(controlRoot: Path, runId: String): String? =
        ConsolePlaneProbe.transcriptOrAbsent(controlRoot, runId, stageIndex = 0, stepIndex = 0)

    /**
     * The transcript, or a failure that says WHICH guarantee was lost.
     *
     * `assertNotNull` returns Unit, so the nullable survives it and every use afterwards needs a
     * `!!` that would report a bare NPE. Here the message carries the guarantee, because
     * "transcript missing" means either the redaction law was dropped or post-mortem retention was
     * deleted, and those need different fixes.
     */
    private fun requiredPlaneTranscript(controlRoot: Path, runId: String, guarantee: String): String =
        planeTranscriptOrNull(controlRoot, runId)
            ?: throw AssertionError(
                "the Output Plane holds no transcript for run '$runId'. $guarantee A missing " +
                    "transcript here is either redaction no longer reaching persistence, or " +
                    "post-mortem retention having been deleted rather than migrated; the two " +
                    "are different failures and must not be collapsed into one null.",
            )

    /**
     * Raw occurrences of [needle] in the PHYSICAL store segments.
     *
     * This is the white-box half of the at-rest law, and it is not redundant with reading through
     * the port. A store that persisted raw bytes and scrubbed them on the way out would satisfy
     * every behavioural row in this file while still having written the secret to disk — and the
     * read port is exactly where such a mistake would hide. So the segments themselves are scanned.
     *
     * Only evidence, never an API: nothing in production reads the store this way.
     */
    private fun rawSecretBytesOnDisk(controlRoot: Path): Int {
        val planeDir = controlRoot.resolve(
            dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.OUTPUT_DIR,
        )
        if (!Files.isDirectory(planeDir)) return 0
        return Files.walk(planeDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToInt { file ->
                    val bytes = Files.readAllBytes(file)
                    countOccurrences(bytes, secret.toByteArray(Charsets.UTF_8))
                }
                .sum()
        }
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            count++
        }
        return count
    }

    // 1. whole secret, plain mode, successful run
    @Test
    fun `whole secret is redacted in the committed transcript of a successful run`() {
        val runId = "r2-whole"
        val root = controlRoot("r2-whole")
        invoke(adapter(runId, root, registry(), InMemoryEventStore()), runId, "echo $secret")

        val transcript = requiredPlaneTranscript(
            root, runId,
            "A successful `echo` must leave a transcript. Without this the next assertion would " +
                "also pass on an empty plane, and 'no raw secret' is indistinguishable from " +
                "'nothing was ever written'.",
        )
        assertEquals(0, transcript.split(secret).size - 1, "the transcript must carry zero raw secret bytes")
        assertTrue(transcript.contains("****"), "the marker must be present, not merely the secret's absence")
        assertEquals(0, rawSecretBytesOnDisk(root), "no segment on disk may hold the raw secret")
    }

    // 2. split across two child writes
    @Test
    fun `secret split across two child writes is redacted`() {
        val runId = "r2-split2"
        val root = controlRoot("r2-split2")
        val half = secret.length / 2
        invoke(
            adapter(runId, root, registry(), InMemoryEventStore()),
            runId,
            "printf '%s' '${secret.substring(0, half)}'; sleep 0.05; printf '%s\\n' '${secret.substring(half)}'",
        )
        val transcript = requiredPlaneTranscript(root, runId, "A split secret must leave a transcript.")
        assertEquals(0, transcript.split(secret).size - 1, "split secret must never persist raw")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 3. byte by byte: forces the redactor across every possible boundary
    @Test
    fun `secret emitted byte by byte is redacted`() {
        val runId = "r2-splitn"
        val root = controlRoot("r2-splitn")
        val chars = secret.chunked(1).joinToString("") { c -> "printf '%s' '$c';" }
        invoke(adapter(runId, root, registry(), InMemoryEventStore()), runId, chars)
        val transcript = requiredPlaneTranscript(root, runId, "A byte-wise secret must leave a transcript.")
        assertEquals(0, transcript.split(secret).size - 1, "byte-wise secret must never reach the plane raw")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 4. stderr, plain mode (merged)
    @Test
    fun `secret on stderr is redacted`() {
        val runId = "r2-stderr"
        val root = controlRoot("r2-stderr")
        invoke(adapter(runId, root, registry(), InMemoryEventStore()), runId, "echo $secret 1>&2")
        val transcript = requiredPlaneTranscript(root, runId, "A stderr secret must leave a transcript.")
        assertEquals(0, transcript.split(secret).size - 1, "stderr secret must never persist raw")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 5. Gate-1: a FAILING child still leaves a usable, sanitized transcript
    @Test
    fun `a failing child leaves a sanitized transcript available for post-mortem`() {
        val runId = "r2-fail-retain"
        val root = controlRoot("r2-fail-retain")
        invoke(
            adapter(runId, root, registry(), InMemoryEventStore()),
            runId,
            "echo $secret; echo about-to-fail 1>&2; exit 3",
        )

        val transcript = requiredPlaneTranscript(
            root, runId,
            "GATE-1: the failed run's transcript must survive. Post-mortem retention is now the " +
                "Output Plane's job, so losing it here would mean the guarantee was deleted rather " +
                "than migrated.",
        )
        assertEquals(0, transcript.split(secret).size - 1, "GATE-1: zero raw secret bytes")
        assertTrue(transcript.contains("****"), "the redaction marker must be present for a human reading it")
        assertTrue(transcript.contains("about-to-fail"), "the surrounding context must survive, or the transcript is useless")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 6. timeout / interruption retention
    @Test
    fun `a timed-out child leaves a sanitized transcript available for post-mortem`() {
        val runId = "r2-timeout"
        val root = controlRoot("r2-timeout")
        invoke(
            adapter(
                runId, root, registry(), InMemoryEventStore(),
                options = ShOptions.EMPTY.copy(timeoutMs = 1500L),
            ),
            runId,
            "echo $secret; sleep 30",
        )

        val transcript = requiredPlaneTranscript(
            root, runId,
            "the timeout-killed run's transcript must survive.",
        )
        assertEquals(0, transcript.split(secret).size - 1, "timeout-killed transcript must carry zero raw bytes")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 7. DURING execution — the row that got stronger
    @Test
    fun `sanitized bytes are committed to the plane while the child is still alive`() {
        val runId = "r2-live"
        val root = controlRoot("r2-live")
        val barrier = root.resolve("BARRIER_REACHED")
        val released = root.resolve("RELEASED")

        // The child emits enough matched bytes to cross many redactor lookaheads and many live
        // windows, then blocks on a sentinel. A barrier, not a sleep, is what makes "while the step
        // is alive" a fact rather than an estimate.
        val payload = (1..400).joinToString("; ") { "printf '%s' '${secret}-line-$it'" }
        val script = "$payload; touch $barrier; while [ ! -s $released ]; do sleep 0.05; done"

        val step = thread(name = "r2-live-step") {
            invoke(adapter(runId, root, registry(), InMemoryEventStore()), runId, script)
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
            while (!Files.exists(barrier) && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(barrier), "the step never reached its barrier, so nothing observed here means nothing")
            Thread.sleep(1000)

            val live = requiredPlaneTranscript(
                root, runId,
                "The plane held nothing while the step was provably alive. Redaction before " +
                    "persistence has not been proven DURING execution, which is the whole point " +
                    "of this row.",
            )
            assertEquals(0, live.split(secret).size - 1, "a live transcript must never contain the raw secret")
            assertTrue(
                live.contains("****"),
                "the live transcript must carry the redaction marker, not merely omit the secret: " +
                    "a truncated transcript would also pass the previous assertion",
            )
            assertEquals(0, rawSecretBytesOnDisk(root), "live segments must be sanitized on disk too")
        } finally {
            Files.writeString(released, "go")
            step.join(90_000)
        }

        // After the step, the same stream continues from where it was — not rewritten.
        assertEquals(0, planeRawCount(root, runId, secret), "post-run check: still zero raw bytes")
    }

    // 8. capture mode: the typed value stays exact, the transcript stays safe
    @Test
    fun `capture mode keeps the typed stdout exact and the transcript safe`() {
        val runId = "r2-capture"
        val root = controlRoot("r2-capture")
        runBlocking {
            adapter(
                runId, root, registry(), InMemoryEventStore(),
                options = ShOptions.EMPTY.copy(captureStdout = true),
            ).invoke(
                command = ShellCommand(
                    script = "echo $secret; echo err-secret $secret 1>&2",
                    returnMode = ShellReturnMode.STDOUT,
                ),
                runId = RunId(runId),
                stepIndex = 0,
            )
        }

        // The typed VALUE channel is exact by contract and is never scrubbed. output.txt may be
        // read-then-deleted on success, so it is asserted only when retained.
        val outputTxt = root.resolve(OpId(runId, 0, 0).format()).resolve("output.txt")
        if (Files.exists(outputTxt)) {
            assertTrue(
                Files.readString(outputTxt).contains(secret),
                "the typed capturedStdout must remain EXACT (never scrubbed)",
            )
        }
        // The observable channel is the transcript, and it is the sanitized one. Its EXISTENCE is
        // part of the claim: this script writes to stderr on purpose, so an absent stream would
        // mean the stderr channel stopped reaching observability at all.
        val transcript = requiredPlaneTranscript(
            root, runId,
            "capture mode with output on stderr must still leave an observable transcript.",
        )
        assertEquals(0, transcript.split(secret).size - 1, "the stderr transcript must carry zero raw bytes")
        assertTrue(transcript.contains("err-secret"), "the stderr line must survive, sanitized")
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 9. negative control: with no registry the composition is explicitly raw
    @Test
    fun `null registry preserves legacy raw behaviour`() {
        val runId = "r2-legacy"
        val root = controlRoot("r2-legacy")
        invoke(adapter(runId, root, null, InMemoryEventStore()), runId, "echo $secret")

        // The negative control for every row above, so their zeros mean something. If this ever
        // reads 0, either redaction became unconditional — and the "explicit" in the composition is
        // a lie — or the reader lost the stream.
        assertEquals(
            1,
            planeRawCount(root, runId, secret),
            "null registry is an explicit legacy composition; raw behaviour must be preserved",
        )
    }

    // 10. large output does not stall the hot path
    @Test
    fun `large output streams through the ingress within budget`() {
        val runId = "r2-large"
        val root = controlRoot("r2-large")
        val start = System.currentTimeMillis()
        invoke(
            adapter(runId, root, registry(), InMemoryEventStore()),
            runId,
            "for i in \$(seq 1 20000); do echo line-${'$'}i filler filler filler; done",
        )
        val elapsed = System.currentTimeMillis() - start
        val transcript = requiredPlaneTranscript(root, runId, "20k lines of output must leave a transcript.")
        assertEquals(0, transcript.split(secret).size - 1)
        val emittedLines = transcript.count { it == '\n' }
        assertTrue(emittedLines > 15_000, "the transcript lost lines: $emittedLines newlines for 20k emitted")
        assertTrue(elapsed < 120_000, "the ingress must not stall the hot path; took ${elapsed}ms")
    }

    // 11. the typed value's exactness must not become a leak into an observable surface
    @Test
    fun `the typed capturedStdout value never leaks raw into the transcript`() {
        val runId = "r2-typed-leak"
        val root = controlRoot("r2-typed-leak")
        runBlocking {
            adapter(
                runId, root, registry(), InMemoryEventStore(),
                options = ShOptions.EMPTY.copy(captureStdout = true),
            ).invoke(
                command = ShellCommand(
                    script = "echo $secret; echo err-line $secret 1>&2",
                    returnMode = ShellReturnMode.STDOUT,
                ),
                runId = RunId(runId),
                stepIndex = 0,
            )
        }

        val transcript = requiredPlaneTranscript(
            root, runId,
            "capture mode with a stderr line must leave an observable transcript.",
        )
        assertFalse(
            transcript.contains(secret),
            "the observable transcript leaked the raw secret: a typed-value boundary violation. Got: [$transcript]",
        )
        assertEquals(0, rawSecretBytesOnDisk(root))
    }

    // 12. the staging file is not a second copy of anything
    @Test
    fun `the canonical path writes no staging transcript`() {
        val runId = "r2-no-staging"
        val root = controlRoot("r2-no-staging")
        invoke(adapter(runId, root, registry(), InMemoryEventStore()), runId, "echo hello; echo $secret")

        val controlDir = root.resolve(OpId(runId, 0, 0).format())
        val staging = controlDir.resolve(
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellFiles.CONSOLE_LOG,
        )
        assertFalse(
            Files.exists(staging),
            "console.log was written again. The transcript must be durable in exactly one place; " +
                "a staging file beside the plane is a second byte authority.",
        )
    }
}
