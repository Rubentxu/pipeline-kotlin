package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Raised when a `http.request` payload is not what this Step can speak. */
class HttpCodecException(message: String) : IllegalArgumentException(message)

/**
 * THE single authority for the `http.request` wire format.
 *
 * Nothing else writes this format. The DSL façade ([httpRequest] in `HttpDsl.kt`)
 * builds a typed [HttpRequestInput] and calls [encode]; the compiler has no branch
 * for this key and must never acquire one. `core.sh` is the frozen counter-example of
 * what happens when a producer and a codec drift: its payload ended up with
 * `kind=sh|shell`, `command|script` and `returnStdout|returnMode`, and every alias
 * became a compatibility shim added after the divergence instead of a bug fixed
 * before it. `Lfc2HttpOfficiallyPluginBoundaryFitnessTest` refuses to manufacture
 * that debt for httpRequest.
 *
 * The schema below is kept honest against [encode] by
 * `HttpRequestStepContractTest`, which fails when the two disagree — a declared
 * schema that no longer describes the bytes is how a consumer ends up rejecting
 * valid payloads.
 */
object HttpRequestCodec : StepCodec<HttpRequestInput> {

    private val json = Json

    override fun encode(value: HttpRequestInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", "httpRequest")
            put("url", value.url)
            put("httpMode", value.method.wireName)
            if (value.customHeaders.isNotEmpty()) {
                // An ARRAY of {name, value}, never a JSON object keyed by name.
                //
                // The object form looks tidier and is the obvious choice, and it was
                // the first one written here — until the contract test caught it
                // silently dropping every value but the last of a repeated header.
                // `Set-Cookie` repeats by design, and D1 makes repetition part of the
                // contract. A wire format that cannot represent the value it carries is
                // not a tidier encoding; it is a lossy one.
                put(
                    "customHeaders",
                    JsonArray(
                        value.customHeaders.map { header ->
                            buildJsonObject {
                                put("name", header.name.value)
                                put("value", header.value.value)
                            }
                        },
                    ),
                )
            }
            value.body?.let { put("requestBody", it) }
            value.contentType?.let { put("contentType", it) }
            value.acceptType?.let { put("acceptType", it) }
            put(
                "validResponseCodes",
                JsonArray(value.validResponseCodes.map { range ->
                    when (range) {
                        is StatusRange.Single -> JsonPrimitive(range.code.toString())
                        is StatusRange.Span -> JsonPrimitive("${range.from}:${range.to}")
                    }
                }),
            )
            put("timeoutSeconds", value.timeoutSeconds)
            value.authentication?.let { put("authentication", it.value) }
        }
        return EncodedStepValue(json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): HttpRequestInput {
        val obj = parseObject(encoded.value)
        val kind = obj.stringField("kind")
            ?: throw HttpCodecException("http.request payload missing mandatory 'kind' field")
        if (kind != "httpRequest") {
            throw HttpCodecException("http.request payload declares kind '$kind'")
        }
        val url = obj.stringField("url")
            ?: throw HttpCodecException("http.request payload missing mandatory 'url' field")
        val methodName = obj.stringField("httpMode") ?: "GET"
        val method = HttpMethod.fromWire(methodName)
            ?: throw HttpCodecException(
                "http.request payload declares unknown httpMode '$methodName'",
            )
        val headers = (obj["customHeaders"] as? JsonArray)
            ?.mapNotNull { element ->
                val headerObj = element as? JsonObject ?: return@mapNotNull null
                val name = headerObj.stringField("name") ?: return@mapNotNull null
                HttpHeader.of(name, headerObj.stringField("value") ?: "")
            }
            .orEmpty()
        val ranges = (obj["validResponseCodes"] as? JsonArray)
            ?.mapNotNull { element ->
                val spec = (element as? JsonPrimitive)?.content ?: return@mapNotNull null
                StatusRange.parse(spec)?.singleOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
            ?: StatusRange.jenkinsDefault()
        return HttpRequestInput(
            url = url,
            method = method,
            customHeaders = headers,
            body = obj.stringField("requestBody"),
            contentType = obj.stringField("contentType"),
            acceptType = obj.stringField("acceptType"),
            validResponseCodes = ranges,
            timeoutSeconds = obj.intField("timeoutSeconds") ?: HttpDefaults.DEFAULT_TIMEOUT_SECONDS,
            authentication = obj.stringField("authentication")?.let { CredentialsId(it) },
        )
    }

    override fun schema(): String = """
        {
          "type": "object",
          "required": ["kind", "url"],
          "properties": {
            "kind": { "const": "httpRequest" },
            "url": { "type": "string" },
            "httpMode": {
              "type": "string",
              "enum": ["GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"]
            },
            "customHeaders": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "value"],
                "properties": { "name": { "type": "string" }, "value": { "type": "string" } }
              }
            },
            "requestBody": { "type": "string" },
            "contentType": { "type": "string" },
            "acceptType": { "type": "string" },
            "validResponseCodes": {
              "type": "array",
              "description": "Each entry is 'N' for a single status or 'from:to' for an inclusive span.",
              "items": { "type": "string" }
            },
            "timeoutSeconds": { "type": "integer" },
            "authentication": { "type": "string" }
          }
        }
    """.trimIndent()
}

/**
 * The authority for the `http.request` OUTPUT wire.
 *
 * Split out of the Step, as `CoreInputOutputCodec` was, so the contract test can pin the
 * format without going through the handler.
 */
object HttpResponseCodec : StepCodec<HttpResponseOutput> {

