package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.time.Instant
import java.util.Base64

/**
 * OBS-E1: the machine format. One record per line, flushed as it goes, and bytes that are not text
 * survive to the wire.
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract over the production encoder, with one row (FLUSH-1) that observes a real
 * `Writer` subclass counting flushes — because "it flushes" is a claim about an interaction, and a
 * pure row could only assert that the encoder returns a string.
 *
 * The frames here are built rather than read from a store. That is a stated limitation: this class
 * proves the ENCODING, and that a real run's bytes reach it through `ObservationOutputReader` is
 * OBS-D2's `LANE-*` rows, which read through the production index and store.
 *
 * ## What this pins
 *
 * ```text
 * JSONL-1   one record per line, and every line is valid JSON on its own
 * JSONL-2   an output record names its channel and operation, not a guess
 * JSONL-3   valid UTF-8 travels as `text`
 * JSONL-4   invalid UTF-8 travels as `base64` with `text` explicitly null
 * JSONL-5   a character split across frames reaches the wire as bytes, not as U+FFFD
 * EVENT-1   an event line keeps the store's own payload fields
 * FLUSH-1   the writer flushes after EVERY record, not once at the end
 * ```
 *
 * ## Mutation
 *
 * - `M-E1` (strict decoding decides the wire form) → always emit `text`, taking the record's lossy
 *   decoded view. This is the plausible wrong fix, and it is silently lossy: a consumer would read a
 *   replacement character as something the process wrote.
 * - `M-E2` (flush per record) → flush once after the loop.
 */
class ObservationJsonLinesTest {

    private val run = "run-obse1"

    private fun frame(
        ordinal: Long,
        channel: OutputChannel,
        stream: String = "$run/build/sh-0/${channel.token}",
    ) = OutputFrame(ordinal, OutputStreamId(stream), channel, 0, 4)

    private fun out(
        ordinal: Long,
        channel: OutputChannel,
        raw: ByteArray,
        text: String = String(raw, Charsets.UTF_8),
        stream: String = "$run/build/sh-0/${channel.token}",
    ) = ObservationRecord.Output(frame(ordinal, channel, stream), raw, text)

    private fun event(text: String) = ObservationRecord.Event(
        EchoOutputCaptured("e-1", run, 3, Instant.parse("2026-10-07T10:00:00Z"), 0, text),
    )

