package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * A filtered follow keeps LOOKING.
 *
 * ## The guarantee
 *
 * A `--follow` that carries a filter delivers the frames the filter ADMITS, and it keeps
 * looking for them until the output lane says there is nothing more. Two facts are one
 * guarantee here, because a follow that delivers the right frames by never looking again has
 * not delivered them:
 *
 *  - a frame the filter DROPS never pins the read window. It was read and it was rejected, and
 *    asking for it again on the next poll re-reads the same window for ever;
 *  - a frame the filter ADMITS is delivered even when the window that first contained it held
 *    nothing else.
 *
 * The second row is the one that was broken. `console --frames --follow --stage beta` over a
 * run whose first frames belong to another stage delivered NOTHING and exited 3, because the
 * window was pinned to the last DELIVERED frame and no frame was ever delivered.
 *
 * ## The capacity this serves
 *
 * An operator who asks a follow for one stage gets that stage. Before this, the same question
 * had two wrong answers: the follow either showed the whole run (the filter was not applied at
 * all) or showed nothing and reported that it had exhausted its poll budget — with the budget
 * burned on re-reading the same frames.
 *
 * ## Why this instrument
 *
 * The loop is the thing, so the loop is what is driven: [MainConsoleCli.followFrames] over an
 * in-memory plane, with the wait between polls INJECTED. That is what makes the instrument
 * cheap and deterministic — no filesystem, no subprocess, no clock, no sleep — and it is what
 * makes it a real instrument rather than a restatement of the rule: the loop, the filter and
 * the emitter are the shipped ones.
 *
 * The seam that made this possible is the `waitBetweenPolls` parameter, and it exists for this
 * row and not for convenience.
 *
 * ## What is frozen, and what is not
 *
 * The THIRD row freezes the resume contract: an exhausted poll budget reports the last frame
 * the consumer was SHOWN, never the last frame the loop EXAMINED. That row is green before the
 * cut and green after it — it is not here to detect the defect, it is here to REJECT A WRONG
 * FIX: an implementation that reports the examined ordinal would resume past frames the
 * consumer was never shown.
 *
 * ## What this instrument cannot see
 *
 * The durable plane, the poll interval, the JVM shutdown hook and the exit code of the process
 * are not here. The plane is in memory and the wait is injected, so this file says nothing
 * about how the loop behaves against a real store, nor about how long it takes. The exit code
 * of the verb is a projection of [FollowExit], and the projection itself is the verb's.
 */
class MainConsoleCliFollowTest {

