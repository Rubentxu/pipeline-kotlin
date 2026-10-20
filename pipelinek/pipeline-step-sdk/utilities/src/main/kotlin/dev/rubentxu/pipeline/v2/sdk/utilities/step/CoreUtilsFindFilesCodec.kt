package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredArray
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredObject
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FileEntry
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.FindFilesPattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `core-utils.findFiles` codecs (input + output) (LFC-2E2, Slice 2 / S2.3).
 *
 * Envelope shape (durable eligible):
 *   input  : { "base": <string>, "pattern": { "kind": "none" } |
 *                                            { "kind": "glob",
 *                                              "glob": <string>,
 *                                              "excludes": <string|null> } }
 *   output : { "basePath": <string>, "patternEcho": <pattern>,
 *              "files": [{ "name": ..., "path": ..., "directory": ...,
 *                          "length": ..., "lastModified": ... }, ...] }
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged.
 */
object CoreUtilsFindFilesInputCodec : StepCodec<FindFilesInput> {

    override fun encode(input: FindFilesInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("base", input.base)
            put("pattern", patternToJson(input.pattern))
        }
    )

    override fun decode(encoded: EncodedStepValue): FindFilesInput {
        val obj = PipelineJson.decode(encoded)
        val base = obj.requiredString("base")
        val pattern = parsePattern(obj.requiredObject("pattern"))
        return FindFilesInput(base = base, pattern = pattern)
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.findFiles input",
          "type": "object",
          "required": ["base", "pattern"],
          "properties": {
            "base": { "type": "string", "minLength": 1 },
            "pattern": {
              "oneOf": [
                { "type": "object", "required": ["kind"], "properties": { "kind": { "const": "none" } } },
                { "type": "object", "required": ["kind", "glob"], "properties": {
                    "kind":     { "const": "glob" },
                    "glob":     { "type": "string", "minLength": 1 },
                    "excludes": { "type": ["string", "null"] }
                } }
              ]
            }
          }
        }
    """.trimIndent()

    /**
     * Public-facing parser for the closed Pattern ADT. Used by both the
     * input codec (during decode) and the output codec (to rehydrate
     * `patternEcho`). Exposed as an object-level function so the two
     * codecs cannot drift.
     */
    fun parsePattern(elem: kotlinx.serialization.json.JsonElement): FindFilesPattern {
        val obj = elem.jsonObject
        return when (val kind = obj.requiredString("kind")) {
            "none" -> FindFilesPattern.None
            "glob" -> {
                val g = obj.requiredString("glob")
                val ex = when (val e = obj["excludes"]) {
                    null, JsonNull -> null
                    else -> e.jsonPrimitive.content
                }
                FindFilesPattern.Glob(g, ex)
            }
            else -> throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.findFiles: unknown pattern kind '$kind' (corrupt journal?)",
                ),
            )
        }
    }

    private fun patternToJson(pattern: FindFilesPattern): JsonObject = when (pattern) {
        is FindFilesPattern.None -> buildJsonObject { put("kind", "none") }
        is FindFilesPattern.Glob -> buildJsonObject {
            put("kind", "glob")
            put("glob", pattern.glob)
            if (pattern.excludes != null) put("excludes", pattern.excludes) else put("excludes", JsonNull)
        }
    }
}

object CoreUtilsFindFilesOutputCodec : StepCodec<FindFilesOutput> {

    override fun encode(output: FindFilesOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("basePath", output.basePath)
            put("patternEcho", patternToJson(output.patternEcho))
            put("files", JsonArray(buildJsonArray {
                output.files.forEach { e ->
                    add(buildJsonObject {
                        put("name", e.name)
                        put("path", e.path)
                        put("directory", e.directory)
                        put("length", e.length)
                        put("lastModified", e.lastModified)
                    })
                }
            }))
        }
    )

    override fun decode(encoded: EncodedStepValue): FindFilesOutput {
        val obj = PipelineJson.decode(encoded)
        val basePath = obj.requiredString("basePath")
        val patternEcho = CoreUtilsFindFilesInputCodec.parsePattern(obj.requiredObject("patternEcho"))
        val arr = obj.requiredArray("files")
        val files = arr.map { fe ->
            val e = fe.jsonObject
            FileEntry(
                name = e.requiredString("name"),
                path = e.requiredString("path"),
                directory = e.requiredString("directory").toBoolean(),
                length = e.requiredString("length").toLong(),
                lastModified = e.requiredString("lastModified").toLong(),
            )
        }
        return FindFilesOutput(basePath = basePath, patternEcho = patternEcho, files = files)
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.findFiles output",
          "type": "object",
          "required": ["basePath", "patternEcho", "files"],
          "properties": {
            "basePath":    { "type": "string" },
            "patternEcho": { "type": "object" },
            "files": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "path", "directory", "length", "lastModified"],
                "properties": {
                  "name":         { "type": "string" },
                  "path":         { "type": "string" },
                  "directory":    { "type": "boolean" },
                  "length":       { "type": "integer" },
                  "lastModified": { "type": "integer" }
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun patternToJson(pattern: FindFilesPattern): JsonObject = when (pattern) {
        is FindFilesPattern.None -> buildJsonObject { put("kind", "none") }
        is FindFilesPattern.Glob -> buildJsonObject {
            put("kind", "glob")
            put("glob", pattern.glob)
            if (pattern.excludes != null) put("excludes", pattern.excludes) else put("excludes", JsonNull)
        }
    }
}
