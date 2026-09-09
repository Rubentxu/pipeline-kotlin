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
        "core.error" to StepMetadata(setOf(Effect.ABORTS_PIPELINE), ReplayPolicy.NEVER),
        "core.sleep" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.file.writeFile" to StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED),
        "core.emit.event" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.milestone" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.deleteDir" to StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED),
        "core.cleanWs" to StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED),
        "core.load" to StepMetadata(setOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.MEMOIZED),
        "core.pwd" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.isUnix" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.waitUntil" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
        "core.archiveArtifacts" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),
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
