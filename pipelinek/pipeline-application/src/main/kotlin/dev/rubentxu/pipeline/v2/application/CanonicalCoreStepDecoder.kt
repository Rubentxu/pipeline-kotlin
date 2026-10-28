package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Metadata bundle for a canonical step — carries the durable execution contract.
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
         * The closed set of core plugin keys that belong to the legacy
         * decode + dispatch family (StructuralStepFamily.LegacyCore).
         *
         * **It is empty, and that is the finished state of the migration.**
         * Every core plugin that still executes is registry-routed: the
         * registry owns production routing and production metadata. The legacy
         * decoder has no branch left -- every core key that reaches it is
         * refused -- and the legacy dispatchers and metadata rows are gone
         * from the tree.
         *
         * The set is still read in two places, and it is read there on
         * purpose. The rule it encodes is about core semantics, not about the
         * current membership: a key in this set stays legacy EVEN IF a
         * definition with the same key is also registered, so registering a
         * definition can never change what a core key means. With no members
         * the rule cannot fire today; keeping the readers means that the day a
         * key is put back, the rule applies in both of them at once instead of
         * in one and not the other.
         */
        val LEGACY_PLUGIN_IDS: Set<String> = setOf(
        )

        /** Derives the short type string from a pluginId (e.g. "core.sh" → "sh"). */
        fun pluginIdToShortType(pluginId: String): String = CanonicalCoreStepMetadata.shortType(pluginId)
    }

    /**
     * The legacy `EmitEvent` command subtype was removed (LEGACY_REMOVED).
     * Production routing is exclusively `CoreEmitEventStep.definition` via the registry.
     * The RAW `core.emit.event` envelope remains the structural control protocol for
     * catchError: `StructuralOverlayProjection` projects push/pop pre-decode from it —
     * `legacy execution removed != structural control protocol removed`.
*/

    /**
     * The legacy `Milestone` command subtype was removed (LEGACY_REMOVED).
     * Production routing is exclusively `CoreMilestoneStep.definition` via the registry.
     * The DSL `milestone(ordinal, label)` now lowers to `StepSpec.RegistryStepSpec` carrying
     * the same canonical envelope (`{"kind":"milestone","ordinal":N,"label":...}`); the raw
     * `core.milestone` envelope is consumed structurally and executively by CoreMilestoneStep
     * through the registry — never here.
*/

    /**
     * DeleteDir step — removed, and the legacy path is gone.
     * Production authority is exclusively the registry (CoreDeleteDirStep.definition
     * via RegistryStepMetadataResolver). Legacy forms removed in this
     * slice: DeleteDir subtype, DELETE_DIR_PLUGIN_ID + decoder branch,
     * CanonicalCoreStepMetadata["core.deleteDir"] row, CanonicalDeleteDirNodeDispatcher.kt,
     * and the CanonicalNodeDispatcher deleteDir seams (field, when branch, deleteDirContext).
*/

    /**
     * CleanWs step — removed, and the legacy path is gone.
     * Production authority is exclusively the registry (CoreCleanWsStep.definition
     * via RegistryStepMetadataResolver). Legacy forms removed in this
     * slice: CleanWs subtype, CLEAN_WS_PLUGIN_ID + decoder branch,
     * CanonicalCoreStepMetadata["core.cleanWs"] row, CanonicalCleanWsNodeDispatcher.kt,
     * and the CanonicalNodeDispatcher cleanWs seams (field, when branch, cleanWsContext).
*/

    /**
     * Load step — DELETED at / (LEGACY_REMOVED). The `core.load` execution
     * authority in `local-core-v1` is DEFERRED + UNSUPPORTED: no `CoreLoadStep` is registered, the
     * DSL `load(path)` is fail-closed at compile-time, and the canonical decoder does not
     * recognise a Load data class. Any `core.load` envelope falls through to the `else` rejection
     * below — as it must, since there is no execution authority.
*/

    /**
     * Pwd step — DELETED at / (LEGACY_REMOVED). The `core.pwd` execution
     * authority is now exclusively the registry (CorePwdStep.definition via
     * RegistryStepMetadataResolver). The DSL `pwd()` / `pwd(tmp=false)` lower to
     * `StepSpec.RegistryStepSpec(core.pwd,...)`; the canonical decoder
     * no longer recognises a Pwd data class. The raw `core.pwd` envelope is consumed
     * structurally (PwdResolved event, pre-decode) and executively by CorePwdStep.
*/

    /**
     * WaitUntil step — DELETED at / (LEGACY_REMOVED). The execution
     * authority is now exclusively the registry (CoreWaitUntilStep.definition). The polling
     * cadence is declared structurally via `BodyExecutionPolicy.Retrying(waitUntil = ...)`,
     * so the canonical decoder does not recognise a WaitUntil data class. The canonical body
     * engine dispatches the polling loop by reading the sub-shape (projectBodyExecution),
     * with no concrete-StepKey branch. Any `core.waitUntil` envelope falls through to the
     * `else` rejection below — as it must, since the registry is the only execution authority.
*/

    /**
     * ArchiveArtifacts step — DELETED at / (LEGACY_REMOVED). The
     * `core.archiveArtifacts` execution authority is now exclusively the registry
     * (CoreArchiveArtifactsStep.definition via CoreStepRegistryFactory). The DSL
     * `archiveArtifacts(...)` lowers to `StepSpec.RegistryStepSpec(core.archiveArtifacts, ...)`;
     * the canonical decoder no longer recognises an ArchiveArtifacts data class.
*/
}

