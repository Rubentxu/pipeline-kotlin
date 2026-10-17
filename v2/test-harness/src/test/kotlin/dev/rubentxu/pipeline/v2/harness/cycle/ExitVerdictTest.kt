package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * BQ4 v2 close-out (2026-10-16 audit, vertical): the cycle's exit
 * code projection is a typed carrier, never a string match and never
 * an isolated probe's `exitCode`.
 *
 * Each test pins ONE property of [ExitVerdict.from]; the whole class
 * exists so a future case (e.g. an `Incomplete` refinement) breaks
 * the compiler at the `when` in [ExitVerdict.from] rather than at a
 * runtime string match deep in a CI log.
 */
class ExitVerdictTest {

    @Nested
    inner class ProjectingTerminalCycleStates {

        @Test
        fun `a Closed cycle projects to Verified with exit 0`() {
            val verdict = ExitVerdict.from(CycleState.Closed("done"))
            assertSame(ExitVerdict.Verified, verdict, "Closed is the only Verified terminal")
            assertEquals(0, verdict.exitCode)
        }

        @Test
        fun `a BlockedExternal cycle projects to BlockedExternal with exit 75`() {
            val verdict = ExitVerdict.from(CycleState.BlockedExternal("harness verdict pending"))
            assertTrue(verdict is ExitVerdict.BlockedExternal)
            assertEquals(75, verdict.exitCode)
            assertEquals("harness verdict pending", (verdict as ExitVerdict.BlockedExternal).reason)
        }

        @Test
        fun `non-terminal states project to Incomplete, not to a fake Verified`() {
            // The decider never closed the cycle. A previous shape read
            // a raw `exitCode == 0` from an isolated probe and called
            // the cycle "Verified"; that is the defect this entire
            // module closes. Pin the projection: a non-terminal state
            // is Incomplete, exit 1, with the state name in the reason.
            val drafts = listOf(
                CycleState.Draft,
                CycleState.Running,
                CycleState.Observed("0123456789abcdef0123456789abcdef01234567"),
                CycleState.Promoted("0123456789abcdef0123456789abcdef01234567"),
            )
            for (state in drafts) {
                val verdict = ExitVerdict.from(state)
                assertTrue(
                    verdict is ExitVerdict.Incomplete,
                    "non-terminal $state must project to Incomplete, got $verdict",
                )
                assertEquals(1, verdict.exitCode)
                assertTrue(
                    (verdict as ExitVerdict.Incomplete).reason.contains(state::class.simpleName!!),
                    "Incomplete reason must name the state for the operator; got '${verdict.reason}'",
                )
            }
        }
    }

    @Nested
    inner class ClassifyingRefusals {

        @Test
        fun `a NoProbesSucceeded refusal routes to NoEvidence with exit 1`() {
            // BQ4 v2 (typed refactor): the cycle-level "no probe
            // succeeded" refusal is now its own [CycleState.Refused.NoProbesSucceeded]
            // case. The [ExitVerdict] projection matches on the
            // typed carrier, NOT on a `reason.startsWith("no probe
            // succeeded")` string match. A future Refused case
            // forces both ends to grow at compile time.
            val verdict = ExitVerdict.from(
                CycleState.Refused.NoProbesSucceeded(observedCount = 0),
            )
            assertTrue(verdict is ExitVerdict.Refusal.NoEvidence)
            assertEquals(1, verdict.exitCode)
            assertTrue((verdict as ExitVerdict.Refusal.NoEvidence).reason.contains("no probe succeeded"))
        }

        @Test
        fun `a ProbeNoEvidence refusal also routes to NoEvidence with exit 1`() {
            // The per-probe no-evidence signal: a single probe
            // returned [ProbeOutcome.NoEvidence]. The cycle
            // refused at the probe boundary, not at
            // AllProbesObserved. The projection distinguishes the
            // two via the typed carrier.
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeNoEvidence(evidenceRef = "p-1"),
            )
            assertTrue(verdict is ExitVerdict.Refusal.NoEvidence)
            assertEquals(1, verdict.exitCode)
        }

