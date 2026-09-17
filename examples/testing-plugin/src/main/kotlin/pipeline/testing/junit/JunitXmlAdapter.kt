package pipeline.testing.junit

import org.w3c.dom.Element
import org.w3c.dom.Node
import pipeline.testing.results.ParseFailureReason
import pipeline.testing.results.TestCaseResult
import pipeline.testing.results.TestFailure
import pipeline.testing.results.TestReport
import pipeline.testing.results.TestReportAdapter
import pipeline.testing.results.TestStatus
import pipeline.testing.results.TestSuiteResult
import javax.xml.parsers.DocumentBuilderFactory

/**
 * E3-T1 — JUnit XML adapter (GREEN phase).
 *
 * Pure JDK implementation. No external XML dependency. Uses
 * [DocumentBuilderFactory] with **fail-closed XML parsing**: external
 * general entities and DOCTYPE declarations are disabled because a
 * well-formedness test suite must NOT quietly expand an attacker-supplied
 * entity.
 *
 * The contract is the T0 contract: same input bytes -> same typed
 * [TestReport]; failures are reported as [TestReport.Unparseable] with a
 * typed [ParseFailureReason]; never as a thrown exception that the
 * caller has to discriminate by message.
 *
 * Identity rule:
 *   fqtn(suite, case) == suite + "::" + case.name
 * Within a single parse, fqtns MUST be unique. Duplicates are routed
 * to [ParseFailureReason.AmbiguousIdentity] (NOT silently merged).
 */
class JunitXmlAdapter : TestReportAdapter {

    override fun parse(bytes: ByteArray, source: String): TestReport {
        // Phase 1: well-formedness + root element check (XML schema level)
        val document = parseXmlSafely(bytes, source) ?: return failure(
            source = source,
            reason = ParseFailureReason.XmlMalformed(
                message = "document could not be parsed as XML",
            ),
        )

        // Phase 2: structural validation
        val root = document.documentElement
            ?: return failure(source, ParseFailureReason.SchemaMismatch("missing root element"))
        when (root.nodeName) {
            "testsuite" -> return parseSingleSuite(root, source)
            "testsuites" -> return parseSuiteCollection(root, source)
            else -> return failure(
                source,
                ParseFailureReason.SchemaMismatch(
                    "expected <testsuite> or <testsuites>, got <${root.nodeName}>",
                ),
            )
        }
    }

