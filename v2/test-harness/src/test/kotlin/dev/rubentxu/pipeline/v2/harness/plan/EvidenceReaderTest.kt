package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The validator that compares a plan against a real run's JUnit XML.
 *
 * ## Harness class
 *
 * `pure` with respect to the product: it reads a directory this test creates under [TempDir] and
 * nothing else. No repository, no network, no clock, no shared singleton — every rule below is
 * hermetic, which matters because the whole point of this type is to be trustworthy about
 * ABSENCE, and a harness that leaks state is exactly how an absence gets misread.
 */
class EvidenceReaderTest {

    private val redactor = "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/" +
        "credentials/api/StreamingRedactor.kt"

    private fun report(
        dir: Path,
        className: String,
        failures: Int = 0,
        errors: Int = 0,
        tests: Int = 1,
        skipped: Int = 0,
        corrupt: Boolean = false,
        sidecar: Boolean = false,
        sourceCommit: String = "0".repeat(40),
        hf: String = "HF0",
    ) {
        val body = if (corrupt) {
            "<testsuite name=\"$className\" tests=\"1\" failures=\"0\" errors=\"0\">"
        } else {
            "<testsuite name=\"$className\" tests=\"$tests\" skipped=\"$skipped\" " +
                "failures=\"$failures\" errors=\"$errors\"/>"
        }
        Files.writeString(dir.resolve("TEST-$className.xml"), body)
        // H1 TODO 2: the gate requires a sidecar per
        // suite. Tests that exercise the "clean" path
        // (matching commit, every suite ran) write
        // the sidecar; tests that exercise "evidence
        // is incomplete" pass sidecar=false.
        if (sidecar) {
            val sidecarJson = """
                {
                  "class_name": "$className",
                  "gradle_path": ":test-harness",
                  "invocation": "gradle :test-harness:test --tests $className",
                  "binary_sha256": "${"0".repeat(64)}",
                  "hf": "$hf",
                  "source_commit": "$sourceCommit"
                }
            """.trimIndent()
            Files.writeString(dir.resolve("$className.json"), sidecarJson)
        }
    }

