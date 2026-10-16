package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the installed distribution reports, and what happens when it cannot be read.
 *
 * ## The defect these tests exist for
 *
 * A real gate run discharged on a tautology. `observedCommit` was the third CLI argument, so the
 * operator named both sides of `ImpactResolver.adjudicate`'s comparison; a run that passed `HEAD`
 * twice satisfied `observedCommit != expectedCommit` by comparing a value with itself. The
 * provenance half of the gate did nothing while reporting that it had.
 *
 * The fix is not a better test of the old behaviour but a different shape: the observed value is
 * now read from an artifact. These tests pin the two properties that make that shape worth having:
 * the read is a real observation of a real document, and every way of failing to observe becomes
 * `null` — which `adjudicate` refuses — rather than a silent match.
 */
class InstalledDistributionTest {

    /** The document the installed CLI actually emits, captured from a real run. */
    private val realDocument =
        """{"productVersion":"0.61.0","buildCommit":"a7a4d5d2a26bb8c2a484002765e3a3c97dcd6b46"}"""

    @Nested
    @DisplayName("parse reads the commit the distribution reports")
    inner class Parsing {

        @Test
        fun `reads buildCommit from the real version --json document`() {
            assertEquals(
                ObservedProvenance.Reported("a7a4d5d2a26bb8c2a484002765e3a3c97dcd6b46"),
                InstalledDistribution.parse(realDocument),
            )
        }

        @Test
        fun `tolerates whitespace around the field separator`() {
            val spaced = "{ \"productVersion\" : \"0.61.0\" , \"buildCommit\" : \"abc123\" }"

            assertEquals(
                ObservedProvenance.Reported("abc123"),
                InstalledDistribution.parse(spaced),
            )
        }

        @Test
        fun `ignores a second document field that merely contains the word commit`() {
            // Guards against a looser match: a field named `expectedCommit` must not be read as the
            // distribution's own answer, or a launcher reporting both would answer with either.
            val document =
                """{"expectedCommit":"deadbeef","productVersion":"0.61.0","buildCommit":"abc123"}"""

            assertEquals(
                ObservedProvenance.Reported("abc123"),
                InstalledDistribution.parse(document),
            )
        }

        /**
         * The mutation this whole class guards: making [InstalledDistribution.parse] return the
         * commit the caller expected rather than the one it read. Here that is modelled by a
         * document whose commit is NOT the expected value — a gate reading it must not agree.
         */
        @Test
        fun `reports the document's commit even when it differs from what the caller expects`() {
            val other = """{"productVersion":"0.61.0","buildCommit":"0000000000000000000000000000000000000000"}"""

            assertEquals(
                ObservedProvenance.Reported("0000000000000000000000000000000000000000"),
                InstalledDistribution.parse(other),
            )
        }
    }

    @Nested
    @DisplayName("every unreadable document refuses rather than matching")
    inner class FailingClosed {

        @Test
        fun `empty output is unreadable`() {
            assertTrue(InstalledDistribution.parse("") is ObservedProvenance.Unreadable)
            assertTrue(InstalledDistribution.parse("   \n  ") is ObservedProvenance.Unreadable)
        }

        @Test
        fun `a document without buildCommit is unreadable`() {
            val result = InstalledDistribution.parse("""{"productVersion":"0.61.0"}""")

            assertTrue(result is ObservedProvenance.Unreadable)
        }

        @Test
        fun `an empty buildCommit is unreadable rather than a match`() {
            // The shape that matters: an empty value must not compare equal to a commit, and must
            // not be silently coerced into "the commit the operator expected".
            val result = InstalledDistribution.parse("""{"productVersion":"0.61.0","buildCommit":""}""")

            assertTrue(result is ObservedProvenance.Unreadable)
        }

        @Test
        fun `a version document from a build with no commit stamped is unreadable`() {
            val result = InstalledDistribution.parse("""{"productVersion":"0.61.0","buildCommit":null}""")

            assertTrue(result is ObservedProvenance.Unreadable)
        }
    }

