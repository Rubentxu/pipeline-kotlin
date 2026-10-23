package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * A reader that is asked for one operation answers with THAT operation.
 *
 * The stream id of an operation is `{runId}/{operationId}/{channel}`, and the operation id is
 * the whole middle of it — which may carry separators of its own. So the operation is not a
 * piece of text that can be looked for: it is read from the two ends of the id. Two wrong
 * answers are available to a reader that does not read it that way, and both of them hand back
 * an operation nobody asked for:
 *
 *  - the id of one operation **contains** the id of another, and asking whether the stream
 *    CONTAINS the asked operation answers with the neighbour's frames;
 *  - the operation spans several segments, and keeping only the first of them names an
 *    operation that does not exist.
 *
 * The plane here is in memory, so the whole test is about the decision and not about storage:
 * the index answers with frames, the store answers with bytes, and nothing else is involved.
 */
class FrameIndexedObservationOutputReaderTest {

    @Test
    fun `the frames of an operation are the frames of that operation and of no other`() {
        val reader = reader(
            frame(0, "run-7/other/foo/stdout", "NEIGHBOUR"),
            frame(1, "run-7/foo/stdout", "MINE"),
        )

        val page = pageOf(reader.readOutput("run-7", "foo", afterOrdinal = -1L, frameLimit = 8))

        assertEquals(listOf("MINE"), page.records.map { it.text })
    }

    @Test
    fun `a piece of an operation id is not the operation`() {
        // The operation is `stage/step/inner`, and it is three segments wide. Asking for the
        // last two of them asks for a DIFFERENT operation, and the frames of the real one are
        // not the answer.
        val reader = reader(
            frame(0, "run-7/stage/step/inner/stdout", "WHOLE"),
        )

        val page = pageOf(reader.readOutput("run-7", "step/inner", afterOrdinal = -1L, frameLimit = 8))

        assertEquals(emptyList<String>(), page.records.map { it.text })
    }

    @Test
    fun `an operation that spans several segments is read whole`() {
        val reader = reader(
            frame(0, "run-7/stage/step/inner/stdout", "WHOLE"),
        )

        val page = pageOf(reader.readOutput("run-7", "stage/step/inner", afterOrdinal = -1L, frameLimit = 8))

        assertEquals(listOf("WHOLE"), page.records.map { it.text })
    }

    @Test
    fun `the tail of an operation does not carry the bytes of its neighbour`() {
        val reader = reader(
            frame(0, "run-7/other/foo/stdout", "NEIGHBOUR"),
            frame(1, "run-7/foo/stdout", "MINE"),
        )

        val page = pageOf(reader.readTail("run-7", "foo", tailBytes = 1024))

        assertEquals(listOf("MINE"), page.records.map { it.text })
    }

    // --- the plane, in memory ------------------------------------------------

    /** A frame and the bytes that were committed for it. */
    private data class Entry(val frame: OutputFrame, val text: String)

    private fun reader(vararg entries: Entry): FrameIndexedObservationOutputReader {
        val frames = entries.map { it.frame }
        val content = entries.associate { it.frame.stream to it.text.toByteArray() }
        return FrameIndexedObservationOutputReader(
            index = InMemoryIndex(frames),
            bytes = InMemoryBytes(content),
            tails = SealedTails(),
        )
    }

    private fun frame(ordinal: Long, stream: String, text: String): Entry {
        val bytes = text.toByteArray()
        return Entry(
            frame = OutputFrame(
                ordinal = ordinal,
                stream = OutputStreamId(stream),
                channel = OutputChannel.STDOUT,
                from = 0L,
                to = bytes.size.toLong(),
            ),
            text = text,
        )
    }

    private fun pageOf(read: ObservationOutputRead): ObservationOutputPage {
        val page = assertInstanceOf(ObservationOutputRead.Page::class.java, read)
        return page.page
    }

    /** The frames of a run, in the order they were published, with the run read from the address. */
    private class InMemoryIndex(private val frames: List<OutputFrame>) : OutputFrameIndex {

        override fun declareStream(stream: OutputStreamId, channel: OutputChannel) = Unit

        override fun append(
            stream: OutputStreamId,
            channel: OutputChannel,
            from: Long,
            to: Long,
        ): OutputFrame = error("a reader never writes")

        override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> =
            frames.filter { it.runId() == runId && it.ordinal > afterOrdinal }.take(limit)

        override fun lastOrdinal(runId: String): Long? =
            frames.filter { it.runId() == runId }.maxOfOrNull { it.ordinal }

        override fun streamsOfRun(runId: String): List<OutputStreamId> =
            frames.filter { it.runId() == runId }.map { it.stream }.distinct()

        override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()

        private fun OutputFrame.runId(): String? = OutputStreamAddress.parse(stream)?.runId
    }

    /** The committed bytes of each stream, whole. */
    private class InMemoryBytes(private val content: Map<OutputStreamId, ByteArray>) : OutputReadPort {

        override fun committedExtent(stream: OutputStreamId): Long? = content[stream]?.size?.toLong()

        override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {
            val bytes = content[stream] ?: return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
            val from = cursor.committedOffset.toInt()
            val end = minOf(bytes.size, from + maxBytes)
            return OutputReadResult.Page(
                OutputPage(
                    bytes = bytes.copyOfRange(from, end),
                    stream = stream,
                    from = from.toLong(),
                    next = null,
                    committedEnd = bytes.size.toLong(),
                ),
            )
        }

        override fun readRange(stream: OutputStreamId, from: Long, to: Long): OutputReadResult {
            val bytes = content[stream] ?: return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
            val start = from.toInt()
            val end = minOf(bytes.size, to.toInt())
            return OutputReadResult.Page(
                OutputPage(
                    bytes = bytes.copyOfRange(start, end),
                    stream = stream,
                    from = from,
                    next = null,
                    committedEnd = bytes.size.toLong(),
                ),
            )
        }
    }

    /** Every stream is closed: the tail of a sealed run is the tail of the run. */
    private class SealedTails : OutputTailPort {
        override fun tailState(stream: OutputStreamId): OutputTailState = OutputTailState.Sealed(0L)
    }
}
