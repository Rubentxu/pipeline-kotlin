package dev.rubentxu.pipeline.v2.domain.step.junit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [JunitParser] — covers the canonical
 * Ant/Maven schema, edge cases, and fail-closed behavior on
 * non-canonical schema variants.
 *
 * No fixture files; the parser is exercised against inline XML
 * strings to keep these tests hermetic and independent of
 * filesystem layout.
 */
class JunitParserTest {

    private fun xml(s: String): ByteArray = s.trimIndent().toByteArray(Charsets.UTF_8)

    // ---------- Happy path ----------

    @Test
    fun `parses a single testsuite with passing cases`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="3" failures="0" errors="0" skipped="0" time="1.234">
              <testcase name="testA" classname="com.example.FooTest" time="0.1"/>
              <testcase name="testB" classname="com.example.FooTest" time="0.2"/>
              <testcase name="testC" classname="com.example.FooTest" time="0.3"/>
            </testsuite>
        """)

        val report = JunitParser.parse(raw, "/abs/path/TEST-FooTest.xml")

        assertEquals(1, report.suites.size)
        assertEquals("com.example.FooTest", report.suites[0].name)
        assertEquals(3, report.totals.tests)
        assertEquals(0, report.totals.failures)
        assertEquals(0, report.totals.errors)
        assertEquals(0, report.totals.skipped)
        assertTrue(report.totals.timeSeconds > 1.2)
        assertTrue(report.outcome is JunitReadOutcome.Passed)
        assertEquals("/abs/path/TEST-FooTest.xml", report.reportPath)
    }

    @Test
    fun `parses testsuites root with multiple suites`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="com.example.A" tests="2" failures="0" errors="0" skipped="0" time="0.5"/>
              <testsuite name="com.example.B" tests="3" failures="0" errors="0" skipped="1" time="0.7"/>
            </testsuites>
        """)

        val report = JunitParser.parse(raw, "/abs/path/TEST-x.xml")

        assertEquals(2, report.suites.size)
        assertEquals(5, report.totals.tests)
        assertEquals(0, report.totals.failures)
        assertEquals(1, report.totals.skipped)
        assertTrue(report.outcome is JunitReadOutcome.Passed)
    }

    @Test
    fun `parses failing cases with message and type`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="2" failures="1" errors="0" skipped="0" time="0.5">
              <testcase name="testPass" classname="com.example.FooTest" time="0.1"/>
              <testcase name="testFail" classname="com.example.FooTest" time="0.4">
                <failure message="expected 1 but was 2" type="java.lang.AssertionError">
                  stack trace body (ignored by parser)
                </failure>
              </testcase>
            </testsuite>
        """)

        val report = JunitParser.parse(raw, "/abs/path/TEST-x.xml")

        assertEquals(1, report.totals.failures)
        assertTrue(report.outcome is JunitReadOutcome.Failed)
        val failed = report.outcome as JunitReadOutcome.Failed
        assertEquals(1, failed.failingCases.size)
        val fc = failed.failingCases[0]
        assertEquals("testFail", fc.caseName)
        assertEquals("com.example.FooTest", fc.classname)
        assertEquals("expected 1 but was 2", fc.message)
        assertEquals("java.lang.AssertionError", fc.type)
        assertEquals(false, fc.isError)
    }

    @Test
    fun `error element wins over failure when both present`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="1" failures="1" errors="1" skipped="0" time="0.5">
              <testcase name="testBad" classname="com.example.FooTest" time="0.5">
                <failure message="inner failure" type="junit.framework.AssertionFailedError"/>
                <error message="outer error" type="java.lang.RuntimeException"/>
              </testcase>
            </testsuite>
        """)

        val report = JunitParser.parse(raw, "/x.xml")

        val failed = report.outcome as JunitReadOutcome.Failed
        assertEquals(1, failed.failingCases.size)
        val fc = failed.failingCases[0]
        assertEquals("outer error", fc.message)
        assertEquals("java.lang.RuntimeException", fc.type)
        assertEquals(true, fc.isError)
    }

    @Test
    fun `accepts properties system-out system-err as no-op containers`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="1" failures="0" errors="0" skipped="0" time="0.5">
              <properties>
                <property name="os" value="linux"/>
              </properties>
              <testcase name="testA" classname="com.example.FooTest" time="0.1">
                <system-out>captured stdout (ignored)</system-out>
                <system-err>captured stderr (ignored)</system-err>
              </testcase>
            </testsuite>
        """)

        val report = JunitParser.parse(raw, "/x.xml")

        assertEquals(1, report.totals.tests)
        assertTrue(report.outcome is JunitReadOutcome.Passed)
    }

    // ---------- Fail-closed ----------

    @Test
    fun `rejects malformed XML with Malformed failure kind`() {
        val raw = "not xml at all".toByteArray()

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        assertTrue((ex as JunitParseException).failure is JunitParseFailure.Malformed)
    }

    @Test
    fun `rejects unexpected root element with UnsupportedSchema failure kind`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <somethingElse/>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        val failure = (ex as JunitParseException).failure
        assertTrue(failure is JunitParseFailure.UnsupportedSchema)
        assertTrue((failure as JunitParseFailure.UnsupportedSchema).details.contains("somethingElse"))
    }

    @Test
    fun `rejects non-canonical children of testsuites root with UnsupportedSchema`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="com.example.A" tests="1" failures="0" errors="0" skipped="0" time="0.1"/>
              <customRootChild/>
            </testsuites>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        val failure = (ex as JunitParseException).failure
        assertTrue(failure is JunitParseFailure.UnsupportedSchema)
        assertTrue((failure as JunitParseFailure.UnsupportedSchema).details.contains("customRootChild"))
    }

    @Test
    fun `rejects non-canonical children of testsuite with UnsupportedSchema`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.A" tests="1" failures="0" errors="0" skipped="0" time="0.1">
              <testcase name="testA" classname="com.example.A" time="0.1"/>
              <bogusExtension/>
            </testsuite>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        val failure = (ex as JunitParseException).failure
        assertTrue(failure is JunitParseFailure.UnsupportedSchema)
        assertTrue((failure as JunitParseFailure.UnsupportedSchema).details.contains("bogusExtension"))
    }

    @Test
    fun `rejects empty testsuites root with Malformed`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites/>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        assertTrue((ex as JunitParseException).failure is JunitParseFailure.Malformed)
    }

    @Test
    fun `rejects testsuite missing required name attribute with Malformed`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite tests="1" failures="0" errors="0" skipped="0" time="0.1">
              <testcase name="x" classname="y" time="0.1"/>
            </testsuite>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        assertTrue((ex as JunitParseException).failure is JunitParseFailure.Malformed)
    }

    @Test
    fun `rejects non-integer tests attribute with Malformed`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="not-a-number" failures="0" errors="0" skipped="0" time="0.1"/>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        val failure = (ex as JunitParseException).failure
        assertTrue(failure is JunitParseFailure.Malformed)
        assertTrue((failure as JunitParseFailure.Malformed).reason.contains("tests"))
    }

    @Test
    fun `rejects non-double time attribute with Malformed`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="1" failures="0" errors="0" skipped="0" time="NaN-broken"/>
        """)

        val ex = kotlin.runCatching { JunitParser.parse(raw, "/x.xml") }.exceptionOrNull()

        assertNotNull(ex)
        assertTrue(ex is JunitParseException)
        val failure = (ex as JunitParseException).failure
        assertTrue(failure is JunitParseFailure.Malformed)
        assertTrue((failure as JunitParseFailure.Malformed).reason.contains("time"))
    }

    // ---------- Typed outcome invariants ----------

    @Test
    fun `Passed outcome carries totals and has no failing cases`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="X" tests="2" failures="0" errors="0" skipped="1" time="0.5">
              <testcase name="t1" classname="X" time="0.1"/>
              <testcase name="t2" classname="X" time="0.4">
                <skipped message="not yet implemented"/>
              </testcase>
            </testsuite>
        """)

        val report = JunitParser.parse(raw, "/x.xml")
        val passed = report.outcome
        assertTrue(passed is JunitReadOutcome.Passed)
        assertEquals(2, (passed as JunitReadOutcome.Passed).totals.tests)
        assertEquals(1, passed.totals.skipped)
    }

    @Test
    fun `Failed outcome aggregates failing cases across suites`() {
        val raw = xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="A" tests="1" failures="1" errors="0" skipped="0" time="0.1">
                <testcase name="failingA" classname="A" time="0.1">
                  <failure message="a" type="t"/>
                </testcase>
              </testsuite>
              <testsuite name="B" tests="1" failures="0" errors="1" skipped="0" time="0.1">
                <testcase name="errorB" classname="B" time="0.1">
                  <error message="b" type="t"/>
                </testcase>
              </testsuite>
            </testsuites>
        """)

        val report = JunitParser.parse(raw, "/x.xml")
        val failed = report.outcome as JunitReadOutcome.Failed
        assertEquals(2, failed.failingCases.size)
        assertEquals(setOf("failingA", "errorB"), failed.failingCases.map { it.caseName }.toSet())
        assertEquals(setOf("A", "B"), failed.failingCases.map { it.suiteName }.toSet())
    }
}
