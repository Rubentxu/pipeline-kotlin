package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 6 entries (Milestone, DeleteDir, CleanWs,
 *   Load, WaitUntil, ArchiveArtifacts).
 *   S3.1 removed Echo (core.echo migrated to the open StepRegistry via CoreEchoStep).
 *   S6 removed Shell (core.sh migrated to the open StepRegistry via CoreShellStep).
 *   LFC-2E1-S2-A1 / G6 removed Error (core.error migrated to the open StepRegistry
 *   via CoreErrorStep).
 *   LFC-2E1-S2-A2 / G5 removed Sleep (core.sleep migrated to the open StepRegistry
 *   via CoreSleepStep; CERTIFIED at S2-A2/G8).
 *   LFC-2E1-S2-A3 / G5 removed WriteFile (core.file.writeFile migrated to the open
 *   StepRegistry via CoreWriteFileStep).
 *   LFC-2E1-S2-A4 / G5 removed EmitEvent.
 *   LFC-2E1-S2-A5 / G5 removed IsUnix.
 *   LFC-2E1-S2-A6 / G5 removed Pwd.
 * - LEGACY_PLUGIN_IDS derived from the sealed hierarchy matches the expected set.
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has exactly 6 entries`() {
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(6, subclasses.size, "Expected exactly 6 sealed subtypes (EmitEvent removed at S2-A4/G5; IsUnix at S2-A5/G5; Pwd at S2-A6/G5). Found: ${subclasses.map { it.simpleName }}")
    }

    @Test
    fun `LEGACY_PLUGIN_IDS matches expected set`() {
        val expected = setOf(
            // core.sleep removed at LFC-2E1-S2-A2 / G5 (registry-routed, CERTIFIED).
            // core.file.writeFile removed at LFC-2E1-S2-A3 / G4 (registry-routed).
            // core.emit.event removed at LFC-2E1-S2-A4 / G4 (registry-routed).
            // S2-A5 / G4 (2026-09-12): "core.isUnix" removed — REGISTRY_PRIMARY flip.
            // S2-A5 / G5 (2026-09-12): "core.isUnix" legacy subtype/decoder/dispatcher/metadata
            // physically deleted (LEGACY_REMOVED). Production authority is exclusively the
            // open registry (CoreIsUnixStep.descriptor via RegistryStepMetadataResolver).
            // S2-A6 / G4 (2026-09-12): "core.pwd" removed — REGISTRY_PRIMARY flip.
            // S2-A6 / G5 (2026-09-12): "core.pwd" legacy subtype/decoder/dispatcher/metadata
            // physically deleted (LEGACY_REMOVED). Production authority is exclusively the
            // open registry (CorePwdStep.descriptor via RegistryStepMetadataResolver).
            "core.milestone",
            // P1a — workflow-control (v0.33.0)
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            // P1b — utility (v0.33.0)
            "core.waitUntil",
            // P2 — archiveArtifacts (v0.33.1)
            "core.archiveArtifacts",
        )
        // Assert against the registry — single source of truth, no duplication
        assertEquals(expected, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS, "LEGACY_PLUGIN_IDS must match expected set")
    }

    // S2-A4 / G5: EmitEvent legacy command removed (LEGACY_REMOVED); its historical
    // metadata row assertions live in the G1/G2 receipts. Metadata authority is now
    // CoreEmitEventStep.descriptor via RegistryStepMetadataResolver.

    @Test
    fun `Milestone has correct pluginId and defaultMetadata`() {
        val milestoneInstance = CanonicalCoreStepCommand.Milestone(ordinal = 1, label = "post-error")
        assertEquals("core.milestone", milestoneInstance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), milestoneInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, milestoneInstance.defaultMetadata.replayPolicy)
    }

    // P1a — workflow-control canonical step families

    @Test
    fun `DeleteDir has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.DeleteDir(path = ".")
        assertEquals("core.deleteDir", instance.pluginId)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `CleanWs has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.CleanWs(deleteDirs = true, patterns = emptyList())
        assertEquals("core.cleanWs", instance.pluginId)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Load has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.Load(path = "loaded.pipeline.kts")
        assertEquals("core.load", instance.pluginId)
        assertEquals(setOf(Effect.EXECUTES_SUBPROCESS), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    // P1b — utility canonical step families

    // S2-A6 / G5: Pwd test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.pwd are now asserted in S3PwdLegacyRemovedFitnessTest against
    // CorePwdStep.descriptor — the registry authority.

    // S2-A5 / G5: IsUnix test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.isUnix are now asserted in S3IsUnixLegacyRemovedFitnessTest against
    // CoreIsUnixStep.descriptor — the registry authority.

    @Test
    fun `WaitUntil has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.WaitUntil(initialRecurrencePeriod = 1000L, quiet = false)
        assertEquals("core.waitUntil", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    // P2 — archiveArtifacts canonical step family (v0.33.1)

    @Test
    fun `ArchiveArtifacts has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.ArchiveArtifacts(
            artifacts = "build/**/*.jar",
            allowEmptyArchive = false,
            excludes = "",
            fingerprint = true,
        )
        assertEquals("core.archiveArtifacts", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }
}
