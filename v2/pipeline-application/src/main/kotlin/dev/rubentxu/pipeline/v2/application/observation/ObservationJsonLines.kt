package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.Writer

/**
 * The machine-readable projection of the read model: **one record per line**, written and flushed
 * as it goes.
 *
 * ## Why JSONL and not JSON
 *
 * A JSON array is only a document when its closing bracket arrives. A consumer following a run
 * cannot see `]` until the run ends, so `json` is a snapshot format and pretending it streams is the
 * same mistake as calling a console log a protocol. `jsonl` is the live one, and this object is its
 * only writer.
 *
 * ## stdout belongs to the protocol
 *
 * A caller that chose `--format jsonl` has a machine on the other end of stdout. Diagnostics,
 * warnings and human text go elsewhere; nothing else may be interleaved here, because a consumer
 * reading line by line cannot tell a protocol record from a log line that happens to contain a
 * brace. That is why this writer appends the newline itself and why nothing else writes to it in
 * this mode.
 *
 * ## Bytes that are not text
 *
 * The Output Plane holds BYTES, and a process may emit a sequence that is not valid UTF-8. Two
 * things follow, and both are visible in the emitted shape:
 *
 * ```text
 * valid UTF-8   {"type":"output", ..., "text":"..."}
 * invalid       {"type":"output", ..., "text":null, "base64":"..."}
 * ```
 *
 * A decoder that emits U+FFFD for undecodable bytes is right for a human reader and wrong for a
 * machine one, because the replacement character is indistinguishable from a character the process
 * really wrote. So the strict decoder decides, and the base64 field carries the truth. The record's
 * own `text` is the lossy VIEW and is never the wire form.
 *
 * This is also why `ObservationRecord.Output` carries raw bytes and not only decoded text: by the
 * time a record held only a `String`, the option above would already have been gone.
 */
object ObservationJsonLines {

    /**
     * One record, no trailing newline.
     *
     * Pure and total. It never emits a partial line, because a consumer reading by lines would
     * treat a fragment as a record.
     */
    fun encodeOne(record: ObservationRecord): String = when (record) {
        is ObservationRecord.Event -> encodeEventLine(record.event)
        is ObservationRecord.Output -> encodeOutputLine(record)
    }

    /**
     * Writes [records] one line at a time, flushing after each.
     *
     * The flush is the point. A writer that buffers turns `--follow` into a stream that delivers
     * everything at the end, which is indistinguishable from the bug this format exists to remove.
     */
    fun writeTo(records: Iterable<ObservationRecord>, out: Writer) {
        for (record in records) {
            out.write(encodeOne(record))
            out.write("\n")
            out.flush()
        }
    }

    /** Convenience for a whole page, still one line and one flush per record. */
    fun writeTo(page: ObservationOutputPage, out: Writer): Unit = writeTo(page.records, out)

    /**
     * An event line that reuses the store's own encoder for the payload.
     *
     * `JsonEventLog` stays the authority for how an event looks on the wire, so a JSONL consumer
     * sees exactly the event bytes `pipeline events` emits. This object parses that object and adds
     * the two protocol fields structurally — it does not re-encode the payload, and it does not
     * splice strings together, because a second encoder or a string edit would each be a second
     * authority over the same wire shape and would drift from it.
     */
    private fun encodeEventLine(event: DomainEvent): String {
        val payload = Json.parseToJsonElement(JsonEventLog.encodeOne(event)).jsonObject
        return buildJsonObject {
            put("type", "event")
            put("kind", event.kind)
            for ((key, value) in payload) put(key, value)
        }.toString()
    }

    private fun encodeOutputLine(record: ObservationRecord.Output): String {
        val decoded = decodeStrictly(record.bytes)
        return buildJsonObject {
            put("type", "output")
            put("channel", record.channel.token)
            record.address?.let { put("operation", it.operationId) }
            put("ordinal", record.frame.ordinal)
            put("from", record.frame.from)
            put("to", record.frame.to)
            if (decoded == null) {
                // `text` is explicitly null rather than absent, so a consumer can tell "these bytes
                // are not text" from "this record has no text field". A replacement character here
                // would be a character the process never wrote.
                put("text", JsonPrimitive(null as String?))
                put("base64", java.util.Base64.getEncoder().encodeToString(record.bytes))
            } else {
                put("text", decoded)
            }
        }.toString()
    }

    /**
     * The bytes as text, or `null` when they are not valid UTF-8.
     *
     * Returns `null` rather than a lossy string on purpose: the caller must choose between two wire
     * forms, and it can only choose if the difference is visible.
     */
    private fun decodeStrictly(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /** The parsed shape of one line, exposed so a consumer and this writer cannot disagree. */
    internal fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject
}