        @Test
        fun `a ProbeOtherCommit refusal routes to OtherCommit with exit 1`() {
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeOtherCommit(
                    evidenceRef = "p-1",
                    observed = "fedcba9876543210fedcba9876543210fedcba98",
                ),
            )
            assertTrue(verdict is ExitVerdict.Refusal.OtherCommit)
            assertEquals(1, verdict.exitCode)
        }

        @Test
        fun `a ProbeUnsupportedFidelity refusal routes to UnsupportedFidelity with exit 64`() {
            // Distinct exit code: 64 vs 1. The previous shape mapped
            // every Refused to 1 unless the reason started with the
            // literal "UnsupportedFidelity". A future "other commit"
            // refusal that happens to begin with that prefix would
            // silently misroute. The typed carrier makes that impossible.
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeUnsupportedFidelity(evidenceRef = "p-1", fidelity = "HF2"),
            )
            assertTrue(verdict is ExitVerdict.Refusal.UnsupportedFidelity)
            assertEquals(64, verdict.exitCode)
        }

        @Test
        fun `a ProbeFailed refusal routes to Generic with exit 1, reason preserved`() {
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeFailed(evidenceRef = "p-1", failureReason = "exit code 1"),
            )
            assertTrue(verdict is ExitVerdict.Refusal.Generic)
            assertEquals(1, verdict.exitCode)
            val generic = verdict as ExitVerdict.Refusal.Generic
            assertTrue(
                generic.reason.contains("p-1") && generic.reason.contains("Failed") && generic.reason.contains("exit code 1"),
                "ProbeFailed.reason must compose the typed fields into the receipt text; got '${generic.reason}'",
            )
        }

        @Test
        fun `a generic refusal keeps the reason verbatim and routes to Generic with exit 1`() {
            val reason = "manifest cycleId does not match --cycle-id: declared 'foo' got 'bar'"
            val verdict = ExitVerdict.from(CycleState.Refused.Generic(reason))
            assertTrue(verdict is ExitVerdict.Refusal.Generic)
            assertEquals(1, verdict.exitCode)
            assertEquals(reason, (verdict as ExitVerdict.Refusal.Generic).reason)
        }

        // BQ4 v22 (no-evidence carrier accepts blank,
        // 2026-10-17): the three "no evidence" carriers
        // (`ProbeNoEvidence`, `ProbeOtherCommit`,
        // `ProbeUnsupportedFidelity`) now accept blank
        // `evidenceRef`. A refused fidelity check or a
        // commit mismatch check may produce no path; the
        // typed `Refused` variant is the discriminant. The
        // tests below pin the new blank-accepting shape and
        // confirm the `reason` text omits the `probe XXX`
        // prefix when the field is blank.
        @Test
        fun `a ProbeNoEvidence refusal with blank evidenceRef still routes to NoEvidence`() {
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeNoEvidence(evidenceRef = ""),
            )
            assertTrue(verdict is ExitVerdict.Refusal.NoEvidence)
            assertEquals(1, verdict.exitCode)
        }

        @Test
        fun `a ProbeOtherCommit refusal with blank evidenceRef still routes to OtherCommit`() {
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeOtherCommit(
                    evidenceRef = "",
                    observed = "fedcba9876543210fedcba9876543210fedcba98",
                ),
            )
            assertTrue(verdict is ExitVerdict.Refusal.OtherCommit)
            assertEquals(1, verdict.exitCode)
            val refusal = verdict as ExitVerdict.Refusal.OtherCommit
            assertTrue(
                !refusal.reason.contains("probe  "),
                "blank evidenceRef must omit the 'probe XXX' prefix; got '${refusal.reason}'",
            )
        }

        @Test
        fun `a ProbeUnsupportedFidelity refusal with blank evidenceRef still routes to UnsupportedFidelity`() {
            val verdict = ExitVerdict.from(
                CycleState.Refused.ProbeUnsupportedFidelity(evidenceRef = "", fidelity = "HF2"),
            )
            assertTrue(verdict is ExitVerdict.Refusal.UnsupportedFidelity)
            assertEquals(64, verdict.exitCode)
            val refusal = verdict as ExitVerdict.Refusal.UnsupportedFidelity
            assertTrue(
                !refusal.reason.contains("probe  "),
                "blank evidenceRef must omit the 'probe XXX' prefix; got '${refusal.reason}'",
            )
        }
    }
}