    @Test
    fun `JSONL-1 one record per line and every line is valid JSON on its own`() {
        val records = listOf(event("alpha"), out(1, OutputChannel.STDOUT, "beta\n".toByteArray()))

        val writer = StringWriter()
        ObservationJsonLines.writeTo(records, writer)

        val lines = writer.toString().lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size, "got ${lines.size} lines: $lines")
        // Each line stands alone. A consumer reading by lines must never have to buffer to find the
        // end of a record, which is what makes `jsonl` the streaming format and `json` a snapshot.
        lines.forEach { line ->
            assertTrue(
                runCatching { Json.parseToJsonElement(line).jsonObject }.isSuccess,
                "a line must be a complete JSON object on its own: $line",
            )
        }
        assertEquals(
            listOf("event", "output"),
            lines.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `JSONL-2 an output record names its channel and operation rather than guessing`() {
        val line = Json.parseToJsonElement(
            ObservationJsonLines.encodeOne(out(4, OutputChannel.STDERR, "boom\n".toByteArray())),
        ).jsonObject

        assertEquals("output", line["type"]!!.jsonPrimitive.content)
        assertEquals("stderr", line["channel"]!!.jsonPrimitive.content)
        assertEquals("build/sh-0", line["operation"]!!.jsonPrimitive.content)
        assertEquals(4L, line["ordinal"]!!.jsonPrimitive.long)
    }

    @Test
    fun `JSONL-3 valid UTF-8 travels as text`() {
        val payload = "compilación correcta ✓\n".toByteArray(Charsets.UTF_8)

        val line = Json.parseToJsonElement(
            ObservationJsonLines.encodeOne(out(0, OutputChannel.STDOUT, payload)),
        ).jsonObject

        assertEquals("compilación correcta ✓\n", line["text"]!!.jsonPrimitive.content)
        assertFalse(
            line.containsKey("base64"),
            "a text record must not also carry base64; a consumer would not know which one is the " +
                "truth. Got $line",
        )
    }

    @Test
    fun `JSONL-4 invalid UTF-8 travels as base64 with text explicitly null`() {
        val payload = byteArrayOf(0x41, 0xFF.toByte(), 0xFE.toByte(), 0x42)

        val line = Json.parseToJsonElement(
            ObservationJsonLines.encodeOne(out(0, OutputChannel.STDOUT, payload, text = "A��B")),
        ).jsonObject

        assertTrue(
            line["text"] is JsonNull,
            "these bytes are not text, and `text` must say so explicitly. A U+FFFD here would be a " +
                "character the process never wrote. Got ${line["text"]}",
        )
        assertEquals(
            Base64.getEncoder().encodeToString(payload),
            line["base64"]!!.jsonPrimitive.content,
            "the machine-readable form must be the exact bytes, decodable by the consumer",
        )
    }

    @Test
    fun `JSONL-5 a character split across frames reaches the wire as bytes, not as U+FFFD`() {
        val euro = "€".toByteArray(Charsets.UTF_8)
        assertEquals(3, euro.size, "premise broken: the character is not multi-byte")

        // Two frames, exactly as OBS-D2's SPLIT-1 produces them: each carries half the character and
        // each decodes to U+FFFD on its own.
        val head = euro.copyOfRange(0, 2)
        val tail = euro.copyOfRange(2, 3)

        val lines = listOf(head, tail).mapIndexed { index, raw ->
            Json.parseToJsonElement(
                ObservationJsonLines.encodeOne(
                    out(index.toLong(), OutputChannel.STDOUT, raw, text = String(raw, Charsets.UTF_8)),
                ),
            ).jsonObject
        }

        // The lossy view is still lossy, and the row says so rather than hiding it.
        assertTrue(lines.all { it["text"] is JsonNull }, "half a character is not valid UTF-8")

        // Each record carries its own padded base64, so a consumer decodes per record and concatenates the
// BYTES. Joining the base64 text itself would not be valid base64 — padding is per block.
val rejoined = lines
            .map { Base64.getDecoder().decode(it["base64"]!!.jsonPrimitive.content) }
            .fold(ByteArray(0)) { acc, next -> acc + next }
        assertTrue(
            rejoined.contentEquals(euro),
            "the bytes must survive the wire exactly, so a consumer that reassembles the stream can " +
                "recover the character the per-frame decode lost. Got ${rejoined.toList()}",
        )
        assertEquals(
            "€",
            String(rejoined, Charsets.UTF_8),
            "reassembled from the wire, the character is intact — which is what base64 buys over text",
        )
    }

    @Test
    fun `EVENT-1 an event line keeps the store's own payload fields`() {
        val line = Json.parseToJsonElement(ObservationJsonLines.encodeOne(event("hello"))).jsonObject

        assertEquals("event", line["type"]!!.jsonPrimitive.content)
        assertEquals("EchoOutputCaptured", line["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "hello",
            line["content"]!!.jsonPrimitive.content,
            "the payload the store encoded must survive: JsonEventLog is the authority for an event's " +
                "wire shape and this object must not become a second one",
        )
        assertEquals(
            run,
            line["runId"]!!.jsonPrimitive.content,
            "premise broken: the field is named differently in the store's encoding",
        )
    }

    @Test
    fun `FLUSH-1 the writer flushes after every record, not once at the end`() {
        val flushes = CountingWriter()
        val records = List(4) { out(it.toLong(), OutputChannel.STDOUT, "line $it\n".toByteArray()) }

        ObservationJsonLines.writeTo(records, flushes)

        assertEquals(
            records.size,
            flushes.flushes,
            "each record must be flushed as it is written. A writer that flushes once at the end " +
                "turns --follow into a stream that delivers everything at the end, which is " +
                "indistinguishable from the bug this format exists to remove",
        )
    }

    private class CountingWriter : StringWriter() {
        var flushes = 0
            private set

        override fun flush() {
            flushes++
            super.flush()
        }
    }
}