package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What reading an event document may yield.
 *
 * The refusal vocabulary is the one the request codec publishes
 * ([CertificationRequestRejection]), and it is the SAME one on purpose: the
 * documents that cross the boundary between repositories are refused in the
 * same words, so a receiver that has to log a refusal has one vocabulary to
 * learn and not two that could drift apart.
 */
sealed interface CertificationEventDecode {

    /** The document is an event this build understands. */
    data class Accepted(val event: CertificationRequestedEvent) : CertificationEventDecode

    /** The document is not an event this build can act on. */
    data class Rejected(val rejection: CertificationRequestRejection) : CertificationEventDecode
}

/**
 * The serialisation of the certification request event, and the reading of a
 * document this product did not write.
 *
 * The document is closed in the same way the request is: a field the schema
 * does not define, a schema version this build does not read, a protocol
 * version this build does not speak, and a name this build does not publish
 * are all refusals, and each refusal says which one it is.
 */
object CertificationRequestedEventCodec {

    private val writer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private val reader = Json {
        ignoreUnknownKeys = false
    }

    /**
     * The event as JSON. The same event always yields the same bytes.
     */
    fun encode(event: CertificationRequestedEvent): String =
        writer.encodeToString(CertificationRequestedEvent.serializer(), event) + "\n"

    /**
     * Read an event from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing something the event requires, that
     * carries an unknown field inside the request, that declares another
     * schema version, that speaks another protocol version, or that carries
     * another name is refused with the reason.
     */
    fun decode(text: String): CertificationEventDecode {
        val element = try {
            reader.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return CertificationEventDecode.Rejected(
                CertificationRequestRejection.MalformedDocument(
                    e.message ?: "the document is not valid JSON.",
                ),
            )
        }
        val fields = element as? JsonObject
            ?: return CertificationEventDecode.Rejected(
                CertificationRequestRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no event.",
                ),
            )

        // The names the schema defines, read from the serializer itself, so
        // the check and the document cannot disagree.
        val known = CertificationRequestedEvent.serializer().descriptor.elementNames.toSet()
        val unknown = fields.keys - known
        if (unknown.isNotEmpty()) {
            return CertificationEventDecode.Rejected(
                CertificationRequestRejection.UnknownField(unknown.sorted()),
            )
        }

        val decoded = readEvent(element)
        if (decoded is CertificationEventDecode.Accepted) {
            val refusal = refusalOf(decoded.event)
            if (refusal != null) {
                return CertificationEventDecode.Rejected(refusal)
            }
        }
        return decoded
    }

    /**
     * The value of the document, or the reason it is not one.
     *
     * The read is an EXPRESSION and not a statement with a `return` in each
     * branch, because the analysis that guards this module cannot resolve the
     * generated serializer: it reads a body that cannot complete normally as
     * a body that always throws, and it then declares every line after the
     * `try` unreachable. Nothing follows the `try` here, so there is nothing
     * for it to declare.
     */
    private fun readEvent(element: JsonElement): CertificationEventDecode =
        try {
            CertificationEventDecode.Accepted(
                reader.decodeFromJsonElement(CertificationRequestedEvent.serializer(), element),
            )
        } catch (e: SerializationException) {
            CertificationEventDecode.Rejected(
                CertificationRequestRejection.MalformedDocument(
                    e.message ?: "the document does not describe a certification request event.",
                ),
            )
        } catch (e: IllegalArgumentException) {
            CertificationEventDecode.Rejected(
                CertificationRequestRejection.MalformedDocument(
                    e.message ?: "the document does not describe a certification request event.",
                ),
            )
        }

    /**
     * The reason this event is not one this build can act on, or null when it
     * is one.
     */
    private fun refusalOf(event: CertificationRequestedEvent): CertificationRequestRejection? {
        if (event.schemaVersion != CertificationRequestedEvent.SCHEMA_VERSION) {
            return CertificationRequestRejection.MalformedDocument(
                "the document declares schema '${event.schemaVersion}' and this build reads " +
                    "'${CertificationRequestedEvent.SCHEMA_VERSION}'. An event written for " +
                    "another schema is not an event this build can act on.",
            )
        }
        if (event.protocolVersion != CertificationRequestedEvent.PROTOCOL_VERSION) {
            return CertificationRequestRejection.MalformedDocument(
                "the event speaks protocol '${event.protocolVersion}' and this build speaks " +
                    "'${CertificationRequestedEvent.PROTOCOL_VERSION}'. A receiver and a " +
                    "producer that speak different protocols do not have a contract.",
            )
        }
        if (event.name != CertificationRequestedEvent.NAME) {
            return CertificationRequestRejection.MalformedDocument(
                "the event carries the name '${event.name}' and this build publishes " +
                    "'${CertificationRequestedEvent.NAME}'. An event the receiver does not " +
                    "listen for is not an event.",
            )
        }
        return null
    }
}

/** Operator-facing diagnostic for a refused event. */
fun CertificationEventDecode.render(): String = when (this) {
    is CertificationEventDecode.Accepted ->
        "certification requested event accepted: ${event.candidateId.canonical} published as " +
            "${event.release.tag}"

    is CertificationEventDecode.Rejected -> when (val r = rejection) {
        is CertificationRequestRejection.UnknownField ->
            "certification requested event REJECTED: unknown field(s) ${r.fields.joinToString()}"

        is CertificationRequestRejection.MalformedDocument ->
            "certification requested event REJECTED: ${r.detail}"
    }
}
