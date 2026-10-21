package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Metadata bundle for a canonical step — carries the durable execution contract.
 *
 * @property effects The side-effect classification of the step.
 * @property replayPolicy The replay strategy for the step.
 * @property recoveryPolicy Declared recovery behaviour of the operation; the durable
 *   protocol reads this typed property and never a concrete Step name.
 */
data class StepMetadata(
    val effects: Set<Effect>,
    val replayPolicy: ReplayPolicy,
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.None,
)

/** Typed command decoded from the `dsl-v1` payloads owned by the canonical IR. */
sealed interface CanonicalCoreStepCommand {
    val pluginId: String

    /**
     * Durable metadata for this command, resolved from [pluginId] by the single legacy authority
     * [CanonicalCoreStepMetadata]. Constant per plugin, so it needs no decoded field.
     */
    val defaultMetadata: StepMetadata
        get() = CanonicalCoreStepMetadata.metadata(pluginId)

    companion object {
        /**
         * Closed legacy-core canonical COMMAND plugin IDs — the set of plugin keys the canonical
         * dispatcher routes through the legacy decode + dispatch family (StructuralStepFamily.LegacyCore).
         *
         * Registry-routed core plugins (e.g. `core.echo` and `core.sh`) are deliberately excluded:
         * they live behind the open [dev.rubentxu.pipeline.v2.domain.step.StepRegistry]
         * registered via [CoreStepRegistryFactory] and classify as StructuralStepFamily.Registry.
         *
         * This constant does NOT mean "all core plugins" — only the legacy executable ones.
         * Renamed from `ALL_PLUGIN_IDS` to [LEGACY_PLUGIN_IDS] so the semantic boundary is
         * unmistakable.
         */
        val LEGACY_PLUGIN_IDS: Set<String> = setOf(
            // The set is intentionally empty: every executable core plugin now lives behind the
            // registry, so the canonical decoder routes all of them through the `else` arm of
            // [decode]. The raw `core.<x>` envelopes remain the structural control protocol for
            // project overlay/projection (pre-decode control context) and for the registry steps
            // that consume them — they are not here.
        )

        /** Derives the short type string from a pluginId (e.g. "core.sh" → "sh"). */
        fun pluginIdToShortType(pluginId: String): String = CanonicalCoreStepMetadata.shortType(pluginId)
    }

    /**
     * the legacy `EmitEvent` command subtype was removed.
     * Production routing is exclusively `CoreEmitEventStep.definition` via the registry.
     * The RAW `core.emit.event` envelope remains the structural control protocol for
     * catchError: `StructuralOverlayProjection` projects push/pop pre-decode from it —
     * removing the legacy execution path does not remove the structural control protocol.
     */

    /**
     * the legacy `Milestone` command subtype was removed.
     * Production routing is exclusively `CoreMilestoneStep.definition` via the registry.
     * The DSL `milestone(ordinal, label)` lowers to `StepSpec.RegistryStepSpec` carrying
     * the same canonical envelope (`{"kind":"milestone","ordinal":N,"label":...}`); the raw
     * `core.milestone` envelope is consumed structurally and executively by CoreMilestoneStep
     * through the registry — never here.
     */

    /**
     * Production authority for `deleteDir` is exclusively the registry
     * (CoreDeleteDirStep.definition via RegistryStepMetadataResolver). No legacy command
     * subtype exists; any legacy envelope falls through to the `else` rejection.
     */

    /**
     * Production authority for `cleanWs` is exclusively the registry
     * (CoreCleanWsStep.definition via RegistryStepMetadataResolver). No legacy command
     * subtype exists; any legacy envelope falls through to the `else` rejection.
     */

    /**
     * `core.load` execution authority in `local-core-v1` is DEFERRED + UNSUPPORTED: no
     * `CoreLoadStep` is registered, the DSL `load(path)` is fail-closed at compile-time, and
     * the canonical decoder does not recognise a Load data class. Any `core.load` envelope
     * falls through to the `else` rejection below — as it must, since there is no execution
     * authority.
     */

