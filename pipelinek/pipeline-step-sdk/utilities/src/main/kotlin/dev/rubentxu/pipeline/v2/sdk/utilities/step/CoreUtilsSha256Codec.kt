package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.longOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredLong
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON codec for [Sha256Input] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * Note: the codec accepts arbitrary string for [Sha256Input.algorithm] but
 * the handler refuses anything other than `SHA-256` (default) or `SHA-1`,
 * producing a typed USER-class failure rather than silently substituting
 * another algorithm. This is part of the explicit-tolerance contract.
 *
 * C3 / D-011 (audit 2026-09-26, H14): migrated to `PipelineJson` /
 * `JsonAccessors`. Wire format unchanged (roundtrip byte-identical to the
 * pre-refactor `Json.encodeToString(JsonObject.serializer(), obj)` shape).
 */
object CoreUtilsSha256InputCodec : StepCodec<Sha256Input> {

    override fun encode(value: Sha256Input): EncodedStepValue = PipelineJson.encode(
        kotlinx.serialization.json.buildJsonObject {
            put("path", value.path)
            put("algorithm", value.algorithm)
        }
    )

    override fun decode(encoded: EncodedStepValue): Sha256Input {
        val obj = PipelineJson.decode(encoded)
        return Sha256Input(
            path = obj.requiredString("path"),
            algorithm = obj.stringOrNull("algorithm") ?: "SHA-256",
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
 * JSON codec for [Sha256Output]. Migrated to `PipelineJson` (C3 / D-011).
 */
object CoreUtilsSha256OutputCodec : StepCodec<Sha256Output> {

    override fun encode(value: Sha256Output): EncodedStepValue = PipelineJson.encode(
        kotlinx.serialization.json.buildJsonObject {
            put("hexDigest", value.hexDigest)
            put("byteSize", value.byteSize)
            put("algorithm", value.algorithm)
        }
    )

    override fun decode(encoded: EncodedStepValue): Sha256Output {
        val obj = PipelineJson.decode(encoded)
        return Sha256Output(
            hexDigest = obj.requiredString("hexDigest"),
            byteSize = obj.requiredLong("byteSize"),
            algorithm = obj.requiredString("algorithm"),
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
