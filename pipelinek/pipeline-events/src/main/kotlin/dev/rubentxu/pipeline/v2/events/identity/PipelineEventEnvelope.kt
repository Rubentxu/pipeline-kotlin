package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.InvalidResourceRefException
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs

import dev.rubentxu.pipeline.v2.events.DomainEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Observable-projection envelope wrapping a [DomainEvent] with typed cross-system
 * identity (EVT-1).
 *
 * Laws:
 * - identity projection only: adding envelopes MUST NOT change journal schema,
 *   fingerprints, replay, outcomes, or the event's own semantics;
 * - `sequence` appears as PROJECTION of the store-assigned value; the store
 *   remains the sequence authority (EVT-0 §2);
 * - explicit version ([VERSION]); decoding an unknown major version fails typed;
 * - no `Map<String, Any>`: identity is closed, typed data.
 */
@Serializable(with = PipelineEventEnvelopeSerializer::class)
data class PipelineEventEnvelope(
    val version: Int,
    val eventRef: EventRef,
    val kind: String,
    val occurredAt: Instant,
    val sequence: Long,
    val subject: ResourceRef,
    val causation: EventRef? = null,
    val correlation: EventRef? = null,
    /**
     * LFC-2E2-prep / ADR-0092 / C8: audit projection of the provider
     * metadata for the Step that produced the event. `null` for Steps
     * registered via the legacy `register(StepDefinition)` overload
     * (C10 backwards-compat) and for events not emitted by a Step
     * (run lifecycle, compile lifecycle, file-IO events, etc.).
     */
    val provenance: ProviderProvenance? = null,
) {
    companion object {
        /**
         * Envelope format version. Major bump = breaking codec change.
         * Version is NOT bumped for the additive `provenance` field:
         * a V1 wire form without `provenance` decodes to
         * `provenance = null`. An encoder that does not know about
         * `provenance` continues to produce a V1 form that the new
         * decoder accepts (the field is optional).
         */
        const val VERSION: Int = 1
    }
}

/**
 * Typed failure when decoding an envelope with an unsupported format version.
 */
class UnsupportedEnvelopeVersionException(val found: Int) :
    IllegalArgumentException("Unsupported PipelineEventEnvelope version: $found (supported: ${PipelineEventEnvelope.VERSION})")

/**
 * Hand-written serializer keeping the wire form closed and versioned:
 * `{version, eventRef:{source:{kind,segments},id}, kind, occurredAt, sequence,
 *   subject:{...}, causation?, correlation?}`.
 */
object PipelineEventEnvelopeSerializer : KSerializer<PipelineEventEnvelope> {

    private val refSerializer = ResourceRefSerializer

    @Serializable
    private data class Wire(
        val version: Int,
        @Serializable(with = ResourceRefSerializer::class) val eventRefSource: ResourceRef,
        val eventRefId: String,
        val kind: String,
        val occurredAt: String,
        val sequence: Long,
        @Serializable(with = ResourceRefSerializer::class) val subject: ResourceRef,
        @Serializable(with = EventRefSerializer::class) val causation: EventRef? = null,
        @Serializable(with = EventRefSerializer::class) val correlation: EventRef? = null,
        val provenance: ProviderProvenance? = null,
    )

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PipelineEventEnvelope) {
        val w = Wire(
            version = value.version,
            eventRefSource = value.eventRef.source,
            eventRefId = value.eventRef.id.value,
            kind = value.kind,
            occurredAt = value.occurredAt.toString(),
            sequence = value.sequence,
            subject = value.subject,
            // BLOCK 2: these two were declared on the wire and never written. The consumer build
            // in `examples/fabric-contract-consumer` found it by round-tripping an envelope through
            // this serializer and getting `causation = null, correlation = null` back, while this
            // file's own KDoc advertised both in the wire form. A field that is accepted, declared
            // and documented, and then silently dropped on encode, is the "dead semantic parameter"
            // the semantic constitution forbids: the envelope looked like it carried causal context
            // and carried none.
            causation = value.causation,
            correlation = value.correlation,
            provenance = value.provenance,
        )
        encoder.encodeSerializableValue(Wire.serializer(), w)
    }

    override fun deserialize(decoder: Decoder): PipelineEventEnvelope {
        val w = decoder.decodeSerializableValue(Wire.serializer())
        if (w.version != PipelineEventEnvelope.VERSION) {
            throw UnsupportedEnvelopeVersionException(w.version)
        }
        return PipelineEventEnvelope(
            version = w.version,
            eventRef = EventRef(source = w.eventRefSource, id = EventId(w.eventRefId)),
            kind = w.kind,
            occurredAt = Instant.parse(w.occurredAt),
            sequence = w.sequence,
            subject = w.subject,
            // Read back for the same reason they are written: a field that survives neither
            // direction is not a field, it is a comment in a constructor.
            causation = w.causation,
            correlation = w.correlation,
            provenance = w.provenance,
        )
    }
}

