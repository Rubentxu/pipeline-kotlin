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
    ) {
        val body = if (corrupt) {
            "<testsuite name=\"$className\" tests=\"1\" failures=\"0\" errors=\"0\">"
        } else {
            "<testsuite name=\"$className\" tests=\"$tests\" skipped=\"$skipped\" " +
                "failures=\"$failures\" errors=\"$errors\"/>"
        }
        Files.writeString(dir.resolve("TEST-$className.xml"), body)
    }

    @Test
    fun `a clean report counts as evidence`(@TempDir dir: Path) {
        report(dir, "ShTranscriptRedactionReproductionTest")
        assertEquals(
            setOf("ShTranscriptRedactionReproductionTest"),
            EvidenceReader.suitesWithEvidence(dir),
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
            EvidenceReader.suitesWithEvidence(dir),
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
        plan.suites.forEach { report(dir, it.filter) }
        assertNull(
            EvidenceReader.verify(plan, dir, "abc123", "abc123"),
            "every selected suite ran and the commit matches, so there is nothing to refuse",
        )
    }

    @Test
    fun `evidence from another commit refuses even when every suite ran`(@TempDir dir: Path) {
        val plan = ImpactResolver.resolve(listOf(redactor)) as ValidationPlan.Selected
        plan.suites.forEach { report(dir, it.filter) }
        val refusal = EvidenceReader.verify(plan, dir, "abc123", "def456")
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
