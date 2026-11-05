package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What reading a promotion certification may yield.
 *
 * The refusal vocabulary is the one the request codec publishes
 * ([CertificationRejection]), and it is the SAME one on purpose: the
 * documents that cross the boundary between repositories are refused in the
 * same words, so a receiver that has to log a refusal has one vocabulary to
 * learn and not two that could drift apart.
 */
sealed interface PromotionCertificationDecode {

    /** The document is a certification this build understands. */
    data class Accepted(val certification: PromotionCertification) : PromotionCertificationDecode

    /** The document is not a certification this build can act on. */
    data class Rejected(val rejection: CertificationRejection) : PromotionCertificationDecode
}

/**
 * The serialisation of a promotion certification, and the reading of a
 * document this product did not write.
 *
 * The document is closed in the same way the request and the receipt are: a
 * field the schema does not define, a schema version this build does not
 * read, a part this build does not know and a status this build does not
 * know are all refusals, and each refusal says which one it is.
 *
 * Two properties hold, and both are properties of this codec rather than of
 * convention:
 *
 * 1. **Determinism** — the same certification always produces byte-identical
 *    JSON, so a certification that is stated twice is the same file.
 * 2. **Closed shape** — a document that carries a field the schema does not
 *    define, that leaves out a field the schema requires, or that declares a
 *    schema version this version does not know, is refused. Reading is not a
 *    negotiation: the reader either understands the whole document or refuses
 *    it.
 */
object PromotionCertificationCodec {

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
     * The certification as JSON. The same certification always yields the same
     * bytes.
     */
    fun encode(certification: PromotionCertification): String =
        writer.encodeToString(PromotionCertification.serializer(), certification) + "\n"

    /**
     * Read a certification from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing something the certification
     * requires, that carries an unknown field inside a part, or that declares
     * another schema version is refused with the reason.
     */
    fun decode(text: String): PromotionCertificationDecode {
        val element = try {
            reader.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document is not valid JSON.",
                ),
            )
        }
        val fields = element as? JsonObject
            ?: return PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no certification.",
                ),
            )

        // The names the schema defines, read from the serializer itself, so
        // the check and the document cannot disagree.
        val known = PromotionCertification.serializer().descriptor.elementNames.toSet()
        val unknown = fields.keys - known
        if (unknown.isNotEmpty()) {
            return PromotionCertificationDecode.Rejected(
                CertificationRejection.UnknownField(unknown.sorted()),
            )
        }

        val decoded = readCertification(element)
        if (decoded is PromotionCertificationDecode.Accepted) {
            val refusal = refusalOf(decoded.certification)
            if (refusal != null) {
                return PromotionCertificationDecode.Rejected(refusal)
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
    private fun readCertification(element: JsonElement): PromotionCertificationDecode =
        try {
            PromotionCertificationDecode.Accepted(
                reader.decodeFromJsonElement(PromotionCertification.serializer(), element),
            )
        } catch (e: SerializationException) {
            PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document does not describe a promotion certification.",
                ),
            )
        } catch (e: IllegalArgumentException) {
            PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    e.message ?: "the document does not describe a promotion certification.",
                ),
            )
        }

    /**
     * The reason this certification is not one this build can act on, or null
     * when it is one.
     */
    private fun refusalOf(certification: PromotionCertification): CertificationRejection? {
        if (certification.schemaVersion != PromotionCertification.SCHEMA_VERSION) {
            return CertificationRejection.MalformedDocument(
                "the document declares schema '${certification.schemaVersion}' and this build " +
                    "reads '${PromotionCertification.SCHEMA_VERSION}'. A certification written " +
                    "for another schema is not a certification this build can act on.",
            )
        }
        return null
    }
}

/** Operator-facing diagnostic for a certification that was read. */
fun PromotionCertificationDecode.render(): String = when (this) {
    is PromotionCertificationDecode.Accepted ->
        "promotion certification accepted: ${certification.candidateId.canonical}, " +
            "${certification.parts.size} part(s)"

    is PromotionCertificationDecode.Rejected -> when (val r = rejection) {
        is CertificationRejection.UnknownField ->
            "promotion certification REJECTED: unknown field(s) ${r.fields.joinToString()}"

        is CertificationRejection.MalformedDocument ->
            "promotion certification REJECTED: ${r.detail}"
    }
}
