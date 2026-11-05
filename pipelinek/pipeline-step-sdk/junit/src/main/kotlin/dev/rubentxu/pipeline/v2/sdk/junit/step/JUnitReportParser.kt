package dev.rubentxu.pipeline.v2.sdk.junit.step

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * Pure SAX parser for JUnit XML reports
 * Security and resource guarantees:
 *  - **External entities disabled** (XXE). We construct the
 *    [SAXParserFactory] with `setFeature("http://xml.org/sax/features/external-general-entities", false)`
 *    and `external-parameter-entities`, plus a hard `disallow-doctype-decl`
 *    so a malicious XML cannot smuggle a DTD that resolves remote
 *    schemas or reads local files. The factory is NOT the JVM default
 *    (which is namespace-aware but allows external entities).
 *  - **Streaming, not DOM.** We accumulate counters only; we never
 *    build a per-element tree, so an arbitrarily large XML cannot OOM
 *    the parser. The actual byte cap lives in [parse]; the parser
 *    itself runs against an [InputStream] that the caller has already
 *    bounded.
 *  - **No reflection / no ScriptEngine.** A malicious XML cannot load
 *    a class, evaluate an XPath, or open a network socket from inside
 *    the parser.
 * Element mapping (the Maven Surefire / Gradle Test schema):
 *   <testsuite tests="N" failures="F" errors="E" skipped="S" time="T">
 *     <testcase>...</testcase>
 *   </testsuite>
 *
 * What makes a document a report:
 *  - **The root.** The first element of the document has to be
 *    `<testsuite>` or `<testsuites>`. A document whose root is something
 *    else is not a JUnit report even when it carries a suite inside, and
 *    the parser refuses it naming the root it found. A `<testsuites>`
 *    element is the root and nothing else: it carries the totals of the
 *    whole run, so it cannot sit inside a suite.
 *  - **The wrapper.** A report may wrap its suites in a `<testsuites>`
 *    root. That wrapper REPEATS the totals of the suites it wraps, so its
 *    attributes are never added to theirs: the suites are the finest
 *    declaration of the run and they are counted once. The wrapper's own
 *    totals are used only when the document declares no suite at all, so a
 *    report that carries its totals nowhere else does not become a report
 *    with no tests.
 *  - **A nested suite.** A suite inside another suite is a PART of its
 *    parent, not an alternative declaration of the same run. Per axis the
 *    parent's own declaration wins, and where the parent declares nothing
 *    the nested suites fill it in. The nested suite's declaration is never
 *    added to its parent's, so the run is never counted twice.
 *  - **A counter.** An absent counter declares nothing: a zero is a claim
 *    about the run, and an absent attribute makes no claim. A counter that
 *    IS present and is not a valid non-negative finite number is refused,
 *    naming the attribute and the value, because reading it as a zero
 *    would publish a total the report never declared.
*/
object JUnitReportParser {

