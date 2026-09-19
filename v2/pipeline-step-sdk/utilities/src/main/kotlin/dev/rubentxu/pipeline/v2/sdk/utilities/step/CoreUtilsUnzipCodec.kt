package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFile
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.TestReport
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipMode
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `core-utils.unzip` codecs (LFC-2E2 utilities, Slice 2 / S2.5).
 *
 * Envelope shape (durable eligible):
 *   input  : { "path": <string>,
 *              "destination": <string|null>,
 *              "glob": <string|null>,
 *              "mode": "extract" | "read" | "test" }
 *   output : { "extracted":   <object|null>, | "readEntries": <object|null>,
 *              "testReport":  <object|null> }
 *
 * Exactly one of the three output fields is set; the codec enforces
 * this invariant on decode.
 */
object CoreUtilsUnzipInputCodec : StepCodec<UnzipInput> {

    override fun encode(input: UnzipInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("path", input.path)
            input.destination?.let { put("destination", it) }
            input.glob?.let { put("glob", it) }
            put("mode", modeToString(input.mode))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): UnzipInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val path = obj.getValue("path").jsonPrimitive.content
        val destination = obj["destination"]?.jsonPrimitive?.content
        val glob = obj["glob"]?.jsonPrimitive?.content
        val mode = modeFromString(obj.getValue("mode").jsonPrimitive.content)
        return UnzipInput(
            path = path,
            destination = destination,
            glob = glob,
            mode = mode,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.unzip input",
          "type": "object",
          "required": ["path", "mode"],
          "properties": {
            "path":        { "type": "string", "minLength": 1 },
            "destination": { "type": "string" },
            "glob":        { "type": "string" },
            "mode":        { "enum": ["extract", "read", "test"] }
          }
        }
    """.trimIndent()

    private fun modeToString(m: UnzipMode): String = when (m) {
        is UnzipMode.Extract -> "extract"
        is UnzipMode.Read -> "read"
        is UnzipMode.Test -> "test"
    }

    private fun modeFromString(s: String): UnzipMode = when (s) {
        "extract" -> UnzipMode.Extract
        "read" -> UnzipMode.Read
        "test" -> UnzipMode.Test
        else -> throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "core-utils.unzip: unknown mode '$s' (corrupt journal?)",
            ),
        )
    }
}

object CoreUtilsUnzipOutputCodec : StepCodec<UnzipOutput> {

    override fun encode(output: UnzipOutput): EncodedStepValue {
        val obj = buildJsonObject {
            output.extracted?.let { extracted ->
                put("extracted", buildJsonObject {
                    put("destination", extracted.destination)
                    put("files", kotlinx.serialization.json.buildJsonArray {
                        extracted.files.forEach { f ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("name", f.name)
                                put("path", f.path)
                                put("size", f.size)
                            })
                        }
                    })
                })
            }
            output.readEntries?.let { entries ->
                put("readEntries", buildJsonObject {
                    entries.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
            output.testReport?.let { report ->
                put("testReport", buildJsonObject {
                    put("ok", report.ok)
                    put("entryCount", report.entryCount)
                    put("badEntries", kotlinx.serialization.json.buildJsonArray {
                        report.badEntries.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): UnzipOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val extracted = obj["extracted"]?.let { e ->
            if (e is JsonNull) null
            else {
                val o = e.jsonObject
                ExtractedFiles(
                    destination = o.getValue("destination").jsonPrimitive.content,
                    files = o.getValue("files").jsonArray.map { entry ->
                        val f = entry.jsonObject
                        ExtractedFile(
                            name = f.getValue("name").jsonPrimitive.content,
                            path = f.getValue("path").jsonPrimitive.content,
                            size = f.getValue("size").jsonPrimitive.content.toLong(),
                        )
                    },
                )
            }
        }
        val readEntries = obj["readEntries"]?.let { e ->
            if (e is JsonNull) null
            else e.jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val testReport = obj["testReport"]?.let { e ->
            if (e is JsonNull) null
            else {
                val o = e.jsonObject
                TestReport(
                    ok = o.getValue("ok").jsonPrimitive.content.toBoolean(),
                    entryCount = o.getValue("entryCount").jsonPrimitive.content.toInt(),
                    badEntries = o.getValue("badEntries").jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }
        val populated = listOfNotNull(extracted, readEntries, testReport)
        if (populated.isEmpty()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: output envelope has no populated field",
                ),
            )
        }
        if (populated.size > 1) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: output envelope has more than one populated field",
                ),
            )
        }
        return UnzipOutput(extracted, readEntries, testReport)
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "core-utils.unzip output",
          "type": "object",
          "oneOf": [
            { "required": ["extracted"] },
            { "required": ["readEntries"] },
            { "required": ["testReport"] }
          ],
          "properties": {
            "extracted": {
              "type": "object",
              "required": ["destination", "files"],
              "properties": {
                "destination": { "type": "string" },
                "files": { "type": "array", "items": { "type": "object",
                  "required": ["name", "path", "size"],
                  "properties": {
                    "name": { "type": "string" },
                    "path": { "type": "string" },
                    "size": { "type": "integer" } } } }
              }
            },
            "readEntries": { "type": "object", "additionalProperties": { "type": "string" } },
            "testReport": {
              "type": "object",
              "required": ["ok", "entryCount", "badEntries"],
              "properties": {
                "ok": { "type": "boolean" },
                "entryCount": { "type": "integer" },
                "badEntries": { "type": "array", "items": { "type": "string" } }
              }
            }
          }
        }
    """.trimIndent()
}
