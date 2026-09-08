package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

/**
 * Structural, pre-decode canonical invocation (B1.2c2-CDE.1).
 *
 * This is the durable, extensible input form for routing and execution: a Step identified by its
 * [PluginStepId] together with its raw canonical payload, carried exactly as the engine persisted it.
 * It deliberately predates [CanonicalCoreStepCommand] (the closed, already-decoded legacy/core world),
 * so registry-vs-legacy selection can happen on form/schema/type before any concrete Step decode.
 *
 * [encodedInput] is derived losslessly and step-agnostically from [VersionedStepPayload.encoded], so
 * fingerprint/journal/operation-identity authority over the durable payload is untouched. It is NOT a
 * reconstruction from a decoded command (no `when(key){ Echo -> text }` bridging).
 *
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
