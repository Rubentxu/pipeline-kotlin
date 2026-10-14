package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Closes the loop: compares what the plan SELECTED against what the run actually EXECUTED.
 *
 * ## Why this exists
 *
 * The planner prints a list of suites. Printing is not enforcing. A list on stdout that nobody
 * checks is documentation with extra steps, and the mandate's decisive test is that the change
 * cannot be closed when the required suite is missing, failed, or ran against a stale binary.
 *
 * So this reads the JUnit XML of the run and refuses. It exists as a separate step from
 * [ImpactResolver.adjudicate] because it observes a DIFFERENT thing: adjudicate takes the
 * executed-suite set as an argument (and is therefore only as honest as its caller), while this
 * derives that set from the filesystem, which no caller can conveniently lie about.
 *
 * ## Why the XML is the oracle and not the console
 *
 * The console is lossy under truncation and says nothing about which classes ran. An absent XML is
 * NOT a suite that passed quietly, it is a suite with no evidence, and this treats it as exactly
 * that. That distinction is the whole point: the failure mode being closed is an absence being
 * read as a green.
 *
 * ## What it deliberately does not do
 *
 * It does not run anything. Selection, execution and adjudication are three decisions and merging
 * them would make the first one unfalsifiable.
 */
object EvidenceReader {

    /** The suites whose evidence exists, read from the JUnit XML of [resultsDir]. */
    fun suitesWithEvidence(resultsDir: Path): Set<String> {
        if (!Files.isDirectory(resultsDir)) return emptySet()
        val classes = mutableSetOf<String>()
        // newDirectoryStream rather than Files.list(...).use: the Kotlin Stream extensions need an
        // explicit type parameter here, and DirectoryStream is a plain Iterable<Path> that reads
        // without the ceremony.
        Files.newDirectoryStream(resultsDir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.startsWith("TEST-") && name.endsWith(".xml")) {
                    classNameFrom(entry)?.let { classes.add(it) }
                }
            }
        }
        return classes
    }

    /** The simple class name of a JUnit report, e.g. `TEST-FooTest.xml` -> `FooTest`. */
    private fun classNameFrom(xml: Path): String? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The XML comes from our own build, but a parser that will fetch a DTD over the
            // network turns a malformed report into an outbound request. Disable it explicitly.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.toFile())
        val root = doc.documentElement
        val name = root.getAttribute("name")
        val failures = root.getAttribute("failures").toIntOrNull() ?: 0
        val errors = root.getAttribute("errors").toIntOrNull() ?: 0
        if (failures > 0 || errors > 0) {
            null
        } else {
            name.substringAfterLast('.').ifEmpty { null }
        }
    } catch (e: Exception) {
        // A corrupt report is not a passing report. Treating it as "some evidence exists" is the
        // silent-green shape this whole line of work exists to prevent.
        null
    }

    /** The commit the binary reports is NOT read here; see [verify]'s observedCommit argument. */

    /**
     * The gate proper.
     *
     * @param plan what the planner selected.
     * @param resultsDir where this run's JUnit XML landed.
     * @param expectedCommit the SHA the evidence must be bound to.
     * @param observedCommit what the binary that produced it reports, or null if unknown.
     * @return null when the evidence discharges the plan, otherwise the refusal.
     */
    fun verify(
        plan: ValidationPlan,
        resultsDir: Path,
        expectedCommit: String,
        observedCommit: String?,
    ): ValidationPlan.Refused? = ImpactResolver.adjudicate(
        plan = plan,
        executedSuites = suitesWithEvidence(resultsDir),
        expectedCommit = expectedCommit,
        observedCommit = observedCommit,
    )

    /** The conventional location of a module's test results, for callers that need it. */
    fun resultsDirFor(repoRoot: Path, module: String): Path =
        repoRoot.resolve(module).resolve("build/test-results/test")
}

/** The repository root, so the CLI and the gate agree on where they are looking. */
fun defaultRepoRoot(): Path = Paths.get("").toAbsolutePath().normalize()
