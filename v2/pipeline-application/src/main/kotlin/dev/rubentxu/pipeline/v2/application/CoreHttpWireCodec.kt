package dev.rubentxu.pipeline.v2.application

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

/** Raised when a `core.httpRequest` payload is not what this Step can speak. */
class CoreHttpCodecException(message: String) : IllegalArgumentException(message)

/**
 * THE single authority for the `core.httpRequest` wire format.
 *
 * The DSL compiler lowers `StepSpec.HttpRequest` through this object and never
 * authors the format itself. `core.sh` is the frozen counter-example of what happens
 * when a compiler producer and a codec drift: its payload ended up with
 * `kind=sh|shell`, `command|script` and `returnStdout|returnMode`, and every alias became
 * a compatibility shim added after the divergence instead of a bug fixed before it.
 *
 * `Lfc2HttpWireAuthorityFitnessTest` refuses to manufacture that debt for httpRequest.
 */
object CoreHttpWireCodec : StepCodec<CoreHttpInput> {

    private val json = Json

    override fun encode(value: CoreHttpInput): EncodedStepValue {
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
            put("validResponseCodes", buildJsonObject {
                put("spec", value.validResponseCodes.joinToString(",") { range ->
                    when (range) {
                        is StatusRange.Single -> range.code.toString()
                        is StatusRange.Span -> "${range.from}:${range.to}"
                    }
                })
            })
            put("timeoutSeconds", value.timeoutSeconds)
            value.authentication?.let { put("authentication", it.value) }
        }
        return EncodedStepValue(json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreHttpInput {
        val obj = parseObject(encoded.value)
        val kind = obj.stringField("kind")
            ?: throw CoreHttpCodecException("core.httpRequest payload missing mandatory 'kind' field")
        if (kind != "httpRequest") {
            throw CoreHttpCodecException("core.httpRequest payload declares kind '$kind'")
        }
        val url = obj.stringField("url")
            ?: throw CoreHttpCodecException("core.httpRequest payload missing mandatory 'url' field")
        val methodName = obj.stringField("httpMode") ?: "GET"
        val method = HttpMethod.fromWire(methodName)
            ?: throw CoreHttpCodecException(
                "core.httpRequest payload declares unknown httpMode '$methodName'",
            )
        val headers = (obj["customHeaders"] as? JsonArray)
            ?.mapNotNull { element ->
                val headerObj = element as? JsonObject ?: return@mapNotNull null
                val name = headerObj.stringField("name") ?: return@mapNotNull null
                HttpHeader.of(name, headerObj.stringField("value") ?: "")
            }
            .orEmpty()
        val ranges = obj.objectField("validResponseCodes")
            ?.stringField("spec")
            ?.let { spec -> StatusRange.parse(spec) }
            ?: StatusRange.jenkinsDefault()
        return CoreHttpInput(
            url = url,
            method = method,
            customHeaders = headers,
            body = obj.stringField("requestBody"),
            contentType = obj.stringField("contentType"),
            acceptType = obj.stringField("acceptType"),
            validResponseCodes = ranges,
            timeoutSeconds = obj.intField("timeoutSeconds") ?: CoreHttpInput.DEFAULT_TIMEOUT_SECONDS,
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
              "type": "object",
              "properties": { "spec": { "type": "string" } }
            },
            "timeoutSeconds": { "type": "integer" },
            "authentication": { "type": "string" }
          }
        }
    """.trimIndent()
}

/**
 * The authority for the `core.httpRequest` OUTPUT wire.
 *
 * Split out of the Step, as `CoreInputOutputCodec` was, so the contract test can pin the
 * format without going through the handler.
 */
object CoreHttpOutputCodec : StepCodec<CoreHttpResponse> {

    private val json = Json

    override fun encode(value: CoreHttpResponse): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", "httpResponse")
            put("url", value.url)
            put("httpMode", value.method.wireName)
            put("status", value.status)
            put(
                "headers",
                JsonArray(
                    value.headers.map { header ->
                        buildJsonObject {
                            put("name", header.name.value)
                            put("value", header.value.value)
                        }
                    },
                ),
            )
            put("body", value.body)
            put("bodySha256", value.bodySha256)
            put("bodySizeBytes", value.bodySizeBytes)
            put("bodyTruncated", value.bodyTruncated)
        }
        return EncodedStepValue(json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreHttpResponse {
        val obj = parseObject(encoded.value)
        val kind = obj.stringField("kind")
            ?: throw CoreHttpCodecException("core.httpRequest output missing mandatory 'kind' field")
        if (kind != "httpResponse") {
            throw CoreHttpCodecException("core.httpRequest output declares kind '$kind'")
        }
        val url = obj.stringField("url")
            ?: throw CoreHttpCodecException("core.httpRequest output missing mandatory 'url' field")
        val methodName = obj.stringField("httpMode") ?: "GET"
        val method = HttpMethod.fromWire(methodName)
            ?: throw CoreHttpCodecException("core.httpRequest output declares unknown httpMode '$methodName'")
        val headers = (obj["headers"] as? JsonArray)
            ?.mapNotNull { element ->
                val headerObj = element as? JsonObject ?: return@mapNotNull null
                val name = headerObj.stringField("name") ?: return@mapNotNull null
                HttpHeader.of(name, headerObj.stringField("value") ?: "")
            }
            .orEmpty()
        return CoreHttpResponse(
            url = url,
            method = method,
            status = obj.intField("status") ?: 0,
            headers = headers,
            body = obj.stringField("body") ?: "",
            bodySha256 = obj.stringField("bodySha256") ?: "",
            bodySizeBytes = obj.longField("bodySizeBytes") ?: 0L,
            bodyTruncated = obj.booleanField("bodyTruncated") ?: false,
        )
    }

    override fun schema(): String = """
        {
          "type": "object",
          "required": ["kind", "url", "status"],
          "properties": {
            "kind": { "const": "httpResponse" },
            "url": { "type": "string" },
            "httpMode": { "type": "string" },
            "status": { "type": "integer" },
            "headers": { "type": "array" },
            "body": { "type": "string" },
            "bodySha256": { "type": "string" },
            "bodySizeBytes": { "type": "integer" },
            "bodyTruncated": { "type": "boolean" }
          }
        }
    """.trimIndent()
}

internal fun parseObject(raw: String): JsonObject = try {
    Json.parseToJsonElement(raw).jsonObject
} catch (e: Exception) {
    throw CoreHttpCodecException("core.httpRequest payload is not a JSON object: ${e.message ?: "parse failed"}")
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

private fun JsonObject.objectField(name: String): JsonObject? =
    get(name) as? JsonObject

private fun kotlinx.serialization.json.JsonElement.primitiveText(): String =
    (this as? JsonPrimitive)?.content ?: ""