    private val json = Json

    /**
     * The wire form of the carrier.
     *
     * It records the ATTEMPT, not a flattened response, and that is the change
     * that makes the durable record honest. The previous shape wrote
     * `status = 0` for a request that never answered, so a refused request and a
     * step that never ran were indistinguishable in the journal — and a reader
     * could not tell success from failure without re-deriving it. The attempt
     * case is explicit here, so the round-trip preserves WHICH thing happened.
     */
    override fun encode(value: HttpResponseOutput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", "httpAttempt")
            val attempt = value.attempt
            put("url", attempt.url)
            put("httpMode", attempt.method.wireName)
            put("durationMs", attempt.durationMs)
            when (attempt) {
                is HttpAttempt.Answered -> {
                    put("attempt", "answered")
                    put("status", attempt.response.status)
                    put("headers", JsonArray(attempt.response.headers.map(::headerObject)))
                    put("body", attempt.response.body)
                    put("bodySha256", attempt.response.bodySha256)
                    put("bodySizeBytes", attempt.response.bodySizeBytes)
                    put("bodyTruncated", attempt.response.bodyTruncated)
                }
                is HttpAttempt.Refused -> {
                    put("attempt", "refused")
                    put("status", attempt.status)
                    put("accepted", JsonArray(attempt.accepted.map(::statusRangeSpec)))
                }
                is HttpAttempt.Failed -> {
                    put("attempt", "failed")
                    put("failure", attempt.failure.diagnostic)
                    // H4-Q: the DIAGNOSTIC STRING is for humans and cannot be decoded.
                    // Without the case written down, every failure came back as
                    // `Unreachable` on replay — so a TIMEOUT was durably recorded as a
                    // network fact, and a body cut short at 37 MiB was recorded as a
                    // host that does not exist. Additive on purpose: a record written
                    // before this field simply has no case, and decode says so.
                    when (val failure = attempt.failure) {
                        is HttpFailure.Unreachable -> put("failureKind", "unreachable")
                        is HttpFailure.Expired -> {
                            put("failureKind", "expired")
                            put("failureAfterMs", failure.afterMs)
                        }
                        is HttpFailure.Rejected -> {
                            put("failureKind", "rejected")
                            put("failureRejection", failure.rejection.diagnostic)
                        }
                        is HttpFailure.ResponseInterrupted -> {
                            put("failureKind", "responseInterrupted")
                            // The RAW reason, separately from `failure`. `failure` is the
                            // decorated diagnostic for a human; feeding it back as the
                            // reason would grow a new sentence on every round trip.
                            put("failureReason", failure.reason)
                            put("failureBytesReceived", failure.bytesReceived)
                        }
                        is HttpFailure.EgressDenied -> put("failureKind", "egressDenied")
                    }
                }
                is HttpAttempt.Unauthorized -> {
                    put("attempt", "unauthorized")
                    put("rejection", attempt.rejection.diagnostic)
                    // Same reason as above: the diagnostic is a sentence, and the CASE
                    // is a shape. Reconstructing `NotFound` without the id would leave a
                    // record naming a credential nobody can find.
                    when (val rejection = attempt.rejection) {
                        is CredentialRejection.NotFound -> {
                            put("rejectionKind", "notFound")
                            put("rejectionId", rejection.id)
                        }
                        is CredentialRejection.KindUnsupported -> {
                            put("rejectionKind", "kindUnsupported")
                            put("rejectionId", rejection.id)
                            put("rejectionFound", rejection.found)
                            put(
                                "rejectionSupported",
                                JsonArray(rejection.supported.map { JsonPrimitive(it) }),
                            )
                        }
                    }
                }
            }
        }
        return EncodedStepValue(json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): HttpResponseOutput {
        val obj = parseObject(encoded.value)
        val kind = obj.stringField("kind")
            ?: throw HttpCodecException("http.request output missing mandatory 'kind' field")
        if (kind != "httpAttempt") {
            throw HttpCodecException("http.request output declares kind '$kind'")
        }
        val url = obj.stringField("url")
            ?: throw HttpCodecException("http.request output missing mandatory 'url' field")
        val methodName = obj.stringField("httpMode") ?: "GET"
        val method = HttpMethod.fromWire(methodName)
            ?: throw HttpCodecException("http.request output declares unknown httpMode '$methodName'")
        val durationMs = obj.longField("durationMs") ?: 0L
        return HttpResponseOutput(
            attempt = when (obj.stringField("attempt") ?: "answered") {
                "answered" -> HttpAttempt.Answered(
                    url = url,
                    method = method,
                    durationMs = durationMs,
                    response = HttpResponse(
                        url = url,
                        method = method,
                        status = obj.intField("status") ?: 0,
                        headers = decodeHeaders(obj),
                        body = obj.stringField("body") ?: "",
                        bodySha256 = obj.stringField("bodySha256") ?: "",
                        bodySizeBytes = obj.longField("bodySizeBytes") ?: 0L,
                        bodyTruncated = obj.booleanField("bodyTruncated") ?: false,
                    ),
                )
                "refused" -> HttpAttempt.Refused(
                    url = url,
                    method = method,
                    durationMs = durationMs,
                    status = obj.intField("status") ?: 0,
                    accepted = decodeAccepted(obj),
                )
                "failed" -> HttpAttempt.Failed(
                    url = url,
                    method = method,
                    durationMs = durationMs,
                    failure = decodeFailure(obj),
                )
                "unauthorized" -> HttpAttempt.Unauthorized(
                    url = url,
                    method = method,
                    durationMs = durationMs,
                    rejection = decodeRejection(obj),
                )
                // An unknown `attempt` is not a silent `Failed`. Decoding a record this
                // build cannot interpret into "the request failed" would make a journal
                // from a NEWER build look like a real incident from this one.
                else -> throw HttpCodecException(
                    "http.request output declares an unknown attempt kind '${obj.stringField("attempt")}'",
                )
            },
        )
    }

