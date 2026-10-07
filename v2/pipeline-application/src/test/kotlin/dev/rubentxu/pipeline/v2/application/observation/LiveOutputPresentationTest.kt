package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * OBS-E4: what `pipeline run` writes for each format, and to which stream.
 *
 * ## Harness fidelity
 *
 * HF0 — a pure contract row. This format decision is a projection over a record that
 * `ObservationJsonLinesTest` already certifies against real bytes, so re-forking a store here would
 * test the encoder twice and assert nothing new. What is pinned here is what this TYPE does with the
 * encoder's output, and the two things a `run` gets wrong if nobody pins them: the human format
 * decorating bytes it must not decorate, and a refusal corrupting the machine protocol.
 *
 * ## What this pins
 *
 * ```text
 * FMT-1   TEXT writes the bytes verbatim — no prefix, no re-indentation
 * FMT-2   JSON_LINES writes one parseable object per line and flushes each
 * FMT-3   JSON writes nothing live, because an array cannot be streamed
 * FMT-4   a refusal goes to diagnostics, never to the protocol stream
 * ```
 *
 * ## Mutation
 *
 * - `M-E14` (human text decorated) → prefix output records the way event lines are prefixed.
 * - `M-E15` (refusal corrupts stdout) → write the refusal to `out`.
 * - `M-E16` (json pretends to stream) → emit records into the live json array.
 */
class LiveOutputPresentationTest {

    private fun record(
        text: String,
        channel: OutputChannel = OutputChannel.STDOUT,
        ordinal: Long = 0L,
    ) = ObservationRecord.Output(
        frame = OutputFrame(
            stream = OutputStreamId("run-1/run-1-s0-0/${channel.token}"),
            channel = channel,
            from = 0L,
            to = text.toByteArray(Charsets.UTF_8).size.toLong(),
            ordinal = ordinal,
        ),
        bytes = text.toByteArray(Charsets.UTF_8),
        text = text,
    )

    private fun capture(format: ObservationFormat): Triple<LiveOutputPresentation, ByteArrayOutputStream, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        val diagnostics = ByteArrayOutputStream()
        return Triple(
            LiveOutputPresentation(
                format,
                PrintStream(out, true, "UTF-8"),
                PrintStream(diagnostics, true, "UTF-8"),
            ),
            out,
            diagnostics,
        )
    }

    @Test
    fun `FMT-1 TEXT writes the bytes verbatim`() {
        val (presentation, out, _) = capture(ObservationFormat.TEXT)

        presentation.emit(record("Compiling module A\n"))
        presentation.emit(record("warning: deprecated API\n", OutputChannel.STDERR, ordinal = 1))
        presentation.emit(record("\u001b[32mgreen\u001b[0m\n", ordinal = 2))

        assertEquals(
            "Compiling module A\nwarning: deprecated API\n\u001b[32mgreen\u001b[0m\n",
            out.toString("UTF-8"),
            "the human format writes the child's bytes exactly as they were committed. Prefixing " +
                "them the way PipelineK's own event lines are prefixed would break ANSI colour, " +
                "progress bars, partial lines and copy-paste, which is what a person watches a " +
                "build for. The renderer holds presentation state so it does not cut into a " +
                "partial line, and none of that touches the bytes.",
        )
    }

    @Test
    fun `FMT-2 JSON_LINES writes one parseable object per line`() {
        val (presentation, out, _) = capture(ObservationFormat.JSON_LINES)

        presentation.emit(record("Compiling module A\n"))
        presentation.emit(record("warning\n", OutputChannel.STDERR, ordinal = 1))

        val lines = out.toString("UTF-8").lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "one record per line: ${lines}")

        val stdout = Json.parseToJsonElement(lines[0]).jsonObject
        val stderr = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("output", stdout["type"]!!.jsonPrimitive.content)
        assertEquals("stdout", stdout["channel"]!!.jsonPrimitive.content)
        assertEquals("stderr", stderr["channel"]!!.jsonPrimitive.content, "channel rides on each record")
    }

    @Test
    fun `FMT-2b JSON_LINES flushes every record rather than buffering`() {
        val out = ByteArrayOutputStream()
        // A PrintStream that does not auto-flush: the presentation must flush explicitly, because
        // the defect this replaces was a buffer that withheld the bytes until the run ended.
        val presentation = LiveOutputPresentation(
            ObservationFormat.JSON_LINES,
            PrintStream(out, false, "UTF-8"),
            PrintStream(ByteArrayOutputStream(), true, "UTF-8"),
        )

        presentation.emit(record("first\n"))
        val afterFirst = out.size()

        presentation.emit(record("second\n", ordinal = 1))
        val afterSecond = out.size()

        assertTrue(afterFirst > 0, "the first record must be visible before the second is written")
        assertTrue(afterSecond > afterFirst, "each record reaches the stream on its own")
    }

    @Test
    fun `FMT-3 JSON writes nothing live`() {
        val (presentation, out, _) = capture(ObservationFormat.JSON)

        presentation.emit(record("Compiling module A\n"))

        assertEquals(
            "",
            out.toString("UTF-8"),
            "a JSON array is only a document once its `]` arrives, so a live record has no honest " +
                "place in it. Emitting one anyway produces a stream that parses as neither an array " +
                "nor a sequence of records — the same reason jsonl exists and json does not pretend.",
        )
        assertNull(presentation.render(record("x\n")), "and render agrees: no live contribution")
    }

    @Test
    fun `FMT-3b JSON still owes its end-of-run document`() {
        val (presentation, out, _) = capture(ObservationFormat.JSON)
        val document = StringWriter()

        presentation.documentFor(listOf(record("a\n"), record("b\n", ordinal = 1)), document)

        val array = Json.parseToJsonElement(document.toString())
        assertEquals(2, (array as kotlinx.serialization.json.JsonArray).size, "one element per record")
    }

    @Test
    fun `FMT-4 a refusal goes to diagnostics and never to the protocol stream`() {
        val (presentation, out, diagnostics) = capture(ObservationFormat.JSON_LINES)

        presentation.emit(record("real output\n"))
        presentation.reportRefusal(OutputRefusal.RecoveryNotCompleted)

        assertTrue(
            out.toString("UTF-8").lines().filter { it.isNotBlank() }.all { line ->
                runCatching { Json.parseToJsonElement(line) }.isSuccess
            },
            "every line on stdout must still parse. A refusal written into the protocol stream " +
                "would turn a storage fault into a broken consumer.",
        )
        assertFalse(
            out.toString("UTF-8").contains("refused"),
            "the protocol stream carries records, not diagnostics",
        )
        assertTrue(
            diagnostics.toString("UTF-8").contains("refused"),
            "and the person still finds out the console is incomplete: " +
                diagnostics.toString("UTF-8").trim(),
        )
    }
}

/** Minimal StringWriter stand-in so the test carries no extra dependency. */
private class StringWriter : java.io.Writer() {
    private val sb = StringBuilder()
    override fun write(cbuf: CharArray, off: Int, len: Int) { sb.appendRange(cbuf, off, off + len) }
    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = sb.toString()
}