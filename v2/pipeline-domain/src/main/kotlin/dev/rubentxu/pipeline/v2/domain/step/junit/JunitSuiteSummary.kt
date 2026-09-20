package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * A single `<testsuite>` summary extracted from a JUnit XML report.
 *
 * The summary aggregates the test counts reported by the suite
 * itself (the parser does NOT recompute them by walking the
 * `<testcase>` children; the upstream tool's counts are
 * authoritative).
 *
 * @property name         the `<testsuite>` name attribute
 * @property tests        `<testsuite tests="...">` value
 * @property failures     `<testsuite failures="...">` value
 * @property errors       `<testsuite errors="...">` value
 * @property skipped      `<testsuite skipped="...">` value
 * @property timeSeconds  `<testsuite time="...">` value
 */
data class JunitSuiteSummary(
    val name: String,
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val timeSeconds: Double,
) {
    init {
        require(name.isNotEmpty()) { "suite name must not be empty" }
        require(tests >= 0) { "tests must be non-negative" }
        require(failures >= 0) { "failures must be non-negative" }
        require(errors >= 0) { "errors must be non-negative" }
        require(skipped >= 0) { "skipped must be non-negative" }
        require(!timeSeconds.isNaN()) { "timeSeconds must not be NaN" }
        require(timeSeconds >= 0.0) { "timeSeconds must be non-negative" }
    }
}
