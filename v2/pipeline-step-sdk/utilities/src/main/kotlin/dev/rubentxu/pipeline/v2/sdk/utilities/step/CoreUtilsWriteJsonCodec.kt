package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [WriteJsonInput] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * The codec preserves both [WriteJsonInput.value] (typed JsonElement) and
 * [WriteJsonInput.rawText]. Exactly one is expected to be populated at
 * decode-time per the [WriteJsonInput.useRawText] flag.
 */
object CoreUtilsWriteJsonInputCodec : StepCodec<WriteJsonInput> {

    override fun encode(value: WriteJsonInput): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("path", value.path)
            if (value.value != null) put("value", value.value)
            if (value.rawText != null) put("rawText", value.rawText)
            put("prettyPrint", value.prettyPrint)
            put("useRawText", value.useRawText)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): WriteJsonInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val value = obj["value"]
        val rawText = obj["rawText"]?.jsonPrimitive?.contentOrNull
        return WriteJsonInput(
            path = obj.getValue("path").jsonPrimitive.content,
            value = value,
            rawText = rawText,
            prettyPrint = obj.boolOr("prettyPrint") ?: true,
            useRawText = obj.boolOr("useRawText") ?: false,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "WriteJsonInput",
          "type": "object",
          "required": ["path"],
          "properties": {
            "path": {"type": "string"},
            "value": {"type": ["object", "array", "string", "number", "boolean", "null"]},
            "rawText": {"type": "string"},
            "prettyPrint": {"type": "boolean", "default": true},
            "useRawText": {"type": "boolean", "default": false}
          }
        }
    """.trimIndent()

    private fun JsonObject.boolOr(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}

/**
 * JSON codec for [WriteJsonOutput].
 */
object CoreUtilsWriteJsonOutputCodec : StepCodec<WriteJsonOutput> {

    override fun encode(value: WriteJsonOutput): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("absolutePath", value.absolutePath)
            put("byteSize", value.byteSize)
            put("sha256Hex", value.sha256Hex)
            put("bytesWritten", value.bytesWritten)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): WriteJsonOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return WriteJsonOutput(
            absolutePath = obj.getValue("absolutePath").jsonPrimitive.content,
            byteSize = obj.getValue("byteSize").jsonPrimitive.content.toLong(),
            sha256Hex = obj.getValue("sha256Hex").jsonPrimitive.content,
            bytesWritten = obj.getValue("bytesWritten").jsonPrimitive.content.toLong(),
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "WriteJsonOutput",
          "type": "object",
          "required": ["absolutePath", "byteSize", "sha256Hex", "bytesWritten"],
          "properties": {
            "absolutePath": {"type": "string"},
            "byteSize": {"type": "integer"},
            "sha256Hex": {"type": "string"},
            "bytesWritten": {"type": "integer"}
          }
        }
    """.trimIndent()
}
