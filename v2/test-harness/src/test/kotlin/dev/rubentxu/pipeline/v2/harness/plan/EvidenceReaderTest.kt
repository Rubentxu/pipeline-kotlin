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
        corrupt: Boolean = false,
    ) {
        val body = if (corrupt) {
            "<testsuite name=\"$className\" tests=\"1\" failures=\"0\" errors=\"0\">"
        } else {
            "<testsuite name=\"$className\" tests=\"1\" failures=\"$failures\" errors=\"$errors\"/>"
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
