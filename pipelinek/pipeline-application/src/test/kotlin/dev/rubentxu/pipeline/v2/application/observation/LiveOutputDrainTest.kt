package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The drain's contract, with the plane replaced by a script.
 *
 * What is at stake here is a console that has to be COMPLETE: a drain that
 * dropped the last chunk of a run, or that reported a clean finish after the
 * plane refused to answer, would show a transcript with a silent hole in it
 * and call it a whole. These are the three ways that happens — losing the tail,
 * losing the order, and mistaking a refusal for an empty page — and each one is
 * pinned here.
 */
class LiveOutputDrainTest {

    @Test
    fun `emits every record in observation order and asks for the next page from the last ordinal`() {
        val reader = ScriptedReader(
            pages = listOf(
                page(records(0, 1)),
                page(records(2)),
                page(),
            ),
        )
        val emitted = mutableListOf<Long>()

        val result = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 1).drain(
            runId = RUN,
            shouldStop = { emitted.size >= 3 },
        ) { record -> emitted += record.frame.ordinal }

        assertEquals(listOf(0L, 1L, 2L), emitted)
        assertInstanceOf(LiveOutputDrainResult.Drained::class.java, result)
        // The resume point is the last record's ordinal, so the page after the
        // one that ended at ordinal 1 is asked for from 1, and not from the
        // page's own counter.
        assertEquals(listOf(-1L, 1L), reader.requestedOrdinals)
    }

    @Test
    fun `delivers the bytes it read before it learns that the owner stopped`() {
        // The owner stopped the drain. The bytes it had already read are still
        // delivered, because the drain reads and THEN asks. A drain that asked
        // first would drop them, and the last chunk of a build is the one a
        // person is waiting for.
        val reader = ScriptedReader(pages = listOf(page(records(7))))
        val emitted = mutableListOf<Long>()

        LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 1).drain(
            runId = RUN,
            shouldStop = { true },
        ) { record -> emitted += record.frame.ordinal }

        assertEquals(listOf(7L), emitted)
    }

    @Test
    fun `a refusal ends the drain and is reported, never read as an empty page`() {
        val reason = OutputRefusal.UnknownStream(OutputStreamId("$RUN/op/stdout"))
        val reader = ScriptedReader(pages = listOf(ObservationOutputRead.Refused(reason)))
        val emitted = mutableListOf<Long>()

        val result = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 1).drain(
            runId = RUN,
            shouldStop = { false },
        ) { record -> emitted += record.frame.ordinal }

        val refused = assertInstanceOf(LiveOutputDrainResult.Refused::class.java, result)
        assertEquals(reason, refused.reason)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `an open tail is a stop, not a drain`() {
        val reader = ScriptedReader(
            pages = listOf(page()),
            tails = listOf(OutputTailState.Open(4L)),
        )

        val result = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 1).drain(
            runId = RUN,
            shouldStop = { true },
        ) { }

        assertInstanceOf(LiveOutputDrainResult.Stopped::class.java, result)
    }

    @Test
    fun `flowing pages are read back to back and only a quiet page parks`() {
        // Two full pages and then a quiet one. A drain that parked between the
        // full pages would add the poll interval to the latency of every
        // chunk of a producing build — the park count is the whole assertion.
        val reader = ScriptedReader(
            pages = listOf(
                page(records(0, 1)),
                page(records(2)),
                page(),
            ),
        )
        val parks = mutableListOf<Long>()

        val result = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 25) { millis ->
            parks += millis
            true
        }.drain(
            runId = RUN,
            shouldStop = { reader.reads >= 4 },
        ) { }

        assertInstanceOf(LiveOutputDrainResult.Drained::class.java, result)
        // Exactly one park: after the first QUIET read. The two producing
        // reads before it never parked, whatever the interval is.
        assertEquals(listOf(25L), parks)
    }

    @Test
    fun `a park that yields to interruption stops the drain instead of looping`() {
        val reader = ScriptedReader(pages = listOf(page()))
        val result = LiveOutputDrain(reader, frameLimit = 64, pollIntervalMs = 25) { false }.drain(
            runId = RUN,
            shouldStop = { false },
        ) { }

        assertInstanceOf(LiveOutputDrainResult.Stopped::class.java, result)
    }

    private fun records(vararg ordinals: Long): List<ObservationRecord.Output> =
        ordinals.map { ordinal ->
            val bytes = "chunk-$ordinal".toByteArray()
            ObservationRecord.Output(frame(ordinal), bytes, String(bytes))
        }

    private fun frame(ordinal: Long): OutputFrame = OutputFrame(
        ordinal = ordinal,
        stream = OutputStreamId("$RUN/op/stdout"),
        channel = OutputChannel.STDOUT,
        from = 0L,
        to = 1L,
    )

    private fun page(records: List<ObservationRecord.Output> = emptyList()): ObservationOutputRead =
        ObservationOutputRead.Page(
            ObservationOutputPage(
                records = records,
                lastOrdinal = records.lastOrNull()?.frame?.ordinal ?: -1L,
                moreFrames = false,
            ),
        )

    /** A reader that answers with a fixed script and records what it was asked for. */
    private class ScriptedReader(
        private val pages: List<ObservationOutputRead>,
        private val tails: List<OutputTailState?> = listOf(OutputTailState.Sealed(0L)),
    ) : ObservationOutputReader {
        val requestedOrdinals = mutableListOf<Long>()
        var reads = 0

        override fun readOutput(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead {
            requestedOrdinals += afterOrdinal
            val page = pages[minOf(reads, pages.size - 1)]
            reads++
            return page
        }

        override fun readOutput(
            runId: String,
            opId: String,
            afterOrdinal: Long,
            frameLimit: Int,
        ): ObservationOutputRead = throw UnsupportedOperationException("the drain reads the whole run")

        override fun tailStatesOf(runId: String): List<OutputTailState?> = tails

        override fun readTail(runId: String, tailBytes: Long): ObservationOutputRead =
            throw UnsupportedOperationException("the drain does not read tails")

        override fun readTail(runId: String, opId: String, tailBytes: Long): ObservationOutputRead =
            throw UnsupportedOperationException("the drain does not read tails")
    }

    private companion object {
        const val RUN = "run-1"
    }
}
