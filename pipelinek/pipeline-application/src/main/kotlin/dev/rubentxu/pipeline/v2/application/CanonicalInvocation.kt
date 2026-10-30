package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Structural, pre-decode canonical invocation
 * This is the durable, extensible input form for routing and execution: a Step identified by its
 * [PluginStepId] together with its raw canonical payload, carried exactly as the engine persisted it.
 * It is deliberately structural: the selection of a concrete Step happens on form/schema/type
 * BEFORE any concrete Step decode.
 * [encodedInput] is derived losslessly and step-agnostically from [VersionedStepPayload.encoded], so
 * fingerprint/journal/operation-identity authority over the durable payload is untouched. It is NOT a
 * reconstruction from a decoded command (no `when(key){ Echo -> text }` bridging).
 * The registry strategy feeds [encodedInput] unchanged to `StepDefinition.inputCodec`.
*/
data class CanonicalInvocation(
    val stepKey: PluginStepId,
    val schemaVersion: String,
    val encodedInput: EncodedStepValue,
) {
    companion object {
        /** Structural projection of a compiler [StepNode] before any canonical decode. */
        fun fromNode(node: StepNode): CanonicalInvocation = CanonicalInvocation(
            stepKey = node.pluginStepId,
            schemaVersion = node.payload.schemaVersion,
            encodedInput = EncodedStepValue(node.payload.encoded),
        )
    }
}

/**
 * Outcome of the structural (pre-decode) phase of an invocation
 * Structural preparation validates only what a step-agnostic envelope gate may decide before any
 * concrete Step semantics run: the canonical payload schema version and that the encoded payload is a
 * well-formed dsl-v1 JSON object. It is deliberately structural, so a structurally-invalid node is
 * rejected as `SCHEMA` without ever entering typed field extraction. This is what keeps the
 * decode-first laws intact: typed decode happens later, behind the durable resolution.
 * A [Ready] carries the structural [CanonicalInvocation] (stepKey + schemaVersion + lossless
 * [EncodedStepValue]); durable resolution and typed decode consume it. A [Rejected] carries the reason
 * the envelope gate rejected the node (terminal `SCHEMA`, never the executor).
*/
sealed interface StructuralPreparation {
    /**
     * Envelope validation succeeded. [invocation] is the structural (pre-decode) invocation and
     * [envelope] is the structurally-validated dsl-v1 JSON object, so pre-reconcile control
     * projection never needs a typed command. The envelope is NOT a typed Step input: consuming it
     * for concrete Step semantics is the later TypedInputDecode's job.
*/
    data class Ready(
        val invocation: CanonicalInvocation,
        val envelope: JsonObject,
    ) : StructuralPreparation

    data class Rejected(val reason: String) : StructuralPreparation
}

/** Step-agnostic envelope gate that yields a [StructuralPreparation] without concrete decode. */
object CanonicalStructuralPreparation {
    private const val SCHEMA_VERSION = "dsl-v1"

    /**
     * Validates the canonical envelope of [node]: schema version must be `dsl-v1` and the encoded
     * payload must parse as a JSON object. Anything else is a terminal [StructuralPreparation.Rejected].
     * No field is read, so no concrete Step semantics run here.
*/
    fun prepare(node: StepNode): StructuralPreparation {
        if (node.payload.schemaVersion != SCHEMA_VERSION) {
            return StructuralPreparation.Rejected(
                "Unsupported step payload schema '${node.payload.schemaVersion}' for '${node.id.value}'",
            )
        }
        val element = try {
            Json.parseToJsonElement(node.payload.encoded)
        } catch (e: IllegalArgumentException) {
            return StructuralPreparation.Rejected(
                "Malformed canonical payload for '${node.id.value}': ${e.message}",
            )
        }
        if (element !is JsonObject) {
            return StructuralPreparation.Rejected(
                "Canonical payload for '${node.id.value}' must be a JSON object",
            )
        }
        return StructuralPreparation.Ready(CanonicalInvocation.fromNode(node), element)
    }
}

