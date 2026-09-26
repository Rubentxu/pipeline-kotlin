package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.boolOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredLong
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON codec for [WriteJsonInput] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * The codec preserves both [WriteJsonInput.value] (typed JsonElement) and
 * [WriteJsonInput.rawText]. Exactly one is expected to be populated at
 * decode-time per the [WriteJsonInput.useRawText] flag.
 *
 * C3 / D-011 (audit 2026-09-26, H14): migrated to `PipelineJson` /
 * `JsonAccessors`. Wire format unchanged.
 */
object CoreUtilsWriteJsonInputCodec : StepCodec<WriteJsonInput> {

    override fun encode(value: WriteJsonInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("path", value.path)
            if (value.value != null) put("value", value.value)
            if (value.rawText != null) put("rawText", value.rawText)
            put("prettyPrint", value.prettyPrint)
            put("useRawText", value.useRawText)
        }
    )

    override fun decode(encoded: EncodedStepValue): WriteJsonInput {
        val obj = PipelineJson.decode(encoded)
        return WriteJsonInput(
            path = obj.requiredString("path"),
            value = obj["value"],
            rawText = obj.stringOrNull("rawText"),
            prettyPrint = obj.boolOrNull("prettyPrint") ?: true,
            useRawText = obj.boolOrNull("useRawText") ?: false,
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
}

/**
 * JSON codec for [WriteJsonOutput]. Migrated to `PipelineJson` (C3 / D-011).
 */
object CoreUtilsWriteJsonOutputCodec : StepCodec<WriteJsonOutput> {

    override fun encode(value: WriteJsonOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("absolutePath", value.absolutePath)
            put("byteSize", value.byteSize)
            put("sha256Hex", value.sha256Hex)
            put("bytesWritten", value.bytesWritten)
        }
    )

    override fun decode(encoded: EncodedStepValue): WriteJsonOutput {
        val obj = PipelineJson.decode(encoded)
        return WriteJsonOutput(
            absolutePath = obj.requiredString("absolutePath"),
            byteSize = obj.requiredLong("byteSize"),
            sha256Hex = obj.requiredString("sha256Hex"),
            bytesWritten = obj.requiredLong("bytesWritten"),
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
