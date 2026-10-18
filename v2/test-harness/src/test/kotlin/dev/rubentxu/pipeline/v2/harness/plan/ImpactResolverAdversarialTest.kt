package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            executedSuites = adjudicateEvidence(plan, emptySet(), expectedCommit = "abc123"),
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
            executedSuites = adjudicateEvidence(plan, executed, expectedCommit = "abc123"),
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
        val commit40 = "0".repeat(40)
        assertNull(
            ImpactResolver.adjudicate(
                plan,
                adjudicateEvidence(plan, executed, expectedCommit = commit40),
                commit40,
                commit40,
            ),
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

    // ---- BQ1 audit (rc4 follow-up, 2026-10-16): build-distribution resources
    // The rc4 HANDOFF claimed that the new
    // `Build.composition.pathPatterns = listOf("v2/gradle/wrapper/gradle-wrapper.properties")`
    // would fire on a change to that path. The audit found the resolver's
    // `isProductionPath` filtered the change out before the catalogue was
    // consulted, so the contract never entered the plan. These tests pin the
    // extended heuristic (see `ImpactResolver.isProductionPath` KDoc and the
    // `QUALITY_VALUE_ADDENDUM.md` §BQ1 line 33 audit scope).

    @Test
    fun `gradle wrapper properties is a production path`() {
        // AAT-BQ1-05: a change to the Gradle wrapper properties
        // is a production change (which build runs the project).
        assertTrue(
            ImpactResolver.isProductionPath(
                "v2/gradle/wrapper/gradle-wrapper.properties",
            ),
        )
    }

    @Test
    fun `gradle wrapper jar is a production path`() {
        // AAT-BQ1-06 (companion of AAT-BQ1-05): the wrapper
        // jar that bootstraps the Gradle distribution.
        assertTrue(
            ImpactResolver.isProductionPath("v2/gradle/wrapper/gradle-wrapper.jar"),
        )
    }

    @Test
    fun `root gradle properties is a production path`() {
        // AAT-BQ1-07: gradle.properties carries daemon
        // memory, parallel mode, and JVM flags. A change
        // here invalidates prior build provenance.
        assertTrue(ImpactResolver.isProductionPath("v2/gradle.properties"))
    }

    @Test
    fun `libs versions toml at any depth is a production path`() {
        // AAT-BQ1-08: the version catalog at any module
        // depth. A version bump in `libs.versions.toml`
        // changes the resolved version of every dependency
        // that consumes it.
        assertTrue(
            ImpactResolver.isProductionPath(
                "v2/pipeline-application/gradle/libs.versions.toml",
            ),
        )
    }

    @Test
    fun `gradlew script is a production path`() {
        // AAT-BQ1-09: the wrapper script. Renaming or
        // removing `gradlew` is a product change because
        // the entire build invocation flows through it.
        assertTrue(ImpactResolver.isProductionPath("v2/gradlew"))
    }

    @Test
    fun `unknown build resource under v2 gradle is classified production but refuses when unclassified`() {
        // AAT-BQ1-10: the shape of the remaining defect. A
        // real build file the catalogue does not bind must
        // REFUSE rather than silently select nothing. The
        // path is under `v2/gradle/` so the new heuristic
        // recognises it as production; the catalogue's
        // `Build.composition` does not list it, so the
        // resolver must surface `UnclassifiedPath` instead
        // of returning an empty `Selected`.
        val unknown = "v2/gradle/custom-thing.txt"
        assertTrue(
            ImpactResolver.isProductionPath(unknown),
            "a path under v2/gradle/ must be classified as production",
        )
        val plan = ImpactResolver.resolve(listOf(unknown))
        assertTrue(
            plan is ValidationPlan.Refused,
            "an unclassified build resource must refuse, got $plan",
        )
        val reasons = (plan as ValidationPlan.Refused).reasons
        assertTrue(
            reasons.any { it is SelectionRefusal.UnclassifiedPath && it.path == unknown },
            "the refusal must name the unclassified build resource, got $reasons",
        )
    }

    @Test
    fun `changing gradle-wrapper properties selects Build composition contract end to end`() {
        // AAT-BQ1-11: the end-to-end shape. The contract
        // that claims this path must actually appear in the
        // plan with the suites the catalogue binds to it.
        // This is the test that proves the rc4 claim is now
        // backed by the resolver, not just the catalogue.
        val plan = ImpactResolver.resolve(
            listOf("v2/gradle/wrapper/gradle-wrapper.properties"),
        )
        assertTrue(
            plan is ValidationPlan.Selected,
            "the plan must be Selected, got $plan",
        )
        val selected = plan as ValidationPlan.Selected
        assertTrue(
            selected.capabilities.contains("Build.composition"),
            "Build.composition must be selected, got ${selected.capabilities}",
        )
        val suiteFilters = selected.suites.map { it.filter }.toSet()
        assertTrue(
            suiteFilters.containsAll(
                setOf("CompatibilityCorpusTest", "ContractCatalogueFitnessTest"),
            ),
            "the suites bound to Build.composition must be selected, got $suiteFilters",
        )
    }

    /**
     * Measured 2026-10-18 against `scripts/validation-gate.py`: a real
     * change to `v2/pipeline-release/build.gradle.kts` was REFUSED with
     * `unclassified production path ... add a catalogue row or remove the
     * file`. That file decides what ships inside the distribution ZIP, so
     * the gate was unable to certify a change to what it packages.
     *
     * The classifier already called it Production `BuildComposition`; only
     * the catalogue was missing. `Provenance.build.identity` is the right
     * owner because a module's dependencies are exactly what the
     * distribution contains.
     */
    @Test
    fun `changing a module build file selects the provenance identity contract end to end`() {
        val plan = ImpactResolver.resolve(
            listOf("v2/pipeline-release/build.gradle.kts"),
        )
        assertTrue(
            plan is ValidationPlan.Selected,
            "a module build file must be certifiable, got $plan",
        )
        val selected = plan as ValidationPlan.Selected
        assertTrue(
            selected.capabilities.contains("Provenance.build.identity"),
            "Provenance.build.identity must be selected, got ${selected.capabilities}",
        )
        val suiteFilters = selected.suites.map { it.filter }.toSet()
        assertTrue(
            suiteFilters.contains("RuntimeBuildIdentityTest"),
            "the identity suites must be selected, got $suiteFilters",
        )
    }

    /**
     * The control for the row above, and the reason it cannot be
     * satisfied by claiming every `build.gradle.kts` under one capability
     * for the wrong reason.
     *
     * The ROOT `build.gradle.kts` pins the JVM toolchain for every
     * subproject, which is composition rather than identity. If it were
     * absorbed by the identity capability the two rows would be
     * indistinguishable and the split would be a name, not a decision.
     */
    @Test
    fun `the root build file is composition and not identity`() {
        val plan = ImpactResolver.resolve(listOf("build.gradle.kts"))
        assertTrue(plan is ValidationPlan.Selected, "got $plan")
        val selected = plan as ValidationPlan.Selected
        assertTrue(
            selected.capabilities.contains("Build.composition"),
            "Build.composition must be selected, got ${selected.capabilities}",
        )
        assertFalse(
            selected.capabilities.contains("Provenance.build.identity"),
            "the root build file pins the toolchain, so claiming it as build identity " +
                "would make the two capabilities indistinguishable and the split arbitrary",
        )
    }

    /**
     * The control for the OTHER half of this change.
     *
     * `SELF_OWNED_PREFIXES` used to make every `v2/test-harness/` path
     * non-production. Its consumer was deleted by BQ4 P0, the constant
     * survived, and the KDoc kept asserting an exemption the wiring did
     * not have. If anyone restores the exemption by prefix, a defect in
     * the harness's own `src/main` code becomes a silent green again --
     * so this row pins the refusal instead of merely allowing it.
     */
    @Test
    fun `a harness production source still refuses for want of a contract`() {
        val plan = ImpactResolver.resolve(
            listOf("v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ChangedInput.kt"),
        )
        assertTrue(
            plan is ValidationPlan.Refused,
            "the harness is the product; restoring a self-owned prefix exemption would make a " +
                "harness defect a silent green, so this path must keep refusing until a contract " +
                "covers it. Got $plan",
        )
        val refused = plan as ValidationPlan.Refused
        assertTrue(
            refused.reasons.any { it is SelectionRefusal.UnclassifiedPath },
            "got ${refused.reasons}",
        )
    }
}

/** Path helper kept explicit so the adversarial tests never depend on ambient state. */
private fun unusedPathAnchor(): Path = Paths.get(".")