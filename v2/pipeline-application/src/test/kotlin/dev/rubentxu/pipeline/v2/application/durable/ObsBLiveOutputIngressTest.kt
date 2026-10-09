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
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.TRANSCRIPT_LIVE_WINDOW_BYTES
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
 * ## What this file used to characterise, and what it asserts now
 *
 * As CHARACTERISATIONS, every row asserted a defect. OBS-B2 landed and each one is INVERTED:
 *
 * ```text
 * D1  the producer was late, unconditionally     -> the store is written WHILE the step runs
 * D2  the staging file withheld a short tail     -> the staging file does not exist at all
 * ```
 *
 * The attribution of D2 was measured, not assumed, and two earlier guesses were wrong. With
 * `DurableShellExecutor`'s pump at `redacted.copyTo(sink, 8192)`:
 *
 * ```text
 * MUTATION unbuffered sink                    -> no row changes
 * MUTATION redactor lookahead -> 1            -> no row changes (and a broken redactor)
 * MUTATION redactor lookahead -> 4 + unbuffered-> no row changes
 * MUTATION copyTo(sink, 8192) -> copyTo(sink, 8)-> the staging row flips RED with exactly 8 bytes
 * ```
 *
 * The 8 is the mutation value, and that is what attributed it: `RedactingInputStream.read` returns
 * only at `len` bytes or EOF, so the copy buffer WAS the flush granularity of the whole path.
 *
 * ## The same defect survived OBS-B2, at a smaller size, and this file said so wrongly
 *
 * The pump stopped being `copyTo` and became an explicit read loop, but it kept asking for a whole
 * `TRANSCRIPT_LIVE_WINDOW_BYTES`, so the flush granularity did not go away — it just shrank from
 * 8 KiB to 1 KiB. This file then claimed the residual latency was the redactor's lookahead, and
 * asserted the consequence: a short payload is NOT visible mid-step.
 *
 * Both halves of that were wrong, and the rows were written so that neither could be noticed:
 * `SMALL_PAYLOAD_BYTES` (15) is larger than `MIN_SECRET_WINDOW` (7), so there were always bytes the
 * redactor could emit, and the row asserted only `during.size < payload` — a condition that held for
 * "nothing was committed" and for "all but the lookahead were committed" alike.
 *
 * Measured against the installed distribution, with the step provably blocked: 4 KiB appeared live,
 * 20 bytes did not, and the Output Plane held zero segments until the step ended. That is a console
 * quantised in 1024-byte steps, not a live one. The pump now asks for `min(window, available())`,
 * which moves the floor from 1024 bytes to the 7 bytes that redaction genuinely requires, and the
 * rows below assert that tight bound instead of a loose one that passed either way.
 *
 * What is still deliberately NOT asserted:
 *
 * - the whole tail is NOT visible mid-step — the redactor withholds up to `MIN_SECRET_WINDOW` bytes
 *   because a byte inside them could still begin a secret. Emitting them early would be a leak, so
 *   that floor is a property to certify, not a latency to tune;
 * - the staging file is not "eventually visible" — it is absent in both windows, because a file
 *   that reappears at the end is the shape this change exists to remove.
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
class ObsBLiveOutputIngressTest {

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
        val consoleLogBytesAfterRelease: Long,
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
        // OBS-C2.3: this row is about LIVE VISIBILITY, not about channels, so it reads whichever
        // stream carries the work. A plain `sh` step writes its stdout to the stdout-addressed
        // stream and its stderr to the stderr-addressed one; the emitters here write to stdout, so
        // that is the stream sampled. Reading the old two-argument merged stream would report an
        // empty store for a producer that is working perfectly.
        val streams = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 0).format())
        val stream = streams.stdout.stream
        val duringBlock = readAll(store, stream)
        val consoleDuringBlock = consoleLogSize()

        Files.writeString(released, "go")
        runner.join(TimeUnit.SECONDS.toMillis(90))
        assertTrue(!runner.isAlive, "the step thread did not finish after release")

        return Observation(
            storeDuringBlock = duringBlock,
            storeAfter = readAll(store, stream),
            consoleLogBytesDuringBlock = consoleDuringBlock,
            consoleLogBytesAfterRelease = consoleLogSize(),
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
         * Asserted by the rows rather than assumed, because "past the live window" is the whole
         * premise of the discriminator: a payload that only *looks* large would make the staging
         * buffer look innocent, and the two defects in this file would silently merge into one.
         *
         * **128 KiB since OBS-F, and the size is load-bearing.** The window was 8 KiB when this
         * was written, 1 KiB after OBS-B, and 64 KiB after OBS-F measured what a window costs.
         * A fixed 32 KiB stopped crossing it at the 64 KiB value, which would have made the row
         * pass by accident rather than by the law it states. Two full windows is the smallest size
         * that keeps the premise true with room to spare, so a further window increase fails
         * loudly here instead of silently weakening the row.
         */
        const val LARGE_PAYLOAD_BYTES: Int = 128 * 1024

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
    /**
     * NON-REGRESSION (was CHARACTERISATION) — a short transcript is visible mid-step, minus the
     * redaction lookahead.
     *
     * ## What changed and what did not
     *
     * This row used to assert that a 15-byte payload committed NOTHING until the step ended, and
     * blamed the redactor's lookahead for it. That explanation was half right and half wrong, and
     * the wrong half is what hid the defect: the real reason nothing was committed was the pump
     * asking `redacted.read(window)` for a whole 1024-byte window, and `read` returns only at `len`
     * bytes or EOF. Fifteen bytes is not 1024, so the payload waited for the step to end.
     *
     * The redactor's lookahead was never the binding constraint here, because
     * `SMALL_PAYLOAD_BYTES > MIN_SECRET_WINDOW`: there were always bytes it COULD have emitted. It
     * only ever withheld the last seven.
     *
     * So the row is INVERTED, and what it now pins is the honest post-fix property: the payload is
     * committed live, minus a tail the redactor is still holding because a byte inside that tail
     * could turn out to begin a secret. That tail is not a latency to be tuned away — emitting it
     * early would be a leak.
     *
     * The mutation that kills this row is the pump reverting to a full-window read, which takes
     * `during` back to zero bytes and nothing else.
     */
    @Test
    fun `a short transcript is visible mid-step, minus the redaction lookahead`() {
        val runId = "r-obsb-d1-small"
        assertTrue(
            SMALL_PAYLOAD_BYTES > SecretPatternRegistry.MIN_SECRET_WINDOW,
            "premise broken: the payload is not longer than the redaction lookahead, so there is no " +
                "part of it that COULD be emitted early and this row measures nothing",
        )
        val observation = observeBlockedStep(runId, smallEmitter())

        val during = observation.storeDuringBlock.bytesOrEmpty("mid-step read")
        assertTrue(
            during.isNotEmpty(),
            "a $SMALL_PAYLOAD_BYTES byte payload committed nothing mid-step. The lookahead withholds " +
                "at most ${SecretPatternRegistry.MIN_SECRET_WINDOW} bytes, so there were bytes " +
                "available to emit and the pump did not ask for them.",
        )
        assertTrue(
            during.size < SMALL_PAYLOAD_BYTES,
            "the whole payload was already committed mid-step. The redactor must hold back its tail " +
                "until the lookahead can rule out a secret spanning it, so emitting all " +
                "$SMALL_PAYLOAD_BYTES bytes before the step ends is the defect, not the fix.",
        )
        assertEquals(
            SMALL_PAYLOAD,
            observation.storeAfter.bytesOrEmpty("post-step read").toString(Charsets.UTF_8),
            "and the whole short transcript must be durable and byte-exact once the step ends",
        )
    }

    /**
     * The payload-size contrast that used to separate the two defects.
     *
     * It kept its value after the fix, inverted: a payload that crosses many copy windows must be
     * visible mid-step AND must not be truncated. Before OBS-B2 this row asserted the store was
     * empty even for 32 KiB, which is how D1 was shown to be unconditional rather than a
     * consequence of the copy granularity.
     */
    @Test
    fun `a large transcript is committed mid-step and is complete`() {
        val runId = "r-obsb-d1-large"
        val observation = observeBlockedStep(runId, largeEmitter())

        val during = observation.storeDuringBlock.bytesOrEmpty("mid-step read")
        assertTrue(
            during.isNotEmpty(),
            "the store held nothing mid-step for a $LARGE_PAYLOAD_BYTES byte payload",
        )
        assertTrue(
            during.size < LARGE_PAYLOAD_BYTES,
            "the whole $LARGE_PAYLOAD_BYTES byte payload was already committed mid-step, so this " +
                "row is no longer distinguishing anything",
        )
        assertEquals(
            LARGE_PAYLOAD_BYTES.toLong(),
            observation.storeAfter.bytesOrEmpty("post-step read").size.toLong(),
            "the complete payload must be durable once the step ends",
        )
    }

    // ------------------------------------------------------------ D2: the staging file is gone

    /**
     * The canonical path does not write `console.log` at all.
     *
     * This was a characterisation: the staging file was invisible mid-step for a small payload
     * because the pump's copy granularity was the flush granularity of the whole path. OBS-B2 did
     * not fix that latency — it removed the file, which is what made fixing the latency possible
     * without two authorities.
     *
     * The row now asserts absence in both windows, because a file that appears only at the end is
     * exactly the shape this change exists to remove.
     */
    @Test
    fun `the canonical path writes no staging transcript`() {
        val runId = "r-obsb-d2-small"
        val observation = observeBlockedStep(runId, smallEmitter())

        assertEquals(
            -1L,
            observation.consoleLogBytesDuringBlock,
            "console.log was recreated mid-step. OBS-B2 removed it as a write target because a " +
                "second durable copy of the transcript is a second byte authority.",
        )
        assertEquals(
            -1L,
            observation.consoleLogBytesAfterRelease,
            "console.log was recreated after the step. Whatever holds the transcript must be the " +
                "Output Plane, which is also where post-mortem retention comes from.",
        )
    }

    /**
     * NON-REGRESSION (was CHARACTERISATION, and then inverted once) — live visibility is bounded by
     * the REDACTION lookahead, not by the copy window.
     *
     * The window it used to name was real, and it WAS the defect: the pump asked for a whole
     * `TRANSCRIPT_LIVE_WINDOW_BYTES` and `read` returns only at `len` bytes or EOF, so a payload had
     * to cross an entire window before one byte was visible. What is left after the pump asks for
     * what is ready is the redactor's tail — at most `MIN_SECRET_WINDOW` bytes — and that one is not
     * tunable without leaking.
     *
     * So the discriminator inverts. The old row asked for "at least one window committed", which the
     * new behaviour satisfies trivially and which would therefore have passed against a pump that
     * had been broken in a dozen other ways. The claim worth pinning is the tight one: everything
     * except the lookahead is committed while the step is still alive.
     */
    @Test
    fun `live visibility is bounded by the redaction lookahead, not by the window`() {
        val runId = "r-obsb-d2-window"
        assertTrue(
            LARGE_PAYLOAD_BYTES > TRANSCRIPT_LIVE_WINDOW_BYTES,
            "premise broken: the payload no longer crosses a copy window, so the contrast this row " +
                "draws between a tuned pump and an untuned one is gone",
        )
        val observation = observeBlockedStep(runId, largeEmitter())

        val during = observation.storeDuringBlock.bytesOrEmpty("mid-step read")
        assertTrue(
            during.size >= LARGE_PAYLOAD_BYTES - SecretPatternRegistry.MIN_SECRET_WINDOW,
            "only ${during.size} of $LARGE_PAYLOAD_BYTES bytes were committed mid-step. Once the " +
                "pump asks for what is ready rather than for a whole window, everything except the " +
                "redactor's ${SecretPatternRegistry.MIN_SECRET_WINDOW}-byte lookahead must be " +
                "committed while the step is still alive.",
        )
    }
}