    /**
     * Parses [xml] and returns the typed [JUnitReportSummary].
     * @param xml the JUnit XML content (caller already bounded the byte
     *            count via `maxReportBytes`).
     * @param reportPath the path the summary will report as
     *                   `reportPath` (no I/O here; just a tag).
     * @throws JUnitReportParseException when the XML is malformed, its root
     *         is not a JUnit root, one of its counters is not a valid
     *         non-negative finite number, or the parser is misconfigured.
*/
    fun parse(xml: InputStream, reportPath: String): JUnitReportSummary {
        val factory = SAXParserFactory.newInstance().apply {
            // XXE hardening: disable DOCTYPE, external entities, and
            // external parameter entities. These features are part of
            // SAX2 and supported on every JAXP implementation on the
            // JVM we target (Java 21).
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // Namespace-unaware: JUnit XML is the flat Surefire shape.
            isNamespaceAware = false
            isXIncludeAware = false
            // XMLConstants.FEATURE_SECURE_PROCESSING where supported.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }

        val handler = CountingHandler(reportPath)
        val parser = try {
            factory.newSAXParser()
        } catch (e: Exception) {
            throw JUnitReportParseException(
                "Failed to construct a hardened SAX parser: ${e.message ?: "unknown"}",
                e,
            )
        }
        // Belt-and-braces: even with all features disabled above, the
        // SAX driver still asks the EntityResolver for any DTD / external
        // entity it encounters. We reject all such requests by throwing.
        parser.xmlReader.entityResolver = REJECTING_ENTITY_RESOLVER
        try {
            parser.parse(xml, handler)
        } catch (e: JUnitReportParseException) {
            throw e
        } catch (e: Exception) {
            throw JUnitReportParseException(
                "Malformed JUnit XML at $reportPath: ${e.message ?: "unknown"}",
                e,
            )
        }

        if (!handler.sawRoot) {
            throw JUnitReportParseException(
                "Empty or XML without a <testsuite(s)> root: $reportPath",
            )
        }
        val totals = handler.totals
        return JUnitReportSummary(
            tests = totals.tests,
            failures = totals.failures,
            errors = totals.errors,
            skipped = totals.skipped,
            durationSeconds = totals.durationSeconds,
            reportPath = reportPath,
        )
    }

    /**
     * EntityResolver that REJECTS any external entity reference. Set
     * on the SAX reader directly so a malicious DTD or external
     * entity cannot smuggle a remote schema or local file read.
*/
    private val REJECTING_ENTITY_RESOLVER =
        org.xml.sax.EntityResolver { publicId, systemId ->
            throw JUnitReportParseException(
                "External entity resolution is disabled (refused publicId=$publicId systemId=$systemId). " +
                    "If you need a schema, declare it inline.",
            )
        }
}

/**
 * The counters one report declares, once every declaration has been read.
 *
 * A report that wraps its suites declares the same run twice: once in
 * the wrapper and once in each suite it wraps, and the wrapper's
 * numbers are the sum of the suites'. Keeping the two apart is what
 * lets the parser use the finest declaration without counting the same
 * tests twice.
 */
private data class Totals(
    val tests: Int = 0,
    val failures: Int = 0,
    val errors: Int = 0,
    val skipped: Int = 0,
    val durationSeconds: Double = 0.0,
) {
    operator fun plus(other: Totals): Totals = Totals(
        tests = tests + other.tests,
        failures = failures + other.failures,
        errors = errors + other.errors,
        skipped = skipped + other.skipped,
        durationSeconds = durationSeconds + other.durationSeconds,
    )
}

/**
 * What ONE element of the schema declares, per axis.
 *
 * A missing attribute declares NOTHING about that axis. A zero is a claim
 * about the run, and an absent attribute makes no claim at all, so the two
 * are not the same thing and are not written the same way here: a declared
 * axis carries a number, an undeclared axis carries nothing.
 */
private data class Declared(
    val tests: Int? = null,
    val failures: Int? = null,
    val errors: Int? = null,
    val skipped: Int? = null,
    val durationSeconds: Double? = null,
) {
    /**
     * The declared axes as totals, where the axes that declare nothing
     * count as nothing.
     */
    fun toTotals(): Totals = Totals(
        tests = tests ?: 0,
        failures = failures ?: 0,
        errors = errors ?: 0,
        skipped = skipped ?: 0,
        durationSeconds = durationSeconds ?: 0.0,
    )
}

/**
 * A suite and the suites nested in it.
 *
 * The nesting is what keeps a run from being counted twice: a nested suite
 * is a part of its parent, and the parent already declares the total of its
 * own subtree.
 */
private class Suite(
    val name: String?,
    val declared: Declared,
) {
    val nested: MutableList<Suite> = mutableListOf()

    /**
     * What this suite contributes to the report's totals.
     *
     * Per axis: what THIS suite declares, and where it declares nothing, the
     * sum of what its nested suites contribute. The nested suites never add to
     * what their parent declares.
     */
    fun contribution(): Totals {
        val nestedTotals = nested.fold(Totals()) { acc, suite -> acc + suite.contribution() }
        return Totals(
            tests = declared.tests ?: nestedTotals.tests,
            failures = declared.failures ?: nestedTotals.failures,
            errors = declared.errors ?: nestedTotals.errors,
            skipped = declared.skipped ?: nestedTotals.skipped,
            durationSeconds = declared.durationSeconds ?: nestedTotals.durationSeconds,
        )
    }
}

/**
 * The SAX handler that reads a report and keeps what it declares.
 *
 * It carries the whole shape of the document: which element is the root,
 * which suites are open, which suites are nested in which, and what each of
 * them declares. Everything it refuses is refused WHILE the document is
 * being read, so a document that says something impossible stops at the
 * sentence that says it.
 */
private class CountingHandler(
    private val reportPath: String,
) : DefaultHandler() {

    /** Whether the document's root was a JUnit root. */
    var sawRoot: Boolean = false

    private var elements: Int = 0
    private var wrapper: Declared? = null
    private val topLevel: MutableList<Suite> = mutableListOf()
    private val open: MutableList<Suite> = mutableListOf()

    /**
     * The totals the report declares, counted once: the suites when the
     * document declares any, and the wrapper's own totals only when it
     * declares none.
     */
    val totals: Totals
        get() = if (topLevel.isNotEmpty()) {
            topLevel.fold(Totals()) { acc, suite -> acc + suite.contribution() }
        } else {
            wrapper?.toTotals() ?: Totals()
        }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        val first = elements == 0
        elements++
        if (first) {
            // The root is what makes a document a report. A document whose root
            // is something else is not a JUnit report, even when it carries a
            // suite somewhere inside.
            if (qName != "testsuite" && qName != "testsuites") {
                refuse(
                    "The root of a JUnit report must be <testsuite> or <testsuites>, " +
                        "but the document's root is <$qName>",
                )
            }
            sawRoot = true
        }
        when (qName) {
            // The wrapper's attributes repeat the totals of the suites it
            // wraps, so they are held apart and never added to them. It is the
            // root and nothing else: it carries the totals of the whole run, so
            // it cannot sit inside a suite.
            "testsuites" -> {
                if (!first) {
                    refuse(
                        "A <testsuites> element may only be the root of a JUnit report, " +
                            "and this one is nested inside <${open.lastOrNull()?.name ?: "the document"}>",
                    )
                }
                wrapper = readDeclared(attributes, "testsuites")
            }
            "testsuite" -> {
                val suite = Suite(attributes?.getValue("name"), readDeclared(attributes, "testsuite"))
                if (open.isEmpty()) topLevel.add(suite) else open.last().nested.add(suite)
                open.add(suite)
            }
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (qName == "testsuite" && open.isNotEmpty()) {
            open.removeAt(open.size - 1)
        }
    }

    private fun readDeclared(attributes: Attributes?, element: String): Declared = Declared(
        tests = readCount(attributes, "tests", element),
        failures = readCount(attributes, "failures", element),
        errors = readCount(attributes, "errors", element),
        skipped = readCount(attributes, "skipped", element),
        durationSeconds = readDuration(attributes, element),
    )

    private fun readCount(attributes: Attributes?, name: String, element: String): Int? {
        val raw = attributes?.getValue(name) ?: return null
        // An attribute with no value carries no information: it is the
        // absence of a count, not a count of zero.
        if (raw.isBlank()) return null
        val whole = raw.toLongOrNull()
            ?: refuse("The attribute \"$name\" of <$element> declares \"$raw\", which is not a whole number")
        if (whole > Int.MAX_VALUE || whole < Int.MIN_VALUE) {
            refuse("The attribute \"$name\" of <$element> declares \"$raw\", which is not a whole number")
        }
        val value = whole.toInt()
        if (value < 0) {
            refuse("The attribute \"$name\" of <$element> declares \"$raw\", which is negative")
        }
        return value
    }

    private fun readDuration(attributes: Attributes?, element: String): Double? {
        val raw = attributes?.getValue("time") ?: return null
        if (raw.isBlank()) return null
        val value = raw.toDoubleOrNull()
            ?: refuse("The attribute \"time\" of <$element> declares \"$raw\", which is not a number")
        if (!value.isFinite()) {
            refuse("The attribute \"time\" of <$element> declares \"$raw\", which is not a finite number")
        }
        if (value < 0) {
            refuse("The attribute \"time\" of <$element> declares \"$raw\", which is negative")
        }
        return value
    }

    private fun refuse(reason: String): Nothing =
        throw JUnitReportParseException("$reason: $reportPath")
}

/**
 * Typed parse-failure exception. Carries the original cause so
 * the handler can wrap it as a USER failure (malformed input is not an
 * infrastructure defect).
*/
class JUnitReportParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
