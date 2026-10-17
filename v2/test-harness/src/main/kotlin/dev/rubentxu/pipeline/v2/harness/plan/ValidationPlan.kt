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
 * BQ4 P0 (HITO 1 "Selección y evidencia vinculadas",
 * 2026-10-17): the carrier now carries the FQCN
 * (fully-qualified class name) alongside the JUnit
 * `--tests` filter pattern. The two are different
 * identities that the audit's "módulo + FQCN +
 * invocación real" bullet requires:
 *
 * - [filter] is the JUnit filter pattern passed to
 *   `--tests`; JUnit accepts either a simple class
 *   name (`FooTest`) or a FQCN
 *   (`com.example.FooTest`).
 * - [fqcn] is the identity used by the gate to
 *   look up evidence in the runtime's suite set.
 *   Two suites with the same simple name in
 *   different modules have different FQCNs, so
 *   the FQCN is the closed-world identity. The
 *   default `fqcn = filter` preserves the previous
 *   shape for call sites that did not yet pass an
 *   explicit FQCN; production call sites SHOULD
 *   pass the real FQCN so two same-named suites
 *   in different modules cannot collide.
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
data class TestSuiteId(
    val filter: String,
    val gradleProject: String,
    // BQ4 P0 (HITO 1 "Selección y evidencia vinculadas",
    // 2026-10-17): the FQCN is the closed-world identity
    // for evidence lookup. The default `fqcn = filter`
    // preserves the previous shape for call sites that
    // pass a simple class name as the JUnit filter;
    // production call sites SHOULD pass the real FQCN
    // (the XML's `<testsuite name="...">` attribute) so
    // two same-named suites in different modules
    // cannot collide on the evidence-map key. The
    // carrier self-protects: a blank `fqcn` is
    // refused at construction.
    val fqcn: String = filter,
) {
    // BQ4 v9 (plan carrier self-protect, 2026-10-17):
    // the previous shape had no init validation. A blank
    // `filter` or `gradleProject` would silently never
    // match a real suite in the JUnit XML reader, and
    // the suite would be a no-op in the plan. The
    // carrier now refuses blanks with precise messages
    // naming the field; the `gradleProject` shape
    // (`:module`) is a domain convention, not a format
    // requirement, so the check is `isNotBlank` only.
    init {
        require(filter.isNotBlank()) { "TestSuiteId.filter must not be blank" }
        require(gradleProject.isNotBlank()) { "TestSuiteId.gradleProject must not be blank" }
        require(fqcn.isNotBlank()) { "TestSuiteId.fqcn must not be blank" }
    }
}

/**
 * Why a capability entered the plan.
 *
 * The reason is part of the plan rather than a log line, because a gate has to be able to explain
 * itself to whoever is being blocked. Without it, "run these 40 suites" is unfalsifiable.
 */
sealed interface SelectionReason {
    /** A named source file changed. */
    data class SourceChanged(val path: String, val capability: String) : SelectionReason {
        init {
            require(path.isNotBlank()) { "SourceChanged.path must not be blank" }
            require(capability.isNotBlank()) { "SourceChanged.capability must not be blank" }
        }
    }

    /** A module the changed file belongs to has no classification at all. */
    data class ModuleUnclassified(val module: String) : SelectionReason {
        init {
            require(module.isNotBlank()) { "ModuleUnclassified.module must not be blank" }
        }
    }
}

