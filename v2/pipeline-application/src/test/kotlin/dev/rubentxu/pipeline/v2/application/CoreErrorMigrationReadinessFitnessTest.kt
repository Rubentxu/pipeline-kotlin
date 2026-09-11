package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalErrorNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S2-A1 / G4 — `core.error` Migration Readiness Fitness.
 *
 * Law (per user directive at 2026-09-11T10:20Z):
 *
 *   REGISTRY_IMPLEMENTATION_READY
 *   + SEMANTIC_PARITY_PROVEN (G3)
 *   + LEGACY_AUTHORITY_STILL_INTACT
 *   = SAFE_TO_FLIP
 *
 * G4 proves three SEPARATE facts in ONE class:
 *
 *   1. REGISTRY_IMPLEMENTATION_READY
 *      CoreErrorStep exists, is registered in the production
 *      [CoreStepRegistryFactory.registry()], and resolves exactly to the new
 *      [StepDefinition]. There is no parallel "alternate" definition.
 *
 *   2. NO STEP-SPECIFIC BRANCHES ADDED TO COORDINATOR / BOUNDARY
 *      The durable coordinator and the [CommonExecutionBoundary] projection mechanism
 *      do NOT branch on `core.error`. The projection authority is the generic
 *      `produced as? TypedStepOutput` (the [CoreShellOutput] pattern), not a per-Step
 *      `when` clause.
 *
 *   3. LEGACY_AUTHORITY_STILL_INTACT (PRECONDITION, NOT DEBT)
 *      `core.error` IS still in [CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS].
 *      [StructuralFamilyResolver] returns [StructuralStepFamily.LegacyCore] for the key.
 *      [CanonicalErrorNodeDispatcher] still exists.
 *      Legacy metadata row still exists.
 *      Legacy decoder branch still exists.
 *      Legacy counters: 12 / 12 / 12.
 *
 * The legacy presence at G4 is NOT a debt of the test. It is the explicit precondition
 * that demonstrates the flip has NOT yet happened. The flip is G5; source-level deletion
 * is G6.
 *
 * This test is NOT `S3ErrorLegacyRemovedFitnessTest` and must NEVER be confused with it.
 * The `S3...LegacyRemoved` test asserts the IRREVERSIBLE state after G6. This class
 * asserts the G4 transient state.
 *
 * This class does NOT contain assertions that are inverted in G5/G6. Migration-tracking
 * assertions (LEGACY_PLUGIN_IDS membership, family classification, file existence) are
 * encoded as STRUCTURAL FACTS about the current state, NOT as booleans that get flipped
 * later. Migration state lives in the corresponding G5 / G6 tests.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorMigrationReadinessFitnessTest {

    // ===== 1. REGISTRY_IMPLEMENTATION_READY =====================================

    @Test
    fun `G4 readiness -- CoreErrorStep KEY is the canonical core error identifier`() {
        assertEquals(PluginStepId("core.error"), CoreErrorStep.KEY,
            "CoreErrorStep.KEY MUST equal PluginStepId('core.error')")
    }

    @Test
    fun `G4 readiness -- CoreErrorStep definition exists and is non-null`() {
        val d = CoreErrorStep.definition
        assertNotNull(d, "CoreErrorStep.definition MUST exist at G4")
    }

    @Test
    fun `G4 readiness -- production registry resolves core error to exactly CoreErrorStep definition`() {
        val registry = CoreStepRegistryFactory.registry()
        val resolved = registry.definition(CoreErrorStep.KEY)
        assertNotNull(resolved, "production registry MUST resolve core.error at G4")
        assertSame(CoreErrorStep.definition, resolved,
            "production registry MUST return CoreErrorStep.definition (no parallel definition)")
    }

    @Test
    fun `G4 readiness -- production registry contains core error alongside echo and sh`() {
        val keys = CoreStepRegistryFactory.registry().keys().map { it.value }.toSet()
        assertTrue("core.echo" in keys, "core.echo MUST remain registered")
        assertTrue("core.sh" in keys, "core.sh MUST remain registered")
        assertTrue("core.error" in keys, "core.error MUST be registered at G4")
    }

    // ===== 2. NO STEP-SPECIFIC BRANCHES ADDED TO COORDINATOR / BOUNDARY =========

    @Test
    fun `G4 readiness -- RegistryExecutionBoundary uses the generic TypedStepOutput projection`() {
        // The boundary that registry-routed executions flow through projects the typed
        // outcome via `produced as? TypedStepOutput`. That projection MUST exist.
        val boundaryFile = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt"
        )
        assertTrue(boundaryFile.exists(),
            "RegistryExecutionBoundary.kt MUST exist on disk at G4")
        val source = boundaryFile.readText()
        assertTrue("TypedStepOutput" in source,
            "RegistryExecutionBoundary MUST reference TypedStepOutput (generic projection seam)")
        assertTrue("produced as?" in source || "(produced as?" in source,
            "RegistryExecutionBoundary MUST use 'produced as? TypedStepOutput' projection")
    }

    @Test
    fun `G4 readiness -- durable dispatchers directory has exactly 12 per-Step files (no new file for error)`() {
        // No new dispatcher was added for core.error; the legacy CanonicalErrorNodeDispatcher
        // remains the legacy authority until G6 deletion. If a future edit added a parallel
        // per-Step dispatcher for core.error, the directory would exceed 12.
        val dir = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable"
        )
        val dispatcherFiles = dir.listFiles { f ->
            f.isFile &&
                f.name.startsWith("Canonical") &&
                f.name.endsWith("NodeDispatcher.kt") &&
                f.name != "CanonicalNodeDispatcher.kt"
        }?.toList() ?: emptyList()
        assertEquals(12, dispatcherFiles.size,
            "The per-Step dispatcher directory MUST have exactly 12 files at G4 " +
                "(CanonicalErrorNodeDispatcher + 11 others); a parallel dispatcher would " +
                "indicate a per-Step privileged path was introduced")
        assertTrue(dispatcherFiles.any { it.name == "CanonicalErrorNodeDispatcher.kt" },
            "CanonicalErrorNodeDispatcher.kt MUST still be present at G4")
    }

    @Test
    fun `G4 readiness -- CanonicalNodeDispatcher facade still routes Error through legacy`() {
        // At G4, the facade's `when(command)` MUST still include `is CanonicalCoreStepCommand.Error`.
        // The Error case is removed at G6 (source-level deletion of the variant). G4 still
        // has the variant and the branch.
        val facadeFile = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt"
        )
        val source = facadeFile.readText()
        assertTrue("is CanonicalCoreStepCommand.Error" in source,
            "CanonicalNodeDispatcher MUST still dispatch 'is CanonicalCoreStepCommand.Error' at G4")
        assertTrue("errorDispatcher" in source,
            "CanonicalNodeDispatcher MUST still hold an errorDispatcher field at G4")
        // Instantiating the facade proves the legacy path is still wired.
        val facade = CanonicalNodeDispatcher()
        assertNotNull(facade, "CanonicalNodeDispatcher MUST be instantiable at G4")
    }

    @Test
    fun `G4 readiness -- legacy decoder has exactly 12 pluginId branches (no new branch added)`() {
        // The decoder's `when (node.pluginStepId.value)` MUST have one branch per legacy
        // key. Adding a parallel branch for core.error in this decoder would mean the
        // migration introduced a Step-specific seam; the registry path is the seam.
        val decoderFile = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt"
        )
        val source = decoderFile.readText()
        // Count branches: each legacy key has a `XXX_PLUGIN_ID ->` arm.
        val pluginIdBranches = Regex("""[A-Z_]+_PLUGIN_ID\s*->\s*\{""").findAll(source).count()
        assertEquals(12, pluginIdBranches,
            "CanonicalCoreStepDecoder MUST have exactly 12 pluginId branches at G4 " +
                "(one per legacy key); a parallel branch would mean a Step-specific seam was added")
    }

    // ===== 3. LEGACY_AUTHORITY_STILL_INTACT (PRECONDITION) =======================

    @Test
    fun `G4 readiness -- core error IS in LEGACY_PLUGIN_IDS (precondition)`() {
        val legacyIds = CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS
        assertTrue("core.error" in legacyIds,
            "core.error MUST be in LEGACY_PLUGIN_IDS at G4 (the flip is G5; " +
                "absence here would mean the flip happened prematurely)")
    }

    @Test
    fun `G4 readiness -- StructuralFamilyResolver classifies core error as LegacyCore`() {
        val registry = CoreStepRegistryFactory.registry()
        val family = StructuralFamilyResolver.classify(CoreErrorStep.KEY, registry)
        assertEquals(StructuralStepFamily.LegacyCore, family,
            "StructuralFamilyResolver MUST return LegacyCore for core.error while " +
                "it remains in LEGACY_PLUGIN_IDS (G4 precondition)")
    }

    @Test
    fun `G4 readiness -- CanonicalErrorNodeDispatcher file is still present on disk`() {
        val file = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalErrorNodeDispatcher.kt"
        )
        assertTrue(file.exists(),
            "CanonicalErrorNodeDispatcher.kt MUST still exist at G4 (deletion is G6)")
    }

    @Test
    fun `G4 readiness -- legacy metadata row for core error still exists`() {
        val row = CanonicalCoreStepMetadata.metadata("core.error")
        assertNotNull(row, "CanonicalCoreStepMetadata MUST still carry a 'core.error' row at G4")
        assertNotNull(row.effects, "metadata effects MUST be present")
        assertNotNull(row.replayPolicy, "metadata replayPolicy MUST be present")
    }

    @Test
    fun `G4 readiness -- legacy decoder still declares ERROR_PLUGIN_ID and its branch`() {
        val decoderFile = sourceFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt"
        )
        val source = decoderFile.readText()
        assertTrue("""ERROR_PLUGIN_ID = "core.error"""" in source,
            "CanonicalCoreStepDecoder MUST still declare ERROR_PLUGIN_ID at G4")
        assertTrue("""ERROR_PLUGIN_ID ->""" in source,
            "CanonicalCoreStepDecoder MUST still have an ERROR_PLUGIN_ID branch at G4")
    }

    @Test
    fun `G4 readiness -- legacy dispatcher still works end-to-end`() {
        val dispatcher = CanonicalErrorNodeDispatcher()
        val outcome = dispatcher.dispatch(
            CanonicalCoreStepCommand.Error("x", FailureKind.USER)
        )
        assertTrue(outcome is StepOutcome.Failure,
            "legacy CanonicalErrorNodeDispatcher MUST still produce StepOutcome.Failure at G4")
    }

    // ===== 4. LEGACY COUNTERS INVARIANT ==========================================

    @Test
    fun `G4 readiness -- LEGACY_PLUGIN_IDS has exactly 12 entries (precondition)`() {
        val count = CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size
        assertEquals(12, count,
            "LEGACY_PLUGIN_IDS MUST have exactly 12 entries at G4 (no removal yet)")
    }

    @Test
    fun `G4 readiness -- CanonicalCoreStepMetadata has exactly 12 rows (precondition)`() {
        val count = CanonicalCoreStepMetadata.pluginIds.size
        assertEquals(12, count,
            "CanonicalCoreStepMetadata MUST have exactly 12 rows at G4 (no removal yet)")
    }

    // ===== helpers ==============================================================

    /**
     * Locate a source file by path relative to the repo root. Walks up from the current
     * working directory to find the repo root (the directory containing `v2/`).
     */
    private fun sourceFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "v2").isDirectory) {
                return File(dir, relativePath)
            }
            dir = dir.parentFile
        }
        error("Could not locate repo root from CWD=${File(".").absolutePath}")
    }
}
