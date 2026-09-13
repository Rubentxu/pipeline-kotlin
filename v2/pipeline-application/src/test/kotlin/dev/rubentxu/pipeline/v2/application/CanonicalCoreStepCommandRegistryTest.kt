package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 3 entries (Load, WaitUntil, ArchiveArtifacts).
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
 *   LFC-2E1-S2-A7 / G5 removed DeleteDir.
 *   LFC-2E1-S2-A9 / G5 removed Milestone.
 *   LFC-2E1-S2-A10 / G5 (2026-09-13): "core.cleanWs" legacy subtype/decoder branch/
 *   dispatcher file/metadata row physically deleted (LEGACY_REMOVED). Production
 *   routing is exclusively CoreCleanWsStep.definition via the open registry.
 *   Counter converges 3/4/4 -> 3/3/3.
 * - LEGACY_PLUGIN_IDS derived from the sealed hierarchy matches the expected set.
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has exactly 3 entries`() {
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(3, subclasses.size, "Expected exactly 3 sealed subtypes (Echo/Sh/Error/Sleep/WriteFile/EmitEvent/IsUnix/Pwd/DeleteDir/Milestone/CleanWs all removed at their respective G5 closures). Found: ${subclasses.map { it.simpleName }}")
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
            // core.milestone removed at LFC-2E1-S2-A9 / G5 (registry-routed, CERTIFIED).
            // S2-A7 / G4 (2026-09-12): "core.deleteDir" removed — REGISTRY_PRIMARY flip.
            // S2-A7 / G5 (2026-09-12): "core.deleteDir" legacy subtype/decoder branch/metadata
            // row/dispatcher physically deleted (LEGACY_REMOVED).
            // S2-A10 / G4 (2026-09-13): "core.cleanWs" removed — REGISTRY_PRIMARY flip.
            // S2-A10 / G5 (2026-09-13): "core.cleanWs" legacy subtype/decoder branch/
            // dispatcher file/metadata row physically deleted (LEGACY_REMOVED). Counter
            // converges 3/4/4 -> 3/3/3.
            // P1a — workflow-control (v0.33.0)
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

    // S2-A9 / G5: Milestone legacy command removed (LEGACY_REMOVED); its historical
    // metadata row assertions live in the G1/G2 receipts. Metadata authority is now
    // CoreMilestoneStep.descriptor via RegistryStepMetadataResolver.

    // P1a — workflow-control canonical step families
    // S2-A7 / G5: `DeleteDir has correct pluginId and defaultMetadata` removed —
    // the legacy subtype no longer exists (LEGACY_REMOVED); metadata authority is
    // CoreDeleteDirStep.descriptor via RegistryStepMetadataResolver.

    // S2-A10 / G5 (2026-09-13): "core.cleanWs" legacy command removed (LEGACY_REMOVED);
    // its historical pluginId/Effects/ReplayPolicy invariants are now asserted in
    // CoreCleanWsStepContractSuiteTest against CoreCleanWsStep.descriptor — the
    // registry authority.

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
