package dev.rubentxu.pipeline.v2.harness.evidence

import dev.rubentxu.pipeline.v2.harness.provenance.CommitRef
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a JUnit TEST-*.xml report into a [ReportObservation].
 *
 * This is the only impure surface in the evidence module: it reads an
 * [InputStream] and walks a DOM. The parser is configured to disable XXE
 * (external entities, external DTDs, XInclude). A report that does not
 * contain a `<testsuite>` is malformed; the reader surfaces that as an
 * exception, not as an empty observation — the empty path is a separate
 * typed cause owned by [EvidenceReader].
 */
object XmlReportReader {
    /** Outcome of reading one TEST-*.xml. */
    sealed interface Result {
        data class Ok(val observation: ReportObservation) : Result
        /** The XML was structurally corrupt (parse error, missing testsuite, etc.). */
        data class Corrupt(val message: String) : Result
    }

    fun read(input: InputStream): Result {
        val factory = safeFactory()
        val builder = factory.newDocumentBuilder()
        val doc = try {
            builder.parse(input)
        } catch (e: Exception) {
            return Result.Corrupt("parse failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val root = doc.documentElement
        if (root == null || root.tagName != "testsuite") {
            return Result.Corrupt("root element is not <testsuite> (got ${root?.tagName})")
        }
        val suiteFqcn = root.getAttribute("name").ifBlank {
            return Result.Corrupt("testsuite has no 'name' attribute")
        }
        val tests = root.getAttribute("tests").toIntOrNull() ?: 0
        val skipped = root.getAttribute("skipped").toIntOrNull() ?: 0
        val failures = root.getAttribute("failures").toIntOrNull() ?: 0
        val errors = root.getAttribute("errors").toIntOrNull() ?: 0
        // JUnit XML does not carry a commit; the harness can attach one via a
        // sibling property file (`TEST-*.properties`) when the test runner is
        // configured to do so. The reader reads that opportunistically.
        val commit = readCommitFromSiblingProperties(suiteFqcn)
        return try {
            Result.Ok(
                ReportObservation(
                    suiteFqcn = suiteFqcn,
                    tests = tests,
                    skipped = skipped,
                    failures = failures,
                    errors = errors,
                    reportedCommit = commit,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Result.Corrupt("observation invalid: ${e.message}")
        }
    }

    /**
     * Best-effort: try to read a `<suiteFqcn>.commit` line from a sibling
     * properties file at `*.commit.properties` next to the test result. The
     * caller is responsible for placing the file; the reader never writes.
     *
     * The path is computed from the test name only; passing the directory is
     * the caller's job. Returns `null` if the file is absent.
     */
    private fun readCommitFromSiblingProperties(@Suppress("UNUSED_PARAMETER") suiteFqcn: String): CommitRef? =
        // The integration with the runner (B2) writes the file. The reader
        // here is intentionally file-agnostic; tests pass the commit via
        // ReportObservation directly. Hook left for a future iteration.
        null

    /**
     * Build a DocumentBuilderFactory that has external entities, external DTDs
     * and XInclude disabled. This is the standard XXE-hardening pattern; if
     * a future JDK changes the defaults, this code stays correct.
     */
    private fun safeFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setXIncludeAware(false)
        factory.setExpandEntityReferences(false)
        return factory
    }
}