/**
 * Closed serializer for ResourceRef: `{kind, segments:[...]}` — canonical text is
 * derived, never parsed back from free-form strings.
 */
object ResourceRefSerializer : KSerializer<ResourceRef> {
    @Serializable
    private data class Wire(val kind: String, val segments: List<String>)

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ResourceRef) {
        encoder.encodeSerializableValue(
            Wire.serializer(),
            Wire(value.kind.name, value.segments),
        )
    }

    override fun deserialize(decoder: Decoder): ResourceRef {
        val w = decoder.decodeSerializableValue(Wire.serializer())
        val kind = try {
            ResourceKind.valueOf(w.kind)
        } catch (e: IllegalArgumentException) {
            throw InvalidResourceRefException("Unknown ResourceKind '${w.kind}'")
        }
        return ResourceRef(kind, w.segments)
    }
}

/**
 * Closed serializer for [EventRef]: `{source:{kind, segments}, id}`.
 *
 * Added with BLOCK 2, when the envelope's `causation` and `correlation` were found to be declared on
 * the wire and dropped on encode. The shape is the one [PipelineEventEnvelope] already uses for its
 * own `eventRef`, so a consumer reading an envelope sees one ref encoding rather than two.
 */
object EventRefSerializer : KSerializer<EventRef> {
    @Serializable
    private data class Wire(
        @Serializable(with = ResourceRefSerializer::class) val source: ResourceRef,
        val id: String,
    )

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EventRef) {
        encoder.encodeSerializableValue(Wire.serializer(), Wire(source = value.source, id = value.id.value))
    }

    override fun deserialize(decoder: Decoder): EventRef {
        val w = decoder.decodeSerializableValue(Wire.serializer())
        return EventRef(source = w.source, id = EventId(w.id))
    }
}

/**
 * Minimal codec facade for envelope JSON round-trips (spec R3/R4).
 */
object EnvelopeCodec {
    val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(envelope: PipelineEventEnvelope): String =
        json.encodeToString(PipelineEventEnvelopeSerializer, envelope)

    fun encodeAll(envelopes: List<PipelineEventEnvelope>): String =
        json.encodeToString(ListSerializer(PipelineEventEnvelopeSerializer), envelopes)

    fun decode(payload: String): PipelineEventEnvelope =
        json.decodeFromString(PipelineEventEnvelopeSerializer, payload)

    fun decodeAll(payload: String): List<PipelineEventEnvelope> =
        json.decodeFromString(ListSerializer(PipelineEventEnvelopeSerializer), payload)
}

/**
 * Characterization-only CloudEvents mapping (spec R5). Deliberately NOT a
 * transport or SDK adapter: no HTTP, no bindings, no SDK types. Values are
 * plain strings so a future adapter can freeze its wire form separately.
 */
data class CloudEventsCharacterization(
    val id: String,
    val source: String,
    val type: String,
    val subject: String,
)

fun PipelineEventEnvelope.toCloudEventsCharacterization(): CloudEventsCharacterization =
    CloudEventsCharacterization(
        id = eventRef.id.value,
        source = eventRef.source.canonicalText(),
        type = "$kind.v$version",
        subject = subject.canonicalText(),
    )
