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
 * @property recoveryPolicy Declared recovery behaviour of the operation (CDE.2-b4); the durable
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
     * [CanonicalCoreStepMetadata] (CDE.2-b1). Constant per plugin, so it needs no decoded field.
     */
    val defaultMetadata: StepMetadata
        get() = CanonicalCoreStepMetadata.metadata(pluginId)

    companion object {
        /**
         * Closed legacy-core canonical COMMAND plugin IDs — the set of plugin keys the canonical
         * dispatcher routes through the legacy decode + dispatch family (StructuralStepFamily.LegacyCore).
         *
         * Registry-routed core plugins (e.g. `core.echo`, and — since LB-02 / A4 — `core.sh`) are
         * deliberately excluded: they live behind the open [dev.rubentxu.pipeline.v2.domain.step.StepRegistry]
         * registered via [CoreStepRegistryFactory] and classify as StructuralStepFamily.Registry.
         *
         * Naming: this constant does NOT mean "all core plugins" — only the legacy executable ones.
         * S3.3 renamed the authority from `ALL_PLUGIN_IDS` to [LEGACY_PLUGIN_IDS] to make the
         * semantic boundary unmistakable.
         *
         * LB-02 / A4 (REGISTRY_PRIMARY flip): `"core.sh"` is removed from this set so the
         * structural classifier routes it through `Registry` (via `StructuralFamilyResolver.classify`).
         * LB-02 / S6 burn-down removed the legacy `CanonicalCoreStepCommand.Shell` subtype, the
         * legacy Sh decoder branch, the `CanonicalShellNodeDispatcher`, and the
         * `CanonicalCoreStepMetadata["core.sh"]` row. Production Sh metadata is read exclusively
         * from `CoreShellStep.descriptor` via `RegistryStepMetadataResolver`.
         */
        val LEGACY_PLUGIN_IDS: Set<String> = setOf(
            // "core.error" removed at LFC-2E1-S2-A1 / G5 (2026-09-11T10:34Z).
            // The production routing authority flipped to the registry path
            // (CoreErrorStep.definition). The legacy `core.error` source code
            // remains physically present until G6 deletes it (LEGACY_REMOVED):
            //   - CanonicalCoreStepCommand.Error subtype
            //   - ERROR_PLUGIN_ID decoder branch
            //   - CanonicalErrorNodeDispatcher.kt file
            //   - CanonicalCoreStepMetadata["core.error"] row
            // Until G6 the legacy dispatcher is unreachable in production but
            // still type-loadable; the parity test (G3) can still drive it.
            // S2-A4 / G4 (2026-09-11): "core.emit.event" removed — REGISTRY_PRIMARY flip.
            // Production routing authority is now CoreEmitEventStep.definition via the open
            // registry (CoreStepRegistryFactory). Legacy `core.emit.event` source remains
            // physically present until G5 (LEGACY_UNREACHABLE, not LEGACY_REMOVED):
            //   - CanonicalCoreStepCommand.EmitEvent subtype
            //   - EMIT_EVENT_PLUGIN_ID decoder branch
            //   - CanonicalEmitEventNodeDispatcher.kt + CanonicalNodeDispatcher emitEvent branch
            //   - CanonicalCoreStepMetadata["core.emit.event"] row
            // StructuralOverlayProjection keeps its own structural dependency on the
            // "core.emit.event" key (pre-decode control context), independent of the legacy
            // execution path.
            // S2-A5 / G4 (2026-09-12): "core.isUnix" removed — REGISTRY_PRIMARY flip.
            // Production routing authority is now CoreIsUnixStep.definition via the open
            // registry (CoreStepRegistryFactory). Legacy `core.isUnix` source remains
            // physically present until G5 (LEGACY_UNREACHABLE, not LEGACY_REMOVED):
            //   - CanonicalCoreStepCommand.IsUnix subtype
            //   - IS_UNIX_PLUGIN_ID decoder branch
            //   - CanonicalIsUnixNodeDispatcher.kt + CanonicalNodeDispatcher isUnix branch
            //   - CanonicalCoreStepMetadata["core.isUnix"] row
            // After this flip StructuralFamilyResolver routes core.isUnix through the
            // Registry family, and RegistryStepMetadataResolver reads metadata exclusively
            // from CoreIsUnixStep.descriptor. Legacy execution path remains type-loadable
            // for parity tests but is unreachable in production.
            // S2-A6 / G5 (2026-09-12): "core.pwd" legacy execution authority physically
            // deleted (LEGACY_REMOVED). All four legacy forms are gone:
            //   - CanonicalCoreStepCommand.Pwd subtype (line ~177, replaced with provenance comment)
            //   - PWD_PLUGIN_ID decoder branch (line ~278, replaced with provenance comment)
            //   - PWD_PLUGIN_ID constant (line ~219, replaced with provenance comment)
            //   - CanonicalCoreStepMetadata["core.pwd"] row (already removed in this slice)
            // The legacy CanonicalPwdNodeDispatcher.kt file is deleted in this slice;
            // CanonicalNodeDispatcher.pwd branch + pwdContext() helper are removed too.
            // Counter converges 6 / 8 / 8 -> 6 / 6 / 6 at the end of this slice.
            // S2-A7 / G4 (2026-09-12): "core.deleteDir" flipped REGISTRY_PRIMARY.
            // Removed from LEGACY_PLUGIN_IDS; StructuralFamilyResolver routes
            // core.deleteDir through the Registry family (CoreDeleteDirStep).
            // Legacy forms stay type-loadable but UNREACHABLE in production until
            // the G5 physical removal (command subtype, metadata row, dispatcher
            // file deleted at G5; counter converges 5/6/6 -> 5/5/5 there).
            // S2-A9 / G4 (2026-09-13): "core.milestone" flipped REGISTRY_PRIMARY.
            // S2-A9 / G5 (2026-09-13): "core.milestone" removed from LEGACY_PLUGIN_IDS
            // (LEGACY_REMOVED — physical destructive). Counter converges 4/5/5 -> 4/4/4.
            // Legacy forms (CanonicalCoreStepCommand.Milestone subtype, MILESTONE_PLUGIN_ID
            // decoder branch + constant, metadata row, CanonicalMilestoneNodeDispatcher.kt
            // file, CanonicalNodeDispatcher Milestone seams) are all removed in this slice.
            // Production routing is exclusively CoreMilestoneStep.definition via the registry.
            // S2-A10 / G4 (2026-09-13): "core.cleanWs" removed — REGISTRY_PRIMARY flip.
            // Production routing authority is now CoreCleanWsStep.definition via the open
            // registry (CoreStepRegistryFactory). Legacy `core.cleanWs` source remains
            // physically present until G5 (LEGACY_REMOVED, not LEGACY_UNREACHABLE):
            //   - CLEAN_WS_PLUGIN_ID decoder branch + constant
            //   - CanonicalCleanWsNodeDispatcher.kt file
            //   - CanonicalCoreStepMetadata["core.cleanWs"] row
            // Until G5 the legacy dispatcher is unreachable in production but still
            // type-loadable. Counter converges 4/4/4 -> 3/4/4 (ids only).
            // S2-A10 / G5 (2026-09-13): physical removal of legacy forms (LEGACY_REMOVED).
            // CleanWs subtype, CLEAN_WS_PLUGIN_ID constant + decoder branch,
            // CanonicalCoreStepMetadata["core.cleanWs"] row, CanonicalCleanWsNodeDispatcher.kt,
            // and the CanonicalNodeDispatcher cleanWs seams (field, when branch, cleanWsContext)
            // are all removed in this slice. Production routing is exclusively
            // CoreCleanWsStep.definition via the registry. Counter converges 3/4/4 -> 3/3/3.
            // S2-B10 / G4 (2026-09-13): "core.archiveArtifacts" removed — REGISTRY_PRIMARY flip.
            // Production routing authority is now CoreArchiveArtifactsStep.definition via the open
            // registry (CoreStepRegistryFactory). This flip is behaviourally observable, not just
            // structural: the legacy CanonicalArchiveArtifactsNodeDispatcher anchored its glob
            // against absolute paths (workspaceRoot = controlDirRoot.resolve("workspace")) and
            // therefore could never match, so `10-smoke-e2e.pipeline.kts` failed end-to-end with
            // `ArtifactArchiveFailed: No files matched glob pattern 'build/libs/*.jar'`. The
            // registry path uses the certified AntStyleGlob engine (frozen delta D1), which turns
            // CompatibilityCorpusTest.fixture10SmokeE2E green (runFixtureFail -> runFixturePass).
            // Evidence: docs/v2/07-uat/evidence/s2-b10-g2/fixture10-legacy-glob-defect.json
            // S2-B10 / G5 (2026-09-13): physical removal of legacy forms (LEGACY_REMOVED).
            // CanonicalCoreStepCommand.ArchiveArtifacts subtype, the ARCHIVE_ARTIFACTS_PLUGIN_ID
            // constant + decoder branch, the CanonicalCoreStepMetadata["core.archiveArtifacts"]
            // row, CanonicalArchiveArtifactsNodeDispatcher.kt, and the CanonicalNodeDispatcher
            // archiveArtifacts seams (field, when branch, archiveArtifactsContext) are all
            // removed in this slice. Production routing is exclusively
            // CoreArchiveArtifactsStep.definition via the registry. The behaviour-level proof
            // that the legacy glob authority is gone (not merely textually absent) is the
            // counter-preserved A/B: CompatibilityCorpusTest.fixture10SmokeE2E archives
            // build/libs/smoke.jar with a real sha256 where the legacy authority failed with
            // `No files matched glob pattern 'build/libs/*.jar'`. Counter converges
            // 2/3/3 -> 2/2/2.
            "core.load",
            "core.waitUntil",
        )

        /** Derives the short type string from a pluginId (e.g. "core.sh" → "sh"). */
        fun pluginIdToShortType(pluginId: String): String = CanonicalCoreStepMetadata.shortType(pluginId)
    }

    /**
     * S2-A4 / G5: the legacy `EmitEvent` command subtype was removed (LEGACY_REMOVED).
     * Production routing is exclusively `CoreEmitEventStep.definition` via the registry.
     * The RAW `core.emit.event` envelope remains the structural control protocol for
     * catchError: `StructuralOverlayProjection` projects push/pop pre-decode from it —
     * `legacy execution removed != structural control protocol removed`.
     */

    /**
     * S2-A9 / G5: the legacy `Milestone` command subtype was removed (LEGACY_REMOVED).
     * Production routing is exclusively `CoreMilestoneStep.definition` via the registry.
     * The DSL `milestone(ordinal, label)` now lowers to `StepSpec.RegistryStepSpec` carrying
     * the same canonical envelope (`{"kind":"milestone","ordinal":N,"label":...}`); the raw
     * `core.milestone` envelope is consumed structurally and executively by CoreMilestoneStep
     * through the registry — never here.
     */

    /**
     * T-05: deleteDir step — DELETED at S2-A7 / G5 (2026-09-12, LEGACY_REMOVED).
     * Production authority is exclusively the registry (CoreDeleteDirStep.definition
     * via RegistryStepMetadataResolver). Legacy forms removed in this
     * slice: DeleteDir subtype, DELETE_DIR_PLUGIN_ID + decoder branch,
     * CanonicalCoreStepMetadata["core.deleteDir"] row, CanonicalDeleteDirNodeDispatcher.kt,
     * and the CanonicalNodeDispatcher deleteDir seams (field, when branch, deleteDirContext()).
     */

    /**
     * T-05: cleanWs step — DELETED at S2-A10 / G5 (2026-09-13, LEGACY_REMOVED).
     * Production authority is exclusively the registry (CoreCleanWsStep.definition
     * via RegistryStepMetadataResolver). Legacy forms removed in this
     * slice: CleanWs subtype, CLEAN_WS_PLUGIN_ID + decoder branch,
     * CanonicalCoreStepMetadata["core.cleanWs"] row, CanonicalCleanWsNodeDispatcher.kt,
     * and the CanonicalNodeDispatcher cleanWs seams (field, when branch, cleanWsContext()).
     */

    /**
     * T-05: load step — reads and evaluates a pipeline script file in the workspace.
     * Re-entrant: subsequent calls with same (path, sha256) are skipped.
     */
    data class Load(
        val path: String,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.load"
    }

    /**
     * T-07: pwd step — DELETED at S2-A6 / G5 (LEGACY_REMOVED). The `core.pwd` execution
     * authority is now exclusively the registry (CorePwdStep.definition via
     * RegistryStepMetadataResolver). The DSL `pwd()` / `pwd(tmp=false)` lower to
     * `StepSpec.RegistryStepSpec(core.pwd, ...)` (S2-A6 / G3R); the canonical decoder
     * no longer recognises a Pwd data class. The raw `core.pwd` envelope is consumed
     * structurally (PwdResolved event, pre-decode) and executively by CorePwdStep.
     */

    /**
     * T-07: waitUntil step — polls a condition lambda until it returns true or deadline elapses.
     * @param initialRecurrencePeriod Initial poll interval in milliseconds (default 1000)
     * @param quiet If true, suppress output during polling
     */
    data class WaitUntil(
        val initialRecurrencePeriod: Long = 1000L,
        val quiet: Boolean = false,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.waitUntil"
    }

    /**
     * T-08: archiveArtifacts step — DELETED at S2-B10 / G5 (LEGACY_REMOVED). The
     * `core.archiveArtifacts` execution authority is now exclusively the registry
     * (CoreArchiveArtifactsStep.definition via CoreStepRegistryFactory). The DSL
     * `archiveArtifacts(...)` lowers to `StepSpec.RegistryStepSpec(core.archiveArtifacts, ...)`;
     * the canonical decoder no longer recognises an ArchiveArtifacts data class.
     */
}

