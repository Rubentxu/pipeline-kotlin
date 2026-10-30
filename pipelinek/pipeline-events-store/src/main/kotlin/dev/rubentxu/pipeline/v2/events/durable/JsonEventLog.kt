package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.DomainEvent

/**
 * JSON wire format encoder/decoder for domain events.
 * Wire shape: single-line JSON array, tagged `kind` field.
*/
object JsonEventLog {

    fun encode(events: List<DomainEvent>): String {
        val sb = StringBuilder("[")
        events.forEachIndexed { index, event ->
            if (index > 0) sb.append(",")
            sb.append(EventJsonWriter.encodeEvent(event))
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * ONE event as ONE JSON object, with no array wrapper.
     * ## Why this exists rather than slicing [encodeTo]'s output
     * [encode] and [encodeTo] are DOCUMENT codecs: their wire form is a bracketed array. A paged
     * command cannot use them directly, because a page boundary has to land between two objects —
     * and the two ways to get there are both wrong. Re-implementing the field writing here would
     * create a second encoder that can drift from the store's, and it would be free to emit a shape
     * the store never wrote. Wrapping a single event in an array and stripping the brackets would
     * mean parsing what was just serialized, and would break on any field whose value legitimately
     * contains the delimiter.
     * So this delegates. The field order, the escaping and the `kind` tag are produced by the same
     * [EventJsonWriter.encodeEvent] the store itself uses, and an event encoded here is byte-for-byte
     * the same element [encode] would put at that index.
*/
    fun encodeOne(event: DomainEvent): String = EventJsonWriter.encodeEvent(event)

    /**
     * Streams the same JSON array as [encode] to
     * [out] one event at a time so the full document is never materialised as
     * a single in-memory String. Byte-for-byte identical output.
*/
    fun encodeTo(events: Sequence<DomainEvent>, out: java.io.Writer) {
        out.append('[')
        var index = 0
        for (event in events) {
            if (index > 0) out.append(',')
            out.append(EventJsonWriter.encodeEvent(event))
            index++
        }
        out.append(']')
    }

    /**
     * Decodes a JSON ARRAY of events, skipping any element that will not decode.
     * ## This is a DOCUMENT codec, and it is NOT the durable read path (E4c)
     * The `?: continue` below is deliberate and it is why this method must not be used to read
     * stored rows. Its unit is a DOCUMENT, and a document has no per-element identity to report: a
     * caller cannot say "the record at sequence 41 exists and I could not read it" from an empty
     * list, because the sequence was never in this method's hands. Inventing one would create a
     * second authority for position, which is precisely what the store's sequence law forbids.
     * What this codec CAN do — and what the store must not do for it — is be told the answer it
     * actually has. The row carries its identity in its own COLUMNS, so the refusal belongs to the
     * store, at [decodeStoredRow] / `SqliteEventStore.readRecord`. That is where an `Undecodable`
     * gets a real sequence instead of a guess.
     * The split is held by the RETURN TYPE, not by a test: `List<DomainEvent>` has nowhere to put a
     * position, so this method cannot answer "row 41 exists and is unreadable" even if it tried to.
     * A caller that needs that answer has to go through [decodeStoredRow], whose `StoredRowDecode`
     * can carry one.
*/
    fun decode(payload: String): List<DomainEvent> {
        val document = arrayBoundsOf(payload) ?: return emptyList()
        if (payload.substring(document.first, document.last + 1) == "[]") return emptyList()
        val events = mutableListOf<DomainEvent>()
        val eventStrings = splitArray(payload.substring(document.first + 1, document.last))
        for (eventStr in eventStrings) {
            val trimmed = eventStr.trim()
            if (trimmed.isEmpty()) continue
            val event = when (val outcome = DomainEventJsonDecoder.decode(trimmed)) {
                is EventDecodeOutcome.Decoded -> outcome.event
                // A document legitimately holds kinds this binary does not know, and an object whose
                // field cannot be read is skipped exactly as before. [decode] answers "which events
                // are in this document" and has no row to attach a reason to; [decodeStoredRow] is
                // the surface that must refuse, and it calls the same decoder and keeps the cause.
                is EventDecodeOutcome.Failed -> continue
            }
            events.add(event)
        }
        return events
    }

    /**
     * Decodes the FIRST event object in [payload] and keeps what happened to it.
     * [decodeStoredRow] needs this because its unit of work is one row, and a row that exists but
     * did not decode has to be answered for — not silently reduced to an empty list. Routing
     * through [decode] and taking `firstOrNull()` would throw away the failure, which is the
     * information the store is obliged to report.
     * A payload with no decodable object at all is a failure of the DOCUMENT shape, reported as a
     * quoted payload rather than a named field: there is no field to name when the array itself
     * could not be read.
*/
    private fun decodeFirstOutcome(payload: String): EventDecodeOutcome {
        if (payload.isBlank() || payload == "[]") {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.UnreadableField(
                    "payload",
                    "the stored payload holds no event array",
                )
            )
        }
        for (eventStr in splitArray(payload.substring(1, payload.length - 1))) {
            val trimmed = eventStr.trim()
            if (trimmed.isEmpty()) continue
            return DomainEventJsonDecoder.decode(trimmed)
        }
        return EventDecodeOutcome.Failed(
            EventDecodeFailure.UnreadableField(
                "payload",
                "does not satisfy the stored payload schema: " +
                    payload.take(STORED_ROW_DETAIL_CHARS).let { "\"$it\"" },
            )
        )
    }

    /**
     * Does this binary have a decoder for [kind]? E4c
     *
     * [decode] answers with events and cannot say WHY one is missing: an unknown `kind` and a known
     * `kind` with a bad payload both come back as "no event". A durable read that has to report a
     * refusal needs that distinction — "this runtime is older than the row" is version skew worth
     * surfacing upward, and "this runtime knows the row and cannot read it" is corruption — so it
     * cannot infer one from the other.
     *
     * The vocabulary itself lives in [EventKindMatrix], the table that binds every kind to the
     * type that owns it, so that what can be read and the arms that read it are one thing and
     * not two.
     */
    fun knowsKind(kind: String): Boolean = DomainEventJsonDecoder.knowsKind(kind)

    /**
     * Decodes ONE stored payload into a closed outcome, rather than a list that may be shorter than
     * one. E4c.
     * [decode] answers "which events are in this document", and for a document assembled from
     * stdout that is the whole question. It is the WRONG shape for a durable row, where the caller's
     * unit is one row that the store has already identified and must answer for: an empty list there
     * has to become a refusal carrying that row's sequence, and expressing it as `List<DomainEvent>`
     * forces every caller to write `firstOrNull()` and decide — which is precisely the discarding the
     * read-side law forbids.
     * So this is the codec's own statement of "the row did not decode", and it carries a REASON so
     * the store does not have to guess one by re-parsing. An unknown `kind` and a known kind with an
     * unsatisfiable payload are different facts and they stay different here.
     * [rowKind] is the row's `kind` COLUMN, passed in rather than parsed from [payload], because
     * classification must not depend on the thing being classified.
     * The null payload case is a refusal too, not an absence: the row exists, the column is the
     * only thing that could have held it, and it held nothing.
*/
    fun decodeStoredRow(payload: String?, rowKind: String?): StoredRowDecode = when {
        payload == null -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload("payload column is null")
        )
        // The kind comes from the ROW COLUMN, and that ordering is the whole point of the parameter.
        // The first version of this read the kind out of the payload, which makes the classification
        // wrong exactly when it matters: a row can be unreadable *because* of its payload, and then
        // the payload is the one thing that cannot be trusted to name the row. The column is written
        // by `bindInsert` from the event and is NOT NULL, so it is the authority that survives.
        rowKind == null || rowKind.isEmpty() -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload("row carries no kind")
        )
        !knowsKind(rowKind) -> StoredRowDecode.Refused(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind(rowKind)
        )
        else -> {
            // Decode ONCE and keep both halves of the outcome. The decoder's own cause is
            // preferred over quoting the payload, because the quote is truncated at
            // [STORED_ROW_DETAIL_CHARS] and the offending field is routinely cut off; a 339-char
            // payload refused with a detail that stopped mid-filler, naming neither field nor value.
            // The payload echo survives only as a fallback for a document whose SHAPE was unusable,
            // which is a fact about the payload as a whole rather than about one field.
            when (val first = decodeFirstOutcome(payload)) {
                is EventDecodeOutcome.Decoded ->
                    // The column and the payload are two statements about the same row, and the
                    // column is the one that was trusted to classify it. When they disagree the
                    // row is not what the column said it was, and it is refused rather than
                    // accepted under the payload's own name.
                    if (first.event.kind == rowKind) {
                        StoredRowDecode.Accepted(first.event)
                    } else {
                        StoredRowDecode.Refused(
                            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload(
                                "row kind \"$rowKind\" does not match the payload kind \"${first.event.kind}\"",
                            ),
                        )
                    }
                is EventDecodeOutcome.Failed -> StoredRowDecode.Refused(
                    when (val c = first.cause) {
                        is EventDecodeFailure.UnknownEventKind ->
                            dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind(c.kind)
                        is EventDecodeFailure.UnreadableField ->
                            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload(c.description)
                        is EventDecodeFailure.KindMismatch ->
                            dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload(c.description)
                    }
                )
            }
        }
    }

    /** How much of a failing payload a refusal quotes. Bounded: these payloads can be GiB-scale. */
    private const val STORED_ROW_DETAIL_CHARS = 200



    /**
     * Locates the JSON event array inside [payload], returning its `[` and `]` as indices.
     * `decode` assumed the array started at index 0 and ended at the last
     * character. That is right for a document from [encode], but the CLI writes the
     * `LaunchReceipt` (`pipelinek.launch/1`) to the same stream first, so positional slicing
     * removed the receipt's `}` and the last progress line instead of the array's own brackets.
     * Balance alone is not enough to find the array: the receipt and the progress lines contain
     * `[` and `]`, and `[PipelineK] …` opens and closes a pair on one line. So each candidate
     * pair is checked to actually hold a list of JSON objects, and the first that does is the
     * array. Brackets inside JSON strings are skipped, so an event whose field contains a bracket
     * cannot truncate the document, and an unterminated array is never read as a partial list.
*/
    private fun arrayBoundsOf(payload: String): IntRange? {
        for (open in payload.indices) {
            if (payload[open] != '[') continue
            val close = closingBracketAt(payload, open) ?: continue
            val inner = payload.substring(open + 1, close)
            if (inner.isBlank() || splitArray(inner).all { it.trimStart().startsWith("{") }) {
                return open..close
            }
        }
        return null
    }

    /** Index of the `]` that closes the `[` at [open], or null if that bracket never closes. */
    private fun closingBracketAt(payload: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in open until payload.length) {
            val ch = payload[i]
            when {
                escape -> escape = false
                ch == '\\' && inString -> escape = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '[' -> depth++
                ch == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * Splits a JSON array content into individual event strings.
     * Handles nested objects and strings correctly.
*/
    private fun splitArray(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        for (ch in s) {
            when {
                escape -> {
                    current.append(ch)
                    escape = false
                }
                ch == '\\' && inString -> {
                    current.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                ch == '{' && !inString -> { depth++; current.append(ch) }
                ch == '}' && !inString -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 && !inString -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val trailing = current.toString().trimEnd()
        if (trailing.isNotEmpty() && trailing != "]") result.add(current.toString())
        return result
    }

}
