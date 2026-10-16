package dev.rubentxu.pipeline.v2.harness.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Tests for [XmlReportReader] — the impure side of the evidence module.
 * The reader must disable XXE and must surface malformed XML as `Corrupt`,
 * not as a fake-green observation.
 */
class XmlReportReaderTest {

    @Test
    fun `parses a valid JUnit testsuite`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="owner.ContractTest" tests="10" skipped="1" failures="0" errors="0">
              <testcase name="t1" classname="owner.ContractTest"/>
              <testcase name="t2" classname="owner.ContractTest">
                <skipped/>
              </testcase>
            </testsuite>
        """.trimIndent()
        val result = XmlReportReader.read(ByteArrayInputStream(xml.toByteArray()))
        assertTrue(result is XmlReportReader.Result.Ok) { "expected Ok, got $result" }
        val obs = (result as XmlReportReader.Result.Ok).observation
        assertEquals("owner.ContractTest", obs.suiteFqcn)
        assertEquals(10, obs.tests)
        assertEquals(1, obs.skipped)
        assertEquals(0, obs.failures)
        assertEquals(0, obs.errors)
    }

    @Test
    fun `malformed XML is Corrupt, not empty`() {
        val xml = "<not-a-testsuite></not-a-testsuite>"
        val result = XmlReportReader.read(ByteArrayInputStream(xml.toByteArray()))
        assertTrue(result is XmlReportReader.Result.Corrupt) { "expected Corrupt, got $result" }
    }

    @Test
    fun `DOCTYPE is refused by the secure parser`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE testsuite [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <testsuite name="owner.ContractTest" tests="1" skipped="0" failures="0" errors="0">
              <testcase name="t" classname="owner.ContractTest"/>
            </testsuite>
        """.trimIndent()
        val result = XmlReportReader.read(ByteArrayInputStream(xml.toByteArray()))
        // Either the parser refuses the DOCTYPE outright (Corrupt) or it
        // parses without expanding the entity. Either way, no file access
        // must have happened. The test asserts Corrupt for the secure path.
        assertTrue(result is XmlReportReader.Result.Corrupt) { "got $result — XXE hardening broken" }
    }

    @Test
    fun `testcase nested counts aggregate to testsuite numbers`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="owner.ContractTest" tests="3" skipped="0" failures="1" errors="0">
              <testcase name="t1" classname="owner.ContractTest"/>
              <testcase name="t2" classname="owner.ContractTest"/>
              <testcase name="t3" classname="owner.ContractTest">
                <failure type="AssertionError">x != y</failure>
              </testcase>
            </testsuite>
        """.trimIndent()
        val result = XmlReportReader.read(ByteArrayInputStream(xml.toByteArray()))
        assertTrue(result is XmlReportReader.Result.Ok) { "got $result" }
        val obs = (result as XmlReportReader.Result.Ok).observation
        assertEquals(3, obs.tests)
        assertEquals(0, obs.skipped)
        assertEquals(1, obs.failures)
        assertEquals(0, obs.errors)
    }
}
