package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * Typed output envelope of `core.junit`.
 *
 * Holds the resolved on-disk path of the report, the per-suite
 * summaries, the aggregated totals, and the closed [JunitReadOutcome]
 * ADT.
 *
 * The codec round-trips the whole envelope; the engine never
 * reconstructs it from individual fields (per project AGENTS.md
 * §Strict typed functional design).
 *
 * @property reportPath the absolute path of the JUnit XML the handler read
 * @property suites     the per-suite summaries (one per `<testsuite>` in the file)
 * @property totals     the aggregated totals across all suites
 * @property outcome    the closed outcome ADT (Passed / Failed)
 */
data class JunitReport(
    val reportPath: String,
    val suites: List<JunitSuiteSummary>,
    val totals: JunitTotals,
    val outcome: JunitReadOutcome,
) {
    init {
        require(reportPath.isNotEmpty()) { "reportPath must not be empty" }
        require(suites.isNotEmpty()) { "suites must contain at least one entry" }
    }
}
