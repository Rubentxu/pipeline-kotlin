package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Contract

/**
 * Turns a set of changed paths into a [ValidationPlan].
 *
 * ## Why this is pure
 *
 * Resolving reads only its arguments: the changed paths, the catalogue, and the suite policy. It
 * does not read Git, the filesystem, the clock or the environment. That is deliberate. A resolver
 * that shells out to `git diff` cannot be tested for the property that matters most here — that an
 * unclassified path refuses rather than selecting nothing — without a repository, and the
 * adversarial tests would then be asserting on a working tree rather than on the decision.
 *
 * So the impure edge is [ChangedPaths.fromGitOutput], which parses; and the decision is here.
 *
 * ## The fail-closed rule
 *
 * A changed production path that no contract names produces [ValidationPlan.Refused]. It does not
 * produce an empty [ValidationPlan.Selected]. The difference matters: an empty selection reads as
 * "nothing to do, therefore fine", which is exactly the false green the H0 mandate forbids. The
 * refusal names the path so the operator can add a row.
 *
 * Non-production changes (docs, test sources, tooling) are deliberately NOT
 * refused. A refused plan for a README edit would train operators to ignore refusals, and a gate
 * nobody believes is worse than no gate.
 *
 * ## The harness is NOT exempt, and this section exists to stop that changing by accident
 *
 * An earlier shape carried a `SELF_OWNED_PREFIXES` list, consulted from `isProductionPath`, that
 * made every `v2/test-harness/` path non-production. Measured 2026-10-18: that consumer no longer
 * existed. BQ4 P0 removed the exemption from the classifier (harness `src/main` sources became
 * Production `SourceCode`, so a harness defect stopped being a silent green), and the delegation to
 * the classifier then removed the only reader of the list. The constant survived with no reader and
 * a KDoc asserting an exemption the wiring did not have. It is gone now, and this paragraph is
 * here so the absence is not mistaken for an oversight.
 *
 * The behaviour that replaced it is the correct one. The harness IS the product: a defect in its
 * `src/main` code must be able to invalidate a guarantee, and the honest consequence is that a
 * harness change needs a catalogue contract like any other production path. Census over
 * `git ls-files` on 2026-10-18: 36 tracked paths under `v2/test-harness` classify as Production and
 * currently have no contract, so they refuse. That refusal is fail-closed and correct; it is a
 * coverage gap to close with a contract, not an exemption to restore.
 */
object ImpactResolver {

    /**
     * Placeholder history: NO_SUITE_BOUND_PLACEHOLDER lived here
     * until commit d16ffd44 (StaleEvidence sealed split) and
     * the follow-up MissingSuite sealed split. The placeholder
     * is gone; the `MissingSuite.Unbound` case carries no
     * suite field, and the only remaining `MissingSuite.Bound`
     * case carries the real suite name.
     */

    /**
     * Paths that are production code, i.e. paths whose change invalidates a guarantee.
     *
     * A path is production when it is a `src/main` source, a Gradle build file, a protobuf
     * schema, a `META-INF/services/` resource (ServiceLoader binding), or a build-distribution
     * resource (Gradle wrapper, version catalog, settings, or the wrapper script). The audit at
     * 2026-10-16 (rc4 follow-up) found one further gap:
     *   - `v2/gradle/wrapper/gradle-wrapper.properties` (and its companion `.jar`), the
     *     `gradlew`/`gradlew.bat` scripts, `gradle.properties`, and `libs.versions.toml` are
     *     build-distribution resources. `QUALITY_VALUE_ADDENDUM.md` §BQ1 (line 33) lists
     *     "scripts de build/distribución" as production-impacting inputs, and the catalogue
     *     already binds `Build.composition` to `v2/gradle/wrapper/gradle-wrapper.properties` via
     *     `pathPatterns` (see `ContractCatalogue.Build.composition`); without this clause the
     *     resolver filters the change out at line 92 before the catalogue is consulted and the
     *     `Build.composition` contract never fires. The new clauses cover the Gradle wrapper
     *     under `v2/gradle/wrapper/`, version catalogs at any depth (`libs.versions.toml`),
     *     `gradle.properties` at any depth, and the wrapper scripts. The pre-existing
     *     `endsWith(".gradle.kts")` rule already covers `build.gradle.kts` / `settings.gradle.kts`
     *     / `buildSrc/build.gradle.kts`; the explicit `endsWith("/build.gradle.kts")` is kept
     *     as documentation of intent, not as a behavioural change.
     */
    fun isProductionPath(path: String): Boolean =
        ChangedInputClassifier.isProductionPath(path)

    /**
     * Resolve [changedPaths] into a plan.
     *
     * Refuses when any production path is unclassified, when a selected capability has no suite
     * binding, or when a binding names a suite with no filter. It never returns an empty selection
     * for a non-empty production change.
     */
    fun resolve(changedPaths: Collection<String>): ValidationPlan =
        resolveWithPolicy(changedPaths, SuitePolicy.BINDINGS)

