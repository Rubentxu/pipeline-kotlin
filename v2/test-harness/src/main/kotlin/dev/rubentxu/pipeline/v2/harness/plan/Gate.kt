package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Path

/**
 * The gate proper: run what the plan selected, then refuse unless the evidence discharges it.
 *
 * ## Why this is a separate object from [Dispatch] and [EvidenceReader]
 *
 * Three decisions, kept apart on purpose:
 *
 * - [Dispatch] decides WHICH suites run. Pure, no I/O, testable with a literal plan.
 * - [SuiteRunner] decides WHETHER they ran. The only impure step, and the only one injected.
 * - [EvidenceReader] observes WHAT came back. It reads the filesystem, so a caller cannot
 *   conveniently lie about it.
 *
 * A single object that selected, ran and self-certified would be trusted for none of the three:
 * a narrowing of the selection and a narrowing of the run are indistinguishable from outside.
 *
 * ## Why a failed invocation refuses immediately
 *
 * When Gradle exits non-zero there may be no XML at all, or a stale one from a previous run. Either
 * way there is no trustworthy evidence for the suites that invocation owned, and continuing would
 * adjudicate against evidence for work that did not happen. So a failed run is a refusal, not an
 * input to a later comparison.
 *
 * ## Why the evidence is read per module
 *
 * Each Gradle invocation writes into ITS OWN module's `build/test-results/test`. Reading one shared
 * directory would silently treat a suite as covered when its report landed in a different module's
 * tree, which is the same absence-read-as-a-green shape this exists to prevent.
 */
object Gate {

    /**
     * Runs [invocations], then adjudicates the result against [plan].
     *
     * @param resultsRoot the `v2/` directory; each module's reports live under it.
     * @return null when the run discharges the plan, otherwise the refusal with its reasons.
     */
    fun run(
        plan: ValidationPlan,
        invocations: List<Dispatch.GradleInvocation>,
        runner: SuiteRunner,
        resultsRoot: Path,
        expectedCommit: String,
        observedCommit: String?,
    ): ValidationPlan.Refused? {
        if (plan !is ValidationPlan.Selected) return plan as? ValidationPlan.Refused

        // Evidence is read AFTER the run, never before. Gradle leaves the previous run's reports in
        // place, so a pre-run read would accept a report this invocation never produced — and the
        // suite would be recorded as covered without having run. `GradleSuiteRunner` deletes those
        // reports for the same reason: absence must be read as absence, not as a quiet pass.
        val byModule = invocations.associateBy { it.gradleProject }

        val failed = invocations.filterNot { invocation ->
            runner.run(invocation)
        }
        if (failed.isNotEmpty()) {
            return ValidationPlan.Refused(
                failed.flatMap { invocation ->
                    invocation.filters.map { filter ->
                        SelectionRefusal.SuiteDidNotRun(filter, invocation.gradleProject)
                    }
                },
            )
        }

        // H1 TODO 2: the evidence map is keyed by
        // simple class name (the binding's filter).
        // We union the maps from every project's
        // results directory; later writes win on
        // collision, but the gate reads each suite
        // exactly once because the plan's suite
        // filters are unique.
        val executed: Map<String, EvidenceReader.SuiteEvidence> = byModule.keys
            .flatMap { project ->
                EvidenceReader.suitesWithEvidence(resultsDirFor(resultsRoot, project)).toList()
            }
            .toMap()

        return ImpactResolver.adjudicate(
            plan = plan,
            executedSuites = executed,
            expectedCommit = expectedCommit,
            observedCommit = observedCommit,
        )
    }

    /**
     * Where a project's reports land, derived from its Gradle path.
     *
     * `:pipeline-step-sdk:runtime` is a directory two levels under `v2/`, so the path cannot be
     * split on `:` and joined with the filesystem separator without losing the nesting.
     */
    fun resultsDirFor(resultsRoot: Path, gradleProject: String): Path =
        resultsRoot.resolve(gradleProject.removePrefix(":").replace(':', '/'))
            .resolve("build/test-results/test")
}

/**
 * Runs one Gradle invocation.
 *
 * Injected so the gate's control flow is testable without a build. The production implementation
 * hands [Dispatch.GradleInvocation.argv] to Gradle unchanged, which is what makes the argv the
 * tests assert the argv that executes rather than a parallel description of it.
 */
interface SuiteRunner {
    /** @return true when the invocation succeeded. */
    fun run(invocation: Dispatch.GradleInvocation): Boolean
}

/**
 * The production [SuiteRunner]: shells out to the Gradle wrapper.
 *
 * ## Why it deletes the reports first
 *
 * Gradle skips a `Test` task whose inputs are unchanged and leaves the previous reports in place.
 * Reading those would be reading a prior run's evidence and calling it this one's, so the target
 * directory is removed before the invocation. This is the stale-green shape the whole gate exists
 * to close: without it, a suite that stopped running still has a passing XML on disk.
 *
 * ## Why the working directory is the build root, not the checkout root
 *
 * The wrapper script's location does not decide which build runs. This checkout is a COMPOSITE
 * build: the repository root has its own `settings.gradle.kts` that pulls `v2` in with
 * `includeBuild("v2")`, and it has no modules of its own. Running `v2/gradlew` with the working
 * directory at the checkout root therefore resolves the ROOT build, and every module path in the
 * dispatch — `:pipeline-application`, `:test-harness` — fails with "task not found in root project
 * 'pipeline-kotlin'". Measured, not assumed: that was the first real gate run to reach execution.
 *
 * The working directory must be the build that OWNS the modules, which is `buildRoot`, the same
 * directory the wrapper lives in. [repoRoot] is still needed, for the Git queries and for locating
 * the per-module report directories, so both are parameters and neither is guessed.
 */
class GradleSuiteRunner(
    private val repoRoot: Path,
    private val buildRoot: Path,
) : SuiteRunner {

    /** The wrapper that belongs to [buildRoot]. */
    private val gradlew: Path = buildRoot.resolve("gradlew")

    override fun run(invocation: Dispatch.GradleInvocation): Boolean {
        val resultsDir = Gate.resultsDirFor(repoRoot, invocation.gradleProject)
        clearStaleReports(resultsDir)

        // Gradle skips a `Test` task whose inputs are unchanged, so without an explicit re-run the
        // filters this invocation passes would be ignored and the previous reports would stand in
        // for them. `--rerun-tasks` makes the selection take effect on THIS invocation, which is the
        // only way the argv below is the argv that ran.
        val rerun = listOf("--rerun-tasks")

        val process = ProcessBuilder(listOf(gradlew.toString()) + rerun + invocation.argv())
            .directory(buildRoot.toFile())
            .redirectErrorStream(true)
            .start()

        // The build log goes to our stdout rather than being captured and dropped: a gate that
        // fails with no visible reason is a gate people learn to bypass with --continue.
        process.inputStream.bufferedReader().forEachLine { println(it) }
        return process.waitFor() == 0
    }

    /** Removes only the JUnit reports, so incremental caches elsewhere survive. */
    private fun clearStaleReports(resultsDir: Path) {
        if (!java.nio.file.Files.isDirectory(resultsDir)) return
        java.nio.file.Files.newDirectoryStream(resultsDir, "TEST-*.xml").use { reports ->
            for (report in reports) java.nio.file.Files.deleteIfExists(report)
        }
    }
}
