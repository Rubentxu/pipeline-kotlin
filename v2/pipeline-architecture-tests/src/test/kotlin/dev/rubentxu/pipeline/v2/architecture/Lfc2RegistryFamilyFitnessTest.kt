package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / CDE.3-e — registry execution-structure fitness (closed world for structure, open world for
 * Step semantics).
 *
 * Makes the structural routing decision of the durable coordinator architecturally irreversible: the
 * coordinator's PREPARE selection runs on the closed structural family token
 * ([StructuralStepFamily], classified by [StructuralFamilyResolver] as LegacyCore vs Registry), NEVER
 * by enumerating concrete Step/plugin names. This guards against regressing e3 back into an open
 * catalog switch (the exact correction that reopened e3) and against the coordinator learning about a
 * registry/plugin name.
 *
 * Behavioral law coverage (typed codec not run on replay/divergence; handler not run without capability
 * admission; ephemeral PreparedExecution) lives in RegistryDurableSpineTest DREG-1..5 on the real
 * spine; this file locks the structural routing form.
 */
class Lfc2RegistryFamilyFitnessTest {

    private val coordinatorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt")
    private val familySource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/StructuralStepFamily.kt")

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Structural-family routing is the ONLY prepare-selection form in the coordinator. */
    @Test
    fun `coordinator prepares by closed structural family, never by step name`() {
        val source = read(coordinatorSource)
        assertTrue(
            source.contains("StructuralFamilyResolver.classify"),
            "Coordinator must classify the invocation into a closed StructuralStepFamily before prepare",
        )
        assertTrue(
            source.contains("when (family)"),
            "Coordinator must switch its prepare strategy on the structural family token",
        )
    }

    /** The family is closed over exactly the two structural forms (LegacyCore | Registry). */
    @Test
    fun `structural family is closed over legacy-core and registry only`() {
        val source = read(familySource)
        assertTrue(source.contains("data object LegacyCore : StructuralStepFamily"), "LegacyCore family required")
        assertTrue(source.contains("data object Registry : StructuralStepFamily"), "Registry family required")
        // Classification is by membership in the closed legacy authority plus the open registry, not by
        // enumerating concrete step names inside the family file.
        assertTrue(source.contains("CanonicalCoreStepCommand.ALL_PLUGIN_IDS"), "Closed core authority must drive the family split")
        assertTrue(source.contains("classify(stepKey"), "Family classifier must classify a step key")
    }

    /** The coordinator must not bake a concrete registry/core step name into its selection. */
    @Test
    fun `coordinator carries no concrete plugin-name registry routing`() {
        val source = read(coordinatorSource)
        // Guard against re-introducing an open catalog selection by any step/plugin key literal.
        assertFalse(source.contains("step.pluginStepId.value == "), "No equality routing on a concrete step key")
        assertFalse(
            source.contains("RegistryExecutionPreparation.prepare(") &&
                !source.contains("StructuralStepFamily.Registry"),
            "Registry prepare must be reachable only under the Registry structural family",
        )
    }
}
