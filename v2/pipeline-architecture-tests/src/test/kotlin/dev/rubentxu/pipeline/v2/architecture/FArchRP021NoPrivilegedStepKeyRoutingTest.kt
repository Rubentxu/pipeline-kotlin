package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RP-021 fitness test: the engine MUST NOT contain a privileged
 * `when(stepKey)` / `when(stepName)` branch in the production routing
 * spine (PipelineRun / PipelineOrchestrator / CanonicalDurableRunCoordinator
 * and friends).
 *
 * The Step Constitution mandates that variation comes from
 * `StepKey → StepDefinition → StepHandler` resolution through the
 * registry, never from a concrete switch in a central dispatcher.
 *
 * The accompanying catalog is
 * `docs/v2/07-uat/RP_021_EXECUTION_ROUTES_CATALOG.md` and lists the
 * axes of variation (Authoring surface, Memory mode, Control flow,
 * Lifecycle) that the engine recognises.
 *
 * Reference: AGENTS.md §"Closed execution structure, open Step registry"
 * and "STEP CONSTITUTION & EXTENSIBILITY (MANDATORY)".
 */
class FArchRP021NoPrivilegedStepKeyRoutingTest {

    private val productionSrcRoots = listOf(
        "pipeline-application/src/main",
        "pipeline-domain/src/main",
    )

    private val forbiddenPatterns = listOf(
        // Direct when on a StepKey discriminator
        "when(stepKey)",
        "when (stepKey)",
        "when(stepName)",
        "when (stepName)",
        // Indirect: routing by step class name in a dispatcher
        "is CoreEchoStep",
        "is CoreShellStep",
    )

    @Test
    fun `production spine has no privileged StepKey or stepName when branch`() {
        val root = ScannerSupport.v2Root()
        val findings = forbiddenPatterns.flatMap { pattern ->
            productionSrcRoots.flatMap { srcRoot ->
                val dir = root.resolve(srcRoot)
                if (dir.toFile().exists()) {
                    ScannerSupport.findBuildSubstring(dir, pattern)
                } else {
                    emptyList()
                }
            }
        }
        assertTrue(
            findings.isEmpty(),
            "Production spine must not contain a privileged StepKey/stepName branch. " +
                "Variation belongs in StepContract (effects/replay/recovery/capabilities), " +
                "not in a central when. Findings: $findings"
        )
    }

    @Test
    fun `axes of variation are real implementation files, not placeholders`() {
        val root = ScannerSupport.v2Root()

        val requiredImplementations = listOf(
            // Authoring surface
            "pipeline-application/src/main/kotlin" to "CompositionRoot",         // declarative
            "pipeline-application/src/main/kotlin" to "scripted/ScriptedFrontendRunner", // scripted

            // Memory mode
            "pipeline-domain/src/main/kotlin" to "InMemoryRunCoordinator",
            "pipeline-domain/src/main/kotlin" to "InMemoryCompiledRunCoordinator",
            "pipeline-application/src/main/kotlin" to "durable/CanonicalDurableRunCoordinator",

            // Control flow
            "pipeline-domain/src/main/kotlin" to "durable/BranchInvoker",
            "pipeline-domain/src/main/kotlin" to "durable/RetryReconciler",
            "pipeline-domain/src/main/kotlin" to "durable/ParallelReconciler",
            "pipeline-domain/src/main/kotlin" to "durable/WaitUntilReconciler",
        )

        val missing = requiredImplementations.filter { (dirFragment, fileSubstring) ->
            val base = root.resolve(dirFragment)
            if (!base.toFile().exists()) {
                true
            } else {
                !base.toFile().walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .any { it.path.contains(fileSubstring) }
            }
        }

        assertTrue(
            missing.isEmpty(),
            "Every axis of variation documented in RP-021 must have a real " +
                "implementation file. Missing: $missing"
        )
    }
}
