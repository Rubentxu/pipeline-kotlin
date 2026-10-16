package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity

/**
 * The closed plan the harness executes, and the only thing a gate is allowed to accept as
 * "I ran the right tests".
 *
 * ## Why a plan and not a list of tests
 *
 * H0 established that a change must *select* contracts. Selection alone is not a gate: a selector
 * that silently returns nothing, or that returns a set the caller quietly narrows, produces the
 * same green as a selector that worked. So the selector's output is a value ([ValidationPlan]) with
 * exactly two cases, and [ValidationPlan.Refused] exists so "I could not decide" is a first-class
 * outcome rather than an empty list that reads as success.
 *
 * ## Why `Refused` carries reasons
 *
 * A refusal with no reasons is indistinguishable from a bug in the caller. Every refusal names
 * the specific unclassified path or the specific missing suite, because the operator's next action
 * differs: add a catalogue row versus re-run a suite.
 *
 * ## What this type deliberately cannot express
 *
 * There is no `Selected(emptySet())` reachable from a real change. [dev.rubentxu.pipeline.v2.harness
 * .plan.ImpactResolver] refuses an unclassified production path rather than returning an empty
 * selection, which is the property the mandate calls "a new critical file must not produce an empty
 * plan". Emptiness is expressible in Kotlin; it is not reachable from the resolver, and the
 * adversarial test pins that difference rather than trusting it.
 */
sealed interface ValidationPlan {

    /** The plan to execute. Every field is populated from the catalogue, never from a default. */
    data class Selected(
        val capabilities: Set<String>,
        val contracts: Set<ContractId>,
        val suites: Set<TestSuiteId>,
        val minimumFidelities: Set<Fidelity>,
        val reasons: List<SelectionReason>,
    ) : ValidationPlan

    /** The change cannot be validated with what the catalogue knows. This is never a PASS. */
    data class Refused(
        val reasons: List<SelectionRefusal>,
    ) : ValidationPlan
}

/** Identifies one contract row. */
@JvmInline
value class ContractId(val capability: String)

/**
 * Identifies one runnable suite.
 *
 * [gradleProject] is the module whose `test` task runs it. It is part of the value rather than
 * derived at dispatch time because a suite attributed to the wrong module is a suite that
 * silently does not run, and deriving it by guessing from the class name is exactly how that would
 * happen. The fitness `every bound suite names the module it actually lives in` checks it against
 * the repository, so the mistake is a red test rather than a suite that quietly never executes.
 *
 * A data class rather than a value class: Kotlin forbids a value class with two constructor
 * parameters, and boxing a two-field identity to save an allocation would be a poor trade for a
 * type that appears once per selected suite.
 */
data class TestSuiteId(val filter: String, val gradleProject: String)

/**
 * Why a capability entered the plan.
 *
 * The reason is part of the plan rather than a log line, because a gate has to be able to explain
 * itself to whoever is being blocked. Without it, "run these 40 suites" is unfalsifiable.
 */
sealed interface SelectionReason {
    /** A named source file changed. */
    data class SourceChanged(val path: String, val capability: String) : SelectionReason

    /** A module the changed file belongs to has no classification at all. */
    data class ModuleUnclassified(val module: String) : SelectionReason
}

/** Why the plan is refused. Each case names a different operator action. */
sealed interface SelectionRefusal {
    /** A production file the catalogue has never heard of. Action: add a row or justify the file. */
    /**
     * A production-shape path the catalogue does not bind.
     * H1 TODO 1 added [proposedCapability] and [proposedOwner]
     * so the operator has a starting point for adding a
     * rule, derived from path conventions (not a per-file
     * string table). The two fields are heuristic; the
     * operator replaces them by adding a catalogue row.
     */
    data class UnclassifiedPath(
        val path: String,
        val proposedCapability: String? = null,
        val proposedOwner: String? = null,
    ) : SelectionRefusal

    /**
     * H1 TODO 2: a suite in the plan has no sidecar
     * evidence at all (no JSON file alongside the JUnit
     * XML), or the sidecar is missing required fields.
     * The mandate says "Datos incompletos → Refused, no
     * passed". The [suite] is the binding's filter; the
     * refusal names the suite and the missing field so
     * the operator can re-run with the sidecar writer
     * enabled.
     */
    data class IncompleteEvidence(
        val suite: String,
        val missingField: String,
    ) : SelectionRefusal

    /**
     * H1 TODO 2: the suite's sidecar evidence declares
     * a different binary SHA than the expected commit.
     * The mandate says "SHA/artefacto y HF observado" —
     * the binary that produced the XML must match the
     * commit the plan is closing against. A mismatch is
     * a stale binary; the gate refuses until the build
     * is re-anchored.
     */
    data class StaleBinary(
        val suite: String,
        val expected: String,
        val observed: String,
    ) : SelectionRefusal

