package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [Sha256Input] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * Note: the codec accepts arbitrary string for [Sha256Input.algorithm] but
 * the handler refuses anything other than `SHA-256` (default) or `SHA-1`,
 * producing a typed USER-class failure rather than silently substituting
 * another algorithm. This is part of the explicit-tolerance contract.
 */
object CoreUtilsSha256InputCodec : StepCodec<Sha256Input> {

    override fun encode(value: Sha256Input): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("path", value.path)
            put("algorithm", value.algorithm)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): Sha256Input {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return Sha256Input(
            path = obj.getValue("path").jsonPrimitive.content,
            algorithm = obj["algorithm"]?.jsonPrimitive?.contentOrNull ?: "SHA-256",
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "Sha256Input",
          "type": "object",
          "required": ["path"],
          "properties": {
            "path": {"type": "string"},
            "algorithm": {"type": "string", "enum": ["SHA-256", "SHA-1"], "default": "SHA-256"}
          }
        }
    """.trimIndent()
}

/**
 * JSON codec for [Sha256Output].
 */
object CoreUtilsSha256OutputCodec : StepCodec<Sha256Output> {

    override fun encode(value: Sha256Output): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("hexDigest", value.hexDigest)
            put("byteSize", value.byteSize)
            put("algorithm", value.algorithm)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): Sha256Output {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return Sha256Output(
            hexDigest = obj.getValue("hexDigest").jsonPrimitive.content,
            byteSize = obj.getValue("byteSize").jsonPrimitive.content.toLong(),
            algorithm = obj.getValue("algorithm").jsonPrimitive.content,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "Sha256Output",
          "type": "object",
          "required": ["hexDigest", "byteSize", "algorithm"],
          "properties": {
            "hexDigest": {"type": "string"},
            "byteSize": {"type": "integer"},
            "algorithm": {"type": "string"}
          }
        }
    """.trimIndent()
}
