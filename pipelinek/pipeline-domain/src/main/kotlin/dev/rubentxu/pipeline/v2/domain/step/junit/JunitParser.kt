package dev.rubentxu.pipeline.v2.domain.step.junit

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * In-house parser for the canonical Ant/Maven JUnit XML report.
 *
 * Scope (frozen at E1.1 design):
 *   - Accepts `<testsuites>` (root with N `<testsuite>` children) or
 *     `<testsuite>` (single suite) as the root element.
 *   - Aggregates the totals from each `<testsuite>` element's
 *     attributes (the upstream tool's counts are authoritative; the
 *     parser does NOT recompute them by walking the cases).
 *   - Collects failing cases: `<testcase>` children of any suite that
 *     have a `<failure>` or `<error>` child element.
 *   - Fails closed on:
 *       * no file (caller's job, surfaced by the handler)
 *       * malformed XML (DocumentBuilder throws → wrapped)
 *       * root element not in {testsuites, testsuite}
 *       * non-canonical schema extensions (any element not in the
 *         known set, surfaced as UnsupportedSchema)
 *
 * Pure function: given bytes + path, returns either a [JunitReport]
 * or throws a [JunitParseException] (the handler catches and maps to
 * the typed result algebra). No I/O beyond reading the byte array;
 * the caller owns file access.
 *
 * The parser does NOT depend on any non-stdlib library.
 */
object JunitParser {

    private val dbFactory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            // Disable external entities (XXE). Standard hardening.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }

    /**
     * Parse [bytes] as a JUnit XML report.
     *
     * @param bytes UTF-8 bytes of the XML file (caller reads the file)
     * @param reportPath absolute path of the file (recorded in the result)
     * @return a fully-populated [JunitReport]
     * @throws JunitParseException on any parser-level failure
     */
    fun parse(bytes: ByteArray, reportPath: String): JunitReport {
        val doc = try {
            dbFactory.newDocumentBuilder().parse(bytes.inputStream())
        } catch (e: Exception) {
            throw JunitParseException(
                JunitParseFailure.Malformed(e.message ?: "XML parse failed")
            )
        }

        val root = doc.documentElement
        val rootName = root.tagName

        val suites: List<Element> = when (rootName) {
            "testsuites" -> childElements(root, "testsuite")
            "testsuite" -> listOf(root)
            else -> throw JunitParseException(
                JunitParseFailure.UnsupportedSchema(
                    "Root element is <$rootName>; expected <testsuites> or <testsuite>"
                )
            )
        }

        if (suites.isEmpty()) {
            throw JunitParseException(
                JunitParseFailure.Malformed("<testsuites> root contains no <testsuite> children")
            )
        }

        val suiteSummaries = suites.map { parseSuite(it) }
        val totals = aggregateTotals(suiteSummaries)
        val failingCases = suites.flatMap { parseFailingCases(it) }

        // Validate that no element under the root is non-canonical.
        // Only applies when the root is <testsuites> — when the root
        // is itself a <testsuite>, the root's children are
        // <testcase>/<properties>/etc and are validated below.
        if (rootName == "testsuites") {
            val nonCanonicalRootChildren = childElements(root).filter { el ->
                el.tagName !in KNOWN_ROOT_CHILDREN
            }
            if (nonCanonicalRootChildren.isNotEmpty()) {
                throw JunitParseException(
                    JunitParseFailure.UnsupportedSchema(
                        "Root contains non-canonical children: " +
                            nonCanonicalRootChildren.joinToString { "<${it.tagName}>" }
                    )
                )
            }
        }

        // Per-suite validation: <testsuite> children must be in the
        // known set. Unknown elements are rejected (fail-closed).
        suites.forEach { suite ->
            val unknown = childElements(suite).filter { el ->
                el.tagName !in KNOWN_SUITE_CHILDREN
            }
            if (unknown.isNotEmpty()) {
                throw JunitParseException(
                    JunitParseFailure.UnsupportedSchema(
                        "Suite <${suite.getAttribute("name")}> contains " +
                            "non-canonical children: " +
                            unknown.joinToString { "<${it.tagName}>" }
                    )
                )
            }
        }

        val outcome: JunitReadOutcome = if (totals.failures == 0 && totals.errors == 0) {
            JunitReadOutcome.Passed(totals)
        } else {
            JunitReadOutcome.Failed(totals, failingCases)
        }

        return JunitReport(
            reportPath = reportPath,
            suites = suiteSummaries,
            totals = totals,
            outcome = outcome,
        )
    }

    private fun parseSuite(suite: Element): JunitSuiteSummary {
        return JunitSuiteSummary(
            name = suite.getAttribute("name").ifEmpty {
                throw JunitParseException(
                    JunitParseFailure.Malformed(
                        "<testsuite> is missing required 'name' attribute"
                    )
                )
            },
            tests = parseIntAttr(suite, "tests"),
            failures = parseIntAttr(suite, "failures"),
            errors = parseIntAttr(suite, "errors"),
            skipped = parseIntAttr(suite, "skipped"),
            timeSeconds = parseDoubleAttr(suite, "time"),
        )
    }

    private fun parseFailingCases(suite: Element): List<FailingCase> {
        val suiteName = suite.getAttribute("name")
        val out = mutableListOf<FailingCase>()
        childElements(suite, "testcase").forEach { tc ->
            val caseName = tc.getAttribute("name")
            val classname = tc.getAttribute("classname")
            // Order: <error> wins over <failure> if both present (rare).
            val errorChild = childElements(tc, "error").firstOrNull()
            val failureChild = childElements(tc, "failure").firstOrNull()
            val child = errorChild ?: failureChild
            if (child != null) {
                out += FailingCase(
                    suiteName = suiteName,
                    caseName = caseName,
                    classname = classname,
                    message = child.getAttribute("message"),
                    type = child.getAttribute("type"),
                    isError = errorChild != null,
                )
            }
        }
        return out
    }

    private fun aggregateTotals(suites: List<JunitSuiteSummary>): JunitTotals =
        JunitTotals(
            tests = suites.sumOf { it.tests },
            failures = suites.sumOf { it.failures },
            errors = suites.sumOf { it.errors },
            skipped = suites.sumOf { it.skipped },
            timeSeconds = suites.sumOf { it.timeSeconds },
        )

    private fun parseIntAttr(el: Element, attr: String): Int {
        val raw = el.getAttribute(attr)
        if (raw.isEmpty()) return 0
        return raw.toIntOrNull() ?: throw JunitParseException(
            JunitParseFailure.Malformed(
                "Attribute '$attr' on <${el.tagName}> is not a valid integer: '$raw'"
            )
        )
    }

    private fun parseDoubleAttr(el: Element, attr: String): Double {
        val raw = el.getAttribute(attr)
        if (raw.isEmpty()) return 0.0
        return raw.toDoubleOrNull() ?: throw JunitParseException(
            JunitParseFailure.Malformed(
                "Attribute '$attr' on <${el.tagName}> is not a valid double: '$raw'"
            )
        )
    }

    private fun childElements(parent: Element, name: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node: Node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (name == null || el.tagName == name) {
                    out += el
                }
            }
        }
        return out
    }

    /** Canonical children of the `<testsuites>` root element. */
    private val KNOWN_ROOT_CHILDREN = setOf("testsuite")

    /**
     * Canonical children of a `<testsuite>` element.
     *
     * `<properties>` is accepted as a no-op container (Ant/Maven
     * often emits it). `<system-out>` / `<system-err>` are accepted
     * as no-op text containers (their content is NOT parsed). Any
     * other element is rejected as unsupported schema.
     */
    private val KNOWN_SUITE_CHILDREN = setOf(
        "testcase",
        "properties",
        "system-out",
        "system-err",
    )
}

/**
 * Exception type raised by [JunitParser] when the input cannot be
 * parsed as a canonical JUnit XML report. Carries a typed
 * [JunitParseFailure] so the handler can surface a precise failure
 * kind without losing the schema variant.
 */
class JunitParseException(val failure: JunitParseFailure) :
    RuntimeException(failure.toString())