    // ── XML well-formedness ─────────────────────────────────────────────────
    private fun parseXmlSafely(bytes: ByteArray, source: String): org.w3c.dom.Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Fail-closed defaults — never expand external entities.
                isXIncludeAware = false
                isExpandEntityReferences = false
                // FEATURE_SECURE_PROCESSING + the two "external" features cover
                // the canonical XXE set for JDK 11+ DocumentBuilder.
                setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isNamespaceAware = false
            }
            // Provide the bytes via an explicit UTF-8 Reader rather than
            // relying on JDK's auto-detection. The parser then honours
            // the in-document `<?xml encoding="..."?>` if it differs.
            val inputSource = org.xml.sax.InputSource(
                java.io.InputStreamReader(
                    bytes.inputStream(),
                    java.nio.charset.StandardCharsets.UTF_8,
                ),
            )
            factory.newDocumentBuilder().parse(inputSource)
        } catch (e: org.xml.sax.SAXParseException) {
            null
        } catch (e: javax.xml.parsers.ParserConfigurationException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    // ── <testsuite> at root ─────────────────────────────────────────────────
    private fun parseSingleSuite(root: Element, source: String): TestReport {
        val suites = listOf(parseSuite(root, source))
        return aggregate(suites, source)
    }

    // ── <testsuites> at root ────────────────────────────────────────────────
    private fun parseSuiteCollection(root: Element, source: String): TestReport {
        val suiteElements = childElements(root, "testsuite")
        val suites = suiteElements.map { parseSuite(it, source) }
        return aggregate(suites, source)
    }

    /**
     * Aggregate the parsed suites, checking that no fqtn appears twice.
     * Duplicates are routed to [ParseFailureReason.AmbiguousIdentity].
     */
    private fun aggregate(suites: List<TestSuiteResult>, source: String): TestReport {
        val seenFqtn = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        for (suite in suites) {
            for (case in suite.cases) {
                val fqtn = "${suite.name}::${case.name}"
                if (!seenFqtn.add(fqtn)) duplicates.add(fqtn)
            }
        }
        if (duplicates.isNotEmpty()) {
            return failure(
                source,
                ParseFailureReason.AmbiguousIdentity(duplicates.sorted()),
            )
        }
        return TestReport.Successful(suites = suites)
    }

    // ── <testsuite> element → TestSuiteResult ──────────────────────────────
    private fun parseSuite(element: Element, source: String): TestSuiteResult {
        val name = element.getAttribute("name")
        val hostname = element.getAttribute("hostname").takeIf { it.isNotBlank() }
        val timestamp = element.getAttribute("timestamp").takeIf { it.isNotBlank() }
        val duration = element.getAttribute("time").toDoubleOrNullSafely()

        val cases = childElements(element, "testcase").map { parseCase(it, name) }
        return TestSuiteResult(
            name = name,
            hostname = hostname,
            timestamp = timestamp,
            durationSeconds = duration,
            cases = cases,
        )
    }

    // ── <testcase> element → TestCaseResult ────────────────────────────────
    private fun parseCase(element: Element, suiteName: String): TestCaseResult {
        val name = element.getAttribute("name")
        val classname = element.getAttribute("classname").takeIf { it.isNotBlank() }
        val duration = element.getAttribute("time").toDoubleOrNullSafely()

        val status = parseCaseStatus(element)

        return TestCaseResult(
            suiteName = suiteName,
            name = name,
            classname = classname,
            durationSeconds = duration,
            status = status,
        )
    }

    /**
     * Determine the case status from the children of a `<testcase>`.
     *
     * JUnit-XML convention:
     *   - `<skipped/>` (or `<skipped message="..."/>`) → Skipped
     *   - `<error .../>` → Errored (uncaught exception)
     *   - `<failure .../>` → Failed (assertion)
     *   - none of the above → Passed
     *
     * Multiple status markers in the same case is malformed in JUnit-XML;
     * if we see two we keep the FIRST in the order: failure → error → skipped.
     * (Recording multiple statuses would mean designing a multi-status ADT
     * nobody has asked for.)
     */
    private fun parseCaseStatus(element: Element): TestStatus {
        val children = childElements(element)
        val failureEl = children.firstOrNull { it.nodeName == "failure" }
        if (failureEl != null) {
            return TestStatus.Failed(parseFailure(failureEl))
        }
        val errorEl = children.firstOrNull { it.nodeName == "error" }
        if (errorEl != null) {
            return TestStatus.Errored(parseFailure(errorEl))
        }
        val skippedEl = children.firstOrNull { it.nodeName == "skipped" }
        if (skippedEl != null) {
            return TestStatus.Skipped
        }
        return TestStatus.Passed
    }

    private fun parseFailure(element: Element): TestFailure {
        val message = element.getAttribute("message")
        val type = element.getAttribute("type").takeIf { it.isNotBlank() }
        // CDATA / text content holds the stack trace.  Some reports use
        // System-`\n`-only newlines; the parser MUST tolerate both.
        val text = element.textContent.orEmpty().trim()
        val stackTrace = if (text.isEmpty()) emptyList() else text.lines()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
        return TestFailure(
            message = message,
            failureType = type,
            stackTrace = stackTrace,
        )
    }

    // ── DOM helpers ─────────────────────────────────────────────────────────
    private fun childElements(parent: Element, name: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node: Node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (name == null || el.nodeName == name) out.add(el)
        }
        return out
    }

    private fun String.toDoubleOrNullSafely(): Double? = try {
        toDouble()
    } catch (e: NumberFormatException) {
        null
    }

    private fun failure(source: String, reason: ParseFailureReason): TestReport =
        TestReport.Unparseable(source = source, reason = reason)

    companion object {
        // E3-T1 places no sentinel here: all malformed-XML results flow
        // through `failure(source, XmlMalformed(...))` so the caller sees
        // the real source path.
    }
}
