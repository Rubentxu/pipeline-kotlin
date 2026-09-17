package pipeline.testing.junit

import pipeline.testing.results.ParseFailureReason
import pipeline.testing.results.TestFailure
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestReportAdapter
import pipeline.testing.results.TestStatus
import pipeline.testing.results.TestSuiteResult
import pipeline.testing.TestingContributor

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E3-T2 — `core.junit` Step).
//
// The ADT is the closed taxonomy for any failure the handler may encounter.
// Each case carries the payload meaningful for it. None of these is a
// "report failed tests" outcome: tests failing is a *typed result* (a
// `TestReport.Successful` with `hasTestFailures == true`), not an error.
// ─────────────────────────────────────────────────────────────────────────────

sealed interface JunitStepError {
    /** The report path was provided but no file exists at that location. */
    data class ReportNotFound(val path: String) : JunitStepError

    /** A filesystem-level I/O failure other than NotFound. */
    data class ReportIoFailure(val path: String, val reason: String) : JunitStepError

    /**
     * The adapter returned a typed [TestReport.Unparseable] outcome.
     * Carries the typed [ParseFailureReason] so callers can route
     * infrastructure errors differently from typed test failures.
     */
    data class ReportUnparseable(val path: String, val reason: ParseFailureReason) : JunitStepError
}

class JunitStepException(val reason: JunitStepError) :
    RuntimeException("testing.junit: ${reason::class.simpleName}: ${reason.describe()}")

private fun JunitStepError.describe(): String = when (this) {
    is JunitStepError.ReportNotFound -> "report not found: $path"
    is JunitStepError.ReportIoFailure -> "I/O failure at $path: $reason"
    is JunitStepError.ReportUnparseable -> "report unparseable at $path: $reason"
}

// ────────────────────��─────────────────────────────��──────────────────────────
// Capability port — narrow surface: read a file as bytes.
// ─────────────────────────────────────────────────────────────────────────────

interface JunitFilesystemOperations {
    /**
     * Read the file at [path] and return its raw bytes.
     *
     * Implementations MUST throw a [JunitStepException] for any
     * filesystem-level failure (NotFound / IoFailure); they MUST NOT
     * silently fabricate empty bytes.
     */
    @Throws(JunitStepException::class)
    fun readBytes(path: String): ByteArray
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultJunitFilesystemOperations : JunitFilesystemOperations {
    override fun readBytes(path: String): ByteArray {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw JunitStepException(JunitStepError.ReportNotFound(path))
        }
        return try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw JunitStepException(
                JunitStepError.ReportIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed Input / Output — what `core.junit` accepts and produces.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Input for the `core.junit` Step.
 *
 * [reportPaths] is the list of JUnit XML files to parse. The handler
 * aggregates the typed [TestReport]s into a single [JunitStepOutput].
 *
 * The list is intentionally typed (not a single `String`): a test job
 * typically produces multiple XML files (one per module / runner), and
 * modelling that as a single path forces callers to pre-aggregate
 * outside the Step. A future family (Surefire collector, Gradle
 * aggregator) might choose a different shape; this one matches the
 * common case.
 */
@kotlinx.serialization.Serializable
data class JunitStepInput(
    val reportPaths: List<String>,
)

/**
 * Output of the `core.junit` Step.
 *
 * [report] is the aggregated typed [TestReport] across all input paths.
 *
 * The Step DOES NOT collapse "tests failed" into `Outcome.Failed`; the
 * pipeline policy (a separate Step or DSL construct) decides whether
 * `report.hasTestFailures` aborts the build. The Step's only obligation
 * is to surface a faithful typed representation; that's the central
 * E3 architectural invariant ("tests failed" ≠ "Step execution failed").
 *
 * [parseFailures] is non-empty only when AT LEAST ONE input path
 * produced an [TestReport.Unparseable]. The other paths' typed data is
 * merged into [report] when present (so partial parse success is
 * surfaced faithfully). An entirely unparseable input set yields an
 * empty [report] and a [parseFailures] list equal to [reportPaths].
 */
@kotlinx.serialization.Serializable
data class JunitStepOutput(
    val report: TestReport,
    val parseFailures: List<ParseFailureReason>,
)
