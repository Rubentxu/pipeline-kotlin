package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File

/**
 * LFC-2E0 — Global Closure Fitness (E0 final audit).
 *
 * Authority: `docs/v2/07-uat/LFC2E0_FINAL_CLOSURE_RECEIPT.md` and
 * `docs/v2/07-uat/LFC2E0_ZERO_LEGACY_RESIDUAL_RECEIPT.md` (LFC-2E0 closure).
 *
 * The LFC-2E0 program objective is that EVERY row in the canonical
 * certification matrix ends in a TERMINAL HONEST STATE:
 *
 *   - CERTIFIED + G8 receipt
 *   - REJECTED + rejection receipt
 *   - STOPPED_G7 + G7 STOP_BLOCKED receipt
 *   - DEFERRED + next-milestone pointer
 *
 * No row may remain `IMPLEMENTED_UNCERTIFIED` without explicit reason and
 * next-milestone declaration. No CERTIFIED row may lack a real pipeline
 * fixture, a G8 receipt, or be reached through a legacy path. No rollup
 * counter (YAML ↔ STEP_CERTIFICATION_MATRIX.md ↔ STEP_INVENTORY_LFC2E0.md
 * ↔ STEP_ECOSYSTEM_MATRIX.md) may drift.
 *
 * This test mechanically enforces the post-LFC-2E0 invariants. If any future
 * slice violates them, this test fails with a pinpointing diagnostic.
 */
@Timeout(15)
@DisplayName("LFC-2E0 — global closure fitness")
class Lfc2E0GlobalClosureFitnessTest {

    private val repoRoot: File by lazy {
        // Resolve relative to the gradle module dir (user.dir is v2/pipeline-application/).
        // We need to walk up to the repo root: v2/pipeline-application -> v2 -> repo.
        // user.dir = .../pipeline-wu-g5b/v2/pipeline-application/
        val moduleDir = File(System.getProperty("user.dir"))
        // moduleDir is v2/pipeline-application; go up two levels to repo root.
        File(moduleDir, "../..").canonicalFile
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. Legacy residual = 0 (FIRST ZERO LEGACY RESIDUAL)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `legacy residual ids are zero`() {
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "FIRST ZERO LEGACY RESIDUAL: LEGACY_PLUGIN_IDS must be empty",
        )
    }

