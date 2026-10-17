package dev.rubentxu.pipeline.v2.harness.catalog

/**
 * H0 — the executable catalogue of capability-to-contract bindings.
 *
 * ## Why this type exists
 *
 * The five corpus failures in `CDH_PLANE_DIAGNOSTIC_RECEIPT.md` were `STALE_BINARY`, and the
 * stale binary itself was a symptom. The structural defect is that nothing obliges the harness to
 * change when a contract it observes changes. A functional change to PipelineK could land, a new
 * execution route could appear, and the harness would keep its old assumptions until a test failed
 * — or worse, until it did not.
 *
 * So the harness has to be connected to what it protects. This catalogue is that connection made
 * executable: for each capability that PipelineK owes a guarantee on, it names the owning module,
 * the files whose change means the guarantee moved, and the minimum fidelity level that can honestly
 * speak to it.
 *
 * ## Why it is data and not code
 *
 * The alternative is a `when (stepName)` in a test selector, which is the same closed-world defect
 * the Step registry was split to remove: a new capability would not appear until someone remembered
 * to edit the branch, and the failure mode is silent under-selection rather than a red test. Here a
 * missing capability is a row nobody wrote, and [ContractCatalogueFitness] asserts the catalogue
 * against the repository so the gap is visible instead.
 *
 * ## What the catalogue deliberately does NOT do
 *
 * It does not encode expected functional results. Expectation lives in the test body, so
 * reimplementing PipelineK cannot also rewrite what "correct" means and manufacture a green. This
 * file answers only "which contracts must be reconsidered when this changed".
 */
object ContractCatalogue {

    /**
     * The minimum fidelity a claim about this capability can be made at.
     *
     * These are the HF levels from `PIPELINE_TEST_HARNESS.md` and ADR-0072, represented as a closed
     * set rather than a free string, so an unknown level is a compile error instead of a level that
     * silently matches nothing.
     *
     * The audit at 2026-10-16 found that this enum only declared HF0..HF4 while ADR-0072
     * mandates HF0..HF6. The previous code was an under-specification: an HF5 or HF6
     * claim could not be expressed as a closed value. HF5 (Service Sandbox) and HF6
     * (Online Smoke) are now first-class cases.
     */
    enum class Fidelity {
        /** Pure contract: no process. ADTs, codecs, validation. */
        HF0,

        /** In-process: DSL/IR/handler integration. */
        HF1,

        /** Forked real distribution: the installed CLI. */
        HF2,

        /** Restart/resume: kill, restart, journal, replay. */
        HF3,

        /** Rootless sandbox: isolation and security. */
        HF4,

        /** Service sandbox: HF4 plus external services (Git, HTTP, DB, artifacts) isolated. */
        HF5,

        /** Online smoke: OSS / network. Ecosystem compatibility. */
        HF6,
    }

    /**
     * One binding: a capability, who owns it, what changing it invalidates, and how honestly it
     * can be tested.
     *
     * [sourcePaths] are the files whose modification means the guarantee moved. They are matched as
     * repository-relative paths, and a change to any of them selects this contract. [sourcePaths]
     * being empty is a defect this catalogue's fitness test rejects: a contract that no change can
     * ever trigger is an aspiration, not a binding.
     *
     * [minFidelity] is the LOWEST level that can carry a claim about this capability. A capability
     * marked [Fidelity.HF2] cannot be certified by an HF0 unit test, because the whole reason it is
     * HF2 is that the unit test cannot see the thing that broke.
     */
    data class Contract(
        val capability: String,
        val owningModule: String,
        val sourcePaths: List<String>,
        val minFidelity: Fidelity,
        /**
         * Optional glob patterns that ALSO select this contract.
         * The patterns are matched against the **full** path of a
         * change (repository-relative, forward-slash). A pattern
         * that uses only `*` is a single-segment wildcard; `**`
         * is a recursive wildcard. This is the BQ1 closure: a
         * contract is no longer restricted to a hand-picked list
         * of literal paths; the catalogue can declare "every
         * `.kt` file under this module selects this contract"
         * without losing the explicit-fitness test (the
         * `ContractCatalogueFitnessTest` still asserts that at
         * least one source path matches each contract).
         */
        val pathPatterns: List<String> = emptyList(),
    )

    /**
     * Contracts whose source change must ALSO escalate fidelity.
     *
     * A change that rewrites how output is persisted invalidates more than a unit-level reading:
     * the guarantee only exists at the distribution boundary. Kept separate from [CONTRACTS] so the
     * catalogue reads as "always required" plus "required and additionally escalated", which is
     * different information from a flag on each row.
     */
    data class EscalationRule(
        val capability: String,
        val from: Fidelity,
        val to: Fidelity,
        val reason: String,
    )