/** Decodes a supported canonical core node without reconstructing the DSL model. */
object CanonicalCoreStepDecoder {
    private const val SCHEMA_VERSION = "dsl-v1"
    // EMIT_EVENT_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    // MILESTONE_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED). The raw
    // core.milestone envelope is consumed structurally (pre-decode) and executively by
    // CoreMilestoneStep via the registry — never here.
    // CLEAN_WS_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    // The raw core.cleanWs envelope is consumed structurally (WsCleaned event, pre-decode)
    // and executively by CoreCleanWsStep via the registry — never here. The DSL `cleanWs(...)`
    // Lowers directly to StepSpec.RegistryStepSpec
    // LOAD_PLUGIN_ID removed (LEGACY_REMOVED). The `core.load`
    // execution authority in `local-core-v1` is DEFERRED + UNSUPPORTED. The DSL `load(path)`
    // is fail-closed at compile-time; any legacy `core.load` envelope falls through to the
    // `else` rejection below — as it must, since there is no execution authority.
    // WAIT_UNTIL_PLUGIN_ID removed (LEGACY_REMOVED). The
    // `core.waitUntil` execution authority is the registry (CoreWaitUntilStep.definition).
    // The polling cadence is declared structurally via `BodyExecutionPolicy.Retrying(waitUntil = ...)`;
    // the canonical decoder does not recognise a WaitUntil data class. Any legacy `core.waitUntil`
    // envelope falls through to the `else` rejection below — as it must, since the registry is
    // the only execution authority.
    // ARCHIVE_ARTIFACTS_PLUGIN_ID removed with the legacy branch
    // (LEGACY_REMOVED). The raw core.archiveArtifacts dsl-v1 envelope is consumed executively by
    // CoreArchiveArtifactsStep via the registry — never here. The envelope SHAPE is preserved
    // byte-identically (pinned by the contract suite's codec row); only the legacy decoder's
    // ability to recognise the key is destroyed, which the contract suite asserts behaviourally
    // as an `Unsupported core plugin step` rejection.

    fun decode(node: StepNode): CanonicalCoreStepCommand {
        require(node.payload.schemaVersion == SCHEMA_VERSION) {
            "Unsupported step payload schema '${node.payload.schemaVersion}' for '${node.id.value}'"
        }
        val payload = Json.parseToJsonElement(node.payload.encoded).jsonObject
        return when (node.pluginStepId.value) {
            // EMIT_EVENT_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.emit.event envelope is consumed structurally (StructuralOverlayProjection,
            // pre-decode) and executively by CoreEmitEventStep via the registry — never here.
            // MILESTONE_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.milestone envelope is consumed structurally and executively by
            // CoreMilestoneStep via the registry — never here.
            // DELETE_DIR_PLUGIN_ID decoder branch removed (LEGACY_REMOVED).
            // CLEAN_WS_PLUGIN_ID decoder branch removed (LEGACY_REMOVED).
            // The raw core.cleanWs envelope is consumed structurally (WsCleaned event,
            // pre-decode) and executively by CoreCleanWsStep via the registry — never here.
            // LOAD_PLUGIN_ID branch removed (LEGACY_REMOVED).
            // The `core.load` execution authority is DEFERRED + UNSUPPORTED in `local-core-v1`;
            // any `core.load` envelope falls through to the `else` rejection below.
            // PWD_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw core.pwd
            // envelope is consumed structurally (PwdResolved event, pre-decode) and
            // executively by CorePwdStep via the registry — never here. The DSL
            // `pwd()` and `pwd(tmp=false)` lower to StepSpec.RegistryStepSpec
            // IS_UNIX_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.isUnix envelope is consumed structurally (UnixDetected event, pre-decode)
            // and executively by CoreIsUnixStep via the registry — never here.
            // WAIT_UNTIL_PLUGIN_ID branch removed (LEGACY_REMOVED).
            // The `core.waitUntil` execution authority is the registry (CoreWaitUntilStep.definition).
            // The polling cadence is declared structurally via
            // `BodyExecutionPolicy.Retrying(waitUntil = ...)`; any `core.waitUntil` envelope
            // falls through to the `else` rejection below — as it must, since the registry
            // is the only execution authority.
            // ARCHIVE_ARTIFACTS_PLUGIN_ID branch removed
            // (LEGACY_REMOVED). A core.archiveArtifacts node now falls through to the
            // `else` rejection below — as it must, since the registry and not this decoder
            // is its execution authority. That rejection is asserted behaviourally by the
            // contract suite (no silent fall-through to a no-op).
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



