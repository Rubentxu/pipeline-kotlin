package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * HISTORICAL EVIDENCE (NOT a regression test) — S2-A1 / G4 of `core.error`.
 *
 * Status at G5 (2026-09-11T10:34Z): ARCHIVED.
 *
 * Reason for archival: per the user's directive ("No debe convertirse en el fitness
 * post-flip"), this class is preserved as historical evidence only. Its assertions
 * encoded the G4 PRE-FLIP state (registry ready + legacy intact, including
 * `StructuralFamilyResolver(core.error) == LegacyCore` and
 * `CanonicalErrorNodeDispatcher present on disk`). At G5 we flipped the routing:
 *
 *     LEGACY_PLUGIN_IDS -= "core.error"
 *
 * The structural family flipped to `Registry`. The legacy dispatcher is now
 * unreachable but still on disk (G6 deletes it). The G4 assertions therefore became
 * INCORRECT as a permanent regression assertion — keeping them would have made the
 * regression suite red after a successful migration.
 *
 * The `@Disabled` annotation prevents JUnit from running any of these methods. The
 * G5 state is now covered by `CoreErrorRegistryPrimaryFitnessTest`. The
 * irreversible post-G6 state will be covered by `S3ErrorLegacyRemovedFitnessTest`.
 *
 * Method names are preserved so future readers can SEE what the G4 assertions were.
 * They are NOT a regression gate.
 */
@Disabled("Archived G4 evidence: superseded by CoreErrorRegistryPrimaryFitnessTest (G5) and " +
    "S3ErrorLegacyRemovedFitnessTest (G6). See class kdoc for rationale.")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorMigrationReadinessFitnessTest {

    // ===== 1. REGISTRY_IMPLEMENTATION_READY (preserved G4 assertions) =========

    @Test
    fun `G4 readiness -- CoreErrorStep KEY is the canonical core error identifier`() {
        // G4 assertion: assertEquals(PluginStepId("core.error"), CoreErrorStep.KEY, ...)
        // At G5 still TRUE (the KEY is unchanged). Now covered by the G5 fitness.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc. The G4 evidence is preserved in source.",
        )
    }

    @Test
    fun `G4 readiness -- CoreErrorStep definition exists and is non-null`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- production registry resolves core error to exactly CoreErrorStep definition`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- production registry contains core error alongside echo and sh`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    // ===== 2. NO STEP-SPECIFIC BRANCHES ADDED (architectural at G4) ============

    @Test
    fun `G4 readiness -- RegistryExecutionBoundary uses the generic TypedStepOutput projection`() {
        // G4 structural assertion: source-level check that RegistryExecutionBoundary uses
        // the generic `produced as? TypedStepOutput` projection (no `core.*` literal in
        // a `when` clause). This is now covered structurally by the G5 fitness as a
        // permanent invariant (no per-Step privileged path).
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- durable dispatchers directory has exactly 12 per-Step files`() {
        // G4 structural assertion: dispatcher directory has 12 files (no parallel
        // dispatcher for core.error added). At G5 this is still 12 (G6 deletes one).
        // The G5 fitness checks the count holds; G6 will check it goes to 11.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- CanonicalNodeDispatcher facade still routes Error through legacy`() {
        // G4 assertion: facade still dispatches `is CanonicalCoreStepCommand.Error`.
        // At G5 the legacy code path is unreachable but the source remains (G6 deletes
        // it). The G5 fitness checks reachability; G6 checks source-level absence.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- legacy decoder has exactly 12 pluginId branches (no new branch added)`() {
        // G4 structural assertion: decoder has 12 pluginId branches. The G5 fitness
        // checks that this still holds (one branch removed at G6 source deletion).
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    // ===== 3. LEGACY_AUTHORITY_STILL_INTACT (G4 precondition, archived at G5) ===

    @Test
    fun `G4 readiness -- core error IS in LEGACY_PLUGIN_IDS (precondition)`() {
        // G4 assertion: assertTrue("core.error" in legacyIds).
        // AT G5 THIS IS NOW FALSE (the G5 flip removed it). Kept as historical
        // evidence of the G4 precondition.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- StructuralFamilyResolver classifies core error as LegacyCore`() {
        // G4 assertion: assertEquals(StructuralStepFamily.LegacyCore, family).
        // AT G5 THIS IS NOW Registry. Kept as historical evidence.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- CanonicalErrorNodeDispatcher file is still present on disk`() {
        // G4 assertion: file exists. At G5 still TRUE (G6 deletes it). Kept as
        // historical evidence of the G4 precondition.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- legacy metadata row for core error still exists`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- legacy decoder still declares ERROR_PLUGIN_ID and its branch`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- legacy dispatcher still works end-to-end`() {
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    // ===== 4. LEGACY COUNTERS INVARIANT (G4 precondition) =======================

    @Test
    fun `G4 readiness -- LEGACY_PLUGIN_IDS has exactly 12 entries (precondition)`() {
        // G4 assertion: count == 12.
        // AT G5 THE COUNT IS 11 (the flip removed core.error). Kept as historical
        // evidence of the G4 precondition.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }

    @Test
    fun `G4 readiness -- CanonicalCoreStepMetadata has exactly 12 rows (precondition)`() {
        // G4 assertion: count == 12.
        // AT G5 STILL 12 (metadata row deletion is G6). Kept as historical evidence
        // of the G4 precondition.
        throw UnsupportedOperationException(
            "Archived G4 evidence test — see class kdoc.",
        )
    }
}
