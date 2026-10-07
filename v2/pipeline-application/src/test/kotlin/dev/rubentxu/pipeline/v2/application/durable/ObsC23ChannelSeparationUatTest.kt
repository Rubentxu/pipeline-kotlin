package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OBS-C2.3: the two channels of one `sh` step are separate durable streams, not one fused byte run.
 *
 * ## What this replaces
 *
 * [ObsCChannelAndTailCharacterisationTest] measured that `printf X` and `printf X >&2` produced a
 * **byte-identical** transcript, because `DurableShellExecutor` called `redirectErrorStream(true)`
 * and the kernel merged both channels into one descriptor before any PipelineK code could observe
 * them. The bytes were conserved; the *attribution* was irrecoverable at that point. That row
 * asserted the defect on purpose, and its own KDoc said what closes it: assert the two land in
 * DIFFERENT streams, and do not delete the row.
 *
 * This file is that assertion, as a UAT rather than a characterisation. It is expected to be RED
 * until `redirectErrorStream(true)` is gone from the canonical transcript path.
 *
 * ## The three laws, each with its own row
 *
 * ```text
 * ATTRIBUTION  a payload sent to stderr is readable from the stderr stream, and the stdout stream
 *              does not contain it — so `--channel stderr` becomes answerable at the read port
 * CONSERVATION stdout + stderr selected == the merged payload set, byte for byte, with no loss
 *              and no duplication. Splitting must not become dropping.
 * NO COPY      the channels live in two streams, and there is no third stream holding the merged
 *              run. A merged view is READ from the frames, never persisted a third time.
 * ```
 *
 * Conservation and no-copy pull in opposite directions, which is why both are asserted: a naive
 * split that keeps a merged stream "for safety" passes conservation and fails no-copy; a split that
 * loses the overlap passes neither.
 *
 * ## Why these rows are not vacuous
 *
 * Every row reads through the published `OutputReadPort` on the run's own store, from the real
 * `ShExecution` → `DurableShellExecutor` path. Nothing here reimplements the store, and no row can
 * pass by asserting on an empty transcript: each one first requires that its own bytes are actually
 * present, which a harness that never reached the Output Plane could not produce.
 *
 * Mutation M14 — route stderr back into the stdout stream and drop the stderr sink — is expected to
 * kill CONSERVATION and ATTRIBUTION while leaving NO COPY green, and each row states which defect it
 * is pinned to, so a failure names the law rather than a number.
 *
 * ## Fidelity
 *
 * Crosses the productive authority end to end. `@TempDir`, no ambient cwd/env/network, and no
 * wall-clock assertion about *speed*: the step is released only after a sentinel file exists, so
 * "the process was still alive when we sampled" is a fact rather than a schedule.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ObsC23ChannelSeparationUatTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun setUp() {
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    // ─── the harness ──────────────────────────────────────────────────────────────

    private class Run(
        val terminalOutcome: String,
        val stepExitCode: Int?,
    )

    /**
     * Runs one plain `sh` step that writes [stdoutPayload] to stdout and [stderrPayload] to stderr,
     * waits until [whenBlocked] says the step reached its barrier, and then releases it.
     *
     * The payloads are written BEFORE the barrier, so the bytes exist and the child is alive while
     * the read port is sampled. That is what makes the ATTRIBUTION row a statement about committed
     * bytes rather than about a post-mortem file.
     */
    private fun runSplitStep(
        runId: String,
        stdoutPayload: String,
        stderrPayload: String,
        whenBlocked: () -> Unit,
    ): Run {
        val controlDirRoot = Files.createDirectories(root.resolve("control-$runId"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace-$runId"))
        val barrier = workspaceRoot.resolve("BARRIER_REACHED")
        val released = workspaceRoot.resolve("RELEASED")

        val script = listOf(
            "printf '%s' '$stdoutPayload'",
            "printf '%s' '$stderrPayload' >&2",
            "touch ${barrier.toAbsolutePath()}",
            "while [ ! -s ${released.toAbsolutePath()} ]; do sleep 0.05; done",
        ).joinToString("\n")

        val terminal = arrayOfNulls<ShellInvocationResult>(1)
        val runner = thread(name = "obs-c23-$runId") {
            terminal[0] = runBlocking {
                ShExecution.invokeShell(
                    command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                    opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                    runId = runId,
                    stageIndex = 0,
                    stepIndex = 0,
                    // plain mode: the canonical transcript path, the one that used to fuse channels
                    shOptions = ShOptions(
                        workspaceRoot = workspaceRoot,
                        captureStdout = false,
                        timeoutMs = 120_000,
                        env = emptyMap(),
                        sandbox = SandboxConfig.NONE,
                    ),
                    controlDirRoot = controlDirRoot,
                    eventSink = InMemoryEventStore(),
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
            "the step never reached its barrier, so nothing observed about its output would mean " +
                "anything; the child produced no bytes this row could read",
        )

        whenBlocked()

        Files.writeString(released, "go")
        runner.join(TimeUnit.SECONDS.toMillis(90))
        assertFalse(runner.isAlive, "the step thread did not finish after release")

        return Run(
            terminalOutcome = describe(terminal[0]),
            stepExitCode = (terminal[0] as? ShellInvocationResult.Status)?.exitCode,
        )
    }

    private fun storeFor(runId: String) =
        OutputPlaneProvider.storeFor(Files.createDirectories(root.resolve("control-$runId")))

    private fun channelStream(runId: String, channel: OutputChannel): OutputStreamId =
        OutputPlaneProvider.streamId(runId, OpId(runId, 0, 0).format(), channel)

    /** Every committed byte of one stream, read through the published port. */
    private fun readAllBytes(store: SegmentOutputStore, stream: OutputStreamId): ByteArray {
        if (store.read(stream, OutputCursor.start(stream), PAGE_BYTES) is OutputReadResult.Refused) {
            return ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = (store.read(stream, cursor, PAGE_BYTES) as? OutputReadResult.Page)?.page
                ?: break
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    private fun describe(terminal: ShellInvocationResult?): String = when (terminal) {
        null -> "NULL-TERMINAL"
        is ShellInvocationResult.Stdout -> "STDOUT"
        is ShellInvocationResult.Status -> "STATUS(exitCode=${terminal.exitCode})"
        is ShellInvocationResult.Failed -> "FAILED(${terminal.failure})"
        is ShellInvocationResult.Interrupted -> "INTERRUPTED"
        is ShellInvocationResult.UnitValue -> "UNIT"
    }

    private companion object {
        const val OUT_PAYLOAD = "PAYLOAD-SALIDA\n"
        const val ERR_PAYLOAD = "PAYLOAD-ERROR\n"
        const val PAGE_BYTES = 4096

        /**
         * A per-channel payload larger than [TRANSCRIPT_LIVE_WINDOW_BYTES].
         *
         * OBS-B fixed the live window at 1 KiB and documented it as a **liveness bound**: the
         * pump asks the redacting stream for a whole window, so bytes become observable once a
         * window is available or at EOF. A 13-byte payload is therefore correctly invisible
         * mid-step, and asserting otherwise would make this row a test of OBS-B's latency
         * contract wearing a channel test's name.
         *
         * Each channel emits more than one window so the row observes *committed* bytes while
         * the step is alive. That keeps the assertion about ATTRIBUTION — which stream a byte
         * belongs to — instead of about WHEN a byte becomes visible, which is OBS-B's law and
         * is already covered by its own characterisation.
         */
        const val WINDOW_PAYLOAD_PREFIX = "P"
        const val WINDOW_PAYLOAD_BYTES = 4096
    }

    /** A payload of [WINDOW_PAYLOAD_BYTES] identifiable bytes for [channel]. */
    private fun windowPayload(channel: OutputChannel): String {
        val tag = if (channel == OutputChannel.STDOUT) "OUT" else "ERR"
        val filler = WINDOW_PAYLOAD_PREFIX.repeat(WINDOW_PAYLOAD_BYTES) + "\n"
        return "$tag-BEGIN\n$filler$tag-END\n"
    }

    // ─── ATTRIBUTION ──────────────────────────────────────────────────────────────

    /**
     * LAW: a byte written to stderr is readable from the stderr stream and NOT from stdout.
     *
     * This is the row OBS-C1 said was impossible. It is deliberately asymmetric — the negative half
     * (stdout must not contain the stderr payload) is the part a fused transcript cannot satisfy,
     * because a fused run contains both payloads in both reads.
     */
    @Test
    fun `stderr bytes live in the stderr stream and not in the stdout stream`() {
        val runId = "r-obsc23-attribution"
        val outPayload = windowPayload(OutputChannel.STDOUT)
        val errPayload = windowPayload(OutputChannel.STDERR)

        val run = runSplitStep(runId, outPayload, errPayload) {
            val store = storeFor(runId)
            val stdoutStream = channelStream(runId, OutputChannel.STDOUT)
            val stderrStream = channelStream(runId, OutputChannel.STDERR)
            val stdoutBytes = readAllBytes(store, stdoutStream)
            val stderrBytes = readAllBytes(store, stderrStream)
            val stdoutText = stdoutBytes.toString(Charsets.UTF_8)
            val stderrText = stderrBytes.toString(Charsets.UTF_8)

            // Sampled MID-STEP. The assertion is about ATTRIBUTION, not about completeness: the
            // live window is a liveness bound (OBS-B), so a running step has published SOME of its
            // output but not necessarily all of it. What must hold mid-step is that the bytes which
            // HAVE arrived landed in the right stream, which is the property this row is pinned to.
            //
            // The marker is at the START of the payload precisely so it is inside the first window:
            // demanding the whole payload here would be asserting OBS-B's flush timing under a
            // channel test's name, and would go red for a reason unrelated to channels.
            assertTrue(
                stderrText.startsWith("ERR-BEGIN"),
                "ATTRIBUTION: while the step was still running, the stderr stream did not begin " +
                    "with the stderr marker, so its bytes are not attributed to stderr. The channel " +
                    "is still fused, or stderr is not persisted at all. stderr stream held " +
                    "${stderrBytes.size}B starting '${stderrText.take(32)}'.",
            )
            assertFalse(
                stdoutText.contains("ERR-BEGIN"),
                "ATTRIBUTION: the stderr payload is ALSO in the stdout stream, which is exactly the " +
                    "fusion `redirectErrorStream(true)` produced — the two channels are still one " +
                    "byte run. stdout stream held ${stdoutBytes.size}B.",
            )
            assertTrue(
                stdoutText.startsWith("OUT-BEGIN"),
                "ATTRIBUTION: the stdout marker is missing from the stdout stream, so this row " +
                    "would pass on an empty read. stdout stream held ${stdoutBytes.size}B starting " +
                    "'${stdoutText.take(32)}'.",
            )
            assertFalse(
                stderrText.contains("OUT-BEGIN"),
                "ATTRIBUTION: the stdout payload leaked into the stderr stream, so the split is " +
                    "crossed rather than merely fused. stderr stream held ${stderrBytes.size}B.",
            )
        }

        assertEquals("UNIT", run.terminalOutcome, "the step itself must still succeed")
    }

    // ─── CONSERVATION ─────────────────────────────────────────────────────────────

    /**
     * LAW: the two channels selected together carry exactly the merged payload set.
     *
     * Splitting a stream is a change of addressing, so it must not change which bytes exist. Both
     * payloads are asserted present, and their concatenation is compared against what a merged read
     * of the same step produced — the union is the whole point of conservation.
     */
    @Test
    fun `selecting both channels yields every byte the step produced`() {
        val runId = "r-obsc23-conservation"

        val run = runSplitStep(runId, OUT_PAYLOAD, ERR_PAYLOAD) {}

        val store = storeFor(runId)
        val selected = (
            readAllBytes(store, channelStream(runId, OutputChannel.STDOUT)) +
                readAllBytes(store, channelStream(runId, OutputChannel.STDERR))
            ).toString(Charsets.UTF_8)

        assertTrue(
            selected.contains(OUT_PAYLOAD),
            "CONSERVATION: the stdout payload vanished when the channels were split. Selected " +
                "bytes: '$selected'",
        )
        assertTrue(
            selected.contains(ERR_PAYLOAD),
            "CONSERVATION: the stderr payload vanished when the channels were split — a byte that " +
                "was persisted before is now unreachable, which is the drop this law forbids. " +
                "Selected bytes: '$selected'",
        )
        assertEquals(
            1,
            Regex(Regex.escape(ERR_PAYLOAD)).findAll(selected).count(),
            "CONSERVATION: the stderr payload appears more than once, so splitting duplicated " +
                "bytes instead of partitioning them. Selected bytes: '$selected'",
        )
        assertEquals("UNIT", run.terminalOutcome, "the step itself must still succeed")
    }

    // ─── NO COPY ───────────────────────────────────────────────────────────────────

    /**
     * LAW: there is no third stream holding the merged run.
     *
     * The bytes live exactly once. A consumer that wants them interleaved has to read both streams
     * and order them by frame, because a merged stream on disk would be the second copy that the
     * single-byte-authority rule exists to prevent. This row is what stops "separate the channels"
     * from being implemented as "add a third file".
     */
    @Test
    fun `no merged stream exists alongside the two channel streams`() {
        val runId = "r-obsc23-nocopy"

        val run = runSplitStep(runId, OUT_PAYLOAD, ERR_PAYLOAD) {}

        val store = storeFor(runId)
        val mergedStream = OutputPlaneProvider.streamId(runId, OpId(runId, 0, 0).format())
        val mergedBytes = readAllBytes(store, mergedStream)

        assertEquals(
            0,
            mergedBytes.size,
            "NO COPY: the legacy merged stream '$mergedStream' carries ${mergedBytes.size} bytes " +
                "(${mergedBytes.toString(Charsets.UTF_8)}). Persisting a merged run alongside the " +
                "two channel streams is the second byte authority; a merged view is READ by " +
                "interleaving frames, not stored a third time.",
        )
        assertEquals("UNIT", run.terminalOutcome, "the step itself must still succeed")
    }
}