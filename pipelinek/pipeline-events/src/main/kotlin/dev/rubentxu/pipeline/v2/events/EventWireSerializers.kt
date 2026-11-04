package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The encodings that the durable event wire needs and that a type cannot carry on its own.
 *
 * ## Why these exist
 *
 * An event is a FLAT JSON object: the identity fields and the payload fields live at the same
 * level, side by side. That shape is the contract every historical row carries, so the encoders
 * below reproduce it exactly rather than describing a tidier shape of their own.
 *
 * Three of them exist because the wire says something the Kotlin type cannot:
 *
 *  - [OptionalStringWireSerializer] travels an absent optional string as the EMPTY STRING. The
 *    alternative — a JSON `null` — would be a different document, and a reader that treated
 *    `null` and `""` alike would be reading a contract the writer never promised.
 *  - [AbsentIntWireSerializer] travels an absent optional index as `-1`, so a reader can tell
 *    "no stage" from "stage 0": a failure in the first stage is a different fact from a failure
 *    that does not name its stage.
 *  - [InstantWireSerializer] and [PathWireSerializer] travel the two domain types that have no
 *    JSON form of their own.
 *
 * ## What they refuse
 *
 * An [Instant] that is not an instant is refused rather than read as the epoch, and a token
 * outside a vocabulary is refused rather than mapped to a default. A decoder that invents a value
 * it could not read produces a record that looks intact and says something that never happened.
 */
public object InstantWireSerializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (failure: DateTimeParseException) {
            throw SerializationException("the instant '$text' is not an ISO-8601 instant", failure)
        }
    }
}

/**
 * The wire form of a filesystem path: the path's own text.
 *
 * The text is what every historical row carries, and it is what a reader can use to name the
 * same place the writer named. A path is not a file handle and it is not resolved against the
 * working directory: it travels as the caller stated it.
 */
public object PathWireSerializer : KSerializer<Path> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.Path", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Path = Paths.get(decoder.decodeString())
}

/**
 * An optional string that travels as the EMPTY STRING when it is not declared.
 *
 * The empty string is the encoding every historical record already uses, so the wire and its
 * history agree. The pair has ONE implementation on each side: a writer that forgets the
 * convention and a reader that forgets it are two halves of the same mistake, and the record
 * that comes back is a record that lost its payload without saying so.
 */
public object OptionalStringWireSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.OptionalString", PrimitiveKind.STRING)
            .nullable

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value.orEmpty())
    }

    override fun deserialize(decoder: Decoder): String? = decoder.decodeString().takeIf { it.isNotEmpty() }
}

/**
 * An optional index that travels as `-1` when it is not declared.
 *
 * `-1` is not a stage and not a step: it is the spelling of "this event does not name one". A
 * reader that mapped it to `0` would say that a failure happened in the first stage.
 */
public object AbsentIntWireSerializer : KSerializer<Int?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.AbsentInt", PrimitiveKind.INT).nullable

    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeInt(value ?: -1)
    }

    override fun deserialize(decoder: Decoder): Int? = decoder.decodeInt().takeIf { it != -1 }
}

/**
 * A build result that is not declared, which travels as the EMPTY STRING.
 *
 * The result is a vocabulary, not a free text: the tokens it accepts are the ones the product
 * has always written. A token outside that vocabulary is refused instead of being read as
 * `UNSTABLE`, because a build that says something the product never said is a build nobody can
 * act on.
 */
public object OptionalBuildResultWireSerializer : KSerializer<CatchErrorBuildResult?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "dev.rubentxu.pipeline.v2.events.OptionalBuildResult",
            PrimitiveKind.STRING,
        ).nullable

    override fun serialize(encoder: Encoder, value: CatchErrorBuildResult?) {
        encoder.encodeString(value?.wireToken.orEmpty())
    }

    override fun deserialize(decoder: Decoder): CatchErrorBuildResult? {
        val token = decoder.decodeString()
        if (token.isEmpty()) return null
        return CatchErrorBuildResult.parse(token) ?: throw SerializationException(
            "the build result '$token' is outside the vocabulary " +
                "${CatchErrorBuildResult.supportedTokens}; refusing to invent a result",
        )
    }
}

/**
 * The origin of a compilation, as the wire spells it.
 *
 * A word this build does not know is read as [ScriptOrigin.UNKNOWN], and that is the whole rule:
 * the origin vocabulary declares "not recorded" as one of its OWN values, and a record whose
 * origin nobody can interpret has not been compiled here, reused from a store, or anything else.
 * The reader therefore does not have to choose between two facts it cannot tell apart.
 *
 * The rule lives in [ScriptOrigin.parseOrUnknown] so that every reader of a stored origin applies
 * the same one; this serializer only adapts it to the wire.
 */
public object ScriptOriginWireSerializer : KSerializer<ScriptOrigin> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.ScriptOrigin", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ScriptOrigin) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ScriptOrigin =
        ScriptOrigin.parseOrUnknown(decoder.decodeString())
}

/**
 * The classification of a failure, as the wire spells it.
 *
 * A word this build does not know is read as [FailureKind.UNKNOWN]. The durable reader and the
 * configuration reader want OPPOSITE things here, and the difference is the point:
 *
 *  - a pipeline that spells a failure kind this runtime does not know is a configuration error,
 *    and the DSL bridge refuses it ([FailureKind.parse] returns null and the caller aborts);
 *  - a durable ROW that spells one is history this runtime cannot classify, and refusing the
 *    whole row would lose the run it belongs to — the row is kept, and the word it does not
 *    recognise becomes the vocabulary's own "unknown".
 *
 * The vocabulary decides that for itself, which is why the sentinel is not invented here.
 */
public object FailureKindWireSerializer : KSerializer<FailureKind> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.rubentxu.pipeline.v2.events.FailureKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FailureKind) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): FailureKind =
        FailureKind.parse(decoder.decodeString()) ?: FailureKind.UNKNOWN
}