    /**
     * [resolve] with an injected [bindings] table.
     *
     * The seam exists so the "a contract nobody bound to a suite" scenario can be constructed
     * rather than waited for. Without it that adversarial case can only be asserted negatively
     * ("right now everything happens to be bound"), which goes red the day someone fixes the
     * binding instead of on the day the binding is removed.
     */
    fun resolveWithPolicy(
        changedPaths: Collection<String>,
        bindings: List<SuiteBinding>,
    ): ValidationPlan {
        // H1 TODO 1: classify by *semantic effect*, not by substring.
        // The classifier produces a sealed `ChangedInput`; the
        // `Production` variants are the ones the catalogue is
        // asked about. `UnknownProduction` is refused up front
        // (the path has the surface of a product change but no
        // rule matches it; the resolver proposes an owner /
        // capability so the operator can add the rule).
        val classified = changedPaths.map { ChangedInputClassifier.classify(it) }

        val unknown = classified.filterIsInstance<ChangedInput.UnknownProduction>()
        if (unknown.isNotEmpty()) {
            return ValidationPlan.Refused(
                unknown.map {
                    SelectionRefusal.UnclassifiedPath(
                        path = it.path,
                        // BQ4 v24 (typed proposal pair,
                        // 2026-10-17): the two fields are
                        // now a single [Proposal] carrier
                        // — the parallel-nullable shape
                        // encoded one concept (the
                        // operator's starting point) as
                        // two independent sentinels. The
                        // producer's `UnknownProduction`
                        // carries a non-null
                        // `proposal: Proposal`; passing it
                        // through is the typed shape and
                        // the carrier self-protects.
                        proposal = it.proposal,
                    )
                },
            )
        }

        val production = classified
            .filterIsInstance<ChangedInput.Production>()
            .map { it.path }
            .sorted()
        if (production.isEmpty()) {
            // No production change: nothing to certify, and that is a legitimate outcome rather
            // than a refusal, because there is genuinely no guarantee at stake.
            return ValidationPlan.Selected(
                capabilities = emptySet(),
                contracts = emptySet(),
                suites = emptySet(),
                minimumFidelities = emptySet(),
                reasons = emptyList(),
            )
        }

        // BQ1 v3 (resolver dedupe, 2026-10-17): the previous
        // shape called [ContractCatalogue.contractsFor] three
        // times per production path (once for the
        // unclassified check, once for `capabilities`, once
        // for `reasons`) plus a fourth time inside
        // [ContractCatalogue.requiredFidelityFor]. The new
        // shape builds the per-path contract list ONCE and
        // derives every downstream view from it. A future
        // view added here no longer implies a new catalogue
        // traversal; the catalogue is walked exactly once
        // per production path. The contract-aware overload
        // of [requiredFidelityFor] lets the fidelity view
        // reuse the same list instead of re-fetching it.
        val perPath: Map<String, List<Contract>> =
            production.associateWith { ContractCatalogue.contractsFor(it) }

        val unclassified = perPath.filterValues { it.isEmpty() }.keys.toList()
        val capabilities = perPath.values.flatten()
            .map { it.capability }
            .toSortedSet()
        val fidelities = perPath.values
            .flatMap { ContractCatalogue.requiredFidelityFor(it) }
            .toSortedSet()
        val reasons = perPath.flatMap { (path, contracts) ->
            contracts.map { SelectionReason.SourceChanged(path, it.capability) }
        }

        val suites = capabilities
            .flatMap { capability -> bindings.filter { it.capability == capability }.flatMap { it.suites } }
            .sortedBy { it.filter }
            .toSet()
        val unbound = capabilities.filter { capability ->
            bindings.none { it.capability == capability }
        }

        val refusals = buildList {
            unclassified.forEach { add(SelectionRefusal.UnclassifiedPath(it)) }
            unbound.forEach { add(SelectionRefusal.MissingSuite.Unbound(it)) }
        }

        if (refusals.isNotEmpty()) {
            return ValidationPlan.Refused(refusals)
        }

        return ValidationPlan.Selected(
            capabilities = capabilities,
            contracts = capabilities.map { ContractId(it) }.toSet(),
            suites = suites,
            minimumFidelities = fidelities,
            reasons = reasons,
        )
    }

