package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Single source of truth for the durable metadata of each canonical core plugin.
 * The metadata value is constant per pluginId. [CanonicalCoreStepCommand.defaultMetadata] delegates
 * here (so no duplicate literal is scattered across the 14 sealed subtypes), and lets the
 * durable resolution read this same authority by StepKey before any typed decode.
 * This table is the compatibility authority of the legacy core world only. It is NOT the future
 * global registry authority: that authority is the registry/definition metadata (a
 * `StepMetadataResolver` consumed by durable resolution over a composite of migrated definitions and
 * this legacy core table). Keeping it behind its own object leaves that seam open without coupling the
 * durable protocol to a specific core Step name.
*/
object CanonicalCoreStepMetadata {
    private val table: Map<String, StepMetadata> = mapOf(
        // "core.milestone" row removed (LEGACY_REMOVED). Production metadata is
        // now read exclusively from CoreMilestoneStep.descriptor via RegistryStepMetadataResolver.
        // "core.deleteDir" row removed (LEGACY_REMOVED).
        // Production metadata is CoreDeleteDirStep.descriptor via RegistryStepMetadataResolver.
        // "core.cleanWs" row removed (LEGACY_REMOVED). Production
        // metadata is CoreCleanWsStep.descriptor via RegistryStepMetadataResolver.
        // Metadata counter converges 4/4/4 -> 3/3/3
        // "core.archiveArtifacts" row removed (LEGACY_REMOVED).
        // Production metadata is CoreArchiveArtifactsStep.descriptor via
        // RegistryStepMetadataResolver. This is what makes the registry the pre-decode
        // metadata authority for the key: no legacy row exists to fall back to.
        // Metadata counter converges 3/3/3 -> 2/2/2
        // "core.load" and "core.waitUntil" rows removed
        // (LEGACY_REMOVED). Production metadata is read exclusively from the registry
        // descriptors (CoreWaitUntilStep.descriptor for waitUntil; core.load has no descriptor
        // because the key is DEFERRED + UNSUPPORTED in local-core-v1). This is what makes the
        // registry the pre-decode metadata authority for every surviving key.
        // Metadata counter converges 2/2/2 -> 0/0/0
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
