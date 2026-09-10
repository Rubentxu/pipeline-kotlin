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
) {
    companion object {
        /**
         * Envelope format version. Major bump = breaking codec change.
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
        val causationSource: String? = null,
        val causationId: String? = null,
        val correlationSource: String? = null,
        val correlationId: String? = null,
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
