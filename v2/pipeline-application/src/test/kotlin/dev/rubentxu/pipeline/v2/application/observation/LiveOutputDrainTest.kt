package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * OBS-E4: the live drain, over the real Output Plane.
 *
 * ## Harness fidelity
 *
 * HF2 — a real [SegmentOutputStore] on a real filesystem, written through the same
 * declare/reserve/write/commit/append order `ShExecution` uses, and read back through the
 * production [FrameIndexedObservationOutputReader]. No fake index, no scripted records.
 *
 * The one exception is [REFUSE-2], which substitutes a refusing read port, and that is stated in the
 * row.
 *
 * ## What this pins
 *
 * ```text
 * LIVE-1    bytes committed while the drain runs ARE emitted, before the run is told to stop
 * LIVE-2    a truncated page does not end the drain mid-transcript
 * LIVE-3    a run that has written nothing yet is not "finished"
 * SEALED-1  the owner ends the drain; an open tail only classifies how it ended
 * LIVE-4    a later step output survives every stream looking sealed in between
 * SEALED-2  the owner stopping the drain is a distinct outcome from draining
 * REFUSE-2  a refusal ends the drain as a refusal, not as a clean finish
 * ```
 *
 * ## Mutation
 *
 * - `M-E11` (the plane decides when the run is over) → return Drained as soon as every
 *   stream looks sealed, which is what loses LIVE-4.
 * - `M-E12` (position advances per page) → advance the ordinal once per page.
 * - `M-E13` (refusal propagates) → treat a refusal as an empty page.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LiveOutputDrainTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: SegmentOutputStore
    private lateinit var reader: ObservationOutputReader

    private val runId = "run-obse4"

    @BeforeEach
    fun setUp() {
        store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
        reader = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)
    }

    private fun operationId(stepIndex: Int) =
        dev.rubentxu.pipeline.v2.application.durable.OpId(runId, 0, stepIndex).format()

    /** The production order: declare, reserve, write, commit, THEN append the frame. */
    private fun publish(operationId: String, channel: OutputChannel, text: String) {
        val stream = OutputStreamAddress.of(runId, operationId, channel).stream
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        val reservation = store.open(stream).reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
        store.frameIndex().append(stream, channel, from, from + payload.size)
    }

    /**
     * Sealing is the terminal fact, and it is per stream: one per channel of the operation.
     *
     * Only streams a writer actually opened are sealed, because the store refuses to seal a stream
     * nobody wrote to — which is itself the law that makes a seal mean "this is the end of these
     * bytes" rather than "this stream exists".
     */
    private fun seal(operationId: String) {
        OutputChannel.entries.forEach { channel ->
            val stream = OutputStreamAddress.of(runId, operationId, channel).stream
            if (store.committedExtent(stream) != null) store.seal(stream)
        }
    }

    @Test
    fun `LIVE-1 bytes committed during the drain are emitted before the owner stops it`() {
        val operationId = operationId(0)
        val emitted = StringBuilder()
        // The run is told to stop only once the drain has emitted what the step produced, which is
        // the property `pipeline run` depends on: the bytes must be visible while the step lives.
        // Deliberately does NOT seal: this row is about WHEN bytes are emitted, not about how the
        // drain classifies its ending. The tail stays open, which is also the state a step that is
        // still running is in.
        val stopWhen = {
            emitted.isNotEmpty() && store.committedExtent(
                OutputStreamAddress.of(runId, operationId, OutputChannel.STDOUT).stream,
            ) == emitted.length.toLong()
        }

        publish(operationId, OutputChannel.STDOUT, "compiling module A\n")

        val drain = LiveOutputDrain(reader, frameLimit = 8, pollIntervalMs = 5)
        val result = drain.drain(runId, stopWhen) { record -> emitted.append(record.text) }

        assertEquals(
            "compiling module A\n",
            emitted.toString(),
            "the drain must emit committed bytes WITHOUT being told to stop first — that is the " +
                "whole difference between a live console and an end-of-run document",
        )
        assertInstanceOf(LiveOutputDrainResult.Stopped::class.java, result)
    }

    @Test
    fun `LIVE-2 a truncated page does not end the drain mid-transcript`() {
        val operationId = operationId(0)
        repeat(5) { publish(operationId, OutputChannel.STDOUT, "line $it\n") }
        seal(operationId)

        val emitted = StringBuilder()
        // frameLimit = 2 forces three pages for five frames. A drain that stops when a page comes
        // back short would emit the first two lines and quietly lose the rest.
        var polls = 0
        val drain = LiveOutputDrain(reader, frameLimit = 2, pollIntervalMs = 5)
        val result = drain.drain(runId, { ++polls > 8 }) { emitted.append(it.text) }

        assertEquals(
            "line 0\nline 1\nline 2\nline 3\nline 4\n",
            emitted.toString(),
            "a page truncated by frameLimit means bytes are WAITING, not that the run is over",
        )
        assertInstanceOf(
            LiveOutputDrainResult.Drained::class.java,
            result,
            "and once the last page is short and every stream is sealed, the drain is done",
        )
    }

    @Test
    fun `LIVE-3 a run that has written nothing yet is not finished`() {
        var polls = 0
        var sealedLate = false

        // No bytes are ever committed, so there are no streams. The drain must keep polling and
        // only finish once the owner says stop — an empty tail list is not a sealed run.
        val drain = LiveOutputDrain(reader, frameLimit = 4, pollIntervalMs = 5)
        val result = drain.drain(
            runId,
            {
                polls++
                if (polls > 5) {
                    sealedLate = true
                    true
                } else {
                    false
                }
            },
            { error("nothing was committed, so nothing may be emitted") },
        )

        assertTrue(sealedLate, "the drain must not have ended on its own before the owner stopped it")
        assertInstanceOf(
            LiveOutputDrainResult.Drained::class.java,
            result,
            "a run with no output at all, whose owner stopped it, HAS been fully drained. The " +
                "empty-history law is about not calling a RUN finished early — and the owner, " +
                "not the tail states, is what says a run is finished.",
        )
    }

    @Test
    fun `SEALED-1 the owner ends the drain, and an open tail only classifies how`() {
        val operationId = operationId(0)
        publish(operationId, OutputChannel.STDOUT, "partial\n")

        var polls = 0
        val drain = LiveOutputDrain(reader, frameLimit = 4, pollIntervalMs = 5)
        val result = drain.drain(
            runId,
            {
                polls++
                if (polls > 3) true else false
            },
            { },
        )

        assertInstanceOf(
            LiveOutputDrainResult.Stopped::class.java,
            result,
            "the owner stopped the drain, so it ended — but the stream is OPEN, and that is the " +
                "normal state for a run that died mid-step. It must be reported as Stopped rather " +
                "than as a completed drain, because a seal promises that no more bytes will arrive " +
                "and an open tail is exactly a promise not yet made.",
        )
    }

    @Test
    fun `LIVE-4 a later step output survives every stream looking sealed in between`() {
        // This is the race that decided the drain shape. `ShExecution` seals a step streams from
        // INSIDE `invoke`, while the run continues, so there is a window — between one `sh`
        // finishing and the next one declaring its streams — in which every stream the run has is
        // Sealed. A drain that treated that as the run being over would exit inside the window and
        // drop every byte the remaining steps print.
        val first = operationId(0)
        publish(first, OutputChannel.STDOUT, "step one\n")
        seal(first)

        var polls = 0
        val second = operationId(1)
        val emitted = StringBuilder()

        // The second step streams appear two polls in, well inside the all-sealed window.
        val drain = LiveOutputDrain(reader, frameLimit = 4, pollIntervalMs = 5)
        val result = drain.drain(
            runId,
            {
                polls++
                if (polls == 2) publish(second, OutputChannel.STDOUT, "step two\n")
                polls > 6
            },
            { emitted.append(it.text) },
        )

        assertEquals(
            "step one\nstep two\n",
            emitted.toString(),
            "the second step printed while every stream the run had was already sealed, and the " +
                "drain was still watching. A seal-based terminator ends right here and loses it, " +
                "which is why the OWNER ends the drain and the tail states only classify it.",
        )
        assertInstanceOf(LiveOutputDrainResult.Stopped::class.java, result)
    }

    @Test
    fun `SEALED-2 sealed streams end the drain`() {
        val operationId = operationId(0)
        publish(operationId, OutputChannel.STDOUT, "done\n")
        publish(operationId, OutputChannel.STDERR, "a warning\n")
        seal(operationId)

        val emitted = StringBuilder()
        var polls = 0
        val drain = LiveOutputDrain(reader, frameLimit = 8, pollIntervalMs = 5)
        val result = drain.drain(runId, { ++polls > 8 }) { emitted.append(it.text) }

        assertEquals("done\na warning\n", emitted.toString())
        assertInstanceOf(LiveOutputDrainResult.Drained::class.java, result)
    }

    @Test
    fun `REFUSE-2 a refusal ends the drain as a refusal`() {
        val refusing: ObservationOutputReader = object : ObservationOutputReader {
            override fun readOutput(runId: String, afterOrdinal: Long, frameLimit: Int) =
                ObservationOutputRead.Refused(OutputRefusal.RecoveryNotCompleted)

            override fun tailStatesOf(runId: String) = emptyList<dev.rubentxu.pipeline.v2.output.OutputTailState?>()
        }

        val drain = LiveOutputDrain(refusing, frameLimit = 4, pollIntervalMs = 5)
        val result = drain.drain(runId, { false }) {
            error("nothing may be emitted from a reader that refused")
        }

        assertInstanceOf(
            LiveOutputDrainResult.Refused::class.java,
            result,
            "reporting a refusal as a clean finish would show a user a transcript with a silent " +
                "hole in it and call it complete",
        )
    }
}