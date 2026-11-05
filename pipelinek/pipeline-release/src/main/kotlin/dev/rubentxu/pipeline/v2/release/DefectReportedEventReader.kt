package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What reading a defect report may yield.
 *
 * The refusal vocabulary is the SHARED one ([CertificationRejection]), for the
 * same reason the certification event uses it: the documents that cross the
 * boundary between repositories are refused in the same words, so a receiver
 * that has to log a refusal has one vocabulary to learn and not two that could
 * drift apart.
 */
sealed interface DefectReportedEventDecode {

    /** The document is a defect report this build understands. */
    data class Accepted(val event: DefectReportedEvent) : DefectReportedEventDecode

    /** The document is not a defect report this build can act on. */
    data class Rejected(val rejection: CertificationRejection) : DefectReportedEventDecode
}

/**
 * The reading of a defect report: a document this product did not write.
 *
 * The document is closed in the same way every document that crosses the
 * boundary is closed: a field the schema does not define, a schema version
 * this build does not read, a protocol version this build does not speak, and
 * a name this build does not publish are all refusals, and each refusal says
 * which one it is.
 *
 * Closing the document is what lets the emitter and the receiver agree on one
 * contract instead of on a superset that grows in both directions: an emitter
 * that adds a field learns, at the first reception, that the field is not
 * understood, and it learns it from a refusal that names the field.
 *
 * There is NO encoder here, and that is not an omission: this product reads
 * these reports and never writes them. The report is written by the harness,
 * which is the one that ran the scenarios and saw the failure, and an
 * encoder in this tree would be a way of saying that this product knows how
 * to claim a defect it did not observe.
 */
object DefectReportedEventReader {

    private val reader = Json {
        ignoreUnknownKeys = false
    }

    /**
     * Read an event from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing something the event requires, that
     * carries a value outside a vocabulary, that declares another schema
     * version, that speaks another protocol version, or that carries another
     * name is refused with the reason.
     */
    fun decode(text: String): DefectReportedEventDecode {
        val element = try {
            reader.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return DefectReportedEventDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document is not valid JSON.",
                ),
            )
        }
        val fields = element as? JsonObject
            ?: return DefectReportedEventDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no defect report.",
                ),
            )

        // The names the schema defines, read from the serializer itself, so
        // the check and the document cannot disagree.
        val known = DefectReportedEvent.serializer().descriptor.elementNames.toSet()
        val unknown = fields.keys - known
        if (unknown.isNotEmpty()) {
            return DefectReportedEventDecode.Rejected(
                CertificationRejection.UnknownField(unknown.sorted()),
            )
        }

        val decoded = readEvent(element)
        if (decoded is DefectReportedEventDecode.Accepted) {
            val refusal = refusalOf(decoded.event)
            if (refusal != null) {
                return DefectReportedEventDecode.Rejected(refusal)
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
    private fun readEvent(element: JsonElement): DefectReportedEventDecode =
        try {
            DefectReportedEventDecode.Accepted(
                reader.decodeFromJsonElement(DefectReportedEvent.serializer(), element),
            )
        } catch (e: SerializationException) {
            DefectReportedEventDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document does not describe a defect report.",
                ),
            )
        } catch (e: IllegalArgumentException) {
            DefectReportedEventDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document does not describe a defect report.",
                ),
            )
        }

    /**
     * The reason this event is not one this build can act on, or null when it
     * is one.
     */
    private fun refusalOf(event: DefectReportedEvent): CertificationRejection? {
        if (event.schemaVersion != DefectReportedEvent.SCHEMA_VERSION) {
            return CertificationRejection.MalformedDocument(
                "the document declares schema '${event.schemaVersion}' and this build reads " +
                    "'${DefectReportedEvent.SCHEMA_VERSION}'. A document written for another " +
                    "schema is not a document this build can act on.",
            )
        }
        if (event.protocolVersion != DefectReportedEvent.PROTOCOL_VERSION) {
            return CertificationRejection.MalformedDocument(
                "the document speaks protocol '${event.protocolVersion}' and this build speaks " +
                    "'${DefectReportedEvent.PROTOCOL_VERSION}'. A receiver and a producer that " +
                    "speak different protocols do not have a contract.",
            )
        }
        if (event.name != DefectReportedEvent.NAME) {
            return CertificationRejection.MalformedDocument(
                "the document carries the name '${event.name}' and this build listens to " +
                    "'${DefectReportedEvent.NAME}'. A document the receiver does not listen " +
                    "for is not a document.",
            )
        }
        return null
    }
}

/** Operator-facing diagnostic for a refused document. */
fun DefectReportedEventDecode.render(): String = when (this) {
    is DefectReportedEventDecode.Accepted ->
        "defect report accepted: ${event.defect.scenario} against ${event.candidateId.canonical}"

    is DefectReportedEventDecode.Rejected ->
        "defect report REJECTED: ${rejection.describe()}"
}
