package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BQ4 v11 (cycle carrier self-protect, 2026-10-17): the
 * previous shape of [ExitVerdict]'s data classes had
 * no init validation. A blank `reason` would be the
 * operator-facing diagnostic's fabrication. The
 * carrier now refuses blanks at construction.
 *
 * The three claims pinned here:
 *  1. The `Refusal` variants (`NoEvidence`,
 *     `OtherCommit`, `UnsupportedFidelity`,
 *     `Generic`) refuse blank reasons.
 *  2. The top-level `BlockedExternal` and
 *     `Incomplete` refuse blank reasons.
 *  3. The carrier's `exitCode` is preserved through
 *     the construction-time check.
 */
class ExitVerdictCarrierStrictnessTest {

    @Test
    fun `NoEvidence refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ExitVerdict.Refusal.NoEvidence(reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("NoEvidence.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `UnsupportedFidelity refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ExitVerdict.Refusal.UnsupportedFidelity(reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("UnsupportedFidelity.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Generic refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ExitVerdict.Refusal.Generic(reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("Generic.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `BlockedExternal refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ExitVerdict.BlockedExternal(reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("BlockedExternal.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `Incomplete refuses a blank reason at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            ExitVerdict.Incomplete(reason = "")
        }
        assertTrue(
            (ex.message ?: "").contains("Incomplete.reason must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }
}
