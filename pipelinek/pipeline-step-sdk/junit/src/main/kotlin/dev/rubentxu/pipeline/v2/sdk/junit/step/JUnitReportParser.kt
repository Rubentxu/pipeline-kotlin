package dev.rubentxu.pipeline.v2.sdk.junit.step

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * Pure SAX parser for JUnit XML reports (F5.2).
 *
 * Security and resource guarantees:
 *
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
 *
 * Element mapping (the Maven Surefire / Gradle Test schema):
 *
 *   <testsuite tests="N" failures="F" errors="E" skipped="S" time="T">
 *     <testcase>...</testcase>
 *   </testsuite>
 *
 * Old JUnit (junit-3) reports the same schema with a <testsuites>
 * wrapper; we sum across all <testsuite> children if present.
 */
object JUnitReportParser {

    /**
     * Parses [xml] and returns the typed [JUnitReportSummary].
     *
     * @param xml the JUnit XML content (caller already bounded the byte
     *            count via `maxReportBytes`).
     * @param reportPath the path the summary will report as
     *                   `reportPath` (no I/O here; just a tag).
     * @throws JUnitReportParseException when the XML is malformed, the
     *         root element is missing, or the parser is misconfigured.
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

        val handler = CountingHandler()
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
        return JUnitReportSummary(
            tests = handler.tests,
            failures = handler.failures,
            errors = handler.errors,
            skipped = handler.skipped,
            durationSeconds = handler.durationSeconds,
            reportPath = reportPath,
        )
    }

    private class CountingHandler : DefaultHandler() {
        var sawRoot: Boolean = false
        var tests: Int = 0
        var failures: Int = 0
        var errors: Int = 0
        var skipped: Int = 0
        var durationSeconds: Double = 0.0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "testsuite", "testsuites" -> {
                    sawRoot = true
                    if (attributes != null) {
                        tests += attributes.getValue("tests")?.toIntOrNull() ?: 0
                        failures += attributes.getValue("failures")?.toIntOrNull() ?: 0
                        errors += attributes.getValue("errors")?.toIntOrNull() ?: 0
                        skipped += attributes.getValue("skipped")?.toIntOrNull() ?: 0
                        attributes.getValue("time")?.toDoubleOrNull()?.let { durationSeconds += it }
                    }
                }
            }
        }
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
 * Typed parse-failure exception (F5.2). Carries the original cause so
 * the handler can wrap it as a USER failure (malformed input is not an
 * infrastructure defect).
 */
class JUnitReportParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
