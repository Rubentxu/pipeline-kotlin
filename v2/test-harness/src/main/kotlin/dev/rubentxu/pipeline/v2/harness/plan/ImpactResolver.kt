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
    fun isProductionPath(path: String): Boolean {
        if (SELF_OWNED_PREFIXES.any { path.startsWith(it) }) return false
        if (path.endsWith(".gradle.kts")) return true
        if (path.endsWith(".proto")) return true
        // SPI binding: any resource under META-INF/services/ is a product change.
        if (path.contains("/META-INF/services/") && !path.endsWith("/")) return true
        // Build-distribution resources (QUALITY_VALUE_ADDENDUM.md §BQ1, line 33).
        // The Gradle wrapper directory: properties and jar. The catalogue's
        // `Build.composition` `pathPatterns` already claims these paths; without
        // these two clauses the resolver filters the change out before the
        // catalogue is consulted and the contract never fires.
        if (path.startsWith("v2/gradle/wrapper/") &&
            (path.endsWith(".properties") || path.endsWith(".jar"))
        ) return true
        // Version catalogs (Gradle catalog DSL) and gradle.properties at any
        // module depth. A version-catalog bump changes the resolved version of
        // every dependency that consumes it; gradle.properties carries daemon
        // memory, parallel mode, and a range of JVM flags.
        if (path.endsWith("/libs.versions.toml") ||
            path.endsWith("/gradle.properties")
        ) return true
        // Wrapper scripts. Renaming or removing `gradlew` is a product change
        // because the entire build invocation flows through it.
        if (path.endsWith("/gradlew") || path.endsWith("/gradlew.bat")) return true
        // Any other file under `v2/gradle/`. The audit at 2026-10-16 found that
        // the build-resource category must be recognised even when the file
        // name is not on the explicit list above; the resolver's job is to
        // classify, not to enumerate. A new build resource the catalogue
        // doesn't bind will then REFUSE (UnclassifiedPath) rather than silently
        // select nothing.
        if (path.startsWith("v2/gradle/")) return true
        return path.contains("/src/main/") &&
            (path.endsWith(".kt") || path.endsWith(".java"))
    }

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
        val production = changedPaths.filter { isProductionPath(it) }.sorted()
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
     * @param executedSuites the suites whose evidence exists for this run.
     */
    fun adjudicate(
        plan: ValidationPlan,
        executedSuites: Set<String>,
        expectedCommit: String,
        observedCommit: String?,
    ): ValidationPlan.Refused? {
        if (plan is ValidationPlan.Refused) return plan

        val selected = plan as ValidationPlan.Selected
        val refusals = buildList {
            if (observedCommit != expectedCommit) {
                add(
                    SelectionRefusal.StaleEvidence(
                        expected = expectedCommit,
                        observed = observedCommit ?: "<binary reported no commit>",
                    ),
                )
            }
            selected.suites
                .filter { it.filter !in executedSuites }
                .forEach { add(SelectionRefusal.SelectedButNotRun(it.filter)) }
        }
        return if (refusals.isEmpty()) null else ValidationPlan.Refused(refusals)
    }
}
