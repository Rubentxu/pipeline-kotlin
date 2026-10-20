package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.boolOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonOutput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON codec for [ReadJsonInput] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * Uses kotlinx.serialization runtime JSON (no plugin) following the canonical
 * pattern in `pipeline-step-sdk/scm-git` and `pipeline-step-sdk/junit`.
 *
 * `encodeDefaults = true` semantics are preserved by explicit `put` for every
 * field, so a codec roundtrip losslessly preserves optional defaults.
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged (roundtrip byte-identical to pre-refactor).
 */
object CoreUtilsReadJsonInputCodec : StepCodec<ReadJsonInput> {

    override fun encode(value: ReadJsonInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("path", value.path)
            put("prettyPrint", value.prettyPrint)
            put("returnRawText", value.returnRawText)
        }
    )

    override fun decode(encoded: EncodedStepValue): ReadJsonInput {
        val obj = PipelineJson.decode(encoded)
        return ReadJsonInput(
            path = obj.requiredString("path"),
            prettyPrint = obj.boolOrNull("prettyPrint") ?: true,
            returnRawText = obj.boolOrNull("returnRawText") ?: false,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "ReadJsonInput",
          "type": "object",
          "required": ["path"],
          "properties": {
            "path": {"type": "string"},
            "prettyPrint": {"type": "boolean", "default": true},
            "returnRawText": {"type": "boolean", "default": false}
          }
        }
    """.trimIndent()
}

/**
 * JSON codec for [ReadJsonOutput]. Symmetric to the input codec so a handler
 * failure that returns the typed value lands at the same JSON shape on the
 * wire.
 *
 * The output codec encodes BOTH the raw text and the parsed element. The
 * raw text is preserved for diagnostics; the parsed element is the canonical
 * shape used by downstream Steps.
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged.
 */
object CoreUtilsReadJsonOutputCodec : StepCodec<ReadJsonOutput> {

    override fun encode(value: ReadJsonOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("rawText", value.rawText)
            // `JsonElement` is itself a kotlinx.serialization element; we serialize it
            // as the encoded JsonObject/array/primitive literal.
            if (value.parsed != null) {
                put("parsed", value.parsed)
            }
            put("byteSize", value.byteSize)
            put("absolutePath", value.absolutePath)
        }
    )

    override fun decode(encoded: EncodedStepValue): ReadJsonOutput {
        val obj = PipelineJson.decode(encoded)
        val parsed: JsonElement? = obj["parsed"]
        return ReadJsonOutput(
            rawText = obj.requiredString("rawText"),
            parsed = parsed,
            byteSize = obj.requiredString("byteSize").toLong(),
            absolutePath = obj.requiredString("absolutePath"),
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "ReadJsonOutput",
          "type": "object",
          "required": ["rawText", "byteSize", "absolutePath"],
          "properties": {
            "rawText": {"type": "string"},
            "parsed": {"type": ["object", "array", "string", "number", "boolean", "null"]},
            "byteSize": {"type": "integer"},
            "absolutePath": {"type": "string"}
          }
        }
    """.trimIndent()
}
