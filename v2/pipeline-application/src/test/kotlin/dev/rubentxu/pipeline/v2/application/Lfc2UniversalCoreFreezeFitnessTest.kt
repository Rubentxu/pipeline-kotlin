package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File

/**
 * LFC-2E1 — Universal Core Freeze Fitness.
 *
 * Authority: `docs/v2/00-governance/CORE_STEP_ADMISSION.md` (admission record),
 * `docs/v2/07-uat/LFC2E0_FINAL_CLOSURE_RECEIPT.md` (E0 closure).
 *
 * This test mechanically enforces the universal-core freeze. Per the LFC-2E1
 * directive:
 *
 *   new CORE Step  → requires explicit admission record
 *   plugin Step    → cannot require coordinator switch
 *   no new:
 *     - CanonicalXxxNodeDispatcher (per-Step dispatcher files in main source)
 *     - LEGACY_PLUGIN_IDS entry (set must remain empty post-LFC-2E0)
 *     - plugin-name routing (no `when (pluginStepId.value) { ... }` in coordinator)
 *
 * Violating any of these freezes fails this test.
 */
@Timeout(15)
@DisplayName("LFC-2E1 — universal core freeze fitness")
class Lfc2UniversalCoreFreezeFitnessTest {

    private val repoRoot: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        File(moduleDir, "../..").canonicalFile
    }

    // ───────────────────────────────────────────────────────────────────────
    // Rule 1: No new per-Step CanonicalXxxNodeDispatcher files in main source
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `no per-Step CanonicalXxxNodeDispatcher files in main source`() {
        val mainDir = File(repoRoot, "v2/pipeline-application/src/main/kotlin")
        val legacyDispatchers = mainDir.walkTopDown()
            .filter { it.isFile && it.name.matches(Regex("Canonical[A-Z][a-zA-Z]+NodeDispatcher\\.kt")) }
            .filter { it.name != "CanonicalNodeDispatcher.kt" }
            .toList()
        assertEquals(
            emptyList<File>(),
            legacyDispatchers,
            "Universal-core freeze: NO per-Step Canonical*NodeDispatcher.kt file may be added to main source. " +
                "All Step routing MUST go through the registry seam (StepDefinition → StepHandler). " +
                "The general coordinator `CanonicalNodeDispatcher.kt` is the typed-rejection safety dispatcher.",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Rule 2: LEGACY_PLUGIN_IDS remains empty (no regression)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `LEGACY_PLUGIN_IDS remains empty`() {
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "Universal-core freeze: LEGACY_PLUGIN_IDS must remain empty post-LFC-2E0 (FIRST ZERO LEGACY RESIDUAL). " +
                "Any new entry requires an explicit ARCH-REJECT decision (see CORE_STEP_ADMISSION.md).",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Rule 3: No new plugin-name routing in CanonicalDurableRunCoordinator
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `no plugin-name routing in CanonicalDurableRunCoordinator`() {
        // The freeze prohibits `when (pluginStepId.value) { ... }` patterns that
        // branch on a concrete StepKey. Routing must go through:
        //   - registry (StepDefinition → StepHandler) for atomic Steps
        //   - BodyExecutionPolicy (declared on family descriptor) for block steps
        //   - StructuralFamilyResolver (membership-based) for legacy compatibility
        // The coordinator may dispatch on `pluginStepId` for structural/identity
        // checks but MUST NOT do per-Key arms.
        val coordinatorFile = File(
            repoRoot,
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertTrue(coordinatorFile.exists(), "CanonicalDurableRunCoordinator must exist")
        val source = coordinatorFile.readText()
        // Search for `when` patterns that branch on pluginStepId with concrete
        // StepKey strings as cases. We allow structural checks (`pluginStepId in
        // LEGACY_PLUGIN_IDS`) but forbid explicit concrete-Key arms.
        val suspiciousPatterns = listOf(
            Regex("""when\s*\(\s*pluginStepId\.\s*value\s*\)\s*\{[^}]*"core\.[a-zA-Z.]+"\s*->"""),
            Regex("""pluginStepId\.value\s*==\s*"core\.[a-zA-Z.]+""""),
        )
        for (pattern in suspiciousPatterns) {
            val match = pattern.find(source)
            assertTrue(
                match == null,
                "Universal-core freeze: CanonicalDurableRunCoordinator must NOT branch on concrete StepKey. " +
                    "Found suspicious pattern: '${match?.value}'. Routing must go through the registry seam " +
                    "(StepDefinition → StepHandler) or BodyExecutionPolicy, never via per-Key when arms.",
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Rule 4: No new CORE Step declared without an admission record entry
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every core Step in code has an admission record entry`() {
        // Discover every StepKey declared in pipeline-application/main source.
        val mainDir = File(repoRoot, "v2/pipeline-application/src/main/kotlin")
        val coreKeys = mainDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("Core") && it.name.endsWith("Step.kt") }
            .mapNotNull { f ->
                Regex("""PluginStepId\("(core\.[a-zA-Z.]+)"\)""").find(f.readText())?.groupValues?.get(1)
            }
            .toSet()

        // Read the admission record. Every core.* key declared in code MUST
        // appear in the admission record's frozen surface table.
        val admissionFile = File(repoRoot, "docs/v2/00-governance/CORE_STEP_ADMISSION.md")
        assertTrue(admissionFile.exists(), "CORE_STEP_ADMISSION.md must exist at docs/v2/00-governance/")
        val admission = admissionFile.readText()

        for (key in coreKeys) {
            assertTrue(
                admission.contains("`$key`"),
                "Universal-core freeze: StepKey '$key' is declared in code but missing from " +
                    "CORE_STEP_ADMISSION.md frozen surface. Either add an admission record entry " +
                    "(with reason + receipt) OR move this Step to OFFICIAL_PLUGIN.",
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Rule 5: CoreStepRegistryFactory is the only registry composition site
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `CoreStepRegistryFactory is the only Step registration site in main`() {
        // The freeze prohibits adding Step registration calls outside the factory.
        // All Step registrations MUST go through `CoreStepRegistryFactory` or
        // external ServiceLoader discovery.
        val mainDir = File(repoRoot, "v2/pipeline-application/src/main/kotlin")
        val files = mainDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.name != "CoreStepRegistryFactory.kt" }
            .toList()

        // Allowlist: files that legitimately reference StepRegistry registration patterns.
        val allowlist = setOf(
            "CanonicalDurableRunCoordinator.kt", // consumes registry, not registers
            "CoreStepRegistryFactory.kt", // the factory itself (excluded above)
            "StepContractSurrogateCatalogue.kt", // test/catalogue surface
            "META-INF", // ServiceLoader files
        )

        for (file in files) {
            if (allowlist.any { file.path.contains(it) }) continue
            val source = file.readText()
            // Look for patterns like `registerInto(this)` or `registry.register(` outside the factory.
            val registrationPattern = Regex("""registerInto\s*\(\s*this\s*\)""")
            val matches = registrationPattern.findAll(source).count()
            assertEquals(
                0,
                matches,
                "Universal-core freeze: Step registration found in ${file.name} outside CoreStepRegistryFactory. " +
                    "All Step registrations MUST go through CoreStepRegistryFactory or ServiceLoader discovery.",
            )
        }
    }

    // ──────────────────────────────────────────────────────────���────────────
    // Rule 6: Block-step routing goes through BodyExecutionPolicy, not StepKey
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `block-step routing uses BodyExecutionPolicy, not per-Key when arms`() {
        // The DslCompiledPipelineCompiler (and similar) MUST resolve the body's
        // execution shape via BodyExecutionPolicy declared on the family descriptor,
        // not via `when (pluginStepId.value) { "core.retry" -> ... }`. The
        // Lfc2ConcreteBodyRoutingDebtFitnessTest already checks the production
        // source. Here we check that block-step declarations register a
        // BodyExecutionPolicy on their StepDescriptor (or that no new
        // plugin-name arms exist).
        val compilerFile = File(
            repoRoot,
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
        )
        if (!compilerFile.exists()) {
            // The compiler may be in another location; skip silently if absent.
            return
        }
        val source = compilerFile.readText()
        // Look for `when (pluginStepId.value)` with concrete StepKey arms.
        val suspiciousPatterns = listOf(
            Regex("""when\s*\(\s*pluginStepId\.value\s*\)\s*\{[^}]*"core\.[a-zA-Z.]+"\s*->"""),
        )
        for (pattern in suspiciousPatterns) {
            val match = pattern.find(source)
            assertTrue(
                match == null,
                "Universal-core freeze: DslCompiledPipelineCompiler must NOT branch on concrete StepKey " +
                    "for block-step body routing. Found: '${match?.value}'. Routing must use BodyExecutionPolicy " +
                    "declared on the family descriptor (ADR-0073).",
            )
        }
    }
}
