package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * The eight adversarial cases from the mandate, plus the properties they are really asserting.
 *
 * ## Harness class
 *
 * `model` / `pure`. These tests enter through [ImpactResolver.resolve] and
 * [ImpactResolver.adjudicate], which ARE the productive authority for this slice: the resolver is
 * the thing Gradle will call, and these tests call it directly with its real arguments. Nothing is
 * reimplemented — there is no second decision table to drift from, which was the failure mode the
 * `EffectReplayPolicyTableFitnessTest` had to police elsewhere.
 *
 * The one thing these tests do NOT prove is that Gradle actually calls the resolver. That is a
 * separate concern, and it is claimed only where a Gradle integration test observes the plan.
 */
class ImpactResolverAdversarialTest {

    private val redactor = "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "credentials/api/StreamingRedactor.kt"
    private val pump = "v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "sdk/runtime/durable/DurableShellExecutor.kt"
    private val shExecution = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "application/durable/ShExecution.kt"
    private val appBuild = "v2/pipeline-application/build.gradle.kts"

    private fun selected(paths: List<String>): ValidationPlan.Selected {
        val plan = ImpactResolver.resolve(paths)
        assertTrue(
            plan is ValidationPlan.Selected,
            "expected a selection for $paths but got ${plan::class.simpleName}: " +
                ((plan as? ValidationPlan.Refused)?.reasons?.joinToString() ?: ""),
        )
        return plan as ValidationPlan.Selected
    }

    // ---- 1. changing the redactor selects its contracts, its tests and the secret canary ----

    @Test
    fun `case 1 changing the redactor selects its capability and a runnable suite`() {
        val plan = selected(listOf(redactor))
        assertEquals(setOf("OutputPlane.redaction.streaming"), plan.capabilities)
        assertTrue(
            plan.suites.map { it.filter }.contains("ShTranscriptRedactionReproductionTest"),
            "the redactor must select the suite that reproduces the transcript leak, got ${plan.suites}",
        )
        assertTrue(
            plan.minimumFidelities.contains(ContractCatalogue.Fidelity.HF2),
            "a redaction change cannot be certified below HF2",
        )
    }

    // ---- 2. changing the pump selects the pump tests ----

    @Test
    fun `case 2 changing the pump selects the executor suite`() {
        val plan = selected(listOf(pump))
        assertEquals(setOf("OutputPlane.pump.drain"), plan.capabilities)
        assertTrue(
            plan.suites.map { it.filter }.contains("DurableShellExecutorAdversarialTest"),
            "the pump must select its adversarial suite, got ${plan.suites}",
        )
    }

    // ---- 3. changing ShExecution activates real composition validation ----

    @Test
    fun `case 3 changing ShExecution selects the composition suite`() {
        val plan = selected(listOf(shExecution))
        assertEquals(setOf("Shell.execution.composition"), plan.capabilities)
        assertTrue(
            plan.suites.map { it.filter }.contains("CompatibilityCorpusTest"),
            "shell composition must select the corpus, got ${plan.suites}",
        )
    }

    // ---- 4. changing the build file invalidates prior artefact provenance ----

    @Test
    fun `case 4 changing the application build selects provenance`() {
        val plan = selected(listOf(appBuild))
        assertEquals(setOf("Provenance.build.identity"), plan.capabilities)
        assertTrue(
            plan.suites.map { it.filter }
                .containsAll(listOf("RuntimeBuildIdentityTest", "AppBinProvenanceTest")),
            "a build change must select both provenance suites, got ${plan.suites}",
        )
    }

    // ---- 5. an unknown critical file must NOT produce an empty plan ----

