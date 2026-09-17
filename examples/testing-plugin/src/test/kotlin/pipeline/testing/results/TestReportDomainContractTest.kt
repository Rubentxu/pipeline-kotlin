package pipeline.testing.results

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E3-T0 — Test-report domain contract.
 *
 * These tests pin the invariants of the typed domain model itself:
 *   - totals are computed from contained cases, not duplicated
 *   - the sealed [TestStatus] hierarchy is closed (each case carries
 *     its own payload; there is no boolean/nullable flag bag)
 *   - identity is denormalised for downstream consumers
 *
 * This suite does NOT depend on the JUnit XML adapter. It protects
 * the SPI consumers against accidental domain regressions during
 * later slices (E3-T1..T4, R1, R2).
 */
class TestReportDomainContractTest {

    @Test
    fun `passed case produces zero failure and one success`() {
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "S",
                    cases = listOf(
                        TestCaseResult(
                            suiteName = "S",
                            name = "p",
                            status = TestStatus.Passed,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, report.totalCount)
        assertEquals(1, report.passedCount)
        assertEquals(0, report.failedCount)
        assertEquals(0, report.erroredCount)
        assertFalse(report.hasTestFailures)
    }

    @Test
    fun `failed case has its own typed payload and is not a string sentinel`() {
        val failure = TestFailure(
            message = "boom",
            failureType = "AssertionFailedFailure",
            stackTrace = listOf("frame 1", "frame 2"),
        )
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "S",
                    cases = listOf(
                        TestCaseResult(
                            suiteName = "S",
                            name = "f",
                            status = TestStatus.Failed(failure),
                        ),
                    ),
                ),
            ),
        )

        val case = (report.suites.single().cases.single())
        val status = case.status as TestStatus.Failed
        assertEquals("boom", status.failure.message)
        assertEquals("AssertionFailedFailure", status.failure.failureType)
        assertEquals(2, status.failure.stackTrace.size)
        assertTrue(report.hasTestFailures)
    }

    @Test
    fun `errored case is distinct from failed case`() {
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "S",
                    cases = listOf(
                        TestCaseResult(
                            suiteName = "S",
                            name = "crash",
                            status = TestStatus.Errored(TestFailure("SocketTimeout")),
                        ),
                        TestCaseResult(
                            suiteName = "S",
                            name = "assertion",
                            status = TestStatus.Failed(TestFailure("expected 1 to equal 2")),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, report.totalCount)
        assertEquals(1, report.failedCount, "exactly one failed")
        assertEquals(1, report.erroredCount, "exactly one errored")
    }

    @Test
    fun `skipped and disabled are independent statuses`() {
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "S",
                    cases = listOf(
                        TestCaseResult(suiteName = "S", name = "a", status = TestStatus.Skipped),
                        TestCaseResult(suiteName = "S", name = "b", status = TestStatus.Disabled),
                    ),
                ),
            ),
        )

        assertEquals(1, report.skippedCount)
        assertEquals(1, report.disabledCount)
    }

    @Test
    fun `optional fields are nullable and tolerate missing data`() {
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "S",
                    // no hostname, no timestamp, no durationSeconds
                    cases = listOf(
                        TestCaseResult(
                            suiteName = "S",
                            name = "minimal",
                            // no classname, no durationSeconds
                            status = TestStatus.Passed,
                        ),
                    ),
                ),
            ),
        )

        val suite = report.suites.single()
        assertNull(suite.hostname)
        assertNull(suite.timestamp)
        assertNull(suite.durationSeconds)
        val case = suite.cases.single()
        assertNull(case.classname)
        assertNull(case.durationSeconds)
    }

    @Test
    fun `Unparseable report carries an explicit reason and no suites`() {
        val report = TestReport.Unparseable(
            source = "/missing/path.xml",
            reason = ParseFailureReason.SourceMissing("/missing/path.xml"),
        )

        assertEquals(0, report.totalCount, "Unparseable reports cannot fabricate tests")
        assertNotNull(report as TestReport.Unparseable)
        assertTrue(report.reason is ParseFailureReason.SourceMissing)
    }

    @Test
    fun `suite and case identity is denormalised so consumers do not have to re-walk`() {
        val report = TestReport.Successful(
            suites = listOf(
                TestSuiteResult(
                    name = "com.example.X",
                    cases = listOf(
                        TestCaseResult(suiteName = "com.example.X", name = "testA", status = TestStatus.Passed),
                    ),
                ),
            ),
        )

        val suite = report.suites.single()
        val case = suite.cases.single()
        // Consumers may build fqtn without walking parents:
        assertEquals("com.example.X", case.suiteName)
        assertEquals("testA", case.name)
    }

    @Test
    fun `totals are computed once and only once by the model`() {
        val cases = listOf(
            TestCaseResult(suiteName = "S", name = "p1", status = TestStatus.Passed),
            TestCaseResult(suiteName = "S", name = "p2", status = TestStatus.Passed),
            TestCaseResult(
                suiteName = "S",
                name = "f",
                status = TestStatus.Failed(TestFailure("boom")),
            ),
            TestCaseResult(suiteName = "S", name = "s", status = TestStatus.Skipped),
        )
        val report = TestReport.Successful(
            suites = listOf(TestSuiteResult(name = "S", cases = cases)),
        )

        assertEquals(4, report.totalCount)
        assertEquals(2, report.passedCount)
        assertEquals(1, report.failedCount)
        assertEquals(1, report.skippedCount)
        assertEquals(0, report.erroredCount)
        assertEquals(0, report.disabledCount)
        // The model is the single source of truth: a duplicate field
        // somewhere upstream must not be tolerated.
    }
}
