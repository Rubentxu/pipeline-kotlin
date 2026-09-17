package pipeline.testing.events

import kotlinx.serialization.json.Json
import pipeline.testing.results.ParseFailureReason
import pipeline.testing.results.TestCaseResult
import pipeline.testing.results.TestFailure
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestStatus
import pipeline.testing.results.TestSuiteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E3-T3 — testing-events contract.
 *
 * Pins the three directives of this slice:
 *   1. exactly three event kinds, sealed and exhaustively matchable;
 *   2. **summary + references, never a per-testcase explosion** — asserted
 *      arithmetically and at a 1000-case boundary;
 *   3. pure, total, deterministic derivation with no execution-authority fields.
 */
class TestingEventsContractTest {

    private fun passed(suite: String, name: String) =
        TestCaseResult(suiteName = suite, name = name, status = TestStatus.Passed)

    private fun failed(suite: String, name: String) =
        TestCaseResult(
            suiteName = suite,
            name = name,
            status = TestStatus.Failed(TestFailure("boom")),
        )

    private fun skipped(suite: String, name: String) =
        TestCaseResult(suiteName = suite, name = name, status = TestStatus.Skipped)

    private fun report(vararg suites: TestSuiteResult) =
        TestReport.Successful(suites = suites.toList())

    private fun suite(name: String, vararg cases: TestCaseResult) =
        TestSuiteResult(name = name, cases = cases.toList())

    // ───────── shape ─────────

    @Test
    fun `exactly three event kinds, exhaustively matchable`() {
        val all = listOf(
            TestingEvent.TestReportPublished(TestReportSummary.EMPTY, emptyList()),
            TestingEvent.TestSuiteCompleted(TestReportSummary.EMPTY, TestSuiteSummary("s", 0, 0, 0, 0, 0, 0)),
            TestingEvent.TestFailuresDetected(TestReportSummary.EMPTY, emptyList()),
        )
        // A `when` with only the three cases and no `else` proves the hierarchy is closed.
        val kinds = all.map { e ->
            when (e) {
                is TestingEvent.TestReportPublished -> "published"
                is TestingEvent.TestSuiteCompleted -> "suite"
                is TestingEvent.TestFailuresDetected -> "failures"
            }
        }
        assertEquals(listOf("published", "suite", "failures"), kinds)
    }

    @Test
    fun `no event carries execution-authority identity`() {
        // The event ADT must not expose runId/stepIndex: the authority stamps
        // those when it transports an event; a Step must not be able to forge
        // them (that would allow cross-run event injection).
        val forbidden = setOf("runId", "stepIndex", "eventId")
        val types = listOf(
            TestingEvent.TestReportPublished::class,
            TestingEvent.TestSuiteCompleted::class,
            TestingEvent.TestFailuresDetected::class,
        )
        for (t in types) {
            val names = t.java.declaredFields.map { it.name }.toSet()
            val leaked = names intersect forbidden
            assertTrue(
                leaked.isEmpty(),
                "${t.simpleName} must not carry execution-authority fields, found $leaked",
            )
        }
    }

    // ───────── the non-explosion invariant ─────────

    @Test
    fun `event count is a function of SUITES not CASES - passing report`() {
        val r = report(
            suite("A", passed("A", "a1"), passed("A", "a2"), passed("A", "a3")),
            suite("B", passed("B", "b1")),
        )
        val events = TestingEventDerivation.derive(r)

        // 1 published + 2 suites + 0 failures
        assertEquals(3, events.size)
        assertEquals(3, 1 + r.suites.size + 0)
    }

    @Test
    fun `event count is a function of SUITES not CASES - failing report`() {
        val r = report(
            suite("A", passed("A", "a1"), failed("A", "a2")),
            suite("B", passed("B", "b1")),
        )
        val events = TestingEventDerivation.derive(r)

        // 1 published + 2 suites + 1 failures(aggregate)
        assertEquals(4, events.size)
        assertEquals(4, 1 + r.suites.size + 1)
    }

    @Test
    fun `1000 cases across 2 suites produce at most 4 events - no per-case explosion`() {
        val manyCases = (0 until 500).map { i ->
            if (i % 10 == 0) failed("Big", "c$i") else passed("Big", "c$i")
        }
        val otherCases = (0 until 500).map { passed("Other", "o$it") }
        val r = report(suite("Big", *manyCases.toTypedArray()), suite("Other", *otherCases.toTypedArray()))

        val events = TestingEventDerivation.derive(r)

        assertEquals(1000, r.totalCount, "boundary fixture really has 1000 cases")
        assertEquals(4, events.size, "events must scale with suites (2), not cases (1000)")
        assertTrue(
            events.size < r.totalCount / 100,
            "event count ${events.size} must be orders of magnitude below case count ${r.totalCount}",
        )
    }

    @Test
    fun `failing suites are reported as NAMES - references, not payloads`() {
        val r = report(
            suite("A", passed("A", "a1"), failed("A", "a2")),
            suite("B", passed("B", "b1")),
        )
        val failures = TestingEventDerivation.derive(r)
            .filterIsInstance<TestingEvent.TestFailuresDetected>()
            .single()

        assertEquals(listOf("A"), failures.failingSuites)
        // No case-level identity appears in the reference list.
        assertTrue(failures.failingSuites.none { it.contains("::") })
    }

    @Test
    fun `no failures means no TestFailuresDetected event`() {
        val r = report(suite("A", passed("A", "a1")))
        val events = TestingEventDerivation.derive(r)
        assertTrue(events.none { it is TestingEvent.TestFailuresDetected })
    }

