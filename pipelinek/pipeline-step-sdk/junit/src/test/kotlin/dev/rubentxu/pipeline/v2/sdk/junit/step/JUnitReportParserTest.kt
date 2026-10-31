package dev.rubentxu.pipeline.v2.sdk.junit.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * The parser behind the `junit.results` step.
 *
 * The step reads a report a build tool wrote, and the report is data from
 * outside: a CI system, another team's build, an artifact downloaded from a
 * feed. That makes two guarantees worth having, and the module had neither
 * observed.
 *
 * The first is the ordinary one: the totals the step reports are the totals
 * the report declares. A parser that read the wrong attribute, or summed only
 * the first suite of a multi-suite report, would report a green build as red
 * or the other way round.
 *
 * The second is the one that matters for untrusted data: the parser must not
 * let the document reach outside itself. An XML report can declare an
 * external entity, and a parser that resolves it would read a local file or
 * open a connection on behalf of whoever wrote the report. The module hardens
 * against that in two independent ways — the parser refuses any DOCTYPE
 * outright, and an entity resolver that refuses every request — and this file
 * is what keeps that hardening from being an intention.
 *
 * Nothing else observes this parser. The executable layer runs pipelines, and
 * no scenario in it publishes a JUnit report; the step that calls the parser
 * reads a file, which is not a unit test's business.
 */
class JUnitReportParserTest {

    private val reportPath = "/workspace/target/surefire-reports/TEST-suite.xml"

    private fun parse(xml: String): JUnitReportSummary =
        JUnitReportParser.parse(ByteArrayInputStream(xml.toByteArray()), reportPath)

    @Test
    fun `a report is summarised with the totals it declares`() {
        val summary = parse(
            """
            <testsuite name="suite" tests="7" failures="2" errors="1" skipped="3" time="1.5">
              <testcase name="a" classname="C"/>
            </testsuite>
            """.trimIndent(),
        )

        assertEquals(7, summary.tests)
        assertEquals(2, summary.failures)
        assertEquals(1, summary.errors)
        assertEquals(3, summary.skipped)
        assertEquals(1.5, summary.durationSeconds)
        assertEquals(reportPath, summary.reportPath)
    }

    @Test
    fun `a report of several suites is summed`() {
        // The wrapper shape a build tool writes when a module runs more than one
        // suite. Counting only the first one would report a fraction of the run.
        val summary = parse(
            """
            <testsuites>
              <testsuite name="first" tests="3" failures="0" errors="0" skipped="0" time="0.5"/>
              <testsuite name="second" tests="4" failures="1" errors="0" skipped="2" time="2.0"/>
            </testsuites>
            """.trimIndent(),
        )

        assertEquals(7, summary.tests)
        assertEquals(1, summary.failures)
        assertEquals(2, summary.skipped)
        assertEquals(2.5, summary.durationSeconds)
    }

    @Test
    fun `a report whose wrapper declares the totals is counted once`() {
        // The aggregated form: the wrapper carries the totals of the whole run
        // and the suite below carries the same totals. The two describe the
        // SAME tests, so adding them reports twice as many tests as the run
        // had — and the step publishes that figure as the outcome.
        val summary = parse(
            """
            <testsuites tests="7" failures="2" errors="1" skipped="3" time="1.5">
              <testsuite name="suite" tests="7" failures="2" errors="1" skipped="3" time="1.5"/>
            </testsuites>
            """.trimIndent(),
        )

        assertEquals(7, summary.tests)
        assertEquals(2, summary.failures)
        assertEquals(1, summary.errors)
        assertEquals(3, summary.skipped)
        assertEquals(1.5, summary.durationSeconds)
    }

    @Test
    fun `a report whose suites are empty is a report with no tests`() {
        // The wrapper with nothing inside it. A build that ran no suite still
        // published a report, and the report says the run had no tests — which
        // is not the same as the document being unreadable. Refusing it would
        // turn a build with no tests into a broken step.
        val summary = parse("""<testsuites/>""")

        assertEquals(0, summary.tests)
        assertEquals(0, summary.failures)
        assertEquals(0, summary.errors)
        assertEquals(0, summary.skipped)
    }

    @Test
    fun `a report that declares its totals only in the wrapper keeps them`() {
        // The wrapper with nothing inside it and its own totals. Dropping them
        // would turn a report that declares two failures into a report with no
        // tests and no failures, and the step would call a red run green.
        val summary = parse(
            """<testsuites tests="7" failures="2" errors="1" skipped="3" time="1.5"/>""",
        )

        assertEquals(7, summary.tests)
        assertEquals(2, summary.failures)
        assertEquals(1, summary.errors)
        assertEquals(3, summary.skipped)
        assertEquals(1.5, summary.durationSeconds)
    }

    @Test
    fun `a report that declares a doctype is refused`() {
        // The XXE case. A report that names an external entity must not be
        // parsed at all, because the moment the parser accepts the declaration
        // it has already agreed to resolve it. The entity here points at a
        // local file: a parser that resolved it would read the file and put
        // its content in the summary.
        val hostile = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE testsuite [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <testsuite name="hostile" tests="1" failures="0" errors="0" skipped="0" time="0.1">
              <testcase name="&secret;" classname="C"/>
            </testsuite>
        """.trimIndent()

        val failure = assertThrows(JUnitReportParseException::class.java) { parse(hostile) }

        assertTrue(
            failure.message?.contains("DOCTYPE") == true,
            "the refusal did not name the declaration it refused: ${failure.message}",
        )
    }

    @Test
    fun `a malformed report is refused with a typed failure`() {
        val failure = assertThrows(JUnitReportParseException::class.java) {
            parse("<testsuite name=\"broken\" tests=\"1\"")
        }

        assertTrue(
            failure.message?.contains(reportPath) == true,
            "the refusal did not name the report: ${failure.message}",
        )
    }

    @Test
    fun `a document that is not a report is refused`() {
        // A report that parsed as an empty one would tell the caller that the
        // build ran no tests, which is a claim about the build and not about the
        // file.
        val failure = assertThrows(JUnitReportParseException::class.java) {
            parse("<html><body>not a report</body></html>")
        }

        assertTrue(
            failure.message?.contains("testsuite") == true,
            "the refusal did not say what it expected: ${failure.message}",
        )
    }
}
