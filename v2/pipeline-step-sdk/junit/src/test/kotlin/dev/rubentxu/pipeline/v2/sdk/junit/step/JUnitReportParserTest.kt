package dev.rubentxu.pipeline.v2.sdk.junit.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Parser-level tests for the JUnit OFFICIAL_PLUGIN (F5.2).
 *
 * These tests focus on the typed summary produced by [JUnitReportParser]
 * from a SAX parse of well-formed / malformed / XXE-bearing XML. They
 * do NOT exercise the Step contract (that lives in the application
 * tests, see `F5_2_JUnitStepContractTest`).
 */
class JUnitReportParserTest {

    @Test
    fun `parses a clean Surefire-style XML`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="5" failures="0" errors="0" skipped="0" time="1.234">
                <testcase name="a"/>
                <testcase name="b"/>
                <testcase name="c"/>
                <testcase name="d"/>
                <testcase name="e"/>
            </testsuite>
        """.trimIndent().byteInputStream()
        val summary = JUnitReportParser.parse(xml, "/tmp/sample.xml")
        assertEquals(5, summary.tests)
        assertEquals(0, summary.failures)
        assertEquals(0, summary.errors)
        assertEquals(0, summary.skipped)
        assertEquals(1.234, summary.durationSeconds, 1e-9)
        assertTrue(summary.isClean)
        assertEquals(5, summary.successful)
    }

    @Test
    fun `parses a Surefire XML with failures and errors`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.MixedTest" tests="10" failures="2" errors="1" skipped="3" time="0.5">
                <testcase name="pass-1"/>
                <testcase name="pass-2"/>
                <testcase name="fail-1"><failure message="boom"/></testcase>
                <testcase name="fail-2"><failure message="boom"/></testcase>
                <testcase name="err-1"><error message="npe"/></testcase>
                <testcase name="skip-1"><skipped/></testcase>
                <testcase name="skip-2"><skipped/></testcase>
                <testcase name="skip-3"><skipped/></testcase>
            </testsuite>
        """.trimIndent().byteInputStream()
        val summary = JUnitReportParser.parse(xml, "/tmp/mixed.xml")
        assertEquals(10, summary.tests)
        assertEquals(2, summary.failures)
        assertEquals(1, summary.errors)
        assertEquals(3, summary.skipped)
        assertEquals(3, summary.failed)
        assertFalse(summary.isClean)
        // Successful = tests - failures - errors - skipped = 4
        assertEquals(4, summary.successful)
    }

    @Test
    fun `parses the wrapping testsuites root with multiple suites`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
                <testsuite name="A" tests="3" failures="0" errors="0" skipped="0" time="0.5"/>
                <testsuite name="B" tests="2" failures="1" errors="0" skipped="0" time="0.25"/>
            </testsuites>
        """.trimIndent().byteInputStream()
        val summary = JUnitReportParser.parse(xml, "/tmp/multi.xml")
        assertEquals(5, summary.tests)
        assertEquals(1, summary.failures)
        assertEquals(0, summary.errors)
        assertEquals(0.75, summary.durationSeconds, 1e-9)
    }

    @Test
    fun `rejects XML with a DOCTYPE (XXE attempt)`() {
        // A malicious XML that tries to load a DTD from a remote URL.
        // The hardened SAX parser MUST refuse to parse it; we expect a
        // JUnitReportParseException wrapping the underlying SAXException.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo SYSTEM "http://attacker.example.com/evil.dtd">
            <testsuite name="evil" tests="1" failures="0" errors="0" skipped="0" time="0.0"/>
        """.trimIndent()
        val ex = assertThrows(JUnitReportParseException::class.java) {
            JUnitReportParser.parse(ByteArrayInputStream(xml.toByteArray()), "/tmp/xxe.xml")
        }
        // The exception message MUST surface the XXE rejection (parser
        // construction or the disallow-doctype-decl feature). It must
        // NOT be a vague "SAXException".
        assertTrue(
            ex.message!!.contains("DOCTYPE", ignoreCase = true) ||
                ex.message!!.contains("External entity", ignoreCase = true) ||
                ex.message!!.contains("dtd", ignoreCase = true),
            "Expected the XXE rejection to be surfaced, got: ${ex.message}",
        )
    }

    @Test
    fun `rejects XML that references an external entity`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <testsuite name="evil" tests="1" failures="0" errors="0" skipped="0" time="0.0">
                <testcase name="x">&xxe;</testcase>
            </testsuite>
        """.trimIndent()
        val ex = assertThrows(JUnitReportParseException::class.java) {
            JUnitReportParser.parse(ByteArrayInputStream(xml.toByteArray()), "/tmp/xxe2.xml")
        }
        assertTrue(ex.message!!.isNotBlank())
    }

    @Test
    fun `rejects empty XML without a testsuite(s) root`() {
        val ex = assertThrows(JUnitReportParseException::class.java) {
            JUnitReportParser.parse("<root/>".byteInputStream(), "/tmp/empty.xml")
        }
        assertTrue(
            ex.message!!.contains("testsuite"),
            "Expected diagnostic to mention testsuite, got: ${ex.message}",
        )
    }

    @Test
    fun `rejects malformed XML`() {
        val xml = "<testsuite name=\"x\" tests=\""
        val ex = assertThrows(JUnitReportParseException::class.java) {
            JUnitReportParser.parse(xml.byteInputStream(), "/tmp/broken.xml")
        }
        assertTrue(
            ex.message!!.contains("Malformed", ignoreCase = true) ||
                ex.message!!.contains("XML", ignoreCase = true),
            "Expected malformed-XML diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun `parses a Gradle Test XML with mixed content (whitespace tolerance)`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.GradleTest" tests="2" failures="0" errors="0" skipped="0" time="0.123">
              <testcase name="alpha" classname="GradleTest" time="0.05"/>
              <testcase name="beta" classname="GradleTest" time="0.073"/>
            </testsuite>
        """.trimIndent()
        val summary = JUnitReportParser.parse(xml.byteInputStream(), "/tmp/gradle.xml")
        assertEquals(2, summary.tests)
        assertEquals(0, summary.failures)
        assertEquals(0.123, summary.durationSeconds, 1e-9)
    }
}