    @Test
    fun `errored cases count as failures for the aggregate event`() {
        val errored = TestCaseResult(
            suiteName = "N",
            name = "net",
            status = TestStatus.Errored(TestFailure("timeout")),
        )
        val r = report(suite("N", errored))
        val failures = TestingEventDerivation.derive(r)
            .filterIsInstance<TestingEvent.TestFailuresDetected>()
            .single()
        assertEquals(listOf("N"), failures.failingSuites)
        assertEquals(1, failures.summary.errored)
    }

    // ───────── Unparseable ─────────

    @Test
    fun `unparseable report derives an EMPTY event stream`() {
        val r = TestReport.Unparseable("/x.xml", ParseFailureReason.SourceMissing("/x.xml"))
        assertTrue(
            TestingEventDerivation.derive(r).isEmpty(),
            "a report with no test-domain facts must not fabricate events",
        )
    }

    // ───────── summary projection ─────────

    @Test
    fun `summary projects every count from the typed report - single definition`() {
        val r = report(
            suite(
                "S",
                passed("S", "p1"),
                passed("S", "p2"),
                failed("S", "f"),
                skipped("S", "s"),
                TestCaseResult("S", "d", status = TestStatus.Disabled),
                TestCaseResult("S", "e", status = TestStatus.Errored(TestFailure("x"))),
            ),
        )
        val s = TestingEventDerivation.summaryOf(r)
        assertEquals(6, s.total)
        assertEquals(2, s.passed)
        assertEquals(1, s.failed)
        assertEquals(1, s.errored)
        assertEquals(1, s.skipped)
        assertEquals(1, s.disabled)
        assertTrue(s.hasFailures)

        // Every event in the stream carries the same report-level snapshot.
        val events = TestingEventDerivation.derive(r)
        assertTrue(events.all { it.summary == s })
    }

    @Test
    fun `suite summary carries identity plus counts`() {
        val s = TestSuiteResult(
            name = "com.example.X",
            hostname = "ci-1",
            timestamp = "2026-09-17T10:00:00",
            durationSeconds = 1.5,
            cases = listOf(passed("com.example.X", "a"), failed("com.example.X", "b")),
        )
        val summary = TestingEventDerivation.summaryOf(s)
        assertEquals("com.example.X", summary.name)
        assertEquals(2, summary.total)
        assertEquals(1, summary.passed)
        assertEquals(1, summary.failed)
        assertTrue(summary.hasFailures)
        // Suite summaries carry counts + identity only. Kotlin's `@Serializable`
        // adds a synthetic static serializer delegate, so assert the ABSENCE of
        // the suite's non-identity metadata rather than an exact field set.
        val fields = TestSuiteSummary::class.java.declaredFields.map { it.name }.toSet()
        for (forbidden in listOf("hostname", "timestamp", "durationSeconds", "cases", "classname")) {
            assertFalse(
                fields.contains(forbidden),
                "TestSuiteSummary must not carry suite metadata '$forbidden' (references only)",
            )
        }
        assertTrue(fields.contains("name"), "suite identity must be present as a reference")
    }

    // ───────── purity / determinism ─────────

    @Test
    fun `derivation is deterministic for the same report`() {
        val r = report(suite("A", passed("A", "a1"), failed("A", "a2")))
        val first = TestingEventDerivation.derive(r, listOf("/p/a.xml"))
        val second = TestingEventDerivation.derive(r, listOf("/p/a.xml"))
        assertEquals(first, second)
    }

    @Test
    fun `source paths are references echoed into the published event only`() {
        val r = report(suite("A", passed("A", "a1")))
        val paths = listOf("/reports/a.xml", "/reports/b.xml")
        val events = TestingEventDerivation.derive(r, paths)
        val published = events.filterIsInstance<TestingEvent.TestReportPublished>().single()
        assertEquals(paths, published.sourcePaths)
        // No other event kind embeds the paths.
        assertTrue(
            events.filterIsInstance<TestingEvent.TestSuiteCompleted>().all {
                it.suite.name == "A"
            },
        )
    }

    @Test
    fun `empty report derives exactly one published event and no suites or failures`() {
        val events = TestingEventDerivation.derive(report())
        assertEquals(1, events.size)
        assertTrue(events.single() is TestingEvent.TestReportPublished)
        assertEquals(TestReportSummary.EMPTY, events.single().summary)
    }

    // ───────── serialisation ─────────

    @Test
    fun `events round-trip through JSON without loss`() {
        val r = report(
            suite("A", passed("A", "a1"), failed("A", "a2")),
            suite("B", passed("B", "b1")),
        )
        val json = Json { encodeDefaults = true }
        for (event in TestingEventDerivation.derive(r, listOf("/p/a.xml"))) {
            val encoded = json.encodeToString(TestingEvent.serializer(), event)
            val decoded = json.decodeFromString(TestingEvent.serializer(), encoded)
            assertEquals(event, decoded)
        }
    }

    @Test
    fun `serialised event payload does not embed case-level identity`() {
        val r = report(
            suite("A", passed("A", "passCaseName"), failed("A", "failCaseName")),
        )
        val json = Json { encodeDefaults = true }
        val blob = TestingEventDerivation.derive(r)
            .joinToString("\n") { json.encodeToString(TestingEvent.serializer(), it) }

        assertTrue(blob.contains("\"A\""), "suite name (reference) must appear")
        assertFalse(blob.contains("passCaseName"), "passing case identity must not leak into events")
        assertFalse(blob.contains("failCaseName"), "failing case identity must not leak into events")
    }
}
