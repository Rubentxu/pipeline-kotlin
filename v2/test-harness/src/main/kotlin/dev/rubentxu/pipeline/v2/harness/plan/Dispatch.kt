package dev.rubentxu.pipeline.v2.harness.plan

/**
 * Turns a [ValidationPlan.Selected] into the Gradle invocations that run it.
 *
 * ## Why suites are grouped by module
 *
 * Gradle's `test` task takes many `--tests` filters in one invocation. Running one Gradle process
 * per suite would be correct and absurd: a JVM fork and a full configuration phase per suite, and
 * the mandate explicitly forbids repeating suites inside one invocation. So the dispatch is
 * `module -> filters`, and one `--tests` argument per suite within that single module run.
 *
 * ## Why this does not run anything
 *
 * It returns the ARGV. Execution belongs to the task that owns the suites. Keeping the two apart
 * means the dispatch can be asserted exactly — argv included — without a build, and it means a
 * caller cannot accidentally select, execute and self-certify in one place where a narrowing would
 * be invisible.
 */
object Dispatch {

    /** One Gradle invocation: a project, the task, and the filters to pass to it. */
    data class GradleInvocation(
        val gradleProject: String,
        val task: String,
        val filters: List<String>,
    ) {
        /** The argv, as a build would receive it. Asserted directly by the tests. */
        fun argv(): List<String> =
            listOf(gradleProject, task) + filters.flatMap { listOf("--tests", it) }
    }

    /**
     * The invocations that discharge [plan], one per distinct module.
     *
     * Ordering is deterministic (module name, then filter) so the argv is stable across runs and a
     * diff in the output means the policy changed rather than the filesystem.
     */
    fun dispatch(plan: ValidationPlan): List<GradleInvocation> {
        if (plan !is ValidationPlan.Selected) return emptyList()
        val byModule = plan.suites.groupBy { it.gradleProject }
        return byModule
            .map { (project, suites) ->
                GradleInvocation(
                    gradleProject = project,
                    task = "test",
                    filters = suites.map { it.filter }.sorted(),
                )
            }
            .sortedBy { it.gradleProject }
    }

    /**
     * The suites [dispatch] covers, for comparison against what actually ran.
     *
     * Returned separately rather than re-derived by the caller, because the caller is exactly the
     * party whose convenience we are guarding against: a caller that recomputes "which suites did
     * I intend to run" can arrive at a smaller set than the plan demands without noticing.
     */
    fun dispatchedFilters(invocations: List<GradleInvocation>): Set<String> =
        invocations.flatMap { it.filters }.toSet()

    /**
     * Whether a set of executed suites discharges [plan], by construction of the filters.
     *
     * This exists so the caller cannot compare a plan against an unrelated set: the comparison
     * names the same [TestSuiteId.filter] values the plan holds, and a suite the plan never
     * selected is neither required nor counted.
     */
    fun missingFrom(plan: ValidationPlan, executed: Set<String>): List<String> {
        if (plan !is ValidationPlan.Selected) return emptyList()
        return plan.suites.map { it.filter }.filter { it !in executed }
    }
}
