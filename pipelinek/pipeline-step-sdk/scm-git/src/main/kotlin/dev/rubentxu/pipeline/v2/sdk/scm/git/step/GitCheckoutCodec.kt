package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.boolOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredBoolean
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON codec for [GitCheckoutInput] (LFC-2E2 / F5.1).
 *
 * Uses kotlinx.serialization runtime JSON (no plugin) following the
 * canonical pattern in `pipeline-application/durable/credentials`.
 * `encodeDefaults = true` so codec roundtrips preserve optional fields
 * even when the caller leaves them at their default value (a codec that
 * drops optional fields on encode fails the canonical-envelope contract).
 *
 * C3 / D-011 (audit 2026-09-26, H14): migrated to `PipelineJson` /
 * `JsonAccessors`. Wire format unchanged.
 */
object GitCheckoutInputCodec : StepCodec<GitCheckoutInput> {

    override fun encode(value: GitCheckoutInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("url", value.url)
            put("branch", value.branch)
            if (value.credentialsRef != null) put("credentialsRef", value.credentialsRef)
            put("changelog", value.changelog)
            put("poll", value.poll)
            put("relativeTargetDir", value.relativeTargetDir)
        }
    )

    override fun decode(encoded: EncodedStepValue): GitCheckoutInput {
        val obj = PipelineJson.decode(encoded)
        return GitCheckoutInput(
            url = obj.requiredString("url"),
            branch = obj.stringOrNull("branch") ?: "master",
            credentialsRef = obj.stringOrNull("credentialsRef"),
            changelog = obj.boolOrNull("changelog") ?: true,
            poll = obj.boolOrNull("poll") ?: true,
            relativeTargetDir = obj.stringOrNull("relativeTargetDir") ?: ".",
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
}

/**
 * JSON codec for [GitCheckoutOutput]. Symmetric to the input codec so a
 * handler-side failure that returns the typed value, then runs through
 * the boundary, lands at the same JSON shape on the wire. Migrated to
 * `PipelineJson` (C3 / D-011).
 */
object GitCheckoutOutputCodec : StepCodec<GitCheckoutOutput> {
    override fun encode(value: GitCheckoutOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("resolvedSha", value.resolvedSha)
            put("localPath", value.localPath)
            put("wasCloned", value.wasCloned)
            put("credentialApplied", value.credentialApplied)
        }
    )

    override fun decode(encoded: EncodedStepValue): GitCheckoutOutput {
        val obj = PipelineJson.decode(encoded)
        return GitCheckoutOutput(
            resolvedSha = obj.requiredString("resolvedSha"),
            localPath = obj.requiredString("localPath"),
            wasCloned = obj.requiredBoolean("wasCloned"),
            credentialApplied = obj.requiredBoolean("credentialApplied"),
        )
    }
}
