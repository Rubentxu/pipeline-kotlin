package pipeline.testing.results

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E3-T0 — RED characterization of the JUnit XML adapter contract.
 *
 * Each test loads a fixture from `src/test/resources/junit/` and
 * asserts what the adapter MUST produce. They are the matrix of
 * behaviour E3-T1 (GREEN) has to satisfy — the directive specifies:
 *
 *   - single suite
 *   - multiple suites
 *   - failed testcase
 *   - skipped testcase
 *   - malformed XML
 *   - missing optional fields
 *   - duplicate / ambiguous IDs
 *   - large fixture
 *
 * Each test is named after the fixture so the failure report
 * directly maps to the missing behaviour.
 */
class JunitXmlAdapterContractTest {

    private fun adapter(): TestReportAdapter = JunitAdapterFactory.junitAdapter()

    private fun load(name: String): ByteArray {
        val path = Paths.get("src/test/resources/junit", name)
        return Files.readAllBytes(path)
    }

    @Test
    fun `single suite with one failure produces a typed Failed case with stack trace`() {
        val bytes = load("single-suite-failure.xml")
        val report = adapter().parse(bytes, source = "single-suite-failure.xml")

        require(report is TestReport.Successful) {
            "successful parse expected for well-formed single-suite-failure.xml"
        }
        val suite = report.suites.single()
        assertEquals("com.example.calculator.AdditionTests", suite.name)
        assertEquals(3, suite.totalCount)
        assertEquals(2, suite.passedCount)
        assertEquals(1, suite.failedCount)
        assertEquals(0, suite.erroredCount)

        val failedCase = suite.cases.single { it.status is TestStatus.Failed }
        val failure = (failedCase.status as TestStatus.Failed).failure
        assertTrue(failure.message.contains("expected 1 to equal 2"))
        assertEquals("org.opentest4j.AssertionFailedFailure", failure.failureType)
        assertTrue(failure.stackTrace.isNotEmpty(), "stack trace must be captured")
    }

    @Test
    fun `multiple suites are surfaced as separate TestSuiteResult entries`() {
        val bytes = load("multi-suite-skipped.xml")
        val report = adapter().parse(bytes, source = "multi-suite-skipped.xml")

        require(report is TestReport.Successful) {
            "successful parse expected for well-formed multi-suite-skipped.xml"
        }
        assertEquals(2, report.suites.size)
        assertEquals("com.example.calculator.AdditionTests", report.suites[0].name)
        assertEquals("com.example.calculator.MultiplicationTests", report.suites[1].name)
        assertEquals(3, report.totalCount)
        assertEquals(2, report.passedCount)
        assertEquals(1, report.skippedCount)
    }

    @Test
    fun `skipped testcase produces Skipped status with optional reason`() {
        val bytes = load("multi-suite-skipped.xml")
        val report = adapter().parse(bytes, source = "multi-suite-skipped.xml")
        require(report is TestReport.Successful) { "well-formed" }
        val skipped = report.suites[0].cases.single { it.status is TestStatus.Skipped }
        // The fixture uses JUnit-XML `<skipped message="flaky test"/>`;
        // the contract says Skipped is a closed ADT case (no message
        // string attached to the case); the reason MUST surface
        // SOMEWHERE in the typed model so policy can route it.
        // E3-T1 chooses where (most likely a typed extension on the case).
        // For now the test just asserts the status exists; E3-T1 will
        // refine it as needed during GREEN.
        assertNotNull(skipped.status)
    }

    @Test
    fun `errored testcase is distinct from failed testcase and produces Errored status`() {
        val bytes = load("single-suite-error.xml")
        val report = adapter().parse(bytes, source = "single-suite-error.xml")

        require(report is TestReport.Successful) { "well-formed" }
        val case = report.suites.single().cases.single()
        assertTrue(case.status is TestStatus.Errored)
        val error = (case.status as TestStatus.Errored).failure
        assertTrue(error.message.contains("SocketTimeoutException"))
        assertEquals("java.net.SocketTimeoutException", error.failureType)
    }