    @Test
    fun `a clean report counts as evidence`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest")
        assertEquals(
            setOf("ShTranscriptRedactionReproductionTest") /* placeholder */,
            EvidenceReader.suitesWithEvidence(dir).keys,
        )
    }

    @Test
    fun `a report with a failure does NOT count as evidence`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest", failures = 1)
        assertTrue(
            EvidenceReader.suitesWithEvidence(dir).isEmpty(),
            "a failing suite is not evidence of anything; treating it as evidence is how a red run " +
                "gets counted as coverage",
        )
    }

    @Test
    fun `a report with an error does NOT count as evidence`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest", errors = 2)
        assertTrue(EvidenceReader.suitesWithEvidence(dir).isEmpty())
    }

    @Test
    fun `a corrupt report does NOT count as evidence`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest", corrupt = true)
        assertTrue(
            EvidenceReader.suitesWithEvidence(dir).isEmpty(),
            "unreadable XML is an absence of evidence, not evidence",
        )
    }

    @Test
    fun `an absent directory is an empty evidence set, never a pass`(@TempDir dir: Path) {
        val missing = dir.resolve("never-ran")
        assertTrue(EvidenceReader.suitesWithEvidence(missing).isEmpty())
    }

    @Test
    fun `BQ4 P0 HITO 1 weakness 4 - two same-named suites in different packages do NOT collide`(
        @TempDir dir: Path,
    ) {
        // The HITO 1 "Selección y evidencia vinculadas"
        // bullet in QUALITY_VALUE_ADDENDUM.md (2026-10-17)
        // named the FQCN identity as a required property:
        // "Identificar suites mediante módulo + FQCN +
        // invocación real". The previous shape used the
        // simple class name (`name.substringAfterLast('.')`)
        // as the evidence map key, so two modules with
        // `FooTest` would silently collapse into a single
        // row. The new shape uses the FULL `name` attribute
        // (the FQCN) as the key.
        //
        // The test stages two XML files with the SAME
        // simple class name (`FooTest`) in DIFFERENT
        // packages. The previous shape's map would have
        // one row (the second overwrites the first). The
        // new shape has two rows keyed by FQCN. The
        // property the test pins: every FQCN is its own
        // evidence row; the gate can identify each
        // independently.
        val pkgA = "com.example.modone"
        val pkgB = "com.example.modtwo"
        val simpleName = "FooTest"
        val fqcnA = "$pkgA.$simpleName"
        val fqcnB = "$pkgB.$simpleName"
        // Two XMLs with the SAME simple name but
        // DIFFERENT FQCNs. Each passes the count/failure
        // gates; both are addressable evidence.
        writeRawXml(
            dir.resolve("TEST-$simpleName-A.xml"),
            fqcn = fqcnA,
        )
        writeRawXml(
            dir.resolve("TEST-$simpleName-B.xml"),
            fqcn = fqcnB,
        )

        val evidence = EvidenceReader.suitesWithEvidence(dir)

        assertEquals(
            setOf(fqcnA, fqcnB),
            evidence.keys,
            "BQ4 P0 HITO 1 weakness 4: the evidence map MUST be keyed by " +
                "FQCN, so two same-named suites in different packages produce " +
                "two distinct rows. The previous shape used the simple class " +
                "name and collapsed them; the new shape is the closed-world " +
                "identity the audit required.",
        )
    }

    /**
     * Write a minimal JUnit report with a custom FQCN in
     * the `<testsuite name="...">` attribute. Used by
     * the FQCN identity test to stage two same-named
     * suites in different packages.
     */
    private fun writeRawXml(file: Path, fqcn: String) {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="$fqcn" tests="1" failures="0" errors="0"/>
            """.trimIndent(),
        )
    }

    // ---- silent-green regression: a report that has no executed test is
    // not evidence, regardless of failures/errors being zero. ADR-0072
    // and the EVIDENCE_GATE rule in AGENTS.md require tests > 0 and
    // (tests - skipped) > 0; the previous version of `classNameFrom`
    // accepted an XML with tests="0" failures="0" errors="0" as a
    // passing suite. ----

    @Test
    fun `a report with zero tests does NOT count as evidence`(@TempDir dir: Path) {
        report(dir, "EmptySuite", tests = 0)
        assertTrue(
            EvidenceReader.suitesWithEvidence(dir).isEmpty(),
            "tests=0 with no failures is an absence of execution, not a clean run",
        )
    }

    @Test
    fun `a report with all tests skipped does NOT count as evidence`(@TempDir dir: Path) {
        report(dir, "AllSkipped", tests = 5, skipped = 5)
        assertTrue(
            EvidenceReader.suitesWithEvidence(dir).isEmpty(),
            "100% skipped is not a green run; it is the matrix that AGENTS.md calls " +
                "the silent-green shape (Refused(ALL_SKIPPED))",
        )
    }

    @Test
    fun `a report with mostly skipped but at least one test does count`(@TempDir dir: Path) {
        // tests=5 skipped=4 -> 1 executed test, 0 failures, 0 errors.
        // This is the boundary: the gate requires at least one executed
        // test, not all of them.
        report(dir, "MostlySkipped", tests = 5, skipped = 4)
        assertEquals(
            setOf("MostlySkipped"),
            EvidenceReader.suitesWithEvidence(dir).keys,
        )
    }

    // ---- the gate: a selected suite with no XML refuses ----

    @Test
    fun `a selected suite with no report refuses the gate`(@TempDir dir: Path) {
        val plan = ImpactResolver.resolve(listOf(redactor))
        val refusal = EvidenceReader.verify(
            plan = plan,
            resultsDir = dir,
            expectedCommit = "abc123",
            observedCommit = "abc123",
        )
        assertNotNull(refusal, "the plan selected a suite and nothing ran, so the gate must refuse")
        assertTrue(
            (refusal as ValidationPlan.Refused).reasons.any {
                it is SelectionRefusal.SelectedButNotRun
            },
            "the refusal must name the missing suite, got ${refusal.reasons}",
        )
    }

    @Test
    fun `a selected suite whose report has a failure refuses the gate`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest", failures = 1)
        val plan = ImpactResolver.resolve(listOf(redactor))
        val refusal = EvidenceReader.verify(plan, dir, "abc123", "abc123")
        assertNotNull(refusal, "a red suite must not discharge the plan")
    }

    @Test
    fun `a fully executed plan on the matching commit verifies clean`(@TempDir dir: Path) {
        val plan = ImpactResolver.resolve(listOf(redactor)) as ValidationPlan.Selected
        val commit = "0".repeat(40)
        // The plan's minimumFidelities is HF2 (the catalogue
        // entry for OutputPlane.redaction.streaming is HF2).
        // The sidecar must declare the same fidelity, or
        // the gate refuses on InsufficientFidelity.
        val hf = plan.minimumFidelities.firstOrNull()?.name ?: "HF0"
        plan.suites.forEach {
            report(dir, it.filter, sidecar = true, sourceCommit = commit, hf = hf)
        }
        assertNull(
            EvidenceReader.verify(plan, dir, commit, commit),
            "every selected suite ran with a sidecar and the commit matches; " +
                "the gate must not refuse",
        )
    }

    @Test
    fun `evidence from another commit refuses even when every suite ran`(@TempDir dir: Path) {
        val plan = ImpactResolver.resolve(listOf(redactor)) as ValidationPlan.Selected
        val commit = "0".repeat(40)
        plan.suites.forEach { report(dir, it.filter, sidecar = true, sourceCommit = commit) }
        val refusal = EvidenceReader.verify(plan, dir, commit, "abc123")
        assertNotNull(refusal, "a stale binary's green is not this commit's green")
        assertTrue(
            (refusal as ValidationPlan.Refused).reasons.any { it is SelectionRefusal.StaleEvidence },
            "the refusal must name the stale evidence, got ${refusal.reasons}",
        )
    }

    @Test
    fun `a binary reporting no commit refuses rather than passing silently`(@TempDir dir: Path) {
        val plan = ImpactResolver.resolve(listOf(redactor)) as ValidationPlan.Selected
        plan.suites.forEach { report(dir, it.filter) }
        assertNotNull(
            EvidenceReader.verify(plan, dir, "abc123", null),
            "an absent commit is unknown provenance, and unknown is not clean",
        )
    }
}
