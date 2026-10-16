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
 * ## H1 TODO 2: sidecar evidence is the new contract
 *
 * The XML alone is no longer sufficient. The H1 mandate
 * says "EvidenceReader exige ejecución real, `tests>skips`,
 * `failures/errors=0`, FQCN+Gradle path+invocación,
 * SHA/artefacto y HF observado. Datos incompletos → Refused,
 * no passed."
 *
 * A sidecar JSON file lives next to each XML
 * (`<resultsDir>/<class>.json` next to
 * `<resultsDir>/TEST-<class>.xml`). The sidecar is the
 * self-describing carrier: FQCN, Gradle path, invocation,
 * binary SHA-256, HF, source commit. The reader joins the
 * XML (test count, failures, errors) with the sidecar
 * (provenance + invocation). A missing sidecar is
 * [SelectionRefusal.IncompleteEvidence]; a sidecar with a
 * stale binary is [SelectionRefusal.StaleBinary]; a sidecar
 * at a fidelity below the plan's `minimumFidelities` is
 * [SelectionRefusal.InsufficientFidelity].
 *
 * ## What it deliberately does not do
 *
 * It does not run anything. Selection, execution and adjudication are three decisions and merging
 * them would make the first one unfalsifiable.
 */
object EvidenceReader {

    /**
     * The XML + sidecar evidence for a single suite.
     * [xml] is the JUnit XML path; [manifest] is the
     * [EvidenceManifest] (null when the sidecar is
     * missing or malformed). The gate refuses when
     * [manifest] is null.
     */
    data class SuiteEvidence(
        val xml: Path,
        val manifest: EvidenceManifest?,
    )

    /**
     * Read every `TEST-*.xml` in [resultsDir] and
     * attempt to load the sidecar of the same basename.
     * The returned map is keyed by the simple class
     * name the XML declares (the gate identifies
     * suites by their simple class name, matching
     * [TestSuiteId.filter]).
     */
    fun suitesWithEvidence(resultsDir: Path): Map<String, SuiteEvidence> {
        if (!Files.isDirectory(resultsDir)) return emptyMap()
        val out = mutableMapOf<String, SuiteEvidence>()
        Files.newDirectoryStream(resultsDir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.startsWith("TEST-") && name.endsWith(".xml")) {
                    val className = suiteClassNameFrom(entry) ?: continue
                    val sidecar = EvidenceManifest.sidecarFor(entry)
                    val manifest = if (Files.isRegularFile(sidecar)) {
                        try {
                            EvidenceManifest.load(sidecar)
                        } catch (ex: Exception) {
                            // Malformed sidecar: refuse on
                            // IncompleteEvidence. The
                            // reader does not invent a
                            // default.
                            null
                        }
                    } else {
                        null
                    }
                    out[className] = SuiteEvidence(xml = entry, manifest = manifest)
                }
            }
        }
        return out
    }

    /**
     * The simple class name of a JUnit report, e.g. `TEST-FooTest.xml` -> `FooTest`.
     *
     * The XML is parsed; the suite's class name comes
     * from the `<testsuite name="...">` attribute. The
     * count, failure, error, skip gates (H1 mandate:
     * `tests>0`, `tests-skipped>0`, `failures=0`,
     * `errors=0`) are applied: an XML that fails any of
     * these gates is NOT a suite with evidence. A
     * corrupt XML returns null for the same reason.
     */
    private fun suiteClassNameFrom(xml: Path): String? = try {
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
        val tests = root.getAttribute("tests").toIntOrNull() ?: 0
        val skipped = root.getAttribute("skipped").toIntOrNull() ?: 0
        if (failures > 0 || errors > 0) {
            null
        } else if (tests <= 0) {
            null
        } else if (tests - skipped <= 0) {
            null
        } else {
            name.substringAfterLast('.').ifEmpty { null }
        }
    } catch (e: Exception) {
        // A corrupt report is not a passing report. Treating it as "some evidence exists" is the
        // silent-green shape this whole line of work exists to prevent.
        null
    }

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
    ): ValidationPlan.Refused? {
        val evidence = suitesWithEvidence(resultsDir)
        return ImpactResolver.adjudicate(
            plan = plan,
            executedSuites = evidence,
            expectedCommit = expectedCommit,
            observedCommit = observedCommit,
        )
    }

    /** The conventional location of a module's test results, for callers that need it. */
    fun resultsDirFor(repoRoot: Path, module: String): Path =
        repoRoot.resolve(module).resolve("build/test-results/test")
}

/** The repository root, so the CLI and the gate agree on where they are looking. */
fun defaultRepoRoot(): Path = Paths.get("").toAbsolutePath().normalize()