    /**
     * Production authority for `pwd` is exclusively the registry
     * (CorePwdStep.definition via RegistryStepMetadataResolver). The DSL `pwd()` /
     * `pwd(tmp=false)` lower to `StepSpec.RegistryStepSpec(core.pwd, ...)`; the canonical
     * decoder no longer recognises a Pwd data class. The raw `core.pwd` envelope is consumed
     * structurally (PwdResolved event, pre-decode) and executively by CorePwdStep.
     */

    /**
     * Production authority for `waitUntil` is exclusively the registry
     * (CoreWaitUntilStep.definition). The polling cadence is declared structurally via
     * `BodyExecutionPolicy.Retrying(waitUntil = ...)`, so the canonical decoder does not
     * recognise a WaitUntil data class. The canonical body engine dispatches the polling
     * loop by reading the sub-shape (projectBodyExecution), with no concrete-StepKey branch.
     * Any `core.waitUntil` envelope falls through to the `else` rejection below — as it
     * must, since the registry is the only execution authority.
     */

    /**
     * Production authority for `archiveArtifacts` is exclusively the registry
     * (CoreArchiveArtifactsStep.definition via CoreStepRegistryFactory). The DSL
     * `archiveArtifacts(...)` lowers to `StepSpec.RegistryStepSpec(core.archiveArtifacts, ...)`;
     * the canonical decoder no longer recognises an ArchiveArtifacts data class.
     */
}

/** Decodes a supported canonical core node without reconstructing the DSL model. */
object CanonicalCoreStepDecoder {
    private const val SCHEMA_VERSION = "dsl-v1"
    // Every constant `_PLUGIN_ID` for legacy executable core plugins has been removed. The raw
    // envelopes they used to recognise are now consumed structurally (pre-decode) and
    // executively by their corresponding registry steps — never here. Each affected step lowers
    // directly to `StepSpec.RegistryStepSpec(core.<x>, ...)`. Any envelope without a recognised
    // plugin key (including every legacy key) falls through to the `else` rejection in [decode].

    fun decode(node: StepNode): CanonicalCoreStepCommand {
        require(node.payload.schemaVersion == SCHEMA_VERSION) {
            "Unsupported step payload schema '${node.payload.schemaVersion}' for '${node.id.value}'"
        }
        val payload = Json.parseToJsonElement(node.payload.encoded).jsonObject
        return when (node.pluginStepId.value) {
            // Every legacy executable core-plugin branch has been removed; each enum key now
            // routes exclusively through the registry. The raw envelope for each key is consumed
            // structurally (pre-decode) and executively by the matching registry step. For
            // `core.pwd` the DSL `pwd()` / `pwd(tmp=false)` lower to
            // `StepSpec.RegistryStepSpec`. For `core.waitUntil` the polling cadence is declared
            // structurally via `BodyExecutionPolicy.Retrying(waitUntil = ...)` and decoded
            // through `projectBodyExecution`. For `core.archiveArtifacts` the registry step owns
            // the certified AntStyleGlob engine; the contract suite asserts behaviourally that
            // this decoder refuses the key.
            else -> throw IllegalArgumentException(
                "Unsupported core plugin step '${node.pluginStepId.value}' for '${node.id.value}'"
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) {
            "dsl-v1 payload requires string '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredBoolean(name: String): Boolean =
        requireNotNull(this[name]?.jsonPrimitive?.booleanOrNull) {
            "dsl-v1 payload requires boolean '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
        requireNotNull(this[name]?.jsonPrimitive?.content?.toLongOrNull()) {
            "dsl-v1 payload requires integer '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String): Int =
        requireNotNull(this[name]?.jsonPrimitive?.content?.toIntOrNull()) {
            "dsl-v1 payload requires integer '$name'"
        }
}