/** Why the plan is refused. Each case names a different operator action. */
sealed interface SelectionRefusal {
    /** A production file the catalogue has never heard of. Action: add a row or justify the file. */
    /**
     * A production-shape path the catalogue does not bind.
     * H1 TODO 1 added the [proposal] field so the operator
     * has a starting point for adding a rule, derived from
     * path conventions (not a per-file string table). The
     * proposal is heuristic; the operator replaces it by
     * adding a catalogue row.
     *
     * BQ4 v24 (typed proposal pair, 2026-10-17): the
     * previous shape carried two parallel `String? = null`
     * fields (`proposedCapability`, `proposedOwner`) — a
     * parallel-nullable defect class where the two
     * fields encoded a single concept (the operator's
     * starting-point proposal) as two independent
     * sentinels. The producer
     * [dev.rubentxu.pipeline.v2.harness.plan.ImpactResolver.resolve]
     * always sets both together, but a future consumer
     * that read one without the other had no compile-time
     * signal. The new shape is a single [Proposal]
     * carrier; the proposal is either present (typed,
     * self-protected) or absent (`null`). A consumer
     * either reads the pair atomically or does not read
     * it at all.
     */
    data class UnclassifiedPath(
        val path: String,
        val proposal: Proposal? = null,
    ) : SelectionRefusal {
        init {
            require(path.isNotBlank()) { "UnclassifiedPath.path must not be blank" }
        }
    }

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
    ) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "IncompleteEvidence.suite must not be blank" }
            require(missingField.isNotBlank()) { "IncompleteEvidence.missingField must not be blank" }
        }
    }

    /**
     * H1 TODO 2: a suite in the plan has a sidecar file,
     * but the file is malformed (the JSON does not parse
     * as a valid [EvidenceManifest]). Distinct from
     * [IncompleteEvidence] because the operator's
     * remediation is different: a missing sidecar means
     * "enable the sidecar writer"; a malformed sidecar
     * means "fix the sidecar writer". The [reason] is the
     * parser's message preserved verbatim so the
     * developer can find the malformed field by name.
     */
    data class MalformedSidecar(
        val suite: String,
        val reason: String,
    ) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "MalformedSidecar.suite must not be blank" }
            require(reason.isNotBlank()) { "MalformedSidecar.reason must not be blank" }
        }
    }

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
    ) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "StaleBinary.suite must not be blank" }
            require(expected.isNotBlank()) { "StaleBinary.expected must not be blank" }
            require(observed.isNotBlank()) { "StaleBinary.observed must not be blank" }
        }
    }

    /**
     * H1 TODO 2: the suite's sidecar evidence declares
     * a fidelity that is not in the plan's
     * `minimumFidelities`. The plan's `minimumFidelities`
     * is the union of every contract's `minFidelity`
     * required by the change; a suite that ran at a
     * lower fidelity discharged nothing.
     *
     * BQ4 v4 (typed fidelity refusal, 2026-10-17): the
     * previous shape was `observed: String` + `required: Set<String>`,
     * derived from the sidecar's `hf` String and the plan's
     * `Set<Fidelity>` mapped through `it.name`. The dual
     * shape (typed source mapped to String, then compared
     * with `String in Set<String>`) let a typo or a future
     * HF name silently misroute. The carrier is now the
     * [Fidelity] enum on both sides; the [EvidenceManifest]
     * init validates the wire format and exposes a typed
     * `fidelity` view the resolver consumes directly.
     */
    data class InsufficientFidelity(
        val suite: String,
        val observed: Fidelity,
        val required: Set<Fidelity>,
    ) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "InsufficientFidelity.suite must not be blank" }
        }
    }

    /**
     * A required suite has no runnable target in the policy. Action: restore the suite binding.
     *
     * The previous shape was a single `data class MissingSuite(val capability, val suite)` with
     * the `suite` field receiving the placeholder string `<no suite bound>` when the capability
     * was unbound. The placeholder was a stringly-typed-state defect: the consumer at
     * `GateCli.describe` printed the literal verbatim, and a typo at the resolver site silently
     * misrouted the absence. The new shape is a sealed interface with two typed cases:
     * `Unbound` carries no `suite` field because there is no value to carry; `Bound` carries
     * the suite the binding named (future use).
     */
    sealed interface MissingSuite : SelectionRefusal {
        val capability: String

        /** The capability has no suite bound at all. Distinct from `Bound` because the operator
         *  action is different: add a binding, not fix a named one. */
        data class Unbound(override val capability: String) : MissingSuite {
            init {
                require(capability.isNotBlank()) { "MissingSuite.Unbound.capability must not be blank" }
            }
        }

        /** A suite was named by the binding but is not runnable (future case). */
        data class Bound(override val capability: String, val suite: String) : MissingSuite {
            init {
                require(capability.isNotBlank()) { "MissingSuite.Bound.capability must not be blank" }
                require(suite.isNotBlank()) { "MissingSuite.Bound.suite must not be blank" }
            }
        }
    }

    /** The evidence names a suite that the plan selected but that was not executed. */
    data class SelectedButNotRun(val suite: String) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "SelectedButNotRun.suite must not be blank" }
        }
    }

    /**
     * A Gradle invocation the plan required exited non-zero, so the suites it owned have no
     * trustworthy evidence for this run.
     *
     * Distinct from [SelectedButNotRun]: there the suite may have run and produced nothing usable,
     * here the build that would have run it did not succeed at all. Both refuse, but the operator
     * action differs — one is a coverage gap, the other is a broken invocation.
     */
    data class SuiteDidNotRun(val suite: String, val gradleProject: String) : SelectionRefusal {
        init {
            require(suite.isNotBlank()) { "SuiteDidNotRun.suite must not be blank" }
            require(gradleProject.isNotBlank()) { "SuiteDidNotRun.gradleProject must not be blank" }
        }
    }

    /**
     * The evidence was produced by a binary built from a different commit, or the binary
     * reported no commit at all (e.g. `version --json` failed at adjudication time).
     *
     * The previous shape was a single `data class StaleEvidence(val expected, val observed)` with
     * the `observed` field receiving the placeholder string `<binary reported no commit>` when the
     * binary returned null. The placeholder was a stringly-typed-state defect: the consumer at
     * `GateCli.describe` printed the literal verbatim, and a typo at the resolver site silently
     * misrouted the absence. The new shape is a sealed interface with two typed cases;
     * `NoCommitObserved` carries no `observed` field because there is no value to carry.
     */
    sealed interface StaleEvidence : SelectionRefusal {
        val expected: String

        /** Evidence names a different commit. */
        data class Mismatched(override val expected: String, val observed: String) : StaleEvidence {
            init {
                require(expected.isNotBlank()) { "StaleEvidence.Mismatched.expected must not be blank" }
                require(observed.isNotBlank()) { "StaleEvidence.Mismatched.observed must not be blank" }
            }
        }

        /** The binary reported no commit at all. Distinct from `Mismatched` because the operator
         *  action is different: the installed distribution is the problem, not the commit. */
        data class NoCommitObserved(override val expected: String) : StaleEvidence {
            init {
                require(expected.isNotBlank()) { "StaleEvidence.NoCommitObserved.expected must not be blank" }
            }
        }
    }
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