    @Test
    fun `a follow whose window opens with dropped frames still delivers the frame the filter admits`() {
        val out = ByteArrayOutputStream()

        val exit = follow(
            frames = frames(
                "run-1/op-a/stdout" to "ALPHA",
                "run-1/op-a/stdout" to "\n",
                "run-1/op-b/stdout" to "BETA",
                "run-1/op-b/stdout" to "\n",
            ),
            selection = OperationSelection.Operations(setOf("op-b")),
            frameLimit = 1,
            maxPolls = 10,
            out = PrintStream(out, true, Charsets.UTF_8.name()),
        )

        // The window of the first polls holds nothing the filter admits. The follow has to walk
        // PAST those frames to reach the one it admits, and then it has to say that the output
        // lane is final — which is a different fact from "the budget ran out".
        assertEquals(FollowExit.Completed, exit)
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("BETA"), "the frame the filter admits was not delivered:\n$text")
        assertFalse(text.contains("ALPHA"), "a frame the filter drops was delivered:\n$text")
    }

    @Test
    fun `every admitted frame is delivered once, in order, through the same stream`() {
        val out = ByteArrayOutputStream()
        val stream = PrintStream(out, true, Charsets.UTF_8.name())

        val exit = follow(
            frames = frames(
                "run-1/op-a/stdout" to "ALPHA",
                "run-1/op-b/stdout" to "ONE",
                "run-1/op-a/stdout" to "GAMMA",
                "run-1/op-b/stdout" to "TWO",
            ),
            selection = OperationSelection.Operations(setOf("op-b")),
            frameLimit = 1,
            maxPolls = 10,
            out = stream,
            format = MainConsoleCli.ConsoleFormat.JSONL,
        )

        assertEquals(FollowExit.Completed, exit)
        val lines = out.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "the follow emitted:\n$lines")
        assertTrue(lines[0].contains("ONE"), lines[0])
        assertTrue(lines[1].contains("TWO"), lines[1])

        // The stream is still the CONSUMER'S. The emitter used to close it after the first page,
        // which is why the second page never landed: a PrintStream swallows the write that
        // follows a close, so this assertion is the one that sees the stream die.
        stream.print("AFTER")
        stream.flush()
        assertTrue(
            out.toString(Charsets.UTF_8.name()).endsWith("AFTER"),
            "the emitter closed the consumer's stream",
        )
    }

    @Test
    fun `an exhausted poll budget reports the last delivered ordinal and not the last examined one`() {
        val out = ByteArrayOutputStream()

        val exit = follow(
            frames = frames(
                "run-1/op-a/stdout" to "ALPHA",
                "run-1/op-b/stdout" to "ONE",
                "run-1/op-a/stdout" to "GAMMA",
                "run-1/op-a/stdout" to "DELTA",
            ),
            selection = OperationSelection.Operations(setOf("op-b")),
            frameLimit = 2,
            maxPolls = 2,
            out = PrintStream(out, true, Charsets.UTF_8.name()),
        )

        val exhausted = assertInstanceOf(FollowExit.PollBudgetExhausted::class.java, exit)
        // 1 is the last frame the consumer was SHOWN. 3 is the last frame the loop EXAMINED,
        // and the filter dropped it: a resume from 3 would skip frames nobody was shown.
        assertEquals(1L, exhausted.lastOrdinal)
        assertEquals(2, exhausted.polls)
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("ONE"), text)
        assertFalse(text.contains("GAMMA"), text)
        assertFalse(text.contains("DELTA"), text)
    }

    // --- the loop, over a plane that is only in memory ------------------------

    /**
     * The shipped loop, over the in-memory plane, with the wait between polls injected so the
     * row never sleeps and never touches a clock.
     */
    private fun follow(
        frames: List<Entry>,
        selection: OperationSelection,
        frameLimit: Int,
        maxPolls: Int,
        out: PrintStream,
        format: MainConsoleCli.ConsoleFormat = MainConsoleCli.ConsoleFormat.TEXT,
    ): FollowExit = MainConsoleCli.followFrames(
        request = ConsoleReadRequest.FollowFrames(
            controlDir = "/not-used",
            runId = "run-1",
            opId = null,
            afterOrdinal = -1L,
            frameLimit = frameLimit,
            followConfig = FollowConfig(pollIntervalMillis = 1L, maxPolls = maxPolls),
            format = format,
        ),
        reader = reader(frames),
        out = out,
        err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8.name()),
        selection = selection,
        waitBetweenPolls = { },
    )

    /** A frame and the bytes that were committed for it. */
    private data class Entry(val frame: OutputFrame, val text: String)

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

    private fun reader(entries: List<Entry>): FrameIndexedObservationOutputReader =
        FrameIndexedObservationOutputReader(
            index = InMemoryIndex(entries.map { it.frame }),
            bytes = InMemoryBytes(entries),
            tails = SealedTails(),
        )

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
    private class InMemoryBytes(entries: List<Entry>) : OutputReadPort {

        private val content: Map<OutputStreamId, ByteArray> =
            entries.groupBy { it.frame.stream }.mapValues { (_, group) ->
                val buffer = ByteArrayOutputStream()
                group.forEach { buffer.write(it.text.toByteArray(Charsets.UTF_8)) }
                buffer.toByteArray()
            }

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
