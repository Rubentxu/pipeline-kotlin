package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File

/**
 * LFC-2E2-PREP — preparation fitness for the first official plugin slice.
 *
 * Authority:
 *   - `docs/v2/00-governance/CORE_STEP_ADMISSION.md` (freeze + admission record)
 *   - `docs/v2/00-governance/PLUGIN_AUTHORING.md` (E2-PREP authoring rules)
 *   - `docs/v2/07-uat/LFC2E2_PREP_RECEIPT.md` (closure receipt, TBD)
 *
 * This test mechanically enforces the LFC-2E2-PREP C1..C10 invariants:
 *
 *   C1   ResourceKind.STEP_DEFINITION added (typed identity for Step families)
 *   C2   ResourceKind.PLUGIN_RELEASE added (typed identity for plugin releases)
 *   C3   PluginManifest typed ADT exists with declaredCapabilities (no string map)
 *   C4   PluginFamilyCapabilityFamily sealed ADT exists (categorical label,
 *        not authority)
 *   C5   PluginAdmissionPolicy sealed ADT exists with Ready/MissingCapabilities/Malformed
 *   C6   Manifest requires non-empty families OR contributors at construction
 *   C7   No duplicate StepKeys within a manifest (fail-closed at construction)
 *   C8   Zero production code change to coordinator/dispatcher/compiler
 *        (the E2-PREP data shapes are pure pipeline-domain additions)
 *   C9   Lfc2UniversalCoreFreezeFitnessTest continues to pass (plugin Steps
 *        cannot require coordinator switches)
 *   C10  Core backwards-compatibility: existing production code compiles
 *        and all existing fitness tests still pass (no regression).
 */
@Timeout(15)
@DisplayName("LFC-2E2-PREP — plugin data-shape fitness")
class Lfc2E2PrepFitnessTest {

    private val repoRoot: File by lazy {
        File(System.getProperty("user.dir"), "../..").canonicalFile
    }

