package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * Totals of a JUnit XML report — sums across all `<testsuite>` children.
 *
 * Used by `core.junit` to surface a typed summary to the pipeline
 * without forcing the author to walk the suite tree. The handler
 * computes these once at parse time; the codec round-trips them.
 *
 * @property tests     total number of `<testcase>` elements across all suites
 * @property failures  total `<failure>` count across all suites
 * @property errors    total `<error>` count across all suites
 * @property skipped   total `<skipped>` count across all suites
 * @property timeSeconds cumulative time (seconds) reported by the suites;
 *                       the parser does NOT trust this to be monotonic
 *                       across suites (it is the cumulative sum)
 */
data class JunitTotals(
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val timeSeconds: Double,
) {
    init {
        require(tests >= 0) { "tests must be non-negative" }
        require(failures >= 0) { "failures must be non-negative" }
        require(errors >= 0) { "errors must be non-negative" }
        require(skipped >= 0) { "skipped must be non-negative" }
        require(!timeSeconds.isNaN()) { "timeSeconds must not be NaN" }
        require(timeSeconds >= 0.0) { "timeSeconds must be non-negative" }
    }
}