    /**
     * The bindings.
     *
     * The Output Plane block is the worked example from the mandate: a change to any one of these
     * files selects the whole set, because they share one guarantee — process bytes reach the
     * Output Plane intact, redacted, and without cross-talk — and each of them alone is only half
     * of it. Selecting `StreamingRedactor` but not `RedactingOutputIngress` is exactly the state the
     * harness was in.
     */
    val CONTRACTS: List<Contract> = listOf(
        // ---- Output Plane: streaming, redaction, channels, storage ----
        //
        // The owning modules here are deliberately NOT all :pipeline-application. The redactor is
        // the product's security boundary and lives in :pipeline-credentials-api; the shell pump is
        // the SDK's durable executor. Recording the real owner is the point of the catalogue: a
        // row that guessed its module would select the wrong Gradle project for impact analysis.
        Contract(
            capability = "OutputPlane.redaction.streaming",
            owningModule = ":pipeline-credentials-api",
            sourcePaths = listOf(
                "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "OutputPlane.write.commit",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RedactingOutputIngress.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "OutputPlane.pump.drain",
            owningModule = ":pipeline-step-sdk:runtime",
            sourcePaths = listOf(
                "v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutor.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "OutputPlane.read.cursor",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "Shell.execution.composition",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "Console.cli.selection",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/console/ConsoleOperationSelection.kt",
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "Provenance.build.identity",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/RuntimeApiVersion.kt",
                "v2/pipeline-application/build.gradle.kts",
                // The CLI entry point decides which surface exists at all. `version --json` was
                // added there, and a change to it can silently remove the provenance surface the
                // whole H1.4 line depends on.
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt",
            ),
            minFidelity = Fidelity.HF2,
        ),
        Contract(
            capability = "Build.composition",
            owningModule = ":pipeline-application",
            sourcePaths = listOf(
                // Which modules exist, and which are wired into the distribution. A change here can
                // remove a plugin from the product while every per-module test still passes.
                "v2/settings.gradle.kts",
                // The Gradle wrapper properties: the canonical "which Gradle version runs this
                // build" resource. Listed as a literal so the fitness test's `everyNamedPathExists`
                // assertion continues to pass for a file that already exists on disk.
                "v2/gradle/wrapper/gradle-wrapper.properties",
                // The version catalog and the gradle.properties at the build root.
                "v2/gradle.properties",
            ),
            // BQ1 audit (2026-10-16, RC4 close-out): the
            // contract used to be a closed list of paths only.
            // The pathPatterns field (added in RC2) lets a
            // contract claim broader coverage without
            // enumerating every file. After the RC3 closure
            // this was used to claim the gradle wrapper
            // properties; the RC4 audit showed that the
            // resolver was filtering the path out at the
            // `isProductionPath` boundary before the
            // catalogue was consulted, so the contract never
            // fired. The resolver was extended (see
            // `ImpactResolver.isProductionPath`) to recognise
            // build-distribution resources, and the patterns
            // here now cover the full Gradle wrapper directory
            // plus version catalogs and gradle.properties at
            // any depth, without claiming every
            // `build.gradle.kts` (those are owned by
            // Provenance.build.identity).
            pathPatterns = listOf(
                "v2/gradle/wrapper/gradle-wrapper.properties",
                "v2/gradle/wrapper/gradle-wrapper.jar",
                "v2/**/libs.versions.toml",
                "v2/**/gradle.properties",
            ),
            minFidelity = Fidelity.HF2,
        ),
        // ---- UAT fixture: H0 ephemeral cycle close end-to-end ----
        //
        // The H0 UAT (docs/pipelinek-delivery-first-2026-10-16/,
        // TODO 4) exercises the cycle's state machine and the
        // PromotionEvidenceVerifier in a /tmp/-rooted workspace.
        // The UAT's substitute contract lives at a path under
        // `src/main/` so the resolver treats it as a production
        // change; the contract's minFidelity is HF0 because the
        // runner's HF0 path is the only fidelity the test-harness
        // can run without the product classpath. The contract is
        // fixture-only: the UAT stages the file under the cycle
        // dir at the path below, and the runner's SHA + catalogue
        // checks verify it.
        Contract(
            capability = "OutputPlane.uat.fixture",
            owningModule = ":test-harness",
            sourcePaths = listOf(
                "v2/pipeline-credentials-api/src/main/kotlin/uat-fixtures/uat-001/SubstituteContract.kt",
            ),
            minFidelity = Fidelity.HF0,
        ),
    )

    /** Changes that escalate the required level rather than only selecting contracts. */
    val ESCALATIONS: List<EscalationRule> = listOf(
        EscalationRule(
            capability = "Provenance.build.identity",
            from = Fidelity.HF2,
            to = Fidelity.HF2,
            reason =
                "A change to the build file invalidates the identity of every artefact built " +
                    "before it, so prior evidence is stale by construction and must be re-earned.",
        ),
        EscalationRule(
            capability = "OutputPlane.read.cursor",
            from = Fidelity.HF2,
            to = Fidelity.HF3,
            reason =
                "A change to the read side can keep the bytes intact while breaking resume, so the " +
                    "guarantee is not answerable from a single run.",
        ),
        EscalationRule(
            capability = "OutputPlane.write.commit",
            from = Fidelity.HF2,
            to = Fidelity.HF3,
            reason =
                "A change to the commit path can write every byte and still fail to seal, which is " +
                    "the exact defect REAL-07 exists to detect.",
        ),
    )

    /**
     * Every contract a change to [path] must reconsider, deduplicated and ordered.
     *
     * BQ1 audit (2026-10-16, RC2): the previous matcher used
     * `it == path || it.endsWith(path)`, which has two flaws:
     *   1. `endsWith` collides across owners: a path
     *      `v2/pipeline-application/build.gradle.kts` matches
     *      a contract that ends with the same suffix
     *      regardless of which module the contract belongs
     *      to. The fitness test in [ContractCatalogueFitnessTest]
     *      documented the symptom; the fix here is to require
     *      a path-segment boundary (`/` separator on either
     *      side) for suffix matches.
     *   2. The matcher was a closed list of literal paths:
     *      a contract that wanted to claim "every `.kt` file
     *      under this module" had to enumerate them. The
     *      fix is the `pathPatterns` field on [Contract]
     *      (BQ1 RC2): patterns use `*` for a single
     *      segment and `**` for a recursive match, matched
     *      by [matchesPattern].
     */
    fun contractsFor(path: String): List<Contract> =
        CONTRACTS.filter { contract -> contract.matches(path) }

    private fun Contract.matches(path: String): Boolean {
        // Literal paths require an EXACT match, not a suffix
        // match. A path that ends with a different module's
        // file name is not a match for this contract.
        if (path in sourcePaths) return true
        // Pattern matches: each glob is a "this contract owns
        // everything that matches the glob" claim.
        return pathPatterns.any { matchesPattern(path, it) }
    }

    /**
     * Glob matcher with `*` (single segment) and `**` (recursive).
     * Backed by a regex compiled from the pattern and cached in
     * a [java.util.concurrent.ConcurrentHashMap] keyed by the
     * raw pattern. The cache is process-wide: a pattern is
     * compiled at most once, and subsequent calls amortize the
     * compilation cost. `*` does not match `/`; `**` does.
     */
    private val patternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun matchesPattern(path: String, pattern: String): Boolean {
        val regex = patternCache.computeIfAbsent(pattern) { p ->
            val compiled = buildString {
                append('^')
                var i = 0
                while (i < p.length) {
                    when {
                        // `**` is recursive: it matches any number of
                        // path segments INCLUDING ZERO. The previous
                        // shape compiled `**` to `.*` and required
                        // a literal `/` after it, which silently
                        // excluded the "file directly under the
                        // prefix" case (e.g. `v2/gradle.properties`
                        // against `v2/**/gradle.properties`). The
                        // compiled form is `(?:[^/]+/)*` when
                        // `**` is followed by `/` (matching zero or
                        // more "segment/" runs) and `.*` when it is
                        // not (matching any run of characters
                        // including the empty string).
                        p.startsWith("**/", i) -> {
                            append("(?:[^/]+/)*")
                            i += 3
                        }
                        p.startsWith("**", i) -> {
                            append(".*")
                            i += 2
                        }
                        p[i] == '*' -> {
                            append("[^/]*")
                            i++
                        }
                        p[i] == '.' || p[i] == '(' || p[i] == ')' ||
                            p[i] == '+' || p[i] == '|' || p[i] == '^' ||
                            p[i] == '$' || p[i] == '{' || p[i] == '}' ||
                            p[i] == '\\' -> {
                            append('\\'); append(p[i]); i++
                        }
                        else -> { append(p[i]); i++ }
                    }
                }
                append('$')
            }
            Regex(compiled)
        }
        return regex.matches(path)
    }

    /**
     * The fidelity required for the contracts a path matched, accounting for
     * escalation. Deduplicated and ordered. The contract-aware overload is the
     * source of truth: callers that already hold the matching contracts pass
     * them in and avoid a second catalogue traversal. The path-based overload
     * is a thin convenience that resolves contracts first.
     */
    fun requiredFidelityFor(contracts: List<Contract>): Set<Fidelity> {
        if (contracts.isEmpty()) return emptySet()
        val baseFidelities = contracts.map { it.minFidelity }.toSet()
        val matchingCapabilities = contracts.map { it.capability }.toSet()
        val escalated = ESCALATIONS
            .filter { rule -> rule.capability in matchingCapabilities }
            .map { rule -> rule.to }
        return (baseFidelities + escalated).toSortedSet()
    }

    /**
     * The fidelity required for [path], accounting for escalation. Deduplicated
     * and ordered. The previous shape called [contractsFor] twice (once for
     * `minFidelity`, once for `capability`); the new shape derives both from a
     * single list and the contract-aware overload does the work, so the
     * catalogue is walked once per call. Kept as a path-based convenience for
     * tests and one-shot callers that do not need the contracts themselves.
     */
    fun requiredFidelityFor(path: String): Set<Fidelity> =
        requiredFidelityFor(contractsFor(path))
}
