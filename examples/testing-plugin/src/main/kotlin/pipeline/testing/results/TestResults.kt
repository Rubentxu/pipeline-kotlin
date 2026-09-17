package pipeline.testing.results

import kotlinx.serialization.Serializable

/**
 * E3-T0 — typed test-results domain.
 *
 * LFC-2E3 ABSOLUTELY does NOT model test results as `Map<String, Any>`.
 * The shape below expresses the lifecycle of a structured test result
 * regardless of the underlying test framework that produced it (JUnit
 * today; potentially other frameworks later in LFC-2E3, AFTER a third
 * family proves the need for an abstract ReportStore — not before).
 *
 * Coupling to a particular XML schema (e.g. `surefire-junit-51.xsd`) is
 * an **adapter** concern. The adapter lives in this plugin; the domain
 * types below are an inner edge that the SDK / core / consumers never
 * see.
 *
 * The model represents:
 *  - totals (total / passed / failed / skipped)
 *  - duration (suite + testcase)
 *  - suite + testcase identity (suite name + fully qualified test name)
 *  - failure / error message
 *  - optional source file + classname (BestEffort; some reports lack them)
 *  - DISTINCTION between infrastructure parse failure (carries
 *    [parse exception]) vs parsed-but-tests-failed (no exception)
 */

/**
 * Outcome of an individual test case.
 *
 * `Skipped` and `Disabled` are SEPARATE constructors because some
 * downstream adapters compose two different policies for them
 * (e.g. passing for skipped, ignored for disabled). Holding them as
 * a single "non-run" case would erase the distinction.
 */
sealed interface TestStatus {
    @Serializable
    data object Passed : TestStatus

    @Serializable
    data object Skipped : TestStatus

    @Serializable
    data object Disabled : TestStatus

    /** `Failed` is *not* a discriminated `data class` because the test ran (as opposed to errored / crashed). */
    @Serializable
    data class Failed(val failure: TestFailure) : TestStatus

    /** `Errored` distinguishes a crashed harness / uncaught exception from a normal assertion failure. */
    @Serializable
    data class Errored(val failure: TestFailure) : TestStatus
}

/**
 * A typed failure payload — message + optional [failureType] discriminator
 * ("AssertionFailedError", "ComparisonFailure", "UnsatisfiedDependency", …).
 *
 * The reason is NOT collapsed into a String: a future E3 family (UI /
 * quality gates / Jenkins adapter) might want to highlight certain
 * failure types without depending on substring matching.
 */
@Serializable
data class TestFailure(
    val message: String,
    val failureType: String? = null,
    val stackTrace: List<String> = emptyList(),
)

/**
 * A single test case result.
 *
 * `classname + name` form the **identity**; combined with `suiteName`
 * (see [TestSuiteResult]) they form the canonical fqtn the parser is
 * responsible for emitting **once and only once** per report.
 */
@Serializable
data class TestCaseResult(
    /** Suite the case belongs to (denormalised so consumers don't need to re-walk). */
    val suiteName: String,
    /** Fully qualified name including parameter formatters from the source framework. */
    val name: String,
    /** Optional source class (`com.foo.MyTest`). Best-effort. */
    val classname: String? = null,
    /** Wall-clock duration of the test case, in seconds. Best-effort (some reports omit it). */
    val durationSeconds: Double? = null,
    val status: TestStatus,
)

/**
 * A single test suite result.
 *
 * Aggregation is computed *automatically* from the contained cases so
 * consumers don't have to recompute totals. The parser is the single
 * source of truth.
 */
@Serializable
data class TestSuiteResult(
    /** Suite identity (e.g. test class name or runner group). REQUIRED. */
    val name: String,
    /** Optional host (the running JVM or test runner). */
    val hostname: String? = null,
    /** Optional suite-level timestamp. */
    val timestamp: String? = null,
    /** Wall-clock duration of the suite (sum of cases OR a runtime-reported value). */
    val durationSeconds: Double? = null,
    /** Cases contained in the suite. Order MUST match the source adapter. */
    val cases: List<TestCaseResult>,
) {
    val totalCount: Int get() = cases.size
    val passedCount: Int get() = cases.count { it.status is TestStatus.Passed }
    val failedCount: Int get() = cases.count { it.status is TestStatus.Failed }
    val erroredCount: Int get() = cases.count { it.status is TestStatus.Errored }
    val skippedCount: Int get() = cases.count { it.status is TestStatus.Skipped }
    val disabledCount: Int get() = cases.count { it.status is TestStatus.Disabled }
}

/**
 * The complete result of parsing one or more report files of the same
 * family (e.g. multiple JUnit XML files from a test job).
 *
 * The state space is **explicitly closed** to avoid the "Map<String, Any>"
 * model the directive rejects:
 *   - [Successful]: typed data, no infrastructure errors. `failuresPresent`
 *     is computed from the data so consumers don't need to re-walk.
 *   - [Unparseable]: distinct from "no failures" — the input could not
 *     become a [TestReport]. Carries the UNDERLYING error.
 *
 * This separation is the core architectural claim of E3-T0:
 *
 *   "tests failed"  !=  "Step execution failed"
 *
 * Step execution failed is a [Unparseable] (infra failure) or
 * a higher-fidelity typed ReportRefused — UNLESS a separate
 * policy later decides that "N failing tests fail the build".
 * The parser must NEVER conflate these two outcomes.
 */
sealed interface TestReport {
    val suites: List<TestSuiteResult>

    val totalCount: Int get() = suites.sumOf { it.totalCount }
    val passedCount: Int get() = suites.sumOf { it.passedCount }
    val failedCount: Int get() = suites.sumOf { it.failedCount }
    val erroredCount: Int get() = suites.sumOf { it.erroredCount }
    val skippedCount: Int get() = suites.sumOf { it.skippedCount }
    val disabledCount: Int get() = suites.sumOf { it.disabledCount }

    /** Convenience: did the parsed data report at least one test failure? */
    val hasTestFailures: Boolean
        get() = failedCount > 0 || erroredCount > 0

    @Serializable
    data class Successful(
        override val suites: List<TestSuiteResult>,
    ) : TestReport

    /**
     * The input could not be parsed into a [Successful]. Carries the underlying
     * failure so callers can route infrastructure errors differently from typed
     * test failures.
     */
    @Serializable
    data class Unparseable(
        val source: String,
        val reason: ParseFailureReason,
    ) : TestReport {
        override val suites: List<TestSuiteResult> get() = emptyList()
    }
}

/**
 * Reasons a [TestReport.Unparseable] can be emitted.
 *
 * Discriminated (vs. String) so post-parse policies can route differently:
 *   - well-formed XML but wrong content → maladapted parser version
 *   - malformed XML → bad upstream output, surface the schema location
 *   - missing source file → orchestrator-level issue
 *   - non-XML input → caller provided the wrong file
 */
sealed interface ParseFailureReason {
    @Serializable
    data class XmlMalformed(val message: String, val line: Int? = null, val column: Int? = null) : ParseFailureReason
    @Serializable
    data class SchemaMismatch(val message: String) : ParseFailureReason
    @Serializable
    data class SourceMissing(val path: String) : ParseFailureReason
    @Serializable
    data class AmbiguousIdentity(val duplicates: List<String>) : ParseFailureReason
}
