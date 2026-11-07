package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * The serialisation of the certification request, and the reading of a
 * document this product did not write.
 *
 * Two properties hold, and both are properties of this codec rather than of
 * convention:
 *
 * 1. **Determinism** — the same request always produces byte-identical JSON.
 *    The fixed key order of [CertificationRequest] and the encoded defaults
 *    make that true, so a request that is stated twice is the same file.
 * 2. **Closed shape** — a document that carries a field the schema does not
 *    define, or that declares a schema version this version does not know, is
 *    refused. Reading is not a negotiation: the reader either understands the
 *    whole document or refuses it, and the refusal names what it did not
 *    understand.
 *
 * The reading never throws for a document that is not a request: the caller
 * receives a reason it can record, because the caller is a program that
 * received bytes from somewhere else.
 */
object CertificationRequestCodec {

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
     * The request as JSON. The same request always yields the same bytes.
     */
    fun encode(request: CertificationRequest): String =
        writer.encodeToString(serializer<CertificationRequest>(), request) + "\n"

    /**
     * Read a request from [text].
     *
     * A document that carries an unknown field is refused by name. A document
     * that does not parse, that is missing a field the request requires, or
     * that declares another schema version is refused with the reason. A
     * document that is a request is returned as its value.
     */
    fun decode(text: String): CertificationRequestDecodeResult = try {
        val element = reader.parseToJsonElement(text)
        val fields = element as? JsonObject
        if (fields == null) {
            CertificationRequestDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document is not a JSON object, so it names no request.",
                ),
            )
        } else {
            // The names the schema defines, read from the serializer itself, so
            // the check and the document cannot disagree.
            val known = serializer<CertificationRequest>().descriptor.elementNames.toSet()
            val unknown = fields.keys - known
            if (unknown.isNotEmpty()) {
                CertificationRequestDecodeResult.Rejected(
                    CertificationRejection.UnknownField(unknown.sorted()),
                )
            } else {
                readRequest(fields)
            }
        }
    } catch (e: SerializationException) {
        CertificationRequestDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        CertificationRequestDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document is not valid JSON.",
            ),
        )
    }

    /**
     * Read the request out of a document whose shape is already known to be
     * this build's.
     *
     * Two refusals live here and they differ in kind: a document that does not
     * parse into a request, and a document that parses into a request written
     * for another schema. The second is not a malformed document -- it is a
     * well-formed document this build must not act on, and it says which
     * schema it declares.
     *
     * Every branch produces a value instead of jumping out, so the refusal is
     * the result and not a side effect of the control flow.
     */
    private fun readRequest(fields: JsonObject): CertificationRequestDecodeResult = try {
        val request = reader.decodeFromJsonElement(serializer<CertificationRequest>(), fields)
        if (request.schemaVersion != CertificationRequest.SCHEMA_VERSION) {
            CertificationRequestDecodeResult.Rejected(
                CertificationRejection.MalformedDocument(
                    "the document declares schema '${request.schemaVersion}' and this build " +
                        "reads '${CertificationRequest.SCHEMA_VERSION}'. A request written for " +
                        "another schema is not a request this product can act on.",
                ),
            )
        } else {
            CertificationRequestDecodeResult.Accepted(request)
        }
    } catch (e: SerializationException) {
        CertificationRequestDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document does not describe a certification request.",
            ),
        )
    } catch (e: IllegalArgumentException) {
        // A value the request's own invariants refuse -- a moment that is not a
        // moment, a battery without a name. The message is the invariant's own,
        // and it is the useful part.
        CertificationRequestDecodeResult.Rejected(
            CertificationRejection.MalformedDocument(
                e.message ?: "the document does not describe a certification request.",
            ),
        )
    }
}