/**
 * The operator's starting-point proposal for what to add to
 * the catalogue. The proposal is heuristic: a path
 * `v2/<module>/...` proposes the module name as the
 * [owner] and `Unknown.<module>` as the [capability]. The
 * operator replaces the heuristic by adding a row to the
 * catalogue or a clause to the classifier.
 *
 * BQ4 v24 (typed proposal pair, 2026-10-17): the previous
 * shape carried the two fields as parallel `String? = null`
 * fields on [SelectionRefusal.UnclassifiedPath]. The
 * parallel-nullable shape encoded a single concept (a
 * proposal) as two independent sentinels. The new shape is
 * a single self-protected carrier: a proposal is either
 * present (typed, atomic) or absent (`null`).
 *
 * The carrier self-protects: a direct construction with
 * a blank [capability] or [owner] is refused. The producer
 * [dev.rubentxu.pipeline.v2.harness.plan.ChangedInputClassifier
 * .proposeOwnerAndCapability] is the only legitimate
 * source and always returns a non-blank pair, so the
 * strict-validate is for the structural invariant: a
 * future direct construction that passes blanks would
 * fabricate the operator's starting point.
 */
data class Proposal(
    val capability: String,
    val owner: String,
) {
    init {
        require(capability.isNotBlank()) { "Proposal.capability must not be blank" }
        require(owner.isNotBlank()) { "Proposal.owner must not be blank" }
    }
}