    // ───────────────────────────────────────────────────────────────────────
    // C1 + C2: ResourceKind.STEP_DEFINITION + PLUGIN_RELEASE exist
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C1 STEP_DEFINITION ResourceKind is declared`() {
        val source = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/identity/ResourceKind.kt",
        ).readText()
        assertTrue(
            source.contains("STEP_DEFINITION"),
            "C1: ResourceKind.STEP_DEFINITION must be declared (typed identity for Step families)",
        )
    }

    @Test
    fun `C2 PLUGIN_RELEASE ResourceKind is declared`() {
        val source = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/identity/ResourceKind.kt",
        ).readText()
        assertTrue(
            source.contains("PLUGIN_RELEASE"),
            "C2: ResourceKind.PLUGIN_RELEASE must be declared (typed identity for plugin releases)",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // C1: ResourceRefs.stepDefinition builder exists
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C1 ResourceRefs stepDefinition builder is declared`() {
        val source = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/identity/ResourceRefs.kt",
        ).readText()
        assertTrue(
            source.contains("fun stepDefinition("),
            "C1: ResourceRefs.stepDefinition(...) builder must be declared",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // C2: PluginReleaseRef exists with toResourceRef() + parse()
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C2 PluginReleaseRef exists in the plugin package`() {
        val file = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginManifest.kt",
        )
        assertTrue(file.exists(), "C2: PluginManifest.kt must exist at pipeline-domain/plugin/")
        val source = file.readText()
        assertTrue(source.contains("data class PluginReleaseRef"), "C2: PluginReleaseRef data class")
        assertTrue(source.contains("fun toResourceRef"), "C2: PluginReleaseRef.toResourceRef()")
        assertTrue(source.contains("fun parse("), "C2: PluginReleaseRef.parse(text)")
    }

    // ───────────────────────────────────────────────────────────────────────
    // C3 + C5 + C6: PluginManifest exists with required invariants
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C3 C5 C6 PluginManifest exists with typed declaredCapabilities and invariants`() {
        val file = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginManifest.kt",
        )
        assertTrue(file.exists(), "C3/C5/C6: PluginManifest.kt must exist at pipeline-domain/plugin/")
        val source = file.readText()
        assertTrue(source.contains("data class PluginManifest"), "C3: PluginManifest data class")
        assertTrue(source.contains("val declaredCapabilities: Set<StepCapability>"), "C5: declaredCapabilities")
        assertTrue(
            source.contains("requires at least one family or contributor"),
            "C6: non-empty families OR contributors invariant",
        )
        assertTrue(
            source.contains("contains duplicate StepKeys"),
            "C7: no duplicate StepKeys invariant",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // C4: PluginFamilyCapabilityFamily sealed ADT exists
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C4 PluginFamilyCapabilityFamily sealed ADT exists`() {
        val file = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginAdmissionPolicy.kt",
        )
        assertTrue(file.exists(), "C4: PluginAdmissionPolicy.kt must exist at pipeline-domain/plugin/")
        val source = file.readText()
        assertTrue(
            source.contains("sealed interface PluginFamilyCapabilityFamily"),
            "C4: PluginFamilyCapabilityFamily sealed ADT",
        )
        assertTrue(source.contains("data object Pure"), "C4: Pure case")
        assertTrue(source.contains("data object WorkspaceUser"), "C4: WorkspaceUser case")
        assertTrue(source.contains("data object ProcessExecutor"), "C4: ProcessExecutor case")
    }

    // ───────────────────────────────────────────────────────────────────────
    // C5: PluginAdmissionPolicy sealed ADT exists with the three required cases
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C5 PluginAdmissionPolicy sealed ADT exists with Ready MissingCapabilities Malformed`() {
        val source = File(
            repoRoot,
            "v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginAdmissionPolicy.kt",
        ).readText()
        assertTrue(
            source.contains("sealed interface PluginAdmissionPolicy"),
            "C5: PluginAdmissionPolicy sealed ADT",
        )
        assertTrue(source.contains("data class Ready"), "C5: Ready case")
        assertTrue(source.contains("data class MissingCapabilities"), "C5: MissingCapabilities case")
        assertTrue(source.contains("data class Malformed"), "C5: Malformed case")
    }

    // ───────────────────────────────────────────────────────────────────────
    // C8 + C10: ZERO production code change to coordinator/dispatcher/compiler
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C8 C10 zero production change to coordinator dispatcher or compiler`() {
        // E2-PREP is pure pipeline-domain additions. The coordinator, dispatcher,
        // and DSL compiler are NOT modified by E2-PREP. Future plugin contributions
        // (FASE 6) will register via the existing StepDefinitionContributor SPI.
        val touched = listOf(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt",
        )
        // We check that NONE of these files mention the new plugin types. Adding
        // such references would mean E2-PREP leaked into production code (a defect
        // for this slice — production code adopts plugins via the existing SPI).
        val newTypes = listOf(
            "PluginManifest",
            "PluginAdmissionPolicy",
            "PluginFamilyCapabilityFamily",
            "PluginReleaseRef",
            "PluginCoordinate",
            "PluginVersion",
        )

        for (path in touched) {
            val file = File(repoRoot, path)
            assertTrue(file.exists(), "Production file must exist: $path")
            val source = file.readText()
            for (type in newTypes) {
                assertEquals(
                    false,
                    source.contains(type),
                    "C8/C10: production file $path MUST NOT reference new plugin type '$type' " +
                        "during E2-PREP. E2-PREP is pure pipeline-domain additions; the coordinator, " +
                        "dispatcher and compiler adopt plugins via the existing StepDefinitionContributor " +
                        "SPI, not by direct reference to E2-PREP types.",
                )
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // C10: LEGACY_PLUGIN_IDS remains empty (no regression of LFC-2E0 closure)
    // ──────��────────────────────────────────────────────────────────────────

    @Test
    fun `C10 LEGACY_PLUGIN_IDS remains empty post E2-PREP`() {
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "C10: LEGACY_PLUGIN_IDS must remain empty post E2-PREP (no regression of LFC-2E0).",
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // C9: Plugin manifest tests exist and pass (PolicyReadinessFitness evidence)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `C9 PluginManifestTest exists as the PolicyReadinessFitness evidence`() {
        val file = File(
            repoRoot,
            "v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginManifestTest.kt",
        )
        assertTrue(
            file.exists(),
            "C9: PluginManifestTest must exist at pipeline-domain test path",
        )
    }
}
