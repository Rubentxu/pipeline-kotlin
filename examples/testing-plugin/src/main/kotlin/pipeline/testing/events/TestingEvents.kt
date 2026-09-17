package pipeline.testing.events

import kotlinx.serialization.Serializable
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestSuiteResult

/**
 * LFC-2E3-T3 — typed testing-event model.
 *
 * The directive specifies three testing events:
 *   - [TestingEvent.TestReportPublished]
 *   - [TestingEvent.TestSuiteCompleted]
 *   - [TestingEvent.TestFailuresDetected]
 *
 * with the discipline: **summary + references, NOT a per-testcase explosion.**
 * That discipline is enforced mechanically, not by convention:
 *
 * ```text
 * |derive(report)| == 1 + suites + (1 if hasTestFailures else 0)
 * ```
 *
 * The event count is a function of the SUITE count, never the case count. A
 * report with 10 000 cases in 2 suites yields at most 4 events. The invariant is
 * asserted in `TestingEventsContractTest`, including a 1000-case boundary that
 * would fail loudly if a future change started emitting one event per case.
 *
 * ## Transport status (honest, classified — see E3_T3 receipt)
 *
 * LFC-2E3 does NOT yet transport these events into the durable run event stream.
 * An external plugin cannot: the public SDK surface is `pipeline-domain` +
 * `pipeline-scripting-api`, while `EventSink` lives in `pipeline-events` and
 * `EVENT_SINK_CAPABILITY` lives in `pipeline-application` — neither is published
 * to plugins, and [dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult]
 * carries no sidecar events.
 *
 * This is a pre-existing, INHERITED limitation: the 17 CERTIFIED
 * `pipeline.utilities.json` Steps of LFC-2E2 likewise emit no custom events, and
 * that cycle closed with them CERTIFIED. Per AGENTS.md ("If a plugin needs an
 * internal import for a legitimate feature: classify it as an SDK gap; do not
 * work around it"), the gap is CLASSIFIED here with a concrete minimal seam
 * proposal, and the transport is left to a future approved milestone rather than
 * improvised with a reflective cast or an internal import.
 *
 * What IS delivered: the complete typed event model, a pure total derivation
 * from the typed report, and the mechanical non-explosion guarantee. Any
 * consumer can obtain the faithful event stream from the Step's durable typed
 * output by calling [TestingEventDerivation.derive].
 */

/**
 * Aggregate counts for a report or a suite.
 *
 * A dedicated value type (rather than reusing the report's many computed
 * properties) because an event payload must be self-contained and serialisable:
 * the event is a *snapshot*, not a live view over mutable state.
 */
@Serializable
data class TestReportSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val errored: Int,
    val skipped: Int,
    val disabled: Int,
) {
    /** The event-level projection of "did anything fail". Single definition. */
    val hasFailures: Boolean get() = failed > 0 || errored > 0

    companion object {
        /** The empty summary — used for reports that carried no test data. */
        val EMPTY = TestReportSummary(0, 0, 0, 0, 0, 0)
    }
}

/**
 * Aggregate counts for one suite plus its identity.
 *
 * [name] is a *reference*: the event tells the observer which suite completed
 * without embedding its cases. A consumer that wants case detail reads the
 * durable typed report.
 */
@Serializable
data class TestSuiteSummary(
    val name: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val errored: Int,
    val skipped: Int,
    val disabled: Int,
) {
    val hasFailures: Boolean get() = failed > 0 || errored > 0
}

/**
 * A testing-domain event.
 *
 * Every case carries a [summary] (the report-level snapshot at the time the
 * event was derived) so observers can order/aggregate event streams without
 * re-reading the report. References ([TestReportPublished.sourcePaths],
 * [TestFailuresDetected.failingSuites]) are NAMES/PATHS, never case payloads.
 *
 * Deliberately NO `runId` / `stepIndex` field: those are execution-authority
 * facts stamped by the runtime when it transports an event, not facts a Step may
 * assert about itself. A plugin that could forge them could inject events into
 * another run. When the transport seam lands, the authority stamps them.
 */
@Serializable
sealed interface TestingEvent {
    val summary: TestReportSummary

    /** The whole report is available. Emitted exactly once per successful parse. */
    @Serializable
    data class TestReportPublished(
        override val summary: TestReportSummary,
        /** References to the source documents. Empty when the source is synthetic. */
        val sourcePaths: List<String>,
    ) : TestingEvent

    /** One suite completed. Emitted exactly once per suite. */
    @Serializable
    data class TestSuiteCompleted(
        override val summary: TestReportSummary,
        val suite: TestSuiteSummary,
    ) : TestingEvent

    /**
     * Failures were detected. Emitted AT MOST ONCE per report, carrying the
     * failing suite NAMES — never one event per failing case.
     */
    @Serializable
    data class TestFailuresDetected(
        override val summary: TestReportSummary,
        /** Suite names that contain at least one failure or error. */
        val failingSuites: List<String>,
    ) : TestingEvent
}

/**
 * Pure derivation of the testing event stream from a typed report.
 *
 * Total function: no exceptions, no I/O, no clock. Testable without constructing
 * a coordinator, a filesystem, or any runtime.
 *
 * ```text
 * derive(report).size == 1 + report.suites.size + (if (report.hasTestFailures) 1 else 0)
 * ```
 *
 * An [TestReport.Unparseable] yields an EMPTY event stream. Rationale: it
 * produced no test-domain facts, so publishing "[report published] with 0 tests"
 * or "[failures detected]" would be a lie. The failure is an execution
 * observation and is carried by `JunitStepOutput.parseFailures`, which is the
 * correct channel for a step-infrastructure fact. This keeps the boundary sharp:
 * the event stream speaks about TESTS, the Step output speaks about EXECUTION.
 */
object TestingEventDerivation {

    fun derive(
        report: TestReport,
        sourcePaths: List<String> = emptyList(),
    ): List<TestingEvent> = when (report) {
        is TestReport.Unparseable -> emptyList()
        is TestReport.Successful -> deriveFromSuccessful(report, sourcePaths)
    }

    private fun deriveFromSuccessful(
        report: TestReport.Successful,
        sourcePaths: List<String>,
    ): List<TestingEvent> {
        val summary = summaryOf(report)
        val events = ArrayList<TestingEvent>(report.suites.size + 2)

        events += TestingEvent.TestReportPublished(summary = summary, sourcePaths = sourcePaths)

        for (suite in report.suites) {
            events += TestingEvent.TestSuiteCompleted(summary = summary, suite = summaryOf(suite))
        }

        if (summary.hasFailures) {
            events += TestingEvent.TestFailuresDetected(
                summary = summary,
                failingSuites = report.suites.filter { summaryOf(it).hasFailures }.map { it.name },
            )
        }

        return events
    }

    /** Single definition of the report-level projection. */
    fun summaryOf(report: TestReport): TestReportSummary = TestReportSummary(
        total = report.totalCount,
        passed = report.passedCount,
        failed = report.failedCount,
        errored = report.erroredCount,
        skipped = report.skippedCount,
        disabled = report.disabledCount,
    )

    /** Single definition of the suite-level projection. */
    fun summaryOf(suite: TestSuiteResult): TestSuiteSummary = TestSuiteSummary(
        name = suite.name,
        total = suite.totalCount,
        passed = suite.passedCount,
        failed = suite.failedCount,
        errored = suite.erroredCount,
        skipped = suite.skippedCount,
        disabled = suite.disabledCount,
    )
}
