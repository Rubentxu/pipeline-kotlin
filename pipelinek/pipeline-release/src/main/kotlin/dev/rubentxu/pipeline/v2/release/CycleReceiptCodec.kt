package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What reading a cycle receipt may yield, or why the document is not one.
 *
 * The refusal vocabulary is the SHARED one ([CertificationRejection]), for the
 * same reason the other documents that cross the boundary use it: a reader
 * that has to log a refusal has one vocabulary to learn and not one per
 * document that could drift apart.
 */
sealed interface CycleReceiptDecodeResult {

    /** The document is a cycle receipt this build understands. */
    data class Accepted(val receipt: CycleReceipt) : CycleReceiptDecodeResult

    /** The document is not a cycle receipt this build can act on. */
    data class Rejected(val rejection: CertificationRejection) : CycleReceiptDecodeResult
}

/**
 * The serialisation of the cycle receipt, and the reading of a document this
 * product did not write.
 *
 * Two properties hold, and both are properties of this codec rather than of
 * convention:
 *
 * 1. **Determinism** — the same receipt always produces byte-identical JSON,
 *    so a receipt that is stated twice is the same file.
 * 2. **Closed shape** — a document that carries a field the schema does not
 *    define, that declares a schema version this build does not know, or that
 *    carries a nested document written for another schema, is refused. Reading
 *    is not a negotiation: the reader either understands the whole document
 *    or refuses it, and the refusal names what it did not understand.
 *
 * The nested documents are checked against THEIR OWN schema versions, with
 * the same constants their own readers use. A receipt that carried a request
 * written for another schema would be a receipt about something this build
 * cannot interpret, and it would read as a receipt about something this build
 * CAN interpret.
 */
object CycleReceiptCodec {

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
     * The receipt as JSON. The same receipt always yields the same bytes.
     */
    fun encode(receipt: CycleReceipt): String =
        writer.encodeToString(CycleReceipt.serializer(), receipt) + "\n"

    /**
     * Read a cycle receipt from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing a field the receipt requires, that
     * declares another schema version, or that carries a nested document of
     * another schema is refused with the reason.
     */
    fun decode(text: String): CycleReceiptDecodeResult = try {
        val element = reader.parseToJsonElement(text)
        val fields = element as? JsonObject
        if (fields == null) {
            CycleReceiptDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no receipt.",
                ),
            )
        } else {
            // The names the schema defines, read from the serializer itself, so
            // the check and the document cannot disagree.
            val known = CycleReceipt.serializer().descriptor.elementNames.toSet()
            val unknown = fields.keys - known
            if (unknown.isNotEmpty()) {
                CycleReceiptDecodeResult.Rejected(
                    CertificationRejection.UnknownField(unknown.sorted()),
                )
            } else {
                readReceipt(fields)
            }
        }
    } catch (e: SerializationException) {
        CycleReceiptDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        CycleReceiptDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    }

    /**
     * Read the receipt out of a document whose shape is already known to be
     * this build's.
     */
    private fun readReceipt(fields: JsonObject): CycleReceiptDecodeResult = try {
        val receipt = reader.decodeFromJsonElement(CycleReceipt.serializer(), fields)
        val nested = nestedRefusal(receipt)
        when {
            nested != null -> CycleReceiptDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(nested),
            )

            receipt.schemaVersion != CycleReceipt.SCHEMA_VERSION -> CycleReceiptDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document declares schema '${receipt.schemaVersion}' and this build reads " +
                        "'${CycleReceipt.SCHEMA_VERSION}'. A receipt written for another schema is " +
                        "not a receipt this build can act on.",
                ),
            )

            else -> CycleReceiptDecodeResult.Accepted(receipt)
        }
    } catch (e: SerializationException) {
        CycleReceiptDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document does not describe a cycle receipt.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        // A value the receipt's own invariants refuse. The message is the
        // invariant's own, and it is the useful part.
        CycleReceiptDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document does not describe a cycle receipt.",
            ),
        )
    }

    /**
     * The reason a nested document of [receipt] was written for another
     * schema, or null when they all are the ones this build reads.
     */
    private fun nestedRefusal(receipt: CycleReceipt): String? {
        val candidate = receipt.candidate
        if (candidate.schemaVersion != CandidateHandoff.SCHEMA_VERSION) {
            return "the candidate's own document declares schema '${candidate.schemaVersion}' and " +
                "this build reads '${CandidateHandoff.SCHEMA_VERSION}'."
        }
        val request = receipt.request
        if (request.schemaVersion != CertificationRequest.SCHEMA_VERSION) {
            return "the certification request declares schema '${request.schemaVersion}' and this " +
                "build reads '${CertificationRequest.SCHEMA_VERSION}'."
        }
        val certification = receipt.certification
        if (certification.schemaVersion != CertificationReceipt.SCHEMA_VERSION) {
            return "the certification receipt declares schema '${certification.schemaVersion}' and " +
                "this build reads '${CertificationReceipt.SCHEMA_VERSION}'."
        }
        val promotion = receipt.promotion
        if (promotion != null && promotion.schemaVersion != PromotionCertification.SCHEMA_VERSION) {
            return "the promotion certification declares schema '${promotion.schemaVersion}' and " +
                "this build reads '${PromotionCertification.SCHEMA_VERSION}'."
        }
        return null
    }
}

/**
 * Operator-facing diagnostic for a receipt that was read.
 */
fun CycleReceiptDecodeResult.render(): String = when (this) {
    is CycleReceiptDecodeResult.Accepted ->
        "cycle receipt accepted: ${receipt.candidateId.canonical}"

    is CycleReceiptDecodeResult.Rejected ->
        "cycle receipt REJECTED: ${rejection.describe()}"
}
