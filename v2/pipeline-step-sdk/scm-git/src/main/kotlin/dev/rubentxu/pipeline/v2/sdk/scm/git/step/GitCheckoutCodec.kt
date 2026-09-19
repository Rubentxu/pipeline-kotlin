package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [GitCheckoutInput] (LFC-2E2 / F5.1).
 *
 * Uses kotlinx.serialization runtime JSON (no plugin) following the
 * canonical pattern in `pipeline-application/durable/credentials`.
 * `encodeDefaults = true` so codec roundtrips preserve optional fields
 * even when the caller leaves them at their default value (a codec that
 * drops optional fields on encode fails the canonical-envelope contract).
 */
object GitCheckoutInputCodec : StepCodec<GitCheckoutInput> {

    override fun encode(value: GitCheckoutInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("url", value.url)
            put("branch", value.branch)
            if (value.credentialsRef != null) put("credentialsRef", value.credentialsRef)
            put("changelog", value.changelog)
            put("poll", value.poll)
            put("relativeTargetDir", value.relativeTargetDir)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): GitCheckoutInput {
        val element = Json.parseToJsonElement(encoded.value)
        val obj = element.jsonObject
        return GitCheckoutInput(
            url = obj.string("url"),
            branch = obj.stringOr("branch") ?: "master",
            credentialsRef = obj.stringOr("credentialsRef"),
            changelog = obj.boolOr("changelog") ?: true,
            poll = obj.boolOr("poll") ?: true,
            relativeTargetDir = obj.stringOr("relativeTargetDir") ?: ".",
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "GitCheckoutInput",
          "type": "object",
          "required": ["url"],
          "properties": {
            "url": {"type": "string"},
            "branch": {"type": "string", "default": "master"},
            "credentialsRef": {"type": ["string", "null"]},
            "changelog": {"type": "boolean", "default": true},
            "poll": {"type": "boolean", "default": true},
            "relativeTargetDir": {"type": "string", "default": "."}
          }
        }
    """.trimIndent()

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun JsonObject.stringOr(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolOr(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}

/**
 * JSON codec for [GitCheckoutOutput]. Symmetric to the input codec so a
 * handler-side failure that returns the typed value, then runs through
 * the boundary, lands at the same JSON shape on the wire.
 */
object GitCheckoutOutputCodec : StepCodec<GitCheckoutOutput> {
    override fun encode(value: GitCheckoutOutput): EncodedStepValue {
        val obj = buildJsonObject {
            put("resolvedSha", value.resolvedSha)
            put("localPath", value.localPath)
            put("wasCloned", value.wasCloned)
            put("credentialApplied", value.credentialApplied)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): GitCheckoutOutput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return GitCheckoutOutput(
            resolvedSha = obj.getValue("resolvedSha").jsonPrimitive.content,
            localPath = obj.getValue("localPath").jsonPrimitive.content,
            wasCloned = obj.getValue("wasCloned").jsonPrimitive.boolean,
            credentialApplied = obj.getValue("credentialApplied").jsonPrimitive.boolean,
        )
    }
}
