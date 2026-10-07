package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * OBS-C1 — characterises what the canonical `sh` path can and cannot say about **where a byte came
 * from**, and about **whether more bytes can still arrive**.
 *
 * ## What this file is for
 *
 * OBS-B made the Output Plane live. That is the first half of "observable". The second half is
 * whether a reader can answer the two questions a reader actually asks of a console:
 *
 * ```text
 * show me only stderr          -> needs the channel preserved before fusion
 * is there anything more?      -> needs OPEN vs SEALED, not "no page right now"
 * ```
 *
 * Both are currently unanswerable. This file measures that, at the published read port, through the
 * real `ShExecution` and the real `SegmentOutputStore`. It asserts the defect as it is today, so
 * the size of OBS-C is set by evidence rather than by the shape of the fix.
 *
 * ## The characterisation that is not vacuous
 *
 * "You cannot tell stdout from stderr" is an assertion about the **absence** of a capability, and
 * absent-capability assertions are the classic vacuous test: they pass for the wrong reason, and
 * they also pass when the harness itself is broken. Both failure modes are closed here by
 * **giving the harness a pair it CAN discriminate, in the same file, with the same code**.
 *
 * ```text
 * NEGATIVE CONTROL   two runs whose payloads DIFFER by channel
 *                    -> the read port DOES tell them apart (differing bytes)
 * THE CLAIM          two runs whose payloads are byte-identical but differ ONLY by channel
 *                    -> the read port CANNOT tell them apart (identical bytes)
 * ```
 *
 * Mutation M6 (`redirectErrorStream(true)` -> two PIPEs with stderr routed nowhere, the naive
 * version of "separate the channels") kills three of the four rows, and the attribution is 1:1:
 *
 * ```text
 * row: byte-identical transcript cannot say which channel    -> RED, it now CAN tell them apart
 * row: every byte on both channels is conserved               -> RED, stderr bytes were lost
 * row: read port tells two differently-payloaded apart        -> RED, the stderr control vanished
 * row: a null next cursor means not-yet                       -> GREEN, see its own KDoc
 * ```
 *
 * The control row is what makes the claim non-vacuous: if the reader could not distinguish the
 * control pair either, the claim would be true for a reason that has nothing to do with channels,
 * and the row says so in its own assertion message.
 *
 * ## Fidelity
 *
 * Crosses the productive authority: real `ShExecution.invokeShell`, real `DurableShellExecutor`,
 * real `SegmentOutputStore` from the run's own provider, read through the published
 * `OutputReadPort` with its published cursor. Nothing here reimplements the store. `@TempDir`, no
 * ambient cwd/env/network, no wall-clock assertions — every observation is taken at a sentinel
 * file the step itself created, so "the process was still alive" is a fact and not a schedule.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ObsCChannelAndTailCharacterisationTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun setUp() {
        // One recovered store per control-dir root, cached by the provider. Without this a row
        // would inherit another row's store and read the wrong bytes.
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    // ------------------------------------------------------------------ the harness

    private lateinit var controlDirRoot: Path
    private lateinit var workspaceRoot: Path

    /** Everything a run of one blocked step yields, as an external consumer would see it. */
    private class Observation(
        /**
         * Every committed byte of BOTH channel streams, concatenated in the fixed channel order.
         *
         * OBS-C2.3: this used to be the single fused `.../transcript` stream. That stream no longer
         * exists on the canonical path, so "the transcript" is now something a reader ASSEMBLES from
         * two streams — which is the honest shape, and the reason the rows below had to be rewritten
         * rather than merely flipped.
         */
        val transcript: ByteArray,
        /** The stdout stream's committed bytes, read separately so attribution can be asserted. */
        val stdoutBytes: ByteArray,
        /** The stderr stream's committed bytes, read separately so attribution can be asserted. */
        val stderrBytes: ByteArray,
        /** Page metadata observed MID-STEP, before the release barrier. */
        val midStepNextWasNull: Boolean,
        val midStepCommittedEnd: Long,
        val finalCommittedEnd: Long,
        /** OBS-C3: the tail state sampled MID-STEP, while the child is provably alive. */
        val midStepTailState: dev.rubentxu.pipeline.v2.output.OutputTailState?,
        /** OBS-C3: the same tail state after the step reached its terminal. */
        val finalTailState: dev.rubentxu.pipeline.v2.output.OutputTailState?,
        /** Every stream id that was ever opened by this run, probed after the fact. */
        val terminalOutcome: String,
    )

    /**
     * Runs one `sh` step that emits [emitter], reaches a sentinel, and blocks until released.
     *
     * The emitter runs BEFORE the barrier, so the bytes exist, the child is alive, and only the
     * channel attribution and the tail state are in question. The step is blocking work and
     * `invokeShell` is suspend, so the bridge is `runBlocking` on a plain thread — NOT a coroutine
     * dispatcher — which keeps the step on its own thread while this test samples the store.
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
            "printf '%s' '$TAIL_PAYLOAD'",
        ).joinToString("\n")

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
                    // and supplying one is what activates the PIPE + redaction pump. Its default is
                    // null, which leaves the wrapper redirecting straight to a file and the pump
                    // never running — a path the CLI cannot take.
                    secretPatternRegistry = SecretPatternRegistry(),
                )
            }
        }

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
        val streams = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 0).format())
        val stream = streams.stdout.stream
        val midStep = store.read(stream, OutputCursor.start(stream), PAGE_BYTES)
        val midStepPage = (midStep as? OutputReadResult.Page)?.page
        // Sampled while the barrier holds the step alive, so an Open here is a fact about a running
        // process rather than a race with its termination.
        val midStepTailState = store.tailState(streams.stdout.stream)

        Files.writeString(released, "go")
        runner.join(TimeUnit.SECONDS.toMillis(90))
        assertFalse(runner.isAlive, "the step thread did not finish after release")

        // OBS-C2.3: read BOTH channels. The stdout stream is the one sampled mid-step, because it is
        // the channel the majority of a shell's work goes to; the attribution assertions compare the
        // two afterwards.
        val stdoutBytes = readAllBytes(store, streams.stdout.stream)
        val stderrBytes = readAllBytes(store, streams.stderr.stream)

        return Observation(
            transcript = stdoutBytes + stderrBytes,
            stdoutBytes = stdoutBytes,
            stderrBytes = stderrBytes,
            midStepNextWasNull = midStepPage?.next == null,
            midStepCommittedEnd = midStepPage?.committedEnd ?: -1L,
            finalCommittedEnd = store.committedExtent(stream) ?: -1L,
            midStepTailState = midStepTailState,
            finalTailState = store.tailState(streams.stdout.stream),
            // `ShellInvocationResult` is a closed ADT, so the terminal shape is named rather than
            // rendered: an unexpected variant here means the step failed, which would make every
            // byte observation in this row meaningless.
            terminalOutcome = describe(terminal[0]),
        )
    }

    /** Reads every committed byte through the published port, as any external consumer would. */
    private fun readAllBytes(store: SegmentOutputStore, stream: OutputStreamId): ByteArray {
        val first = store.read(stream, OutputCursor.start(stream), PAGE_BYTES)
        if (first is OutputReadResult.Refused) return ByteArray(0)

        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = assertInstanceOf(
                OutputReadResult.Page::class.java,
                store.read(stream, cursor, PAGE_BYTES),
                "a stream that has been opened must page",
            ).page
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    /** Renders the terminal as a name, so an unexpected variant is legible in a failure message. */
    private fun describe(terminal: ShellInvocationResult?): String = when (terminal) {
        null -> "NULL-TERMINAL"
        is ShellInvocationResult.Stdout -> "STDOUT"
        is ShellInvocationResult.Status -> "STATUS(exitCode=${terminal.exitCode})"
        is ShellInvocationResult.Failed -> "FAILED(${terminal.failure})"
        is ShellInvocationResult.Interrupted -> "INTERRUPTED"
        is ShellInvocationResult.UnitValue -> "UNIT"
    }

    private companion object {
        /**
         * The payload two runs of the *claim* row share byte for byte.
         *
         * Identical content on purpose: the claim is that the store cannot tell them apart, and the
         * only way to demonstrate an inability honestly is to feed it something indistinguishable.
         */
        const val SHARED_PAYLOAD: String = "CONSOLA-SIN-CANAL\n"

        /** What the *negative control* pair writes instead, one byte apart. */
        const val CONTROL_STDOUT_PAYLOAD: String = "CONTROL-SALIDA-STDOUT\n"
        const val CONTROL_STDERR_PAYLOAD: String = "CONTROL-SALIDA-STDERR\n"

        /** Emitted after the release barrier, so it can only exist post-step. */
        const val TAIL_PAYLOAD: String = "COLA-TRAS-LA-BARRA\n"

        const val PAGE_BYTES: Int = 4096
    }

    // -------------------------------------------------- C1: channel attribution

    /**
     * NEGATIVE CONTROL — the reader **does** discriminate when the payloads differ.
     *
     * This row exists to keep the next one honest. It runs the same code path twice with payloads
     * that differ, and requires the read port to return different bytes. If it could not, then the
     * claim in [a byte-identical transcript cannot say which channel it came from] would be true
     * for a reason unrelated to channels, and the file would certify nothing.
     */
    @Test
    fun `the read port tells two differently-payloaded transcripts apart`() {
        val toStdout = observeBlockedStep(
            "r-obsc-c1-ctl-out",
            "printf '%s' '$CONTROL_STDOUT_PAYLOAD'",
        )
        val toStderr = observeBlockedStep(
            "r-obsc-c1-ctl-err",
            "printf '%s' '$CONTROL_STDERR_PAYLOAD' >&2",
        )

        assertFalse(
            toStdout.transcript.contentEquals(toStderr.transcript),
            "the harness cannot distinguish these two transcripts, so the control row proves " +
                "nothing and the claim row below would be vacuous. Both runs produced identical " +
                "bytes: ${toStdout.transcript.toString(Charsets.UTF_8)}",
        )
        assertTrue(
            toStdout.transcript.toString(Charsets.UTF_8).contains(CONTROL_STDOUT_PAYLOAD),
            "the stdout control run did not persist its own payload",
        )
        assertTrue(
            toStderr.transcript.toString(Charsets.UTF_8).contains(CONTROL_STDERR_PAYLOAD),
            "the stderr control run did not persist its own payload",
        )
    }

    /**
     * INVERTED BY OBS-C2.3 — the same payload, byte for byte, now lands in a DIFFERENT stream.
     *
     * This row used to assert that `printf X` and `printf X >&2` produced **byte-identical**
     * transcripts, because `DurableShellExecutor` called `redirectErrorStream(true)` and the kernel
     * merged both channels into one descriptor before any PipelineK code could observe them. The
     * bytes were conserved; the attribution was irrecoverable at that point, so `--channel stderr`
     * had no boundary left to recover from.
     *
     * It now asserts the closure of that defect, and it is deliberately the same payload on both
     * sides so that **only the channel differs**. Everything a fused transcript could not answer is
     * now answerable:
     *
     * ```text
     * before:  stdout-run and stderr-run agree byte for byte, so they are indistinguishable
     * after:   stdout-run's bytes are in the stdout stream, stderr-run's in the stderr stream
     * ```
     *
     * The row was inverted, never deleted: its negative control below is what keeps the inversion
     * honest, because it proves the reader discriminates DIFFERENT payloads too.
     */
    @Test
    fun `an identical payload on stderr is distinguishable from the same payload on stdout`() {
        val viaStdout = observeBlockedStep(
            "r-obsc-c1-same-out",
            "printf '%s' '$SHARED_PAYLOAD'",
        )
        val viaStderr = observeBlockedStep(
            "r-obsc-c1-same-err",
            "printf '%s' '$SHARED_PAYLOAD' >&2",
        )

        // The stdout run wrote to stdout: its own stream must hold it.
        assertTrue(
            viaStdout.stdoutBytes.toString(Charsets.UTF_8).contains(SHARED_PAYLOAD),
            "the stdout run did not persist its payload to the stdout stream, so everything below " +
                "would hold over two empty streams and would mean nothing",
        )
        // The stderr run wrote to stderr: the STDERR stream must hold it, and the stdout one must not.
        assertTrue(
            viaStderr.stderrBytes.toString(Charsets.UTF_8).contains(SHARED_PAYLOAD),
            "ATTRIBUTION NOT CLOSED: an identical payload written to stderr did not reach the " +
                "stderr stream. stderr stream held '${viaStderr.stderrBytes.toString(Charsets.UTF_8)}'",
        )
        assertFalse(
            viaStderr.stdoutBytes.toString(Charsets.UTF_8).contains(SHARED_PAYLOAD),
            "ATTRIBUTION NOT CLOSED: the stderr payload is also in the stdout stream, which is the " +
                "fusion `redirectErrorStream(true)` produced. stdout stream held " +
                "'${viaStderr.stdoutBytes.toString(Charsets.UTF_8)}'",
        )
        assertFalse(
            viaStdout.stderrBytes.toString(Charsets.UTF_8).contains(SHARED_PAYLOAD),
            "ATTRIBUTION CROSSED: the stdout payload also appears in the stderr stream, so the " +
                "split is crossed rather than merely separated",
        )
        assertEquals(
            "UNIT",
            viaStdout.terminalOutcome,
            "a step that only writes to stdout must still succeed; the emitter is a test fixture " +
                "and a failure here is a defect, not a characterisation",
        )
        assertEquals(
            "UNIT",
            viaStderr.terminalOutcome,
            "a step that only writes to stderr must still succeed",
        )
    }

    /**
 * CHARACTERISATION, RETAINED THROUGH OBS-C2.3 — the transcript conserves **every** byte, on both
 * channels.
 *
 * Worth pinning before the fix, because the fix introduced a second pump and a second stream, and a
 * refactor that splits a stream can very easily lose or duplicate a byte. This row is the
 * conservation law the split must not break, and it must still be green afterwards — which it is,
 * because splitting is a change of ADDRESSING and not of which bytes exist.
 */
    @Test
    fun `every byte on both channels is conserved exactly once`() {
        val runId = "r-obsc-c1-both"
        val observation = observeBlockedStep(
            runId,
            listOf(
                "printf '%s' '$CONTROL_STDOUT_PAYLOAD'",
                "printf '%s' '$CONTROL_STDERR_PAYLOAD' >&2",
            ).joinToString("\n"),
        )
        // The two channel streams concatenated in a fixed order. A consumer that wants "the whole
        // console" assembles exactly this; the Output Plane itself stores no merged copy.
        val text = observation.transcript.toString(Charsets.UTF_8)

        assertEquals(
            1,
            occurrences(text, CONTROL_STDOUT_PAYLOAD),
            "stdout bytes must appear exactly once across the two channel streams",
        )
        assertEquals(
            1,
            occurrences(text, CONTROL_STDERR_PAYLOAD),
            "stderr bytes must appear exactly once across the two channel streams",
        )
        assertTrue(
            observation.stdoutBytes.toString(Charsets.UTF_8).contains(CONTROL_STDOUT_PAYLOAD) &&
                observation.stderrBytes.toString(Charsets.UTF_8).contains(CONTROL_STDERR_PAYLOAD),
            "each payload must live in ITS OWN stream: splitting that merely moved both payloads to " +
                "one stream would conserve the bytes while losing the attribution. stdout held " +
                "'${observation.stdoutBytes.toString(Charsets.UTF_8)}', stderr held " +
                "'${observation.stderrBytes.toString(Charsets.UTF_8)}'",
        )
        assertTrue(
            text.contains(TAIL_PAYLOAD),
            "bytes emitted after the release barrier were lost, which would make the " +
                "channel-attribution question moot by losing data",
        )
        assertEquals(
            1,
            occurrences(text, TAIL_PAYLOAD),
            "the tail payload must not be duplicated",
        )
    }

    // -------------------------------------------------- C2: OPEN vs SEALED

    /**
     * INVERTED BY OBS-C3 — the tail state is `Open` mid-step and `Sealed` once it ends.
     *
     * This row used to characterise the ABSENCE of the capability: `next == null` could not be told
     * apart from "finished", so a `--follow` consumer had to choose between stopping too early and
     * polling forever. The defect was an absent interface, which is why the original row carried a
     * self-verifying inequality instead of a mutation that could kill it.
     *
     * Now the capability exists ([dev.rubentxu.pipeline.v2.output.OutputTailState]), so this row can
     * assert it directly, and it IS killable by production code. Both halves are kept:
     *
     * ```text
     * mid-step   Open    the step is provably alive and more bytes will come
     * after      Sealed  the operation reached its terminal and the tail is final
     * ```
     *
     * The inequality the old row carried is still asserted, because it is what makes `Open` mean
     * something: a state that said Open while nothing further arrived would be as useless as the
     * missing interface it replaced.
     */
    @Test
    fun `a running step reports an Open tail and a finished one a Sealed tail`() {
        val runId = "r-obsc-c2-tail"
        val observation = observeBlockedStep(runId, "printf '%s' '$CONTROL_STDOUT_PAYLOAD'")

        assertTrue(
            observation.midStepNextWasNull,
            "premise broken: the mid-step page already offered a next cursor, so this row no " +
                "longer covers the not-yet case it was written for",
        )
        assertTrue(
            observation.midStepCommittedEnd >= 0,
            "premise broken: the mid-step page reported a negative committed extent " +
                "(${observation.midStepCommittedEnd}), so the store refused rather than paging. " +
                "OBS-C3 declares the stream at open time, so a step that has written nothing yet " +
                "pages with committedEnd 0 — an empty page, not an absent stream.",
        )

        // The step is provably alive here (the barrier exists and the release has not happened), so
        // anything other than Open would be a lie about the future.
        assertInstanceOf(
            OutputTailState.Open::class.java,
            observation.midStepTailState,
            "while the step is running its tail must be OPEN: a consumer told Sealed here would stop " +
                "tailing a process that is still running. Observed: ${observation.midStepTailState}",
        )
        assertTrue(
            observation.midStepCommittedEnd < observation.finalCommittedEnd,
            "the mid-step committed extent (${observation.midStepCommittedEnd}) was already the " +
                "final one (${observation.finalCommittedEnd}). If they are equal, Open here would " +
                "have been asserting the opposite of the truth.",
        )

        assertEquals(
            OutputTailState.Sealed(observation.finalCommittedEnd),
            observation.finalTailState,
            "once the step has reached its terminal the tail must be SEALED, or a --follow consumer " +
                "would poll a finished run forever",
        )
        assertTrue(
            observation.transcript.toString(Charsets.UTF_8).contains(TAIL_PAYLOAD),
            "the bytes emitted after the release were lost, which would make the tail question moot",
        )
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}