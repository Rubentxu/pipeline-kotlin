package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue

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
 * Non-production changes (docs, the harness's own tests and sources, tooling) are deliberately NOT
 * refused. A refused plan for a README edit — or for the harness editing its own catalogue — would
 * train operators to ignore refusals, and a gate nobody believes is worse than no gate.
 *
 * That exemption is scoped to [SELF_OWNED_PREFIXES] rather than applied by a wildcard, because a
 * blanket "anything under test-harness is fine" would also excuse a genuine product change that
 * happened to land in the wrong module.
 */
object ImpactResolver {

    /**
     * Path prefixes whose changes cannot invalidate a product guarantee.
     *
     * The harness's own sources and its build file are in here, and the reasoning is asymmetric on
     * purpose: a defect in the harness makes the harness over-report, which is noisy but safe,
     * whereas a defect that makes it under-report is the silent green this whole line of work
     * exists to prevent. So the harness is exempt from the catalogue, never the product.
     */
    val SELF_OWNED_PREFIXES: List<String> = listOf(
        "v2/test-harness/",
    )

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
                        proposedCapability = it.proposedCapability,
                        proposedOwner = it.proposedOwner,
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

        val unclassified = production.filter { path -> ContractCatalogue.contractsFor(path).isEmpty() }
        val capabilities = production
            .flatMap { ContractCatalogue.contractsFor(it) }
            .map { it.capability }
            .toSortedSet()

        val fidelities = production.flatMap { ContractCatalogue.requiredFidelityFor(it) }.toSortedSet()
        val reasons = production.flatMap { path ->
            ContractCatalogue.contractsFor(path).map { contract ->
                SelectionReason.SourceChanged(path, contract.capability)
            }
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
            unbound.forEach { add(SelectionRefusal.MissingSuite(it, "<no suite bound>")) }
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
        val requiredFidelities = selected.minimumFidelities.map { it.name }.toSet()
        val refusals = buildList {
            if (observedCommit != expectedCommit) {
                add(
                    SelectionRefusal.StaleEvidence(
                        expected = expectedCommit,
                        observed = observedCommit ?: "<binary reported no commit>",
                    ),
                )
            }
            for (suite in selected.suites) {
                val evidence = executedSuites[suite.filter]
                when {
                    evidence == null -> {
                        add(SelectionRefusal.SelectedButNotRun(suite.filter))
                    }
                    evidence.manifest == null -> {
                        // The XML exists and passes the
                        // gates, but the sidecar is
                        // missing or malformed. H1
                        // mandate: "Datos incompletos →
                        // Refused, no passed".
                        add(SelectionRefusal.IncompleteEvidence(
                            suite = suite.filter,
                            missingField = "sidecar (EvidenceManifest JSON)",
                        ))
                    }
                    else -> {
                        val m = evidence.manifest
                        if (m.sourceCommit != expectedCommit) {
                            add(SelectionRefusal.StaleBinary(
                                suite = suite.filter,
                                expected = expectedCommit,
                                observed = m.sourceCommit,
                            ))
                        }
                        if (requiredFidelities.isNotEmpty() && m.hf !in requiredFidelities) {
                            add(SelectionRefusal.InsufficientFidelity(
                                suite = suite.filter,
                                observed = m.hf,
                                required = requiredFidelities,
                            ))
                        }
                    }
                }
            }
        }
        return if (refusals.isEmpty()) null else ValidationPlan.Refused(refusals)
    }
}
