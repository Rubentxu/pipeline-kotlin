package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlDestination
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlPayload
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [WriteYamlInput] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * Encodes the sealed [WriteYamlDestination] and [WriteYamlPayload]
 * hierarchies as discriminated unions. Decode validates the mutual-exclusion
 * invariants the type system already enforces at construction time, so a
 * corrupt journal that violates them fails closed at decode time.
 */
object CoreUtilsWriteYamlInputCodec : StepCodec<WriteYamlInput> {

    override fun encode(value: WriteYamlInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("charset", value.charset)
            put("destination", encodeDestination(value.destination))
            put("payload", encodePayload(value.payload))
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): WriteYamlInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val charset = obj["charset"]?.jsonPrimitive?.content ?: "UTF-8"
        val destination = decodeDestination(obj.getValue("destination").jsonObject)
        val payload = decodePayload(obj.getValue("payload").jsonObject)
        return WriteYamlInput(
            destination = destination,
            payload = payload,
            charset = charset,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "WriteYamlInput",
          "type": "object",
          "required": ["destination", "payload"],
          "properties": {
            "charset": {"type": "string", "default": "UTF-8"},
            "destination": {
              "oneOf": [
                {"type": "object", "required": ["kind", "path"], "properties": {
                  "kind": {"const": "file"},
                  "path": {"type": "string"},
                  "overwrite": {"type": "boolean", "default": false}
                }},
                {"type": "object", "required": ["kind"], "properties": {
                  "kind": {"const": "text"}
                }}
              ]
            },
            "payload": {
              "oneOf": [
                {"type": "object", "required": ["kind", "value"], "properties": {
                  "kind": {"const": "single"},
                  "value": {"${'$'}ref": "#/${'$'}defs/YamlDocument"}
                }},
                {"type": "object", "required": ["kind", "items"], "properties": {
                  "kind": {"const": "multiple"},
                  "items": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/YamlDocument"}}
                }}
              ]
            }
          },
          "${'$'}defs": {
            "YamlDocument": {
              "oneOf": [
                {"type": "object", "required": ["str"], "properties": {"str": {"type": "string"}}},
                {"type": "object", "required": ["int"], "properties": {"int": {"type": "integer"}}},
                {"type": "object", "required": ["real"], "properties": {"real": {"type": "number"}}},
                {"type": "object", "required": ["bool"], "properties": {"bool": {"type": "boolean"}}},
                {"type": "object", "required": ["null"], "properties": {"null": {"const": null}}},
                {"type": "object", "required": ["seq"], "properties": {"seq": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/YamlDocument"}}},
                {"type": "object", "required": ["map"], "properties": {"map": {"type": "object", "additionalProperties": {"${'$'}ref": "#/${'$'}defs/YamlDocument"}}}}
              ]
            }
          }
        }
    """.trimIndent()

    // ---- destination ----

    private fun encodeDestination(dest: WriteYamlDestination): JsonObject = when (dest) {
        is WriteYamlDestination.ToFile -> buildJsonObject {
            put("kind", "file")
            put("path", dest.path)
            put("overwrite", dest.overwrite)
        }
        WriteYamlDestination.ToText -> buildJsonObject {
            put("kind", "text")
        }
    }

    private fun decodeDestination(obj: JsonObject): WriteYamlDestination {
        val kind = obj.getValue("kind").jsonPrimitive.content
        return when (kind) {
            "file" -> WriteYamlDestination.ToFile(
                path = obj.getValue("path").jsonPrimitive.content,
                overwrite = obj["overwrite"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
            "text" -> WriteYamlDestination.ToText
            else -> error("core-utils.writeYaml: unknown destination kind '$kind'")
        }
    }

    // ---- payload ----

    private fun encodePayload(p: WriteYamlPayload): JsonObject = when (p) {
        is WriteYamlPayload.Single -> buildJsonObject {
            put("kind", "single")
            put("value", YamlDocumentCodec.encodeDocument(p.value))
        }
        is WriteYamlPayload.Multiple -> buildJsonObject {
            put("kind", "multiple")
            put("items", kotlinx.serialization.json.JsonArray(p.documents.map { YamlDocumentCodec.encodeDocument(it) }))
        }
    }

    private fun decodePayload(obj: JsonObject): WriteYamlPayload {
        val kind = obj.getValue("kind").jsonPrimitive.content
        return when (kind) {
            "single" -> WriteYamlPayload.Single(
                value = YamlDocumentCodec.decodeDocument(obj.getValue("value")),
            )
            "multiple" -> WriteYamlPayload.Multiple(
                documents = obj.getValue("items").let { arr ->
                    @Suppress("UNCHECKED_CAST")
                    (arr as kotlinx.serialization.json.JsonArray).map { YamlDocumentCodec.decodeDocument(it) }
                },
            )
            else -> error("core-utils.writeYaml: unknown payload kind '$kind'")
        }
    }
}

/**
 * JSON codec for [WriteYamlOutput].
 */
object CoreUtilsWriteYamlOutputCodec : StepCodec<WriteYamlOutput> {

    override fun encode(value: WriteYamlOutput): EncodedStepValue {
        val obj = buildJsonObject {
            put("wroteToFile", value.wroteToFile)
            put("text", value.text)
            put("absolutePath", value.absolutePath)
            put("byteSize", value.byteSize)
            put("sha256Hex", value.sha256Hex)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): WriteYamlOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val wroteToFile = obj.getValue("wroteToFile").jsonPrimitive.content.toBoolean()
        // text may be JSON null (encoded explicitly) or absent; both must be
        // treated as "no text". Treat JSON null and missing the same way.
        val text: String? = when (val t = obj["text"]) {
            null, kotlinx.serialization.json.JsonNull -> null
            else -> t.jsonPrimitive.content
        }
        val absolutePath: String? = when (val a = obj["absolutePath"]) {
            null, kotlinx.serialization.json.JsonNull -> null
            else -> a.jsonPrimitive.content
        }
        val byteSize = obj["byteSize"]?.jsonPrimitive?.content?.toLongOrNull()
        val sha256Hex = obj["sha256Hex"]?.jsonPrimitive?.content

        // XOR check: a file write must NOT carry text; a text return must
        // NOT carry a path. A corrupt journal that violates these fails closed.
        if (wroteToFile && text != null) {
            error("core-utils.writeYaml: wroteToFile=true but 'text' is non-null (corrupt journal?)")
        }
        if (!wroteToFile && absolutePath != null) {
            error("core-utils.writeYaml: wroteToFile=false but 'absolutePath' is non-null (corrupt journal?)")
        }
        if (!wroteToFile && text == null) {
            error("core-utils.writeYaml: text return is missing 'text' (corrupt journal?)")
        }
        if (wroteToFile && absolutePath == null) {
            error("core-utils.writeYaml: file write is missing 'absolutePath' (corrupt journal?)")
        }
        return WriteYamlOutput(
            wroteToFile = wroteToFile,
            text = text,
            absolutePath = absolutePath,
            byteSize = byteSize,
            sha256Hex = sha256Hex,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "WriteYamlOutput",
          "type": "object",
          "required": ["wroteToFile"],
          "properties": {
            "wroteToFile": {"type": "boolean"},
            "text": {"type": ["string", "null"]},
            "absolutePath": {"type": ["string", "null"]},
            "byteSize": {"type": ["integer", "null"]},
            "sha256Hex": {"type": ["string", "null"]}
          }
        }
    """.trimIndent()
}

/**
 * Shared YamlDocument ↔ JsonElement codec (also used by ReadYamlOutput).
 *
 * Extracted so the Read and Write codecs agree on the canonical encoding of
 * the closed YamlDocument ADT. Without this, the two codecs would drift and
 * a value read by `readYaml` could not be re-encoded by `writeYaml` without
 * a transformation step.
 */
internal object YamlDocumentCodec {

    fun encodeDocument(doc: YamlDocument): kotlinx.serialization.json.JsonElement =
        when (doc) {
            is YamlDocument.Str -> buildJsonObject { put("str", doc.value) }
            is YamlDocument.Integer -> buildJsonObject { put("int", doc.value) }
            is YamlDocument.Real -> buildJsonObject { put("real", doc.value) }
            is YamlDocument.Bool -> buildJsonObject { put("bool", doc.value) }
            YamlDocument.Null -> buildJsonObject { put("null", kotlinx.serialization.json.JsonNull) }
            is YamlDocument.Seq -> buildJsonObject {
                put("seq", kotlinx.serialization.json.JsonArray(doc.items.map { encodeDocument(it) }))
            }
            is YamlDocument.Map -> buildJsonObject {
                put("map", buildJsonObject {
                    doc.entries.forEach { e -> put(e.key, encodeDocument(e.value)) }
                })
            }
        }

    fun decodeDocument(el: kotlinx.serialization.json.JsonElement): YamlDocument {
        val obj = el.jsonObject
        val keys = obj.keys.toList()
        if (keys.size != 1) {
            error("core-utils: YamlDocument envelope has ${keys.size} discriminator fields, expected 1 (keys=$keys)")
        }
        return when (val key = keys.single()) {
            "str" -> YamlDocument.Str(obj.getValue("str").jsonPrimitive.content)
            "int" -> YamlDocument.Integer(obj.getValue("int").jsonPrimitive.content.toLong())
            "real" -> YamlDocument.Real(obj.getValue("real").jsonPrimitive.content.toDouble())
            "bool" -> YamlDocument.Bool(obj.getValue("bool").jsonPrimitive.content.toBoolean())
            "null" -> YamlDocument.Null
            "seq" -> YamlDocument.Seq(obj.getValue("seq").let { arr ->
                @Suppress("UNCHECKED_CAST")
                (arr as kotlinx.serialization.json.JsonArray).map { decodeDocument(it) }
            })
            "map" -> {
                val mapObj = obj.getValue("map").jsonObject
                YamlDocument.Map(mapObj.entries.map { (k, v) -> YamlDocument.Map.Entry(k, decodeDocument(v)) })
            }
            else -> error("core-utils: unknown YamlDocument discriminator '$key'")
        }
    }
}