    @Test
    fun `legacy residual metadata rows are zero`() {
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepMetadata.pluginIds,
            "FIRST ZERO LEGACY RESIDUAL: CanonicalCoreStepMetadata must have no rows",
        )
    }

    @Test
    fun `legacy residual dispatcher files are zero in main source`() {
        // Source-level proof: walk the main pipeline-application tree and assert no
        // per-Step legacy dispatcher file remains (e.g. CanonicalLoadNodeDispatcher.kt,
        // CanonicalWaitUntilNodeDispatcher.kt). The general coordinator
        // CanonicalNodeDispatcher.kt is the typed-rejection safety dispatcher — it has
        // zero Step-specific arms and is REQUIRED for fail-closed admission, so we
        // allow it.
        val mainDir = File(repoRoot, "v2/pipeline-application/src/main/kotlin")
        val legacyDispatchers = mainDir.walkTopDown()
            .filter { it.isFile && it.name.matches(Regex("Canonical[A-Z][a-zA-Z]+NodeDispatcher\\.kt")) }
            .filter { it.name != "CanonicalNodeDispatcher.kt" }
            .toList()
        assertEquals(
            emptyList<File>(),
            legacyDispatchers,
            "FIRST ZERO LEGACY RESIDUAL: no per-Step Canonical*NodeDispatcher.kt file may remain in main source",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. No unclassified production StepKeys (every key has terminal state)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every production StepKey has a terminal state in the YAML matrix`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        assertTrue(yamlFile.exists(), "YAML source of truth MUST exist at docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        // Inventory ground-truth: every StepKey declared in pipeline-application/main.
        val coreKeys = File(repoRoot, "v2/pipeline-application/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("Core") && it.name.endsWith("Step.kt") }
            .mapNotNull { f ->
                Regex("""PluginStepId\("(core\.[a-zA-Z.]+)"\)""").find(f.readText())?.groupValues?.get(1)
            }
            .toSet()
        // Plus example.uppercase (external plugin, lives outside pipeline-application/main).
        val allProductionKeys = coreKeys + "example.uppercase"

        // Every production key MUST appear in the YAML matrix.
        for (key in allProductionKeys) {
            assertTrue(
                yaml.contains("step_key: $key"),
                "StepKey '$key' is declared in code but missing from canonical YAML — terminal state unresolved",
            )
        }

        // Every YAML entry MUST be in CERTIFIED / STOPPED_G7 / REJECTED state
        // (no IMPLEMENTED_UNCERTIFIED without reason).
        val uncertifiedEntries = Regex("""- step_key: ([^\n]+)\n(?:[^:]*\n){0,12}?\s+certification_state: ([A-Z_]+)""")
            .findAll(yaml)
            .filter { it.groupValues[2] !in setOf("CERTIFIED", "STOPPED_G7", "REJECTED") }
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            emptyList<String>(),
            uncertifiedEntries,
            "No YAML entry may remain in non-terminal state; offending keys listed above",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // 3. Every CERTIFIED Step has a G8 receipt AND a real pipeline fixture
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every CERTIFIED Step has a G8 receipt that exists`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        // Find every step entry with certification_state: CERTIFIED.
        val certifiedBlocks = yaml.split("- step_key:")
            .drop(1) // discard preamble
            .filter { it.lineSequence().any { line -> line.trim() == "certification_state: CERTIFIED" } }

        for (block in certifiedBlocks) {
            val key = block.lineSequence().first().trim()
            val g8Line = block.lineSequence().firstOrNull { it.trim().startsWith("g8_receipt:") }
            val g8Path = g8Line?.substringAfter("g8_receipt:")?.trim()
            assertTrue(
                g8Path != null && g8Path != "null",
                "CERTIFIED Step '$key' MUST have a non-null g8_receipt",
            )
            val receiptFile = File(repoRoot, g8Path!!)
            assertTrue(
                receiptFile.exists(),
                "CERTIFIED Step '$key' has g8_receipt='$g8Path' but file does not exist on filesystem",
            )
        }
    }

    @Test
    fun `every STOPPED_G7 Step has a G7 STOP_BLOCKED receipt that exists`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        val stoppedBlocks = yaml.split("- step_key:")
            .drop(1)
            .filter { it.lineSequence().any { line -> line.trim() == "certification_state: STOPPED_G7" } }

        for (block in stoppedBlocks) {
            val key = block.lineSequence().first().trim()
            val g7Line = block.lineSequence().firstOrNull { it.trim().startsWith("g7_receipt:") }
            val g7Path = g7Line?.substringAfter("g7_receipt:")?.trim()
            assertTrue(
                g7Path != null && g7Path != "null",
                "STOPPED_G7 Step '$key' MUST have a non-null g7_receipt",
            )
            val receiptFile = File(repoRoot, g7Path!!)
            assertTrue(
                receiptFile.exists(),
                "STOPPED_G7 Step '$key' has g7_receipt='$g7Path' but file does not exist on filesystem",
            )
        }
    }

    @Test
    fun `every REJECTED Step has a rejection receipt that exists`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        val rejectedBlocks = yaml.split("- step_key:")
            .drop(1)
            .filter { it.lineSequence().any { line -> line.trim() == "certification_state: REJECTED" } }

        for (block in rejectedBlocks) {
            val key = block.lineSequence().first().trim()
            // REJECTED Steps are documented under legacy_receipts (the rejection memo),
            // not under g8/g7_receipt (which are null).
            val legacyReceiptsLine = block.lineSequence().firstOrNull {
                it.trim().startsWith("legacy_receipts:")
            }
            assertNotNull(
                legacyReceiptsLine,
                "REJECTED Step '$key' MUST have at least one legacy_receipts entry (the rejection memo)",
            )
            // Extract the rejection receipt path from the legacy_receipts block.
            val rejectionPath = block.lineSequence()
                .takeWhile { !it.trim().startsWith("real_fixtures:") }
                .firstOrNull { it.trim().startsWith("- docs/v2/07-uat/") }
                ?.trim()?.removePrefix("- ")
            assertTrue(
                rejectionPath != null && File(repoRoot, rejectionPath).exists(),
                "REJECTED Step '$key' rejection receipt '$rejectionPath' must exist on filesystem",
            )
        }
    }

    @Test
    fun `every CERTIFIED Step has at least one real maintained fixture that exists`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        val certifiedBlocks = yaml.split("- step_key:")
            .drop(1)
            .filter { it.lineSequence().any { line -> line.trim() == "certification_state: CERTIFIED" } }

        for (block in certifiedBlocks) {
            val key = block.lineSequence().first().trim()
            // Extract real_fixtures entries (between "real_fixtures:" and the next "-level" field).
            val fixtureLines = block.lineSequence()
                .dropWhile { !it.trim().startsWith("real_fixtures:") }
                .drop(1)
                .takeWhile { it.trim().startsWith("- v2/compatibility/") }
                .map { it.trim().removePrefix("- ") }
                .toList()

            // External plugins (example.uppercase) may have no fixture by convention.
            if (key == "example.uppercase") continue

            assertTrue(
                fixtureLines.isNotEmpty(),
                "CERTIFIED core Step '$key' MUST have at least one real maintained fixture",
            )
            for (path in fixtureLines) {
                assertTrue(
                    File(repoRoot, path).exists(),
                    "CERTIFIED Step '$key' has fixture '$path' but file does not exist on filesystem",
                )
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4. No CERTIFIED Step is reached through a legacy path
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `no CERTIFIED Step appears in LEGACY_PLUGIN_IDS or CanonicalCoreStepMetadata`() {
        val yamlFile = File(repoRoot, "docs/v2/status/step-certification.yaml")
        val yaml = yamlFile.readText()

        val certifiedKeys = yaml.split("- step_key:")
            .drop(1)
            .filter { it.lineSequence().any { line -> line.trim() == "certification_state: CERTIFIED" } }
            .map { it.lineSequence().first().trim() }
            .toSet()

        val legacyIds = CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS
        val metadataIds = CanonicalCoreStepMetadata.pluginIds

        for (key in certifiedKeys) {
            assertFalse(
                key in legacyIds,
                "CERTIFIED Step '$key' MUST NOT be in LEGACY_PLUGIN_IDS (legacy path closed for CERTIFIED)",
            )
            assertFalse(
                key in metadataIds,
                "CERTIFIED Step '$key' MUST NOT have a row in CanonicalCoreStepMetadata (registry is the metadata authority)",
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 5. Rollup drift = 0 (all four canonical documents report the same counters)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `counter rollup is consistent across YAML, MATRIX, INVENTORY, ECOSYSTEM_MATRIX`() {
        val yaml = File(repoRoot, "docs/v2/status/step-certification.yaml").readText()
        val matrix = File(repoRoot, "docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md").readText()
        val inventory = File(repoRoot, "docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md").readText()
        val ecosystem = File(repoRoot, "docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md").readText()

        // Pull the YAML counters (post-update).
        val certifiedCore = Regex("""certified_core_steps:\s*(\d+)""").find(yaml)?.groupValues?.get(1)?.toInt()
        val certifiedExternal = Regex("""certified_external_plugin_steps:\s*(\d+)""").find(yaml)?.groupValues?.get(1)?.toInt()
        val stoppedG7 = Regex("""stopped_g7_steps:\s*(\d+)""").find(yaml)?.groupValues?.get(1)?.toInt()
        val rejected = Regex("""rejected_steps:\s*(\d+)""").find(yaml)?.groupValues?.get(1)?.toInt()

        // Assert each counter is reflected in all four documents.
        assertNotNull(certifiedCore); assertNotNull(certifiedExternal)
        assertNotNull(stoppedG7); assertNotNull(rejected)

        // MATRIX: "CERTIFIED (core):                12"
        assertTrue(
            Regex("""CERTIFIED \(core\):\s+$certifiedCore\b""").containsMatchIn(matrix),
            "STEP_CERTIFICATION_MATRIX.md must report $certifiedCore CERTIFIED core Steps",
        )
        assertTrue(
            Regex("""CERTIFIED \(external plugin\):\s+$certifiedExternal\b""").containsMatchIn(matrix),
            "STEP_CERTIFICATION_MATRIX.md must report $certifiedExternal CERTIFIED external Steps",
        )
        assertTrue(
            Regex("""STOPPED at G7[^:]*:\s+$stoppedG7\b""").containsMatchIn(matrix),
            "STEP_CERTIFICATION_MATRIX.md must report $stoppedG7 STOPPED_G7 Steps",
        )
        assertTrue(
            Regex("""REJECTED:\s+$rejected\b""").containsMatchIn(matrix),
            "STEP_CERTIFICATION_MATRIX.md must report $rejected REJECTED Steps",
        )

        // INVENTORY: "CERTIFIED Steps: 12" / "STOPPED_G7 Steps: 2" / "REJECTED Steps: 1"
        assertTrue(
            Regex("""CERTIFIED Steps: $certifiedCore\b""").containsMatchIn(inventory),
            "STEP_INVENTORY_LFC2E0.md must report $certifiedCore CERTIFIED Steps",
        )
        assertTrue(
            Regex("""STOPPED_G7 Steps: $stoppedG7\b""").containsMatchIn(inventory),
            "STEP_INVENTORY_LFC2E0.md must report $stoppedG7 STOPPED_G7 Steps",
        )
        assertTrue(
            Regex("""REJECTED Steps: $rejected\b""").containsMatchIn(inventory),
            "STEP_INVENTORY_LFC2E0.md must report $rejected REJECTED Steps",
        )

        // ECOSYSTEM_MATRIX: "CERTIFIED: 13" (12+1) / "STOPPED_G7: 2" / "REJECTED: 1"
        val totalCertified = certifiedCore!! + certifiedExternal!!
        assertTrue(
            Regex("""CERTIFIED:\s+$totalCertified\b""").containsMatchIn(ecosystem),
            "STEP_ECOSYSTEM_MATRIX.md must report $totalCertified CERTIFIED Steps (sum of core + external)",
        )
        assertTrue(
            Regex("""STOPPED_G7:\s+$stoppedG7\b""").containsMatchIn(ecosystem),
            "STEP_ECOSYSTEM_MATRIX.md must report $stoppedG7 STOPPED_G7 Steps",
        )
        assertTrue(
            Regex("""REJECTED:\s+$rejected\b""").containsMatchIn(ecosystem),
            "STEP_ECOSYSTEM_MATRIX.md must report $rejected REJECTED Steps",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // 6. No DSL surface for REJECTED StepKeys (load() physically deleted)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `core load DSL function is physically deleted`() {
        val dslFile = File(repoRoot, "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt")
        assertTrue(dslFile.exists(), "PipelineDsl.kt must exist for the DSL surface audit")
        val dsl = dslFile.readText()
        assertFalse(
            Regex("""fun\s+(?:\w+\.)?load\s*\(""").containsMatchIn(dsl),
            "DSL `load(path)` function MUST be physically deleted post-CORE-LOAD-REJECTED",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // 7. Mandatory disabled acceptance tests are documented (0 in source)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `mandatory disabled acceptance tests are documented in matrix`() {
        val matrix = File(repoRoot, "docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md").readText()
        assertTrue(
            matrix.contains("Mandatory disabled acceptance tests"),
            "STEP_CERTIFICATION_MATRIX.md must contain a 'Mandatory disabled acceptance tests' section",
        )
        assertTrue(
            matrix.contains("SC-011-11"),
            "Mandatory disabled tests section must document SC-011-11 (OBSOLETE-per-REJECTION)",
        )
    }
}
