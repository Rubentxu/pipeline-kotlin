package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * A page of frames names the position a consumer resumes from.
 *
 * ## The guarantee
 *
 * A frame read that still has frames left prints a continuation token, and that token is the
 * ordinal of the LAST frame the consumer was shown. It is a position, not a count: the consumer
 * hands it back as `--after-ordinal` and the next read continues strictly after it.
 *
 * The two are the same number only when the operation's frames begin at the first ordinal of the
 * run. In a run with more than one operation they do not: the frames of the first operation
 * occupy the low ordinals, so an operation that starts higher is read from a position the count
 * does not describe.
 *
 * ## The capacity this serves
 *
 * A consumer that pages one operation's output receives every frame of it exactly once. A token
 * that names a position before the last delivered frame makes the consumer resume inside the page
 * it already has, and the frames it is re-sent are duplicates the consumer cannot tell apart from
 * new ones.
 *
 * ## Why this instrument
 *
 * The reader and the emitter are both the shipped ones, over a plane that is only in memory: the
 * reader produces the page, the emitter prints the token, and the second row resumes with the
 * token the emitter printed. Nothing here touches a filesystem, a subprocess or a clock, so the
 * rows are fast and deterministic, and they exercise the two halves that have to agree.
 *
 * ## What this instrument cannot see
 *
 * The durable store, the file layout and the process boundary are not here. What is here is the
 * agreement between the page the reader builds and the token the emitter prints.
 */
class MainConsoleCliFramesPaginationTest {

    @Test
    fun `the text continuation token names the last delivered frame`() {
        val emission = emit(opId = "op-b", afterOrdinal = -1L, format = MainConsoleCli.ConsoleFormat.TEXT)

        assertEquals(0, emission.exit)
        assertEquals("B1B2", emission.wire)
        assertEquals("out-frame-ord-v1:4", emission.token)
    }

    @Test
    fun `the jsonl continuation token names the last delivered frame`() {
        val emission = emit(opId = "op-b", afterOrdinal = -1L, format = MainConsoleCli.ConsoleFormat.JSONL)

        assertEquals(0, emission.exit)
        assertEquals(listOf(3L, 4L), emission.ordinals)
        assertEquals("out-frame-ord-v1:4", emission.token)
    }

    @Test
    fun `resuming from the printed token delivers no frame twice`() {
        val first = emit(opId = "op-b", afterOrdinal = -1L, format = MainConsoleCli.ConsoleFormat.JSONL)
        val resumeAt = ConsoleFramePlane.decodeFrameOrdinal(first.token)
        assertEquals(4L, resumeAt, "the token did not name a position: ${first.token}")

        val second = emit(opId = "op-b", afterOrdinal = resumeAt!!, format = MainConsoleCli.ConsoleFormat.JSONL)

        assertTrue(
            first.ordinals.none { it in second.ordinals },
            "the resume repeated frames: first=${first.ordinals} second=${second.ordinals}",
        )
        assertEquals(listOf(5L), second.ordinals)
    }

    @Test
    fun `the operation that owns the first frames of the run keeps the token it had`() {
        // The neighbouring case: this operation's frames start at the first ordinal of the run,
        // so the position and the count coincide. It is green before the change and green after
        // it, and it is here to reject a change that moves the token for every operation.
        val emission = emit(opId = "op-a", afterOrdinal = -1L, format = MainConsoleCli.ConsoleFormat.TEXT)

        assertEquals("A1A2", emission.wire)
        assertEquals("out-frame-ord-v1:1", emission.token)
    }

    // --- the shipped reader and the shipped emitter, over a plane in memory ----------

    /** What one read produced: the exit code, what it wrote and the token it printed. */
    private data class Emission(
        val exit: Int,
        val wire: String,
        val ordinals: List<Long>,
        val token: String,
    )

