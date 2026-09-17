package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 1 entry (Load).
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
 *   WU-G5B (2026-09-17): "core.waitUntil" legacy subtype/decoder branch/dispatcher file/
 *   metadata row physically deleted (LEGACY_REMOVED). Production routing is exclusively
 *   the canonical RepeatUntil machinery (BlockStepNode(BodyExecutionPolicy.RepeatUntil)
 *   → dispatchRepeatUntilBody in CanonicalDurableRunCoordinator). Counter converges
 *   2/2/2 -> 1/1/1.
 *   CORE-LOAD-REJECTED (2026-09-17, this slice): "core.load" REJECTED. All six legacy
 *   forms physically deleted (subtype, decoder branch + constant, metadata row,
 *   dispatcher file, DSL façade `load(path)`, `StepSpec.Load` data class). Counter
 *   converges 1/1/1 -> 0/0/0 — FIRST ZERO LEGACY RESIDUAL. The sealed
 *   `CanonicalCoreStepCommand` hierarchy has zero legacy constructors.
 * - LEGACY_PLUGIN_IDS derived from the sealed hierarchy matches the expected set
 *   (empty set post-CORE-LOAD-REJECTED).
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 *
 * **Note on EC-9 (2026-09-17):** with LEGACY_PLUGIN_IDS = {}, adding a new legacy
 * variant now requires ALSO a registry StepDefinition (or a REJECTED justification).
 * The "3 edits, 2 files" cost was correct for the historical legacy surface but is
 * not the future-shape cost; the future-shape cost is registry-burn-down G0..G8.
 * See `STEP_CONSTITUTION` / ADR-0070..0074 / `S2_A5_CORE_LOAD_REJECTION_RECEIPT.md`.
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has zero entries — ZERO LEGACY RESIDUAL`() {
        // CORE-LOAD-REJECTED (2026-09-17): the sealed CanonicalCoreStepCommand hierarchy
        // has no remaining legacy constructors. `Load` was the last subtype and it is
        // now physically deleted. Adding a new subtype requires both a legacy
        // justification AND a parallel registry StepDefinition (or a REJECTED decision);
        // see the test docstring's "Note on EC-9" and
        // `S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` for the full reasoning.
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(
            0,
            subclasses.size,
            "Expected zero sealed subtypes post-CORE-LOAD-REJECTED (FIRST ZERO LEGACY RESIDUAL). " +
                "Found: ${subclasses.map { it.simpleName }}",
        )
    }

    @Test
    fun `LEGACY_PLUGIN_IDS is the empty set — ZERO LEGACY RESIDUAL`() {
        val expected = emptySet<String>()
        // Assert against the registry — single source of truth, no duplication.
        // Historical entries (echo, sh, error, sleep, writeFile, emitEvent, isUnix,
        // pwd, milestone, deleteDir, cleanWs, waitUntil, load, archiveArtifacts) all
        // retired via burn-down (LEGACY_REMOVED → CERTIFIED) or rejection (load).
        assertEquals(
            expected,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "LEGACY_PLUGIN_IDS must be empty post-CORE-LOAD-REJECTED — FIRST ZERO LEGACY RESIDUAL",
        )
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

    // CORE-LOAD-REJECTED (2026-09-17): "Load has correct pluginId and defaultMetadata"
    // removed (REJECTED). `core.load` is REJECTED; no legacy subtype, no descriptor,
    // no test row. The historical Effect.EXECUTES_SUBPROCESS + ReplayPolicy.MEMOIZED
    // assertion was incorrect (load is in-process script evaluation, not subprocess —
    // see SPIKE-018 §1.3). The burn-down ledger has its FIRST ZERO LEGACY RESIDUAL.

    // P1b — utility canonical step families

    // S2-A6 / G5: Pwd test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.pwd are now asserted in S3PwdLegacyRemovedFitnessTest against
    // CorePwdStep.descriptor — the registry authority.

    // S2-A5 / G5: IsUnix test removed (LEGACY_REMOVED). The pluginId/Effects/ReplayPolicy
    // invariants of core.isUnix are now asserted in S3IsUnixLegacyRemovedFitnessTest against
    // CoreIsUnixStep.descriptor — the registry authority.

    // WU-G5B (2026-09-17): WaitUntil test removed (LEGACY_REMOVED). The
    // pluginId/Effects/ReplayPolicy invariants of core.waitUntil are now asserted
    // against the canonical RepeatUntil machinery (BodyExecutionPolicy.RepeatUntil
    // descriptor) — see Lfc2WaitUntilCanonicalReentryFitnessTest and
    // WaitUntilReconcilerTest.

    // S2-B10 / G5 (2026-09-13): the archiveArtifacts test removed (LEGACY_REMOVED). The
    // pluginId / effects / replayPolicy invariants of core.archiveArtifacts are now asserted
    // against CoreArchiveArtifactsStep.descriptor — the registry authority — in
    // CoreArchiveArtifactsStepUnitTest and CoreArchiveArtifactsStepContractSuiteTest.
    // P2 (v0.33.1) introduced the legacy canonical family; S2-B10 retired it.
}