    @Test
    fun `missing optional fields are tolerated and surfaced as nulls not defaults`() {
        val bytes = load("missing-optional-fields.xml")
        val report = adapter().parse(bytes, source = "missing-optional-fields.xml")

        require(report is TestReport.Successful) { "well-formed" }
        val suite = report.suites.single()
        assertEquals("com.example.MinimalTests", suite.name)
        // The fixture omits tests/skipped/failures/errors attributes.
        // The parser MUST NOT invent defaults; it derives totals from cases.
        assertEquals(1, suite.totalCount, "totals come from contained cases")
        assertEquals(1, suite.passedCount)

        val case = suite.cases.single()
        assertEquals("noAttributes", case.name)
        assertTrue(case.status is TestStatus.Passed)
    }

    @Test
    fun `duplicate testcase identities are flagged as AmbiguousIdentity`() {
        val bytes = load("duplicate-testcase-id.xml")
        val report = adapter().parse(bytes, source = "duplicate-testcase-id.xml")

        // The directive explicitly calls out "duplicate / ambiguous IDs"
        // as a parser obligation. The contract: ambiguous identity is a
        // typed [ParseFailureReason.AmbiguousIdentity], NOT a silent merge.
        require(report is TestReport.Unparseable) {
            "duplicate identity must be reported as Unparseable.AmbiguousIdentity"
        }
        assertTrue(
            report.reason is ParseFailureReason.AmbiguousIdentity,
            "reason must be the closed AmbiguousIdentity case",
        )
    }

    @Test
    fun `malformed XML produces XmlMalformed with no fabricated cases`() {
        val bytes = load("malformed-xml.xml")
        val report = adapter().parse(bytes, source = "malformed-xml.xml")

        require(report is TestReport.Unparseable) {
            "malformed XML must be reported as Unparseable"
        }
        assertTrue(report.reason is ParseFailureReason.XmlMalformed)
        assertEquals(0, report.totalCount, "Unparseable MUST NOT fabricate cases")
    }

    @Test
    fun `non-XML input is rejected as XmlMalformed`() {
        val bytes = load("non-xml.txt")
        val report = adapter().parse(bytes, source = "non-xml.txt")

        require(report is TestReport.Unparseable) {
            "non-XML input must be Unparseable"
        }
        // Plain text is NOT well-formed XML; the closed ParseFailureReason
        // distinguishes "malformed XML" (XmlMalformed) from "well-formed
        // XML but wrong content" (SchemaMismatch). Plain text is the
        // former.
        assertTrue(report.reason is ParseFailureReason.XmlMalformed)
    }

    @Test
    fun `large fixture of 1000 cases is parsed with correct totals and within reasonable time`() {
        // Build the large fixture in-memory rather than committing it; the
        // file would otherwise bloat the repo by hundreds of KB.
        val cases = (0 until 1000).joinToString(separator = "\n") { i ->
            val status = when {
                i < 800 -> ""
                i < 900 -> "<skipped/>"
                i < 950 -> "<failure message=\"f$i\" type=\"AssertionFailedFailure\"/>"
                else -> "<error message=\"e$i\" type=\"RuntimeException\"/>"
            }
            """<testcase name="test$i" classname="Stress" time="0.05">$status</testcase>"""
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<testsuite name=\"com.example.Stress\" tests=\"1000\" skipped=\"100\" failures=\"50\" errors=\"50\" time=\"120.5\">\n")
            append(cases)
            append("\n</testsuite>\n")
        }.toByteArray()

        val start = System.nanoTime()
        val report = adapter().parse(xml, source = "synthetic-large.xml")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        require(report is TestReport.Successful) { "well-formed synthetic large" }
        assertEquals(1000, report.totalCount)
        assertEquals(800, report.passedCount)
        assertEquals(100, report.skippedCount)
        assertEquals(50, report.failedCount)
        assertEquals(50, report.erroredCount)
        // 5s is a generous ceiling; a real JUnit XML parser on a desktop
        // JVM parses this in tens of milliseconds. Anything significantly
        // slower than the ceiling suggests an O(n^2) bug in the parser.
        assertTrue(elapsedMs < 5000, "1000-case parse took ${elapsedMs}ms (>5000ms)")
    }

    @Test
    fun `parse result is deterministic for the same input`() {
        val bytes = load("single-suite-failure.xml")
        val first = adapter().parse(bytes, source = "single-suite-failure.xml")
        val second = adapter().parse(bytes, source = "single-suite-failure.xml")

        // Determinism: same bytes -> same typed model.
        assertEquals(first, second)
    }
}