    /**
     * Rebuilds the exact failure case, or the best available reading of a legacy record.
     *
     * Records written before `failureKind` existed carry only a diagnostic string, and
     * a string is not a case. Those fall back to [HttpFailure.Unreachable] — the same
     * reading the previous decoder gave everything — and the fallback is stated rather
     * than hidden, because the alternative is inventing a fact the record does not
     * contain.
     */
    private fun decodeFailure(obj: JsonObject): HttpFailure = when (obj.stringField("failureKind")) {
        "unreachable" -> HttpFailure.Unreachable(obj.stringField("failure") ?: "the request failed")
        "expired" -> HttpFailure.Expired(obj.longField("failureAfterMs") ?: 0L)
        "egressDenied" -> HttpFailure.EgressDenied
        "responseInterrupted" -> HttpFailure.ResponseInterrupted(
            reason = obj.stringField("failureReason")
                ?: obj.stringField("failure")
                ?: "the body stopped",
            bytesReceived = obj.longField("failureBytesReceived") ?: 0L,
        )
        "rejected" -> HttpFailure.Rejected(
            HttpRejection.DeclarationRefused(
                obj.stringField("failureRejection")
                    ?: obj.stringField("failure")
                    ?: "the declaration was refused",
            ),
        )
        // Legacy record: no case was written. Unreachable is the historical reading.
        else -> HttpFailure.Unreachable(obj.stringField("failure") ?: "the request failed")
    }

