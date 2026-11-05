package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The serialisation of a certification receipt, and the reading of a document
 * this product did not write.
 *
 * Two properties hold, and both are properties of this codec rather than of
 * convention:
 *
 * 1. **Determinism** — the same receipt always produces byte-identical JSON.
 *    The fixed key order of [CertificationReceipt] and the encoded defaults
 *    make that true, so a receipt that is stated twice is the same file.
 * 2. **Closed shape** — a document that carries a field the schema does not
 *    define, that leaves out a field the schema requires, or that declares a
 *    schema version this version does not know, is refused. Reading is not a
 *    negotiation: the reader either understands the whole document or refuses
 *    it, and the refusal names what it did not understand.
 *
 * The shape is closed at EVERY level. The explicit checks below cover the
 * fields of the document itself; the reader's own refusal of unknown keys
 * covers the fields nested inside a value, because a reader that swallows an
 * unknown field inside a value it accepted has not understood the document
 * either.
 *
 * The reading never throws for a document that is not a receipt: the caller
 * receives a reason it can record, because the caller is a program that
 * received bytes from somewhere else.
 */
object CertificationReceiptCodec {

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
    fun encode(receipt: CertificationReceipt): String =
        writer.encodeToString(CertificationReceipt.serializer(), receipt) + "\n"

    /**
     * Read a receipt from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that leaves out a field the schema requires is refused by name. A
     * document that does not parse, that states a candidate id that is not a
     * digest, or that declares another schema version is refused with the
     * reason. A document that is a receipt is returned as its value.
     */
    fun decode(text: String): CertificationReceiptDecodeResult = try {
        val element = reader.parseToJsonElement(text)
        val fields = element as? JsonObject
        if (fields == null) {
            CertificationReceiptDecodeResult.Rejected(
                CertificationReceiptRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no receipt.",
                ),
            )
        } else {
            // The names the schema defines, read from the serializer itself, so
            // the check and the document cannot disagree.
            val known = CertificationReceipt.serializer().descriptor.elementNames.toSet()
            val unknown = fields.keys - known
            val missing = known - fields.keys
            when {
                unknown.isNotEmpty() -> CertificationReceiptDecodeResult.Rejected(
                    CertificationReceiptRejection.UnknownField(unknown.sorted()),
                )

                missing.isNotEmpty() -> CertificationReceiptDecodeResult.Rejected(
                    CertificationReceiptRejection.MissingField(missing.sorted()),
                )

                else -> readReceipt(fields)
            }
        }
    } catch (e: SerializationException) {
        CertificationReceiptDecodeResult.Rejected(
            CertificationReceiptRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        CertificationReceiptDecodeResult.Rejected(
            CertificationReceiptRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    }

    /**
     * Read the receipt out of a document whose shape is already known to be
     * this build's.
     *
     * Two refusals live here and they differ in kind: a document that does not
     * parse into a receipt, and a document that parses into a receipt written
     * for another schema. The second is not a malformed document -- it is a
     * well-formed document this build must not act on, and it says which
     * schema it declares.
     *
     * Every branch produces a value instead of jumping out, so the refusal is
     * the result and not a side effect of the control flow.
     */
    private fun readReceipt(fields: JsonObject): CertificationReceiptDecodeResult = try {
        val receipt = reader.decodeFromJsonElement(CertificationReceipt.serializer(), fields)
        if (receipt.schemaVersion != CertificationReceipt.SCHEMA_VERSION) {
            CertificationReceiptDecodeResult.Rejected(
                CertificationReceiptRejection.MalformedDocument(
                    "the document declares schema '${receipt.schemaVersion}' and this build " +
                        "reads '${CertificationReceipt.SCHEMA_VERSION}'. A receipt written for " +
                        "another schema is not a receipt this product can act on.",
                ),
            )
        } else {
            CertificationReceiptDecodeResult.Accepted(receipt)
        }
    } catch (e: SerializationException) {
        CertificationReceiptDecodeResult.Rejected(
            CertificationReceiptRejection.MalformedDocument(
                e.message ?: "the document does not describe a certification receipt.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        // A value the receipt's own invariants refuse -- a candidate id that is
        // not a digest, a battery without a name. The message is the
        // invariant's own, and it is the useful part.
        CertificationReceiptDecodeResult.Rejected(
            CertificationReceiptRejection.MalformedDocument(
                e.message ?: "the document does not describe a certification receipt.",
            ),
        )
    }
}
