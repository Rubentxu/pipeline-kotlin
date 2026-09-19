package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `core-utils.zip` codecs (input + output) (LFC-2E2, Slice 2 / S2.4).
 *
 * Envelope shape (durable eligible):
 *   input  : { "path": <string>, "overwrite": <bool>,
 *              "sources": { "kind": "glob", "glob": <string> }
 *                       | { "kind": "directory", "directory": <string> }
 *                       | { "kind": "files", "paths": [<string>, ...] } }
 *   output : { "absolutePath": <string>, "byteSize": <long>,
 *              "sha256Hex": <string>, "entryCount": <int> }
 */
object CoreUtilsZipInputCodec : StepCodec<ZipInput> {

    override fun encode(input: ZipInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("path", input.path)
            put("overwrite", input.overwrite)
            put("sources", sourcesToJson(input.sources))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): ZipInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val path = obj.getValue("path").jsonPrimitive.content
        val overwrite = obj["overwrite"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val sources = sourcesFromJson(obj.getValue("sources"))
        return ZipInput(path = path, overwrite = overwrite, sources = sources)
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.zip input",
          "type": "object",
          "required": ["path", "sources"],
          "properties": {
            "path":     { "type": "string", "minLength": 1 },
            "overwrite":{ "type": "boolean" },
            "sources": {
              "oneOf": [
                { "type": "object", "required": ["kind","glob"], "properties": {
                    "kind": { "const": "glob" }, "glob": { "type": "string", "minLength": 1 } } },
                { "type": "object", "required": ["kind","directory"], "properties": {
                    "kind": { "const": "directory" }, "directory": { "type": "string", "minLength": 1 } } },
                { "type": "object", "required": ["kind","paths"], "properties": {
                    "kind": { "const": "files" }, "paths": { "type": "array", "minItems": 1,
                      "items": { "type": "string", "minLength": 1 } } } }
              ]
            }
          }
        }
    """.trimIndent()

    private fun sourcesToJson(s: ZipSources): JsonObject = when (s) {
        is ZipSources.FromGlob -> buildJsonObject { put("kind", "glob"); put("glob", s.glob) }
        is ZipSources.FromDirectory -> buildJsonObject {
            put("kind", "directory")
            put("directory", s.directory)
        }
        is ZipSources.FromFiles -> buildJsonObject {
            put("kind", "files")
            put("paths", kotlinx.serialization.json.buildJsonArray {
                s.paths.forEach { add(it) }
            })
        }
    }

    private fun sourcesFromJson(elem: kotlinx.serialization.json.JsonElement): ZipSources {
        val obj = elem.jsonObject
        return when (val kind = obj.getValue("kind").jsonPrimitive.content) {
            "glob" -> ZipSources.FromGlob(obj.getValue("glob").jsonPrimitive.content)
            "directory" -> ZipSources.FromDirectory(obj.getValue("directory").jsonPrimitive.content)
            "files" -> {
                val arr = obj.getValue("paths").jsonArray
                ZipSources.FromFiles(arr.map { it.jsonPrimitive.content })
            }
            else -> throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.zip: unknown sources kind '$kind' (corrupt journal?)",
                ),
            )
        }
    }
}

object CoreUtilsZipOutputCodec : StepCodec<ZipOutput> {

    override fun encode(output: ZipOutput): EncodedStepValue {
        val obj = buildJsonObject {
            put("absolutePath", output.absolutePath)
            put("byteSize", output.byteSize)
            put("sha256Hex", output.sha256Hex)
            put("entryCount", output.entryCount)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): ZipOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return ZipOutput(
            absolutePath = obj.getValue("absolutePath").jsonPrimitive.content,
            byteSize = obj.getValue("byteSize").jsonPrimitive.content.toLong(),
            sha256Hex = obj.getValue("sha256Hex").jsonPrimitive.content,
            entryCount = obj.getValue("entryCount").jsonPrimitive.content.toInt(),
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.zip output",
          "type": "object",
          "required": ["absolutePath", "byteSize", "sha256Hex", "entryCount"],
          "properties": {
            "absolutePath": { "type": "string" },
            "byteSize":     { "type": "integer" },
            "sha256Hex":    { "type": "string" },
            "entryCount":   { "type": "integer" }
          }
        }
    """.trimIndent()
}
