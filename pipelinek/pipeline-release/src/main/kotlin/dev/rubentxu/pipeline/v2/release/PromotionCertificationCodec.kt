package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

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
 *
 * ## Two shapes, and the shape the document declares
 *
 * The reason a part did not apply arrived with a shape of its own, and this
 * build reads BOTH: the shape that carries the reason and the shape that
 * came before. The shape a document declares decides the shape it is read
 * with, so each one is held to its OWN fields: a part of the shape that came
 * before may not carry the reason, because that shape does not define it.
 *
 * That is what keeps the promotion of today working: the certifications that
 * were certified before the reason arrived are documents of the shape that
 * came before, and they are read exactly as they were read before. The order
 * of the two repositories stops mattering: the harness may certify in the
 * new shape whenever it is ready, and this build already reads it.
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
     * The name of the field that says WHY a part did not apply.
     */
    private const val REASON_FIELD: String = "reason"

    /**
     * The names a part carries in the shape that says why.
     *
     * They are read from the serializer itself, so the shape and the check
     * cannot disagree: a field the model has is a field the shape accepts.
     */
    private val CURRENT_PART_FIELDS: Set<String> =
        serializer<CertifiedPart>().descriptor.elementNames.toSet()

    /**
     * The names a part carries in the shape that came before: the same
     * ones, without the reason.
     *
     * The difference between the two shapes IS the reason, so the difference
     * is derived from the shape instead of being written out: a second list
     * of names would be a second answer to the question "what does a part
     * carry", and two answers are free to disagree.
     */
    private val LEGACY_PART_FIELDS: Set<String> = CURRENT_PART_FIELDS - REASON_FIELD

    /**
     * The fields a part carries, per shape.
     */
    private val PART_FIELDS_BY_SCHEMA: Map<String, Set<String>> = mapOf(
        PromotionCertification.SCHEMA_VERSION to CURRENT_PART_FIELDS,
        PromotionCertification.LEGACY_SCHEMA_VERSION to LEGACY_PART_FIELDS,
    )

    /**
     * The certification as JSON. The same certification always yields the same
     * bytes.
     */
    fun encode(certification: PromotionCertification): String =
        writer.encodeToString(serializer<PromotionCertification>(), certification) + "\n"

    /**
     * Read a certification from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing something the certification
     * requires, that carries an unknown field inside a part, or that declares
     * another schema version is refused with the reason.
     *
     * The shape the document declares decides the fields its parts may
     * carry: the reason is a field of the shape that says why, and a part of
     * the shape that came before is refused by name when it carries one.
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
        val known = serializer<PromotionCertification>().descriptor.elementNames.toSet()
        val unknown = fields.keys - known
        if (unknown.isNotEmpty()) {
            return PromotionCertificationDecode.Rejected(
                CertificationRejection.UnknownField(unknown.sorted()),
            )
        }

        val schema = schemaOf(fields)
            ?: return PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document declares no schema version, so it names no shape this build " +
                        "can read. A certification says which shape it follows, because the " +
                        "shape is what says which fields it may carry.",
                ),
            )
        val partFields = PART_FIELDS_BY_SCHEMA[schema]
            ?: return PromotionCertificationDecode.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document declares schema '$schema' and this build reads " +
                        "'${PromotionCertification.SCHEMA_VERSION}' and " +
                        "'${PromotionCertification.LEGACY_SCHEMA_VERSION}'. A certification " +
                        "written for another schema is not a certification this build can act on.",
                ),
            )
        val unknownInParts = unknownPartFields(fields, partFields)
        if (unknownInParts.isNotEmpty()) {
            return PromotionCertificationDecode.Rejected(
                CertificationRejection.UnknownField(unknownInParts),
            )
        }

        return readCertification(element)
    }

    /**
     * The shape the document declares, or null when it declares none.
     */
    private fun schemaOf(fields: JsonObject): String? {
        val declared = fields["schema_version"] ?: return null
        val primitive = declared as? JsonPrimitive ?: return null
        return primitive.content
    }

    /**
     * The fields a part of the document carries that its shape does not
     * define.
     *
     * Every part is read, and the names are collected: a document that
     * carries an unknown field in one of its parts is refused by the name of
     * that field, whichever part it was in.
     */
    private fun unknownPartFields(fields: JsonObject, allowed: Set<String>): List<String> {
        val parts = fields["parts"] as? JsonArray ?: return emptyList()
        val unknown = sortedSetOf<String>()
        parts.forEach { element ->
            val part = element as? JsonObject ?: return@forEach
            part.keys.forEach { name ->
                if (name !in allowed) unknown += name
            }
        }
        return unknown.toList()
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
                reader.decodeFromJsonElement(serializer<PromotionCertification>(), element),
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
     * Whether this build reads a certification of [schema].
     *
     * The two shapes this build reads are the two it knows, and this is
     * where that question is answered: the codec that reads the document and
     * the receipt that binds it ask the same thing, and two answers could
     * disagree about which documents this build acts on.
     */
    internal fun readsSchemaVersion(schema: String): Boolean =
        PART_FIELDS_BY_SCHEMA.containsKey(schema)
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
