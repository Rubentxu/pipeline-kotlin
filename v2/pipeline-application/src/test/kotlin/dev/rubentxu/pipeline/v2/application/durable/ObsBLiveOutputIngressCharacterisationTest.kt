package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * OBS-B — characterises the two defects that stand between a child process and an observable
 * byte, measured **at the Output Plane**, not at the terminal.
 *
 * ## Why the store and not the console
 *
 * OBS-A characterised what the *user* sees. That leaves the question "is the store already
 * progressive, and only nobody is reading it?" unanswered, and the answer decides the size of the
 * fix. This file answers it, so the fix is sized by evidence instead of by the shape of the
 * architecture one would have liked.
 *
 * ## The two defects, and they are different defects
 *
 * **D1 — the producer is late, always.** `ShExecution` ingests `console.log` into the Output Plane
 * *after* the step returns. The store is not at fault: `OutputStreamHandle.appendFrom` is
 * documented as "a full reserve → write → commit cycle [...] a reader tailing the stream sees the
 * transcript in order as it is produced". Nothing feeds it progressively. Measured at 15 B and at
 * 32 KiB: **empty both times**, so no payload size escapes this one.
 *
 * **D2 — the staging file withholds until a copy buffer fills.** The pump drains the child's PIPE
 * with `redacted.copyTo(sink, 8192)`. `RedactingInputStream.read` returns only once it has emitted
 * `len` bytes or reached EOF, so **the copy buffer size IS the flush granularity of the entire live
 * transcript path** — not the `BufferedOutputStream`, and not the redactor's lookahead window.
 *
 * That last attribution was measured, not assumed, and two earlier guesses were wrong:
 *
 * ```text
 * MUTATION unbuffered sink            → no row changes
 * MUTATION redactor lookahead → 1     → no row changes (and a broken redactor: maxLiteral=1 makes
 *                                                 `read` spin, because it tests ringCount == 1
 *                                                 and a pipe read overshoots in one step)
 * MUTATION redactor lookahead → 4
 *            + unbuffered sink        → no row changes
 * MUTATION copyTo(sink, 8192) → 8     → the staging row flips RED with exactly 8 bytes
 * ```
 *
 * The 8 is the mutation value. That is the attribution.
 *
 * The practical consequence for OBS-B2: a flush discipline alone does **not** make the transcript
 * live for short output, and fixing D1 does not fix D2. They are two edits in two places.
 *
 * The second row of each test is the **non-vacuity witness**: a payload that crosses the buffer
 * size DOES appear mid-step. Without it, "nothing arrived" would be indistinguishable from "the
 * harness observed the wrong thing".
 *
 * ## Why barriers and not durations
 *
 * The step blocks on a sentinel file. The observation is taken while the step is *provably* alive,
 * which is a fact about the product rather than about scheduling.
 *
 * ## Fidelity
 *
 * Crosses the productive authority: the real `ShExecution.invokeShell`, the real
 * `DurableShellExecutor` pump, and the real `SegmentOutputStore` that the run's own provider
 * hands out. Reads the store through the published `OutputReadPort` cursor, so nothing here
 * reimplements the thing under test. `@TempDir`, no ambient cwd/env/network.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ObsBLiveOutputIngressCharacterisationTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun setUp() {
        // The provider caches one recovered store per control-dir root. Without this a row would
        // inherit another row's store and read the wrong bytes.
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    private lateinit var controlDirRoot: Path
    private lateinit var workspaceRoot: Path

    /**
 * What a run of one blocked step yields: what the store held while blocked, and what it held after. */
    private class Observation(
        val storeDuringBlock: StoreView,
        val storeAfter: StoreView,
        val consoleLogBytesDuringBlock: Long,
        val terminal: ShellInvocationResult?,
    )

    /**
     * What the read side reports for a stream — as an external consumer would see it.
     *
     * A stream that has never been written to is a typed **refusal**, not an empty page. That
     * distinction is the whole measurement: "refused as unknown" means nothing was ingested yet,
     * while a page of zero bytes means something was ingested and it was empty. Collapsing the two
     * would let a store that ingests empty transcripts pass as one that has not run yet.
     */
    private sealed interface StoreView {
        data class Present(val bytes: ByteArray) : StoreView
        data class Absent(val refusal: OutputRefusal) : StoreView
    }

    /**
     * Runs one `sh` step that emits [emitter], reaches a sentinel, and blocks until released.
     *
     * [emitter] is a complete shell fragment that may span lines, so it is joined with newlines
     * rather than `;` — a heredoc terminator must own its line.
     *
     * The emitter runs BEFORE the barrier, so the bytes are already produced by the child at the
     * moment the store is sampled. That is the whole point: the bytes exist, the child is alive,
     * and only the durability of them is in question.
     */
    private fun observeBlockedStep(runId: String, emitter: String): Observation {
        controlDirRoot = Files.createDirectories(root.resolve("control-$runId"))
        workspaceRoot = Files.createDirectories(root.resolve("workspace-$runId"))
        val barrier = workspaceRoot.resolve("BARRIER_REACHED")
        val released = workspaceRoot.resolve("RELEASED")

        val script = listOf(
            emitter.trimEnd('\n'),
            "touch ${barrier.toAbsolutePath()}",
            "while [ ! -s ${released.toAbsolutePath()} ]; do sleep 0.05; done",
        ).joinToString("\n")

        // The step is blocking work, so it belongs on a plain thread; `invokeShell` is suspend, so
        // the bridge is runBlocking and NOT a coroutine dispatcher. That keeps the step on its own
        // thread while this test samples the store from the test thread.
        val terminal = arrayOfNulls<ShellInvocationResult>(1)
        val runner = thread(name = "sh-step-$runId") {
            terminal[0] = runBlocking {
                ShExecution.invokeShell(
                    command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                    opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                    runId = runId,
                    stageIndex = 0,
                    stepIndex = 0,
                    shOptions = ShOptions(
                        workspaceRoot = workspaceRoot,
                        captureStdout = false,
                        timeoutMs = 120_000,
                        env = emptyMap(),
                        sandbox = SandboxConfig.NONE,
                    ),
                    controlDirRoot = controlDirRoot,
                    eventSink = InMemoryEventStore(),
                    // The production path ALWAYS supplies a registry (Main.kt builds one per run),
                    // and supplying one is what activates the PIPE + redaction pump. Omitting it —
                    // which this harness did at first, and which is the parameter's default — leaves
                    // `transcriptRedactor` null, so the wrapper redirects straight to console.log and
                    // the pump never runs. That is a path the CLI cannot take, and measuring it
                    // would have characterised a substrate that does not ship.
                    secretPatternRegistry = SecretPatternRegistry(),
                )
            }
        }

        // Bounded wait on the sentinel: the step is provably alive from here on.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (!Files.exists(barrier) && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(
            Files.exists(barrier),
            "the step never reached its barrier, so everything observed here would mean nothing",
        )
        // Identical settle window in every row, so a difference between rows is the payload and
        // not how long we waited.
        Thread.sleep(1500)

        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId(runId, OpId(runId, 0, 0).format())
        val duringBlock = readAll(store, stream)
        val consoleDuringBlock = consoleLogSize()

        Files.writeString(released, "go")
        runner.join(TimeUnit.SECONDS.toMillis(90))
        assertTrue(!runner.isAlive, "the step thread did not finish after release")

        return Observation(
            storeDuringBlock = duringBlock,
            storeAfter = readAll(store, stream),
            consoleLogBytesDuringBlock = consoleDuringBlock,
            terminal = terminal[0],
        )
    }

    /** Reads through the published read port, exactly as any external consumer would. */
    private fun readAll(
        store: SegmentOutputStore,
        stream: dev.rubentxu.pipeline.v2.output.OutputStreamId,
    ): StoreView {
        val first = store.read(stream, OutputCursor.start(stream), 4096)
        if (first is OutputReadResult.Refused) return StoreView.Absent(first.reason)

        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = assertInstanceOf(
                OutputReadResult.Page::class.java,
                store.read(stream, cursor, 4096),
                "a stream that has been opened must page",
            ).page
            out.write(page.bytes)
            cursor = page.next
        }
        return StoreView.Present(out.toByteArray())
    }

    /** Bytes read back, or an empty array for a stream that does not exist — with the refusal checked. */
    private fun StoreView.bytesOrEmpty(where: String): ByteArray = when (this) {
        is StoreView.Present -> bytes
        is StoreView.Absent -> {
            // Only "nothing was ever written" is an acceptable absence. Any other refusal means the
            // store is damaged or this cursor is wrong, and reading that as "no bytes yet" would
            // turn a real defect into a passing characterisation.
            assertInstanceOf(
                OutputRefusal.UnknownStream::class.java,
                this.refusal,
                "$where refused for a reason that is NOT 'never written': ${this.refusal}. Treating " +
                    "that as an empty transcript would hide the defect this row is trying to measure.",
            )
            ByteArray(0)
        }
    }

    private fun consoleLogSize(): Long =
        Files.walk(controlDirRoot)
            .filter { it.fileName?.toString() == "console.log" }
            .filter { Files.isRegularFile(it) }
            .max(Comparator.comparing { Files.size(it) })
            .map { Files.size(it) }
            .orElse(-1L)

    /** Bytes emitted before the barrier. Far below the staging buffer, so the buffer cannot help. */
    private fun smallEmitter(): String = "printf '%s' '${SMALL_PAYLOAD.trimEnd('\n')}'; echo"

    /**
     * Emits exactly [LARGE_PAYLOAD_BYTES] bytes through a single `cat`, so the byte count is a
     * property of the script rather than an estimate. The heredoc terminator owns its line.
     *
     * [body] already ends with the newline that terminates its last line, so the delimiter follows
     * it directly. Separating them with another `\n` emits one extra byte, which the byte-exact
     * assertion in the WITNESS row caught the first time this was written.
     */
    private fun largeEmitter(): String {
        val body = "x".repeat(LARGE_PAYLOAD_BYTES - 1) + "\n"
        return "cat <<'PAYLOAD_EOF'\n${body}PAYLOAD_EOF"
    }

    private companion object {
        /**
         * The exact number of bytes [largeEmitter] produces before the barrier.
         *
         * Asserted by the rows rather than assumed, because "past the 8 KiB buffer" is the whole
         * premise of the discriminator: a payload that only *looks* large would make the staging
         * buffer look innocent, and the two defects in this file would silently merge into one.
         */
        const val LARGE_PAYLOAD_BYTES: Int = 32 * 1024

        const val SMALL_PAYLOAD: String = "MARCADOR-CHICO\n"
        const val SMALL_PAYLOAD_BYTES: Int = SMALL_PAYLOAD.length
    }

    // ------------------------------------------------------------ D1: the store

    /**
     * CHARACTERISATION — a payload BELOW the staging buffer size: the store holds nothing while
     * the step is alive, and everything once it ends.
     *
     * The mid-step observation is a typed `UnknownStream` refusal, not an empty page: the stream
     * has never been opened. "Ingested nothing" and "was never ingested" are different facts and
     * the store distinguishes them, so this row states which one it saw.
     *
     * Closed by: OBS-B2. Invert then — assert the marker IS in the mid-step bytes — and do not
     * delete the row.
     */
    @Test
    fun `CHAR a small transcript reaches the Output Plane only after the step ends`() {
        val runId = "r-obsb-d1-small"
        val observation = observeBlockedStep(runId, smallEmitter())

        val during = observation.storeDuringBlock.bytesOrEmpty("mid-step read")
        assertEquals(
            0,
            during.size,
            "CHARACTERISED: the store already held ${during.size} bytes while the step was " +
                "provably alive. OBS-B2 has landed and this row must be INVERTED into a " +
                "non-regression test, not deleted.",
        )
        val after = observation.storeAfter.bytesOrEmpty("post-step read").toString(Charsets.UTF_8)
        assertEquals(
            SMALL_PAYLOAD,
            after,
            "sanity: the bytes DO arrive once the step ends, and byte-exactly, so the absence " +
                "above is about timing and not about a lossy or redaction-damaged pipeline.",
        )
    }

    /**
     * NON-VACUITY WITNESS — the store's lateness is **unconditional**, unlike the staging file's.
     *
     * This row was first written asserting the opposite — that a payload past the 8 KiB staging
     * buffer *does* reach the store mid-step. It failed, and the failure is the finding that makes
     * the file worth having: the store was empty for the large payload too.
     *
     * So the two defects are not two sizes of one defect:
     *
     * ```text
     * console.log  late only while the transcript is under 8 KiB   (buffer overflow flushes it)
     * Output Plane late for every payload, however large          (the producer runs after the step)
     * ```
     *
     * Only the first would be fixed by a flush discipline. Asserting them together would let a
     * flush-only fix look like a complete one, which is the mistake this row exists to prevent.
     *
     * Closed by: OBS-B2 — then it inverts, and the store must be non-empty mid-step for BOTH sizes.
     */
    @Test
    fun `WITNESS the store is empty mid-step for a large payload too`() {
        val runId = "r-obsb-d1-large"
        assertTrue(
            LARGE_PAYLOAD_BYTES > 8 * 1024,
            "premise broken: the 'large' payload is only $LARGE_PAYLOAD_BYTES bytes, so it proves " +
                "nothing about crossing the staging buffer.",
        )
        val observation = observeBlockedStep(runId, largeEmitter())

        val during = observation.storeDuringBlock.bytesOrEmpty("mid-step read")
        assertEquals(
            0,
            during.size,
            "the witness failed: the store held ${during.size} bytes mid-step for a $LARGE_PAYLOAD_BYTES " +
                "byte payload. If this is OBS-B2 landing, invert this row. If it is not, the store's " +
                "lateness is no longer unconditional and the two defects in this file have merged " +
                "into one — which would change the fix.",
        )
        assertTrue(
            observation.consoleLogBytesDuringBlock > 0,
            "sanity: for the SAME payload and the SAME barriers, console.log WAS mid-write " +
                "(${observation.consoleLogBytesDuringBlock} bytes) while the store held nothing. " +
                "Without this contrast the two CHAR rows could both be explained by one defect, " +
                "and this file would claim two where there is one.",
        )
        assertEquals(
            LARGE_PAYLOAD_BYTES.toLong(),
            observation.storeAfter.bytesOrEmpty("post-step read").size.toLong(),
            "sanity: the whole payload is durable once the step ends, so the mid-step emptiness " +
                "above is a timing property and not a payload that never arrived.",
        )
    }

    // ------------------------------------------------------------ D2: the staging buffer

    /**
     * CHARACTERISATION — `console.log` is itself invisible mid-step for a small payload.
     *
     * The holder is the pump's copy granularity (`DurableShellExecutor`: `redacted.copyTo(sink,
     * 8192)`), because `RedactingInputStream.read` returns only at `len` bytes or EOF. Shrinking
     * that call to 8 bytes makes this row report exactly 8 bytes, which is what attributes it.
     *
     * Closed by: OBS-B2, which stops writing `console.log` at all.
     */
    @Test
    fun `CHAR the staging transcript is empty mid-step for a small payload`() {
        val runId = "r-obsb-d2-small"
        val observation = observeBlockedStep(runId, smallEmitter())

        assertEquals(
            0L,
            observation.consoleLogBytesDuringBlock,
            "CHARACTERISED: console.log already held ${observation.consoleLogBytesDuringBlock} bytes " +
                "mid-step, for a $SMALL_PAYLOAD_BYTES byte payload. OBS-B2 has landed and this row " +
                "must be INVERTED, not deleted.",
        )
    }

    /**
     * DISCRIMINATOR — the staging file fills once a copy buffer is satisfied; the store never does.
     *
     * Same script shape as the row above, same settle window, only the byte count moves. This is
     * the measurement that separates the two defects: if the staging file appears for the large
     * payload while the store stays empty (the WITNESS row), then the copy granularity is the
     * staging file's holder and the producer's timing is the store's, and no single edit covers both.
     *
     * The first version of this file emitted exactly 8 KiB here and the row failed. A payload that
     * exactly fills the copy buffer still does not appear, because `read` returns on `len` only if
     * it can emit `len` bytes — and 8 KiB of payload minus the redactor's retained lookahead is
     * short of 8 KiB. The size is now asserted rather than assumed.
     */
    @Test
    fun `DISCRIMINATOR the staging transcript fills once past the copy granularity`() {
        val runId = "r-obsb-d2-large"
        val observation = observeBlockedStep(runId, largeEmitter())

        assertTrue(
            observation.consoleLogBytesDuringBlock > 0,
            "the discriminator failed: console.log held ${observation.consoleLogBytesDuringBlock} " +
                "bytes mid-step for a $LARGE_PAYLOAD_BYTES byte payload. Either the copy granularity " +
                "is gone — in which case OBS-B2 landed and this row inverts — or this harness is " +
                "blind, in which case the CHAR row above proved nothing.",
        )
        assertTrue(
            observation.consoleLogBytesDuringBlock < LARGE_PAYLOAD_BYTES,
            "the staging file held the whole $LARGE_PAYLOAD_BYTES byte payload mid-step, so nothing " +
                "is withheld at all and the CHAR row above is measuring something other than a " +
                "withheld tail.",
        )
    }
}