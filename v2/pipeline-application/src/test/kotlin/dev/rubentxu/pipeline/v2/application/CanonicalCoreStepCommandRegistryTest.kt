package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 0 entries after WU-LPR-301 / G5 (2026-09-18). Every
 *   legacy core canonical subtype has been retired to its registry authority.
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
 *   LFC-2E1-S2-B10 / G5 (2026-09-13): "core.archiveArtifacts" legacy subtype/decoder
 *   branch + constant/dispatcher file/metadata row physically deleted (LEGACY_REMOVED).
 *   Production routing is exclusively CoreArchiveArtifactsStep.definition via the open
 *   registry. Counter converges 2/3/3 -> 2/2/2.
 *   WU-LPR-301 / G5 (2026-09-18): "core.load" and "core.waitUntil" legacy canonical
 *   subtypes, decoder branches + constants, dispatcher files, and metadata rows are
 *   physically deleted (LEGACY_REMOVED). After this gate CanonicalCoreStepCommand has
 *   zero subtypes (counter converges 2/2/2 -> 0/0/0). Production routing authority for
 *   `core.waitUntil` is exclusively `CoreWaitUntilStep.definition` (registry), and
 *   `core.load` is DEFERRED + UNSUPPORTED in `local-core-v1`.
 * - LEGACY_PLUGIN_IDS derived from the sealed hierarchy matches the expected set.
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has exactly 0 entries`() {
        // WU-LPR-301 / G5 (2026-09-18): counter converges 2/2/2 -> 0/0/0. Every legacy core
        // canonical subtype has been physically removed; production routing authority is
        // exclusively the open StepRegistry. Re-introducing a CanonicalCoreStepCommand subtype
        // is a Step Constitution regression that this test fails closed (fitness).
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(0, subclasses.size, "Expected exactly 0 sealed subtypes after WU-LPR-301 / G5. Found: ${subclasses.map { it.simpleName }}")
    }

    @Test
    fun `LEGACY_PLUGIN_IDS matches expected set`() {
        // WU-LPR-301 / G5 (2026-09-18): the empty set is the production-true shape of
        // LEGACY_PLUGIN_IDS. Every prior entry has been retired through its respective
        // burn-down. Adding a new entry without a deletion gate is a Step Constitution
        // regression that this test fails closed.
        val expected = emptySet<String>()
        // Assert against the registry — single source of truth, no duplication
        assertEquals(expected, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS, "LEGACY_PLUGIN_IDS must match expected set (empty after WU-LPR-301 / G5)")
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

    // WU-LPR-301 / G5 (2026-09-18): Load test removed (LEGACY_REMOVED). The pluginId /
    // effects / replayPolicy invariants of `core.load` are no longer asserted here, because
    // `core.load` is DEFERRED + UNSUPPORTED in `local-core-v1` (no CoreLoadStep, no
    // descriptor). The fail-closed admission is asserted by Lpr301CoreLoadUnsupportedFitnessTest
    // against the canonical decoder's typed rejection of any `core.load` envelope.

    // P1b — utility canonical step families

    // S2-A6 / G5: Pwd test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.pwd are now asserted in S3PwdLegacyRemovedFitnessTest against
    // CorePwdStep.descriptor — the registry authority.

    // S2-A5 / G5: IsUnix test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.isUnix are now asserted in S3IsUnixLegacyRemovedFitnessTest against
    // CoreIsUnixStep.descriptor — the registry authority.

    // WU-LPR-301 / G5 (2026-09-18): WaitUntil test removed (LEGACY_REMOVED). The pluginId /
    // effects / replayPolicy invariants of `core.waitUntil` are now asserted against
    // CoreWaitUntilStep.descriptor — the registry authority — in
    // CoreWaitUntilStepContractSuiteTest (identity / contract completeness rows). The
    // body-shape invariant (polling cadence via BodyExecutionPolicy.Retrying(waitUntil = ...))
    // is asserted by Lpr301WaitUntilPolicyShapeFitnessTest.

    // S2-B10 / G5 (2026-09-13): the archiveArtifacts test removed (LEGACY_REMOVED). The
    // pluginId / effects / replayPolicy invariants of core.archiveArtifacts are now asserted
    // against CoreArchiveArtifactsStep.descriptor — the registry authority — in
    // CoreArchiveArtifactsStepUnitTest and CoreArchiveArtifactsStepContractSuiteTest.
    // P2 (v0.33.1) introduced the legacy canonical family; S2-B10 retired it.
}