    /**
     * Rebuilds the credential rejection, keeping the id.
     *
     * An older record has no `rejectionKind`, and its only content is a sentence. The
     * id is taken from the record when present and the sentence is kept as the
     * diagnostic, so nothing is invented — but a rejection whose id is genuinely
     * absent says so instead of inventing a placeholder name.
     */
    private fun decodeRejection(obj: JsonObject): CredentialRejection {
        val id = obj.stringField("rejectionId")
        return when (obj.stringField("rejectionKind")) {
            "notFound" -> CredentialRejection.NotFound(id ?: "the named credential")
            "kindUnsupported" -> CredentialRejection.KindUnsupported(
                id = id ?: "the named credential",
                found = obj.stringField("rejectionFound") ?: "an unknown kind",
                supported = (obj["rejectionSupported"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                    .ifEmpty { listOf("usernamePassword") },
            )
            else -> CredentialRejection.KindUnsupported(
                id = id ?: "the named credential",
                // A record with no kind is a record from before the kind existed. The
                // sentence it carries is the only thing known, so it is reported as
                // the finding rather than discarded.
                found = obj.stringField("rejection") ?: "an unrecorded kind",
                supported = listOf("usernamePassword"),
            )
        }
    }

    private fun headerObject(header: HttpHeader): JsonObject = buildJsonObject {
        put("name", header.name.value)
        put("value", header.value.value)
    }

    private fun statusRangeSpec(range: StatusRange): JsonPrimitive = when (range) {
        is StatusRange.Single -> JsonPrimitive(range.code.toString())
        is StatusRange.Span -> JsonPrimitive("${range.from}:${range.to}")
    }

    private fun decodeHeaders(obj: JsonObject): List<HttpHeader> =
        (obj["headers"] as? JsonArray)
            ?.mapNotNull { element ->
                val headerObj = element as? JsonObject ?: return@mapNotNull null
                val name = headerObj.stringField("name") ?: return@mapNotNull null
                HttpHeader.of(name, headerObj.stringField("value") ?: "")
            }
            .orEmpty()

    private fun decodeAccepted(obj: JsonObject): List<StatusRange> =
        (obj["accepted"] as? JsonArray)
            ?.mapNotNull { element ->
                val spec = (element as? JsonPrimitive)?.content ?: return@mapNotNull null
                StatusRange.parse(spec)?.firstOrNull()
            }
            .orEmpty()

    /**
     * The schema of the ATTEMPT, which is what [encode] writes.
     *
     * It used to declare `kind: "httpResponse"` while the encoder wrote
     * `"httpAttempt"`, and to require `status` — a field that does not exist for a
     * failed or unauthorized attempt, which are exactly the cases a reader most needs
     * to recognise. A schema that rejects valid payloads is not documentation, it is a
     * second, wrong contract, and the only honest fix is to describe the bytes that
     * are actually produced.
     *
     * `status` is therefore required only where it exists: inside the `answered` and
     * `refused` cases. The case discriminators are `attempt` and, for a failure,
     * `failureKind` — which is what makes [decodeFailure] able to rebuild the case
     * rather than guess it from a sentence.
     */
    override fun schema(): String = """
        {
          "type": "object",
          "required": ["kind", "url", "attempt"],
          "properties": {
            "kind": { "const": "httpAttempt" },
            "url": { "type": "string" },
            "httpMode": { "type": "string" },
            "durationMs": { "type": "integer" },
            "attempt": {
              "type": "string",
              "enum": ["answered", "refused", "failed", "unauthorized"]
            },
            "status": {
              "type": "integer",
              "description": "Present for 'answered' and 'refused' only; a request that never got a status has none."
            },
            "headers": { "type": "array" },
            "body": { "type": "string" },
            "bodySha256": { "type": "string" },
            "bodySizeBytes": { "type": "integer" },
            "bodyTruncated": { "type": "boolean" },
            "accepted": { "type": "array" },
            "failure": {
              "type": "string",
              "description": "Human-readable diagnostic. NOT decodable — 'failureKind' carries the case."
            },
            "failureKind": {
              "type": "string",
              "enum": ["unreachable", "expired", "rejected", "responseInterrupted", "egressDenied"],
              "description": "Absent in records written before the case was made durable; those decode as 'unreachable'."
            },
            "failureAfterMs": { "type": "integer" },
            "failureReason": {
              "type": "string",
              "description": "The raw transport reason for 'responseInterrupted', without the diagnostic sentence."
            },
            "failureBytesReceived": { "type": "integer" },
            "failureRejection": { "type": "string" },
            "rejection": { "type": "string" },
            "rejectionKind": { "type": "string", "enum": ["notFound", "kindUnsupported"] },
            "rejectionId": { "type": "string" },
            "rejectionFound": { "type": "string" },
            "rejectionSupported": { "type": "array", "items": { "type": "string" } }
          }
        }
    """.trimIndent()
}

internal fun parseObject(raw: String): JsonObject = try {
    Json.parseToJsonElement(raw).jsonObject
} catch (e: Exception) {
    throw HttpCodecException("http.request payload is not a JSON object: ${e.message ?: "parse failed"}")
}

private fun JsonObject.stringField(name: String): String? =
    get(name)?.takeIf { it !is JsonNull }?.primitiveText()

private fun JsonObject.intField(name: String): Int? = stringOrNull(name)?.trim()?.toIntOrNull()

private fun JsonObject.longField(name: String): Long? = stringOrNull(name)?.trim()?.toLongOrNull()

private fun JsonObject.booleanField(name: String): Boolean? = when (val raw = stringOrNull(name)) {
    "true" -> true
    "false" -> false
    else -> null
}

/**
 * A JSON string field, or `null` when absent, JSON null, or a non-primitive.
 *
 * Total on purpose: a wire payload is untrusted input, and a helper that threw
 * would turn a malformed record into an exception crossing a replay path
 * instead of a typed codec failure.
 */
private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it is JsonPrimitive }?.primitiveText()

private fun JsonObject.objectField(name: String): JsonObject? =
    get(name) as? JsonObject

private fun kotlinx.serialization.json.JsonElement.primitiveText(): String =
    (this as? JsonPrimitive)?.content ?: ""
