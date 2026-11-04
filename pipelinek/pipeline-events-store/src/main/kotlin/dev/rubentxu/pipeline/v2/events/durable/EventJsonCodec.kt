package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The codec of the durable event: ONE JSON object per event, in the wire format that every
 * historical row carries.
 *
 * ## What it reads and writes
 *
 * The wire is FLAT. The identity fields and the payload fields live at the same level, side by
 * side, and the order they appear in is the order this codec emits them:
 *
 * ```
 * {"eventId":"…","runId":"…","sequence":7,"kind":"StepFinished","occurredAt":"…", …payload}
 * ```
 *
 * ## Why there is no branch per kind
 *
 * The codec used to carry one branch per event type on each side: a writer that spelled the
 * fields of every type, and a reader that read them back. Every new event was nine edits in
 * seven files, and the two branches were two statements about the same shape that nothing
 * checked against each other.
 *
 * Now the shape lives ONCE, in the event class itself, and the codec asks the serialization
 * runtime for it. The kind is the discriminator, and the payload is the event's own properties.
 * A new event is a new class and nothing else.
 *
 * ## What it refuses
 *
 * A field that is not there, or that is there with the wrong shape, makes the event unreadable.
 * The codec does not read a missing stage as stage zero, a missing outcome as "unknown" and a
 * missing boolean as false: a durable record that reads back as something nobody wrote is worse
 * than a durable record that is refused, because the refusal is visible and the invention is not.
 *
 * ## What it keeps
 *
 * A kind this binary does not know is version skew and is reported as such, apart from a row
 * this binary knows and cannot read. The two are different facts and they stay different.
 */
internal object EventJsonCodec {

    /**
     * The JSON reader and writer, configured for the durable wire.
     *
     * `ignoreUnknownKeys` is TRUE and the reason is compatibility, not laxity: a row written by
     * a LATER version carries fields this binary has never heard of, and refusing the whole row
     * would make a downgrade lose the history it is supposed to keep. A field nobody asked about
     * is not read; it is not invented either.
     */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** The identity fields, in the order the wire carries them. */
    private const val EVENT_ID = "eventId"
    private const val RUN_ID = "runId"
    private const val SEQUENCE = "sequence"
    private const val KIND = "kind"
    private const val OCCURRED_AT = "occurredAt"

    /** The identity fields, and the discriminator, which is not one of the event's properties. */
    private val IDENTITY_FIELDS = setOf(EVENT_ID, RUN_ID, SEQUENCE, KIND, OCCURRED_AT)

    /**
     * The row this event travels as.
     *
     * The kind that is written is the one the DOMAIN declares, so no byte of the row changes.
     * The certification comes before the row is built: a disagreement between the domain's own
     * kind literal and the discriminator its serializer produced is a defect in the domain, and
     * nothing of it reaches the wire.
     */
    fun encodeEvent(event: DomainEvent): String {
        val certified = EventKindMatrix.certify(EventKindMatrix.of(event), event.kind)
        val encoded = json.encodeToJsonElement(DomainEvent.serializer(), event).jsonObject
        val discriminator = encoded[KIND]?.jsonPrimitive?.content
        check(discriminator == certified.wire) {
            "the serializer produced the discriminator \"$discriminator\" for a type that " +
                "declares the kind \"${certified.wire}\""
        }
        val ordered = buildJsonObject {
            put(EVENT_ID, encoded.getValue(EVENT_ID))
            put(RUN_ID, encoded.getValue(RUN_ID))
            put(SEQUENCE, encoded.getValue(SEQUENCE))
            put(KIND, encoded.getValue(KIND))
            put(OCCURRED_AT, encoded.getValue(OCCURRED_AT))
            encoded.forEach { (name, value) ->
                // A field whose value is absent travels by not being there, and the fields that
                // travel by not being there are the ones the wire spells that way.
                if (name !in IDENTITY_FIELDS && value !is JsonNull) put(name, value)
            }
        }
        return json.encodeToString(JsonObject.serializer(), ordered)
    }

    /**
     * Decodes ONE event object, or the cause it could not be decoded from.
     *
     * The identity is read here, by name and in the wire's order, so a row that is missing one
     * of them says WHICH one. The payload is decoded by the serialization runtime, which knows
     * the shape of the kind that was announced.
     */
    fun decode(text: String): EventDecodeOutcome {
        val fields = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(EVENT_ID, null))

