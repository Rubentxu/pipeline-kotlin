package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BQ4 v12 (cycle carrier self-protect, 2026-10-17): the
 * previous shape of [ProbeOutcome]'s data classes had
 * no init validation. A `Verified("")` was a Verified
 * without evidence — a fabrication that the runner's
 * `ProbeFinished` check would catch, but only at the
 * cycle state boundary, not at the carrier's
 * construction. The carrier is now the boundary.
 *
 * The 5 "real evidence" variants
 * ([ProbeOutcome.Verified], [ProbeOutcome.Failed],
 * [ProbeOutcome.Skipped], [ProbeOutcome.NotRun],
 * [ProbeOutcome.OtherCommit]) refuse blank
 * `evidenceRef` at construction.
 *
 * The 3 "no evidence" variants
 * ([ProbeOutcome.NoEvidence],
 * [ProbeOutcome.UnsupportedFidelity],
 * [ProbeOutcome.BlockedExternal]) intentionally accept
 * blank `evidenceRef`; their meaning is "no evidence
 * was produced" and the blank is the absent-case
 * signal. The asymmetry is documented in
 * [ProbeOutcome.Verified]'s init KDoc.
 */
class ProbeOutcomeCarrierStrictnessTest {

    @Test
    fun `Verified refuses a blank evidenceRef at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProbeOutcome.Verified(evidenceRef = "")
        }
        assertTrue(
            (ex.message ?: "").contains("Verified.evidenceRef must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Failed refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProbeOutcome.Failed(evidenceRef = "ev-1", reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("Failed.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `NotRun refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProbeOutcome.NotRun(evidenceRef = "ev-1", reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("NotRun.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `OtherCommit refuses a blank observed at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ProbeOutcome.OtherCommit(evidenceRef = "ev-1", observed = "")
        }
        assertTrue(
            (ex.message ?: "").contains("OtherCommit.observed must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `NoEvidence accepts a blank evidenceRef (the no-evidence signal)`() {
        // The no-evidence variants intentionally accept
        // blank evidenceRef; the blank IS the signal.
        // A non-blank evidenceRef is also accepted
        // (the runner may record a placeholder).
        val noEvidence = ProbeOutcome.NoEvidence(evidenceRef = "")
        assertTrue(noEvidence.evidenceRef.isBlank())
        val withRef = ProbeOutcome.NoEvidence(evidenceRef = "ev-1")
        assertTrue(withRef.evidenceRef == "ev-1")
    }

    @Test
    fun `BlockedExternal accepts a blank evidenceRef (no evidence yet)`() {
        val blocked = ProbeOutcome.BlockedExternal(evidenceRef = "", blocker = "harness verdict missing")
        assertTrue(blocked.evidenceRef.isBlank())
        assertTrue(blocked.blocker == "harness verdict missing")
    }
}
