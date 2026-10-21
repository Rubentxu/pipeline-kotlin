package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Single source of truth for the durable metadata of each canonical core plugin.
 *
 * The metadata value is constant per pluginId. [CanonicalCoreStepCommand.defaultMetadata] delegates
 * here (so no duplicate literal is scattered across the sealed subtypes), and the durable
 * resolution reads this same authority by StepKey before any typed decode.
 *
 * This table is the compatibility authority of the legacy core world only. It is NOT the future
 * global registry authority: the registry/definition metadata is a
 * `StepMetadataResolver` consumed by durable resolution over a composite of migrated definitions and
 * this legacy core table. Keeping it behind its own object leaves that seam open without coupling the
 * durable protocol to a specific core Step name.
 *
 * The map is intentionally empty: every executable core plugin now lives behind the registry, so
 * every legacy core row has been removed. The legacy authority survives as a typed fast-fail
 * channel and a placeholder for any future row that must land here for parity.
 */
object CanonicalCoreStepMetadata {
    private val table: Map<String, StepMetadata> = mapOf(
        // The map is empty. Every legacy canonical core plugin row has been removed
        // (core.milestone, core.deleteDir, core.cleanWs, core.archiveArtifacts, core.load,
        // core.waitUntil). Production metadata is now read exclusively from each Step's
        // descriptor via the RegistryStepMetadataResolver. There is no legacy row to fall
        // back to for any surviving plugin key — a lookup miss means the key is unknown to
        // the canonical authority, not that a default exists to be silently inherited.
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
