package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [ReadYamlInput] (LFC-2E2 utilities OFFICIAL_PLUGIN).
 *
 * The sealed [ReadYamlSource] hierarchy is encoded as a discriminator field
 * `source.kind` with `path` / `text` per variant. The `file XOR text` invariant
 * is preserved by construction: a `decode` cannot produce a `FromFile` and
 * `FromText` simultaneously.
 */
object CoreUtilsReadYamlInputCodec : StepCodec<ReadYamlInput> {

    override fun encode(value: ReadYamlInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("codePointLimit", value.codePointLimit)
            put("maxAliasesForCollections", value.maxAliasesForCollections)
            val sourceObj = when (val s = value.source) {
                is ReadYamlSource.FromFile -> buildJsonObject {
                    put("kind", "file")
                    put("path", s.path)
                }
                is ReadYamlSource.FromText -> buildJsonObject {
                    put("kind", "text")
                    put("text", s.text)
                }
            }
            put("source", sourceObj)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): ReadYamlInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val sourceObj = obj.getValue("source").jsonObject
        val kind = sourceObj.getValue("kind").jsonPrimitive.content
        val source: ReadYamlSource = when (kind) {
            "file" -> ReadYamlSource.FromFile(
                path = sourceObj.getValue("path").jsonPrimitive.content,
            )
            "text" -> ReadYamlSource.FromText(
                text = sourceObj.getValue("text").jsonPrimitive.content,
            )
            else -> error("core-utils.readYaml: unknown source kind '$kind' (expected 'file' or 'text')")
        }
        val cpl = obj["codePointLimit"]?.jsonPrimitive?.intOrNull
        val mac = obj["maxAliasesForCollections"]?.jsonPrimitive?.intOrNull
        if (cpl != null && cpl <= 0) {
            error("core-utils.readYaml: codePointLimit must be > 0 (got $cpl)")
        }
        if (mac != null && mac <= 0) {
            error("core-utils.readYaml: maxAliasesForCollections must be > 0 (got $mac)")
        }
        return ReadYamlInput(
            source = source,
            codePointLimit = cpl,
            maxAliasesForCollections = mac,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "ReadYamlInput",
          "type": "object",
          "required": ["source"],
          "properties": {
            "source": {
              "oneOf": [
                {
                  "type": "object",
                  "required": ["kind", "path"],
                  "properties": {
                    "kind": {"const": "file"},
                    "path": {"type": "string"}
                  }
                },
                {
                  "type": "object",
                  "required": ["kind", "text"],
                  "properties": {
                    "kind": {"const": "text"},
                    "text": {"type": "string"}
                  }
                }
              ]
            },
            "codePointLimit": {"type": ["integer", "null"], "minimum": 1},
            "maxAliasesForCollections": {"type": ["integer", "null"], "minimum": 1}
          }
        }
    """.trimIndent()
}

/**
 * JSON codec for [ReadYamlOutput].
 *
 * Encodes the closed [YamlDocument] ADT as a tagged JSON object. Each variant
 * becomes a one-field object whose discriminator is the field name and whose
 * payload is the variant's data.
 *
 * The encoding roundtrip is total and lossless — the value returned from a
 * successful Step replayed through the codec reproduces the original ADT
 * shape, which is what makes replay safe.
 */
object CoreUtilsReadYamlOutputCodec : StepCodec<ReadYamlOutput> {

    override fun encode(value: ReadYamlOutput): EncodedStepValue {
        val obj = buildJsonObject {
            put("multipleDocuments", value.multipleDocuments)
            put("byteSize", value.byteSize)
            put("absolutePath", value.absolutePath)
            if (value.single != null) {
                put("single", encodeDocument(value.single))
            }
            if (value.documents != null) {
                put("documents", buildJsonObject {
                    put("items", kotlinx.serialization.json.JsonArray(value.documents.map { encodeDocument(it) }))
                })
            }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): ReadYamlOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        val multiple = obj.getValue("multipleDocuments").jsonPrimitive.content.toBoolean()
        val singleEl = obj["single"]
        val docsEl = obj["documents"]
        val single: YamlDocument? = if (singleEl != null) decodeDocument(singleEl) else null
        val documents: List<YamlDocument>? = if (docsEl != null) {
            val items = docsEl.jsonObject.getValue("items").jsonArray
            items.map { decodeDocument(it) }
        } else null
        // Enforce the XOR invariant at decode time too — protects against
        // corrupt or hand-edited journals.
        if (single != null && documents != null) {
            error("core-utils.readYaml: output envelope has both 'single' and 'documents' (corrupt journal?)")
        }
        if (!multiple && single == null) {
            error("core-utils.readYaml: output envelope is missing 'single' (corrupt journal?)")
        }
        if (multiple && documents == null) {
            error("core-utils.readYaml: output envelope is missing 'documents' (corrupt journal?)")
        }
        return ReadYamlOutput(
            single = single,
            documents = documents,
            multipleDocuments = multiple,
            byteSize = obj.getValue("byteSize").jsonPrimitive.content.toLong(),
            absolutePath = obj["absolutePath"]?.jsonPrimitive?.content,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "ReadYamlOutput",
          "type": "object",
          "required": ["multipleDocuments", "byteSize"],
          "properties": {
            "multipleDocuments": {"type": "boolean"},
            "byteSize": {"type": "integer"},
            "absolutePath": {"type": ["string", "null"]},
            "single": {"${'$'}ref": "#/${'$'}defs/YamlDocument"},
            "documents": {
              "type": "object",
              "required": ["items"],
              "properties": {
                "items": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/YamlDocument"}}
              }
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

    // ----- YamlDocument ↔ JsonElement -----

    private fun encodeDocument(doc: YamlDocument): kotlinx.serialization.json.JsonElement =
        when (doc) {
            is YamlDocument.Str -> kotlinx.serialization.json.buildJsonObject { put("str", doc.value) }
            is YamlDocument.Integer -> kotlinx.serialization.json.buildJsonObject { put("int", doc.value) }
            is YamlDocument.Real -> kotlinx.serialization.json.buildJsonObject { put("real", doc.value) }
            is YamlDocument.Bool -> kotlinx.serialization.json.buildJsonObject { put("bool", doc.value) }
            YamlDocument.Null -> kotlinx.serialization.json.buildJsonObject { put("null", kotlinx.serialization.json.JsonNull) }
            is YamlDocument.Seq -> kotlinx.serialization.json.buildJsonObject {
                put("seq", kotlinx.serialization.json.JsonArray(doc.items.map { encodeDocument(it) }))
            }
            is YamlDocument.Map -> kotlinx.serialization.json.buildJsonObject {
                put("map", buildJsonObject {
                    doc.entries.forEach { e -> put(e.key, encodeDocument(e.value)) }
                })
            }
        }

    private fun decodeDocument(el: kotlinx.serialization.json.JsonElement): YamlDocument {
        val obj = el.jsonObject
        // Exactly one discriminator field is present.
        val keys = obj.keys.toList()
        if (keys.size != 1) {
            error("core-utils.readYaml: YamlDocument envelope has ${keys.size} discriminator fields, expected 1 (keys=$keys)")
        }
        return when (val key = keys.single()) {
            "str" -> YamlDocument.Str(obj.getValue("str").jsonPrimitive.content)
            "int" -> YamlDocument.Integer(obj.getValue("int").jsonPrimitive.content.toLong())
            "real" -> YamlDocument.Real(obj.getValue("real").jsonPrimitive.content.toDouble())
            "bool" -> YamlDocument.Bool(obj.getValue("bool").jsonPrimitive.content.toBoolean())
            "null" -> YamlDocument.Null
            "seq" -> YamlDocument.Seq(obj.getValue("seq").jsonArray.map { decodeDocument(it) })
            "map" -> {
                val mapObj = obj.getValue("map").jsonObject
                YamlDocument.Map(mapObj.entries.map { (k, v) -> YamlDocument.Map.Entry(k, decodeDocument(v)) })
            }
            else -> error("core-utils.readYaml: unknown YamlDocument discriminator '$key'")
        }
    }
}