        val eventId = fields.stringOrNull(EVENT_ID)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(EVENT_ID, null))
        val runId = fields.stringOrNull(RUN_ID)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(RUN_ID, null))
        val sequence = fields.longOrNull(SEQUENCE)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(SEQUENCE, null))
        val kindWire = fields.stringOrNull(KIND)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(KIND, null))
        val occurredAtText = fields.stringOrNull(OCCURRED_AT)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnreadableField(OCCURRED_AT, null))
        // An occurredAt that is present but unparseable is refused. Falling back to the time of
        // READING it stamped every replayed row with the time of the read, so an event from
        // months ago claimed to have happened during the replay, and two reads of the same row
        // produced two different events.
        val occurredAt = try {
            Instant.parse(occurredAtText)
        } catch (_: DateTimeParseException) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.UnreadableField(OCCURRED_AT, occurredAtText),
            )
        }
        // The kind is resolved to the vocabulary BEFORE the payload is decoded: a kind this
        // binary does not know is refused with its own reason, and it is never read as a
        // payload of some kind it does know.
        val kind = EventKindMatrix.fromWire(kindWire)
            ?: return EventDecodeOutcome.Failed(EventDecodeFailure.UnknownEventKind(kindWire))

        val event = try {
            json.decodeFromJsonElement(DomainEvent.serializer(), fields)
        } catch (missing: MissingFieldException) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.UnreadableField(missing.missingFields.firstOrNull() ?: kindWire, null),
            )
        } catch (failure: SerializationException) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.UnreadableField(fieldNamedBy(failure) ?: kindWire, failure.message),
            )
        }
        // The row was read as the kind it announced, and the event it produced declares its own
        // kind. When the two words differ, the row was not what it announced itself to be.
        if (event.kind != kindWire) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.KindMismatch(kindWire, event.kind),
            )
        }
        // The identity the codec read is the identity the event carries, and the two are read
        // from the same row: a row that says one thing about its run and another about its
        // sequence is a row that cannot be trusted about either.
        if (event.eventId != eventId || event.runId != runId || event.sequence != sequence ||
            event.occurredAt != occurredAt
        ) {
            return EventDecodeOutcome.Failed(
                EventDecodeFailure.UnreadableField(EVENT_ID, null),
            )
        }
        return EventDecodeOutcome.Decoded(event)
    }

    /** Does this binary have a decoder for [kind]? */
    fun knowsKind(kind: String): Boolean = EventKindMatrix.knows(kind)

    /**
     * The field a decoding failure names, when the failure names one.
     *
     * The serialization runtime reports the path it was reading when the value it found is not
     * the value it expected. The path is the only thing in the failure that says WHICH field
     * is wrong, and an operator who knows the field can act on it.
     */
    private fun fieldNamedBy(failure: SerializationException): String? =
        PATH_IN_MESSAGE.find(failure.message ?: "")?.groupValues?.getOrNull(1)

    private val PATH_IN_MESSAGE = Regex("""\$\.([A-Za-z][A-Za-z0-9]*)""")

    /** The value of [name], when it is there and it is a string. */
    private fun JsonObject.stringOrNull(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    /** The value of [name], when it is there and it is a whole number. */
    private fun JsonObject.longOrNull(name: String): Long? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return primitive.content.toLongOrNull()
    }
}

/**
 * Why ONE event object did not become a [DomainEvent], naming the field.
 *
 * The cause travels with the refusal instead of being recoverable from an echo of the very
 * bytes that were unreadable. [UnreadableField] is the cause; [UnknownEventKind] is kept
 * separate because an unknown kind is version skew and is reported through
 * `UndecodableReason.UnknownKind` by a caller that can act on it, while an unreadable field in
 * a known kind is corruption.
 */
internal sealed interface EventDecodeFailure {

    /** The cause, rendered for [dev.rubentxu.pipeline.v2.events.UndecodableReason.MalformedPayload]. */
    val description: String

    /** [field] held nothing, or held something this kind cannot read as the type it needs. */
    data class UnreadableField(val field: String, val value: String?) : EventDecodeFailure {
        override val description: String = buildString {
            append("field \"").append(field).append("\" is unreadable")
            if (value != null) append(": ").append(value.take(FIELD_VALUE_DETAIL_CHARS))
        }
    }

    /** The object names a [kind] this binary has no decoder for — forward-compat, not damage. */
    data class UnknownEventKind(val kind: String) : EventDecodeFailure {
        override val description: String = "no decoder for kind \"$kind\""
    }

    /**
     * The payload was read into an event whose own kind is not the kind that was announced.
     *
     * The matrix says which kind belongs to which type, and the decoder built a type. When the
     * two do not agree, the row was not what it announced itself to be: that is damage, not skew,
     * and it is refused instead of accepted under a kind nobody asked for.
     */
    data class KindMismatch(val announcedKind: String, val decodedKind: String) : EventDecodeFailure {
        override val description: String =
            "kind \"$announcedKind\" decoded into an event whose kind is \"$decodedKind\""
    }
}

/** How much of an offending VALUE a cause quotes. Bounded for the same reason as the payload. */
internal const val FIELD_VALUE_DETAIL_CHARS = 64

/**
 * What [EventJsonCodec.decode] produced: the event, or the cause it could not produce.
 *
 * Carries its own payload on each side so a caller cannot be tempted to read a null as
 * "nothing to report" — that is the exact confusion this type removes.
 */
internal sealed interface EventDecodeOutcome {

    data class Decoded(val event: DomainEvent) : EventDecodeOutcome

    data class Failed(val cause: EventDecodeFailure) : EventDecodeOutcome
}
