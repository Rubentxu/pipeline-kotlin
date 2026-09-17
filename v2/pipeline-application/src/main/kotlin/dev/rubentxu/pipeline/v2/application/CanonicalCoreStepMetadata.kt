package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Single source of truth for the durable metadata of each canonical core plugin (CDE.2-b1).
 *
 * The metadata value is constant per pluginId. [CanonicalCoreStepCommand.defaultMetadata] delegates
 * here (so no duplicate literal is scattered across the 14 sealed subtypes), and CDE.2-b2+ lets the
 * durable resolution read this same authority by StepKey before any typed decode.
 *
 * This table is the compatibility authority of the legacy core world only. It is NOT the future
 * global registry authority: CDE.3/CDE.5 introduces the registry/definition metadata (a
 * `StepMetadataResolver` consumed by durable resolution over a composite of migrated definitions and
 * this legacy core table). Keeping it behind its own object leaves that seam open without coupling the
 * durable protocol to a specific core Step name.
 */
object CanonicalCoreStepMetadata {
    private val table: Map<String, StepMetadata> = mapOf(
        // S2-A9 / G5: "core.milestone" row removed (LEGACY_REMOVED). Production metadata is
        // now read exclusively from CoreMilestoneStep.descriptor via RegistryStepMetadataResolver.
        // S2-A7 / G5 (2026-09-12): "core.deleteDir" row removed (LEGACY_REMOVED).
        // Production metadata is CoreDeleteDirStep.descriptor via RegistryStepMetadataResolver.
        // S2-A10 / G5 (2026-09-13): "core.cleanWs" row removed (LEGACY_REMOVED). Production
        // metadata is CoreCleanWsStep.descriptor via RegistryStepMetadataResolver.
        // S2-A10 / G5 metadata counter converges 4/4/4 -> 3/3/3.
        // S2-B10 / G5 (2026-09-13): "core.archiveArtifacts" row removed (LEGACY_REMOVED).
        // Production metadata is CoreArchiveArtifactsStep.descriptor via
        // RegistryStepMetadataResolver. This is what makes the registry the pre-decode
        // metadata authority for the key: no legacy row exists to fall back to.
        // S2-B10 / G5 metadata counter converges 3/3/3 -> 2/2/2.
        // WU-G5B (2026-09-17): "core.waitUntil" row removed (LEGACY_REMOVED).
        // Production metadata for core.waitUntil is the canonical RepeatUntil machinery
        // (BodyExecutionPolicy.RepeatUntil carries its own descriptor-level metadata; the
        // StepDescriptor is the registry authority, and the canonical decoder never sees
        // this key). Counter converges 2/2/2 -> 1/1/1 (only `core.load`).
        // S2-A5 / CORE-LOAD-REJECTED (2026-09-17): "core.load" row removed (REJECTED).
        // Production metadata for core.load is undefined; the Step is REJECTED. The legacy
        // `Effect.EXECUTES_SUBPROCESS` row was incorrect (load is in-process script evaluation,
        // not subprocess spawning — see SPIKE-018 §1.3). Counter converges 1/1/1 -> 0/0/0
        // (FIRST ZERO LEGACY RESIDUAL).
    )

    /** Durable metadata for a canonical core plugin; fails fast when a plugin id is not registered. */
    fun metadata(pluginId: String): StepMetadata =
        table[pluginId]
            ?: throw IllegalArgumentException("No canonical core metadata registered for plugin '$pluginId'")

    /** All canonical core plugin ids known to this legacy metadata authority. */
    val pluginIds: Set<String> = table.keys

    /**
     * Short display type derived from a plugin id (e.g. `core.sh` -> `sh`, `core.file.writeFile` ->
     * `file`). Single source of truth for event/step-type naming so the durable protocol never needs
     * the decoded command world to derive a label.
     */
    fun shortType(pluginId: String): String = pluginId.removePrefix("core.").substringBefore(".")
}