    /**
     * H1 TODO 2: the suite's sidecar evidence declares
     * a fidelity that is not in the plan's
     * `minimumFidelities`. The plan's `minimumFidelities`
     * is the union of every contract's `minFidelity`
     * required by the change; a suite that ran at a
     * lower fidelity discharged nothing.
     */
    data class InsufficientFidelity(
        val suite: String,
        val observed: String,
        val required: Set<String>,
    ) : SelectionRefusal

    /** A required suite has no runnable target in the policy. Action: restore the suite binding. */
    data class MissingSuite(val capability: String, val suite: String) : SelectionRefusal

    /** The evidence names a suite that the plan selected but that was not executed. */
    data class SelectedButNotRun(val suite: String) : SelectionRefusal

    /**
     * A Gradle invocation the plan required exited non-zero, so the suites it owned have no
     * trustworthy evidence for this run.
     *
     * Distinct from [SelectedButNotRun]: there the suite may have run and produced nothing usable,
     * here the build that would have run it did not succeed at all. Both refuse, but the operator
     * action differs — one is a coverage gap, the other is a broken invocation.
     */
    data class SuiteDidNotRun(val suite: String, val gradleProject: String) : SelectionRefusal

    /** The evidence was produced by a binary built from a different commit. */
    data class StaleEvidence(val expected: String, val observed: String) : SelectionRefusal
}

/**
 * Which suites must run for a capability, and how well they must run.
 *
 * The catalogue said "this needs HF2". That is a fidelity *requirement*; it says nothing about
 * which test would discharge it. This table is the bridge, and it is where the mandate's
 * "it is not enough to obtain HF2" is answered: the plan names concrete runnable suites.
 */
data class SuiteBinding(
    val capability: String,
    val suites: List<TestSuiteId>,
    val fidelity: Fidelity,
)

/** The policy mapping capabilities to the suites that discharge them. */
object SuitePolicy {

    /**
     * The suite table.
     *
     * Each capability names at least one suite whose filter is a real Gradle test pattern. The
     * fitness asserts every filter resolves to a test class that exists, which is what stops a
     * removed suite from quietly satisfying a capability forever.
     */
    val BINDINGS: List<SuiteBinding> = listOf(
        SuiteBinding(
            capability = "OutputPlane.redaction.streaming",
            suites = listOf(
                TestSuiteId("ShTranscriptRedactionReproductionTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.write.commit",
            suites = listOf(
                TestSuiteId("ShTranscriptRedactionReproductionTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.pump.drain",
            suites = listOf(
                TestSuiteId("DurableShellExecutorAdversarialTest", ":pipeline-step-sdk:runtime"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.read.cursor",
            suites = listOf(
                TestSuiteId("B1aOutputPlaneProviderLifecycleCharacterizationTest", ":pipeline-application"),
                TestSuiteId("D7CursorOwnershipBehaviourTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Shell.execution.composition",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest", ":pipeline-application"),
                TestSuiteId("ShTranscriptRedactionReproductionTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Console.cli.selection",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Build.composition",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest", ":pipeline-application"),
                TestSuiteId("ContractCatalogueFitnessTest", ":test-harness"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Provenance.build.identity",
            suites = listOf(
                TestSuiteId("RuntimeBuildIdentityTest", ":pipeline-application"),
                TestSuiteId("BuildProvenanceIsReportedByTheInstalledBinaryTest", ":pipeline-application"),
                TestSuiteId("AppBinProvenanceTest", ":pipeline-application"),
            ),
            fidelity = Fidelity.HF2,
        ),
        // H0 UAT fixture binding: the H0 ephemeral-cycle UAT
        // (docs/pipelinek-delivery-first-2026-10-16/, TODO 4)
        // exercises the cycle's state machine and the
        // PromotionEvidenceVerifier at HF0. The binding points
        // to a real test class so the SuitePolicy fitness
        // ("every filter resolves to a real test class")
        // passes. The UAT's contract is `OutputPlane.uat.fixture`
        // in ContractCatalogue; the runner's HF0 path needs a
        // suite binding to avoid MissingSuite refusal.
        SuiteBinding(
            capability = "OutputPlane.uat.fixture",
            suites = listOf(
                TestSuiteId("PromotionEvidenceVerifierTest", ":test-harness"),
            ),
            fidelity = Fidelity.HF0,
        ),
    )

    /** The suites for [capability], empty when the capability is unbound. */
    fun suitesFor(capability: String): List<TestSuiteId> =
        BINDINGS.filter { it.capability == capability }.flatMap { it.suites }
}