    /**
     * One read of one operation, through the shipped reader and the shipped emitter.
     *
     * The request and the read are built from the same arguments on purpose: a consumer's
     * `--after-ordinal` and the position the reader starts from are one decision, and a test
     * that could make them disagree would be testing a pairing nobody uses.
     */
    private fun emit(
        opId: String,
        afterOrdinal: Long,
        format: MainConsoleCli.ConsoleFormat,
        frameLimit: Int = 2,
    ): Emission {
        val request = ConsoleReadRequest.Frames(
            controlDir = "/not-used",
            runId = "run-1",
            opId = opId,
            afterOrdinal = afterOrdinal,
            frameLimit = frameLimit,
            format = format,
        )
        val plane = InMemoryOutputPlane(planeFrames)
        val reader = FrameIndexedObservationOutputReader(
            index = plane,
            bytes = plane,
            tails = plane,
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = ConsolePresentation.emitFrames(
            result = reader.readOutput("run-1", opId, afterOrdinal, frameLimit),
            request = request,
            out = PrintStream(out, true, Charsets.UTF_8.name()),
            err = PrintStream(err, true, Charsets.UTF_8.name()),
        )
        val written = out.toString(Charsets.UTF_8.name())
        return Emission(
            exit = exit,
            wire = written,
            ordinals = if (format == MainConsoleCli.ConsoleFormat.JSONL) {
                written.lines().filter { it.isNotBlank() }.map { line ->
                    ObservationJsonLines.parse(line)["ordinal"]!!.jsonPrimitive.long
                }
            } else {
                emptyList()
            },
            token = err.toString(Charsets.UTF_8.name()).trim(),
        )
    }

    /** A frame and the bytes that were committed for it. */
    private data class Entry(val frame: OutputFrame, val text: String)

    /**
     * A run with two operations: the first owns the low ordinals, the second owns the high ones.
     * That is the shape that makes the position and the count differ.
     */
    private val planeFrames: List<Entry> = frames(
        "run-1/op-a/stdout" to "A1",
        "run-1/op-a/stdout" to "A2",
        "run-1/op-a/stdout" to "A3",
        "run-1/op-b/stdout" to "B1",
        "run-1/op-b/stdout" to "B2",
        "run-1/op-b/stdout" to "B3",
    )

    /**
     * The frames of a run, in the order they were published. Two frames of the SAME stream are
     * given consecutive offsets of that stream, because that is what a stream is: a sequence of
     * committed windows, not a bag of independent texts.
     */
    private fun frames(vararg specs: Pair<String, String>): List<Entry> {
        val offsets = mutableMapOf<OutputStreamId, Long>()
        return specs.mapIndexed { ordinal, (stream, text) ->
            val id = OutputStreamId(stream)
            val bytes = text.toByteArray(Charsets.UTF_8)
            val from = offsets.getOrDefault(id, 0L)
            offsets[id] = from + bytes.size
            Entry(
                frame = OutputFrame(
                    ordinal = ordinal.toLong(),
                    stream = id,
                    channel = OutputChannel.STDOUT,
                    from = from,
                    to = from + bytes.size,
                ),
                text = text,
            )
        }
    }

    /**
     * The plane, in memory: what was published, where, and which bytes each frame carries.
     *
     * One class plays the three roles the reader needs — the index that knows the order, the
     * port that knows the bytes and the port that knows the tails — because they are one set of
     * facts here. A reader never writes, so the write side refuses.
     */
    private class InMemoryOutputPlane(entries: List<Entry>) :
        OutputFrameIndex,
        OutputReadPort,
        OutputTailPort {

        private val frames: List<OutputFrame> = entries.map { it.frame }

        private val content: Map<OutputStreamId, ByteArray> =
            entries.groupBy { it.frame.stream }.mapValues { (_, group) ->
                val buffer = ByteArrayOutputStream()
                group.forEach { buffer.write(it.text.toByteArray(Charsets.UTF_8)) }
                buffer.toByteArray()
            }

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

        override fun tailState(stream: OutputStreamId): OutputTailState = OutputTailState.Sealed(0L)

        private fun OutputFrame.runId(): String? = OutputStreamAddress.parse(stream)?.runId
    }
}
