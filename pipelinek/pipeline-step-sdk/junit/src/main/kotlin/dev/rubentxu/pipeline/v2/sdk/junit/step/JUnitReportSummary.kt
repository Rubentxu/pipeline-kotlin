package dev.rubentxu.pipeline.v2.sdk.junit.step

/**
 * Typed summary of a JUnit XML report (F5.2 / OFFICIAL_PLUGIN junit.results).
 *
 * Every field is the canonical Maven Surefire / Gradle Test naming:
 * - tests:     total test cases declared in the XML.
 * - failures:  <failure> child elements (assertion failures + errors that
 *              follow the test body — distinct from <error> in old JUnit).
 * - errors:    <error> child elements (exceptions thrown outside the
 *              test body, e.g. framework-level setup errors).
 * - skipped:   <skipped> child elements.
 * - durationSeconds: parsed from the suite's `time` attribute; 0.0 if
 *              absent.
 * - reportPath: the absolute path of the XML the summary was derived
 *              from. Persists a typed reference for downstream Steps /
 *              reporting (no Map<String, Any?> smuggling).
 *
 * The summary is intentionally narrower than a full XML parse: a UAT
 * caller wants the totals, not every testcase name. Per-testcase
 * expansion is an F5.2 follow-up (the requirement is the totals +
 * a typed reference to the report).
 */
data class JUnitReportSummary(
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val durationSeconds: Double,
    val reportPath: String,
) {
    val successful: Int get() = tests - failures - errors - skipped
    val failed: Int get() = failures + errors
    val isClean: Boolean get() = failed == 0 && skipped == 0
}

/**
 * Input for the `junit.results` Step (F5.2).
 *
 * - reportPath: REQUIRED. Either absolute or workspace-relative path to
 *   a JUnit XML report.
 * - workspaceRoot: REQUIRED. The directory the relative path is
 *   resolved against (typically the pipeline --workspace).
 * - failOnFailure: when true (default) the Step produces a typed USER
 *   failure if the report has any failures/errors. When false, the
 *   Step always SUCCEEDS — the summary is still emitted, but a
 *   failure does not abort the pipeline (use this for informational
 *   reporting steps).
 * - maxReportBytes: hard cap on the report size we will read (default
 *   10 MiB). Larger reports fail closed with a USER failure; the
 *   failure message names the limit so the caller can raise it.
 */
data class JUnitResultsInput(
    val reportPath: String,
    val workspaceRoot: String,
    val failOnFailure: Boolean = true,
    val maxReportBytes: Long = DEFAULT_MAX_REPORT_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_REPORT_BYTES: Long = 10L * 1024L * 1024L
    }
}
