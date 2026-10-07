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
 * SEALED-1  the drain ends only when every stream is sealed
 * SEALED-2  the owner stopping the drain is a distinct outcome from draining
 * REFUSE-2  a refusal ends the drain as a refusal, not as a clean finish
 * ```
 *
 * ## Mutation
 *
 * - `M-E11` (stop when the page ends) → return Drained as soon as a page comes back short,
 *   ignoring the tail states.
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
        val stopWhen = {
            val stopped = emitted.isNotEmpty() && store.committedExtent(
                OutputStreamAddress.of(runId, operationId, OutputChannel.STDOUT).stream,
            ) == emitted.length.toLong()
            if (stopped) seal(operationId)
            stopped
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
        val drain = LiveOutputDrain(reader, frameLimit = 2, pollIntervalMs = 5)
        val result = drain.drain(runId, { false }) { emitted.append(it.text) }

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
        assertInstanceOf(LiveOutputDrainResult.Stopped::class.java, result)
    }

    @Test
    fun `SEALED-1 the drain ends only when every stream is sealed`() {
        val operationId = operationId(0)
        publish(operationId, OutputChannel.STDOUT, "partial\n")

        var polls = 0
        val drain = LiveOutputDrain(reader, frameLimit = 4, pollIntervalMs = 5)
        val result = drain.drain(
            runId,
            {
                polls++
                if (polls > 5) true else false
            },
            { },
        )

        assertInstanceOf(
            LiveOutputDrainResult.Stopped::class.java,
            result,
            "the stream is OPEN — the seal is a statement that no more bytes will arrive, and " +
                "the pump merely ending is not that. A run that dies mid-step leaves its tail open " +
                "and must keep its console readable.",
        )
    }

    @Test
    fun `SEALED-2 sealed streams end the drain`() {
        val operationId = operationId(0)
        publish(operationId, OutputChannel.STDOUT, "done\n")
        publish(operationId, OutputChannel.STDERR, "a warning\n")
        seal(operationId)

        val emitted = StringBuilder()
        val drain = LiveOutputDrain(reader, frameLimit = 8, pollIntervalMs = 5)
        val result = drain.drain(runId, { false }) { emitted.append(it.text) }

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