/** Decodes a supported canonical core node without reconstructing the DSL model. */
object CanonicalCoreStepDecoder {
    private const val SCHEMA_VERSION = "dsl-v1"
    // S2-A4 / G5: EMIT_EVENT_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    // S2-A9 / G5: MILESTONE_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED). The raw
    // core.milestone envelope is consumed structurally (pre-decode) and executively by
    // CoreMilestoneStep via the registry — never here.
    // S2-A10 / G5 (2026-09-13): CLEAN_WS_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    // The raw core.cleanWs envelope is consumed structurally (WsCleaned event, pre-decode)
    // and executively by CoreCleanWsStep via the registry — never here. The DSL `cleanWs(...)`
    // lowers directly to StepSpec.RegistryStepSpec (S2-A10 / G5).
    private const val LOAD_PLUGIN_ID = "core.load"
    // S2-A6 / G5: PWD_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    // S2-A5 / G5: IS_UNIX_PLUGIN_ID removed with the legacy branch (LEGACY_REMOVED).
    private const val WAIT_UNTIL_PLUGIN_ID = "core.waitUntil"
    // S2-B10 / G5 (2026-09-13): ARCHIVE_ARTIFACTS_PLUGIN_ID removed with the legacy branch
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
            // S2-A4 / G5: EMIT_EVENT_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.emit.event envelope is consumed structurally (StructuralOverlayProjection,
            // pre-decode) and executively by CoreEmitEventStep via the registry — never here.
            // S2-A9 / G5: MILESTONE_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.milestone envelope is consumed structurally and executively by
            // CoreMilestoneStep via the registry — never here.
            // S2-A7 / G5 (2026-09-12): DELETE_DIR_PLUGIN_ID decoder branch removed (LEGACY_REMOVED).
            // S2-A10 / G5 (2026-09-13): CLEAN_WS_PLUGIN_ID decoder branch removed (LEGACY_REMOVED).
            // The raw core.cleanWs envelope is consumed structurally (WsCleaned event,
            // pre-decode) and executively by CoreCleanWsStep via the registry — never here.
            LOAD_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "load") {
                    "Payload kind must be 'load' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.Load(
                    path = payload.requiredString("path"),
                )
            }
            // S2-A6 / G5: PWD_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw core.pwd
            // envelope is consumed structurally (PwdResolved event, pre-decode) and
            // executively by CorePwdStep via the registry — never here. The DSL
            // `pwd()` / `pwd(tmp=false)` lower to StepSpec.RegistryStepSpec (S2-A6 / G3R).
            // S2-A5 / G5: IS_UNIX_PLUGIN_ID branch removed (LEGACY_REMOVED). The raw
            // core.isUnix envelope is consumed structurally (UnixDetected event, pre-decode)
            // and executively by CoreIsUnixStep via the registry — never here.
            WAIT_UNTIL_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "waitUntil") {
                    "Payload kind must be 'waitUntil' for '${node.id.value}'"
                }
                val initialRecurrencePeriod = payload["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
                val quiet = payload["quiet"]?.jsonPrimitive?.booleanOrNull ?: false
                CanonicalCoreStepCommand.WaitUntil(
                    initialRecurrencePeriod = initialRecurrencePeriod,
                    quiet = quiet,
                )
            }
            // S2-B10 / G5 (2026-09-13): ARCHIVE_ARTIFACTS_PLUGIN_ID branch removed
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