    @Test
    fun `case 5 an unclassified production file refuses rather than selecting nothing`() {
        val unknown = "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
            "application/BrandNewExecutionRoute.kt"
        val plan = ImpactResolver.resolve(listOf(unknown))
        assertTrue(
            plan is ValidationPlan.Refused,
            "an unclassified production path must refuse; an empty selection reads as a pass",
        )
        val reasons = (plan as ValidationPlan.Refused).reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.UnclassifiedPath && it.path == unknown },
            "the refusal must name the offending path, got $reasons",
        )
    }

    // ---- 6. removing a mandatory suite cannot produce a pass ----

    @Test
    fun `case 6 a capability with no suite binding refuses instead of selecting`() {
        // The real shape of this defect is a catalogue row whose capability nobody bound to a
        // suite: a contract that looks protected and discharges nothing. The resolver already
        // refuses that, and the refusal is what this pins. Binding policy is passed in so the
        // scenario is constructed rather than waited for.
        val plan = ImpactResolver.resolveWithPolicy(
            changedPaths = listOf(redactor),
            bindings = emptyList(),
        )
        assertTrue(
            plan is ValidationPlan.Refused,
            "a capability with no suite must refuse; selecting it would claim protection it does not give",
        )
        val reasons = (plan as ValidationPlan.Refused).reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.MissingSuite },
            "the refusal must say a suite binding is missing, got $reasons",
        )
    }

    // ---- 7. a selected but unexecuted suite causes rejection ----

    @Test
    fun `case 7 a selected suite that was not executed is rejected`() {
        val plan = selected(listOf(redactor))
        val refusal = ImpactResolver.adjudicate(
            plan = plan,
            executedSuites = emptySet(),
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )
        assertNotNull(refusal, "nothing was executed, so the gate must refuse")
        assertTrue(
            (refusal as ValidationPlan.Refused).reasons.any { it is SelectionRefusal.SelectedButNotRun },
            "the refusal must name the unexecuted suite, got ${refusal.reasons}",
        )
    }

    // ---- 8. evidence from a different SHA is rejected ----

    @Test
    fun `case 8 evidence from a different commit is rejected`() {
        val plan = selected(listOf(redactor))
        val executed = plan.suites.map { it.filter }.toSet()
        val refusal = ImpactResolver.adjudicate(
            plan = plan,
            executedSuites = executed,
            expectedCommit = "abc123",
            observedCommit = "def456",
        )
        assertNotNull(refusal, "a binary from another commit must be rejected")
        val reasons = (refusal as ValidationPlan.Refused).reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.StaleEvidence && it.expected == "abc123" },
            "the refusal must name both commits, got $reasons",
        )
    }

    // ---- the positive adjudication: everything ran, on the right commit ----

    @Test
    fun `a fully executed plan on the matching commit adjudicates clean`() {
        val plan = selected(listOf(redactor))
        val executed = plan.suites.map { it.filter }.toSet()
        assertNull(
            ImpactResolver.adjudicate(plan, executed, "abc123", "abc123"),
            "a plan whose suites all ran on the expected commit must not refuse",
        )
    }

    // ---- docs and test edits neither select nor refuse ----

    @Test
    fun `a documentation change selects nothing and refuses nothing`() {
        val plan = ImpactResolver.resolve(listOf("docs/v2/05-roadmap/ROADMAP.md", "README.md"))
        val selectedPlan = plan as ValidationPlan.Selected
        assertTrue(selectedPlan.suites.isEmpty(), "a doc change has no suite at stake")
        assertTrue(selectedPlan.reasons.isEmpty(), "a doc change has no reason to run anything")
    }

    // ---- every suite binding resolves to a test class that exists ----

    @Test
    fun `every bound suite names a test class that exists in the repository`() {
        val root = Paths.get("..").toAbsolutePath().normalize()
        val sources = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .map { Files.readString(it) }
                .toList()
        }
        val missing = SuitePolicy.BINDINGS
            .flatMap { it.suites }
            .map { it.filter }
            .distinct()
            .filter { suite -> sources.none { it.contains("class $suite") } }
        assertTrue(
            missing.isEmpty(),
            "these suites are bound but no test class declares them, so the binding is fiction: $missing",
        )
    }

    // ---- BQ1 audit: production-impacting extensions ----
    // The previous heuristic missed `.proto` files (wire contracts)
    // and `META-INF/services/*` (SPI bindings). Both are product
    // changes: a renamed ServiceLoader line changes the
    // implementation chosen at runtime; a `.proto` change is a
    // wire contract change. The audit at 2026-10-16 found both
    // gaps; these tests pin the corrected heuristic.

    @Test
    fun `proto schema under src main is a production path`() {
        assertTrue(ImpactResolver.isProductionPath("v2/pipeline-domain/src/main/proto/order.proto"))
    }

    @Test
    fun `proto schema in any module is a production path`() {
        // The `.proto` extension is a wire contract regardless of
        // where the file lives. A protobuf in the docs folder is
        // still a product change.
        assertTrue(ImpactResolver.isProductionPath("v2/pipeline-events/proto/event.proto"))
    }

    @Test
    fun `META-INF services resource is a production path`() {
        // ServiceLoader bindings live under META-INF/services/.
        // Renaming a binding changes which implementation is
        // chosen at runtime.
        assertTrue(
            ImpactResolver.isProductionPath(
                "v2/pipeline-application/src/main/resources/META-INF/services/" +
                    "dev.rubentxu.pipeline.v2.application.Steps",
            ),
        )
    }

    @Test
    fun `META-INF services change in any module is a production path`() {
        assertTrue(
            ImpactResolver.isProductionPath(
                "v2/pipeline-scripting-kotlin24/src/main/resources/META-INF/services/" +
                    "javax.script.ScriptEngineFactory",
            ),
        )
    }
}

/** Path helper kept explicit so the adversarial tests never depend on ambient state. */
private fun unusedPathAnchor(): Path = Paths.get(".")