    @Nested
    @DisplayName("unknown is null, and null is what adjudicate refuses")
    inner class UnreadableBecomesNull {

        @Test
        fun `a reported commit is passed through`() {
            assertEquals("abc123", ObservedProvenance.Reported("abc123").commitOrNull())
        }

        @Test
        fun `an unreadable provenance becomes null rather than a guess`() {
            assertNull(ObservedProvenance.Unreadable("no distribution").commitOrNull())
        }

        /**
         * The end-to-end property, and the one the tautology violated.
         *
         * Adjudication with an unreadable provenance must REFUSE, because unknown is not a match.
         * Under the old shape the caller supplied both operands, so this row could only pass by the
         * caller having chosen to pass a matching value.
         */
        @Test
        fun `adjudication refuses when the distribution could not be read`() {
            val plan = aPlanFor("SomeSuite")
            val commit40 = "a".repeat(40)
            val refusal = ImpactResolver.adjudicate(
                plan = plan,
                executedSuites = adjudicateEvidence(plan, setOf("SomeSuite"), expectedCommit = commit40),
                expectedCommit = commit40,
                observedCommit = ObservedProvenance.Unreadable("no distribution").commitOrNull(),
            )

            assertTrue(refusal is ValidationPlan.Refused) {
                "unknown provenance must refuse, not discharge"
            }
            assertTrue(
                refusal!!.reasons.any { it is SelectionRefusal.StaleEvidence },
            ) { "the refusal must name the provenance disagreement: ${refusal.reasons}" }
        }

        @Test
        fun `a matching reported commit discharges the same plan`() {
            val plan = aPlanFor("SomeSuite")
            val commit40 = "a".repeat(40)
            val refusal = ImpactResolver.adjudicate(
                plan = plan,
                executedSuites = adjudicateEvidence(plan, setOf("SomeSuite"), expectedCommit = commit40),
                expectedCommit = commit40,
                observedCommit = ObservedProvenance.Reported(commit40).commitOrNull(),
            )

            assertNull(refusal)
        }

        /**
         * The other direction, and the one a reader cannot supply: a distribution built from a
         * DIFFERENT commit must refuse even when the caller would happily have passed a matching
         * value. Under the old shape this row was unreachable — the observed side was whatever the
         * operator typed.
         */
        @Test
        fun `a distribution built from a different commit refuses`() {
            val plan = aPlanFor("SomeSuite")
            val commit40 = "a".repeat(40)
            val otherCommit = "9".repeat(40)
            val refusal = ImpactResolver.adjudicate(
                plan = plan,
                executedSuites = adjudicateEvidence(plan, setOf("SomeSuite"), expectedCommit = commit40),
                expectedCommit = commit40,
                observedCommit = ObservedProvenance.Reported(otherCommit).commitOrNull(),
            )

            assertTrue(refusal is ValidationPlan.Refused) {
                "a stale distribution must refuse"
            }
            assertEquals(
                SelectionRefusal.StaleEvidence(expected = commit40, observed = otherCommit),
                refusal!!.reasons.single { it is SelectionRefusal.StaleEvidence },
            )
        }
    }

    /** A minimal selected plan: one suite, no reasons. Enough to reach the adjudication. */
    private fun aPlanFor(suite: String): ValidationPlan.Selected = ValidationPlan.Selected(
        capabilities = setOf("x"),
        contracts = emptySet(),
        suites = setOf(TestSuiteId(filter = suite, gradleProject = ":test-harness")),
        minimumFidelities = emptySet(),
        reasons = emptyList(),
    )

    @Nested
    @DisplayName("read interrogates the artifact on disk")
    inner class Reading {

        @Test
        fun `a build root with no installed distribution is unreadable and says what to run`(@TempDir dir: Path) {
            val result = InstalledDistribution.read(dir)

            assertTrue(result is ObservedProvenance.Unreadable) {
                "a missing distribution must not read as a match, got $result"
            }
            val reason = (result as ObservedProvenance.Unreadable).reason
            assertTrue(reason.contains("installDist"), "the reason must name the action: $reason")
        }

        @Test
        fun `the launcher path is derived from the build root, not from a working directory`(
            @TempDir dir: Path,
        ) {
            // Anchored from the checkout root rather than inside the build, so a wrong join shows up
            // as a path under an unrelated directory instead of passing by accident.
            val expected = dir.resolve("pipeline-application/build/install/pipelinek/bin/pipelinek")

            assertEquals(expected, InstalledDistribution.launcher(dir))
        }

        @Test
        fun `a launcher that is a directory rather than a file is unreadable`(@TempDir dir: Path) {
            // Guards the existence check: `Files.exists` would treat the directory as a hit and the
            // failure would surface as an IOException at launch instead of a legible refusal.
            Files.createDirectories(InstalledDistribution.launcher(dir))

            val result = InstalledDistribution.read(dir)

            assertTrue(result is ObservedProvenance.Unreadable) {
                "a directory is not a launcher; got $result"
            }
        }
    }

    @Nested
    @DisplayName("describe is legible enough to act on")
    inner class Description {

        @Test
        fun `a reported commit is described with its value`() {
            assertEquals(
                "installed distribution reports commit abc123",
                ObservedProvenance.Reported("abc123").describe(),
            )
        }

        @Test
        fun `an unreadable provenance is described with its cause`() {
            assertEquals(
                "installed provenance unavailable: no distribution",
                ObservedProvenance.Unreadable("no distribution").describe(),
            )
        }

        @Test
        fun `description never leaks the reason into a reported commit`() {
            // Two shapes, one discriminator: a caller reading `describe()` to learn the commit must
            // not receive a reason string, or the log becomes ambiguous about what was observed.
            assertEquals(
                "installed distribution reports commit abc123",
                ObservedProvenance.Reported("abc123").describe(),
            )
            assertTrue(ObservedProvenance.Reported("abc123").describe().contains("abc123"))
        }
    }
}