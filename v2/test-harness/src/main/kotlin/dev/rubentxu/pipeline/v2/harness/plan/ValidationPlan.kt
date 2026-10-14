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
 * This is a value class over a Gradle test filter, so a suite cannot exist in the catalogue
 * without also naming the thing that runs it. A suite with no runnable target is a refusal, not a
 * suite.
 */
@JvmInline
value class TestSuiteId(val filter: String)

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
    data class UnclassifiedPath(val path: String) : SelectionRefusal

    /** A required suite has no runnable target in the policy. Action: restore the suite binding. */
    data class MissingSuite(val capability: String, val suite: String) : SelectionRefusal

    /** The evidence names a suite that the plan selected but that was not executed. */
    data class SelectedButNotRun(val suite: String) : SelectionRefusal

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
                TestSuiteId("ShTranscriptRedactionReproductionTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.write.commit",
            suites = listOf(
                TestSuiteId("ShTranscriptRedactionReproductionTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.pump.drain",
            suites = listOf(
                TestSuiteId("DurableShellExecutorAdversarialTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "OutputPlane.read.cursor",
            suites = listOf(
                TestSuiteId("B1aOutputPlaneProviderLifecycleCharacterizationTest"),
                TestSuiteId("D7CursorOwnershipBehaviourTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Shell.execution.composition",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest"),
                TestSuiteId("ShTranscriptRedactionReproductionTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Console.cli.selection",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Build.composition",
            suites = listOf(
                TestSuiteId("CompatibilityCorpusTest"),
                TestSuiteId("ContractCatalogueFitnessTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
        SuiteBinding(
            capability = "Provenance.build.identity",
            suites = listOf(
                TestSuiteId("RuntimeBuildIdentityTest"),
                TestSuiteId("BuildProvenanceIsReportedByTheInstalledBinaryTest"),
                TestSuiteId("AppBinProvenanceTest"),
            ),
            fidelity = Fidelity.HF2,
        ),
    )

    /** The suites for [capability], empty when the capability is unbound. */
    fun suitesFor(capability: String): List<TestSuiteId> =
        BINDINGS.filter { it.capability == capability }.flatMap { it.suites }
}
