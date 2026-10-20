package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.intOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredLong
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import kotlinx.serialization.json.buildJsonObject
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
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged (roundtrip byte-identical to pre-refactor).
 */
object CoreUtilsReadYamlInputCodec : StepCodec<ReadYamlInput> {

    override fun encode(value: ReadYamlInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
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
    )

    override fun decode(encoded: EncodedStepValue): ReadYamlInput {
        val obj = PipelineJson.decode(encoded)
        val sourceJsonObj = obj["source"]!!.jsonObject
        val kind = sourceJsonObj.requiredString("kind")
        val source: ReadYamlSource = when (kind) {
            "file" -> ReadYamlSource.FromFile(
                path = sourceJsonObj.requiredString("path"),
            )
            "text" -> ReadYamlSource.FromText(
                text = sourceJsonObj.requiredString("text"),
            )
            else -> error("core-utils.readYaml: unknown source kind '$kind' (expected 'file' or 'text')")
        }
        val cpl = obj.intOrNull("codePointLimit")
        val mac = obj.intOrNull("maxAliasesForCollections")
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
 *
 * D-012: migrated to `PipelineJson` / `JsonAccessors`.
 */
object CoreUtilsReadYamlOutputCodec : StepCodec<ReadYamlOutput> {

    override fun encode(value: ReadYamlOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("multipleDocuments", value.multipleDocuments)
            put("byteSize", value.byteSize)
            put("absolutePath", value.absolutePath)
            if (value.single != null) {
                put("single", YamlDocumentCodec.encodeDocument(value.single))
            }
            if (value.documents != null) {
                put("documents", buildJsonObject {
                    put("items", kotlinx.serialization.json.JsonArray(value.documents.map { YamlDocumentCodec.encodeDocument(it) }))
                })
            }
        }
    )

    override fun decode(encoded: EncodedStepValue): ReadYamlOutput {
        val obj = PipelineJson.decode(encoded)
        val multiple = obj.requiredString("multipleDocuments").toBoolean()
        val singleEl = obj["single"]
        val docsEl = obj["documents"]
        val single: YamlDocument? = if (singleEl != null) YamlDocumentCodec.decodeDocument(singleEl) else null
        val documents: List<YamlDocument>? = if (docsEl != null) {
            val items = docsEl.jsonObject.getValue("items").jsonArray
            items.map { YamlDocumentCodec.decodeDocument(it) }
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
            byteSize = obj.requiredLong("byteSize"),
            absolutePath = obj.stringOrNull("absolutePath"),
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
}