    /**
     * Compare a plan against what was actually executed and against the binary's identity.
     *
     * This is the gate. The resolver is only useful if something refuses to proceed when the plan
     * and the evidence disagree, so this is a separate function rather than a field on the plan:
     * selection and adjudication are different decisions and merging them would let a caller skip
     * the check by not asking for it.
     *
     * @param expectedCommit the commit the evidence must have been produced from.
     * @param observedCommit what the binary that produced the evidence reports.
     * @param executedSuites the suites whose evidence exists for this
     *        run, keyed by the binding's filter (simple class
     *        name). H1 TODO 2: the value carries the
     *        [EvidenceReader.SuiteEvidence] so the gate can
     *        demand a sidecar, a fresh binary, and a fidelity
     *        that discharges the plan.
     */
    fun adjudicate(
        plan: ValidationPlan,
        executedSuites: Map<String, EvidenceReader.SuiteEvidence>,
        expectedCommit: String,
        observedCommit: String?,
    ): ValidationPlan.Refused? {
        // BQ1 v2 (typed adjudicate, 2026-10-17): the previous
        // shape was `if (plan is Refused) return plan; val
        // selected = plan as ValidationPlan.Selected`. The
        // unchecked cast is safe (the early return rules out
        // `Refused` and the sealed interface has exactly two
        // cases) but it is a developer habit that hides the
        // exhaustiveness. The new shape is an exhaustive
        // `when` that lets the compiler narrow `plan` to
        // `ValidationPlan.Selected` on the `is` branch via
        // the destructured binding. A future third
        // [ValidationPlan] case would force the `when` to
        // grow at compile time. The return type stays
        // `ValidationPlan.Refused?` for caller compatibility
        // (the function reports "did adjudication produce a
        // refusal?" without changing the gate's call site).
        val selected: ValidationPlan.Selected = when (plan) {
            is ValidationPlan.Refused -> return plan
            is ValidationPlan.Selected -> plan
        }
        val requiredFidelities = selected.minimumFidelities
        val refusals = buildList {
            if (observedCommit != expectedCommit) {
                // The previous shape stuffed `<binary reported no commit>` into the
                // `observed` field of a `StaleEvidence` data class. The placeholder
                // is now gone: a null `observedCommit` is its own typed case
                // (`NoCommitObserved`) and a mismatched commit is `Mismatched`.
                add(
                    if (observedCommit != null) {
                        SelectionRefusal.StaleEvidence.Mismatched(
                            expected = expectedCommit,
                            observed = observedCommit,
                        )
                    } else {
                        SelectionRefusal.StaleEvidence.NoCommitObserved(
                            expected = expectedCommit,
                        )
                    },
                )
            }
            for (suite in selected.suites) {
                // BQ4 P0 (HITO 1 "Selección y evidencia
                // vinculadas", 2026-10-17): the lookup
                // key is the FQCN. The previous shape
                // used `suite.filter` (the JUnit
                // filter pattern, often a simple
                // class name) which silently collapsed
                // two modules that have a same-named
                // suite into a single evidence row.
                // The FQCN is unique per JVM.
                val evidence = executedSuites[suite.fqcn]
                // Name every refusal with the identity that was
                // actually looked up. The previous shape reported
                // `suite.filter` here -- the JUnit filter pattern --
                // which is precisely the ambiguous value this loop's
                // FQCN lookup exists to eliminate. A refusal that
                // names the filter cannot say WHICH of two
                // same-named suites produced no evidence, and a
                // refusal the operator cannot act on is the defect
                // this row exists to catch. Where a call site does
                // not override it, `TestSuiteId.fqcn` defaults to
                // `filter` and the rendered name is byte-identical.
                val identity = suite.fqcn
                when (evidence) {
                    null -> {
                        add(SelectionRefusal.SelectedButNotRun(identity))
                    }
                    is EvidenceReader.SuiteEvidence.MissingSidecar -> {
                        add(SelectionRefusal.IncompleteEvidence(
                            suite = identity,
                            missingField = "sidecar (EvidenceManifest JSON)",
                        ))
                    }
                    is EvidenceReader.SuiteEvidence.MalformedSidecar -> {
                        add(SelectionRefusal.MalformedSidecar(
                            suite = identity,
                            reason = evidence.reason,
                        ))
                    }
                    is EvidenceReader.SuiteEvidence.WithManifest -> {
                        val m = evidence.manifest
                        if (m.sourceCommit != expectedCommit) {
                            add(SelectionRefusal.StaleBinary(
                                suite = identity,
                                expected = expectedCommit,
                                observed = m.sourceCommit,
                            ))
                        }
                        if (requiredFidelities.isNotEmpty() && m.fidelity !in requiredFidelities) {
                            add(SelectionRefusal.InsufficientFidelity(
                                suite = identity,
                                observed = m.fidelity,
                                required = requiredFidelities,
                            ))
                        }
                        // The sidecar's own `class_name` versus the report it sits beside.
                        // `EvidenceManifest.sidecarFor` keys the PAIRING by the XML's file name, so
                        // without this the placement of the file and the name inside it could
                        // disagree and the gate would discharge. Measured 2026-10-18: before this
                        // comparison, `TEST-FooTest.xml` + `FooTest.json` whose `class_name` is
                        // `SomeOtherTest` closed the plan for `FooTest` with no refusal.
                        if (m.className != identity) {
                            add(SelectionRefusal.SidecarNamesAnotherClass(
                                suite = identity,
                                observed = m.className,
                            ))
                        }
                    }
                }
            }
        }
        return if (refusals.isEmpty()) null else ValidationPlan.Refused(refusals)
    }
}
