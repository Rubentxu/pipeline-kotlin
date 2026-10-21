package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a durable status MEANS, and therefore what an operator is told about it.
 *
 * The table is the only place where a status is turned into advice, so these are the rules
 * that decide whether an operator is told the truth about a run. Each case states one claim
 * about one status; the last two check the table against the lifecycle the engine obeys, so
 * the advice cannot drift away from the moves the engine is allowed to make.
 */
class SagaObligationStandingTest {

    @Test
    fun `a promise that was never tried owes nothing and needs nobody`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.ARMED)

        assertEquals(SagaActionPhase.FORWARD, standing.phase)
        assertEquals(SagaNextAction.ProceedForward, standing.next)
        assertNull(standing.error)
        assertFalse(standing.needsOperator)
    }

    @Test
    fun `a dispatched effect whose answer never came back has to be settled before anything else`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.EFFECT_PENDING)

        assertEquals(SagaActionPhase.FORWARD, standing.phase)
        assertEquals(SagaNextAction.SettleForward, standing.next)
        assertEquals(SagaActionError.ForwardOutcomeLost, standing.error)
        assertFalse(standing.needsOperator)
    }

    @Test
    fun `a confirmed effect is owed a compensation`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.EFFECT_CONFIRMED)

        assertEquals(SagaActionPhase.COMPENSATE, standing.phase)
        assertEquals(SagaNextAction.Compensate, standing.next)
        assertNull(standing.error)
        assertFalse(standing.needsOperator)
    }

    @Test
    fun `an effect nobody can place is not a failure but an open question`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.EFFECT_UNKNOWN)

        assertEquals(SagaActionPhase.FORWARD, standing.phase)
        assertEquals(SagaNextAction.SettleForward, standing.next)
        assertEquals(SagaActionError.ForwardOutcomeUnknown, standing.error)
        assertFalse(standing.needsOperator)
    }

    @Test
    fun `a compensation in flight has to be settled, not retried`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.COMPENSATION_PENDING)

        assertEquals(SagaActionPhase.COMPENSATE, standing.phase)
        assertEquals(SagaNextAction.SettleCompensation, standing.next)
        assertNull(standing.error)
        assertFalse(standing.needsOperator)
    }

    @Test
    fun `a discharged promise owes nothing and needs nobody`() {
        listOf(
            CompensationObligationStatus.EFFECT_KEPT,
            CompensationObligationStatus.EFFECT_ABSENT,
            CompensationObligationStatus.COMPENSATED,
        ).forEach { status ->
            val standing = SagaObligationStanding.of(status)

            assertEquals(SagaActionPhase.TERMINAL, standing.phase, "phase of $status")
            assertEquals(SagaNextAction.None, standing.next, "next of $status")
            assertNull(standing.error, "error of $status")
            assertFalse(standing.needsOperator, "operator of $status")
        }
    }

    @Test
    fun `a failed compensation is a human's decision`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.COMPENSATION_FAILED)

        assertEquals(SagaActionPhase.TERMINAL, standing.phase)
        assertEquals(SagaNextAction.Decide, standing.next)
        assertEquals(SagaActionError.CompensationFailed, standing.error)
        assertTrue(standing.needsOperator)
    }

    @Test
    fun `an uncertain compensation is a human's decision`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.COMPENSATION_UNCERTAIN)

        assertEquals(SagaActionPhase.TERMINAL, standing.phase)
        assertEquals(SagaNextAction.Decide, standing.next)
        assertEquals(SagaActionError.CompensationUncertain, standing.error)
        assertTrue(standing.needsOperator)
    }

    @Test
    fun `a stopped saga is a human's decision`() {
        val standing = SagaObligationStanding.of(CompensationObligationStatus.NEEDS_INTERVENTION)

        assertEquals(SagaActionPhase.TERMINAL, standing.phase)
        assertEquals(SagaNextAction.Decide, standing.next)
        assertEquals(SagaActionError.SagaStopped, standing.error)
        assertTrue(standing.needsOperator)
    }

    @Test
    fun `a promise is terminal exactly when the engine cannot move it any further`() {
        CompensationObligationStatus.entries.forEach { status ->
            val engineCanMove = CompensationLifecycle.successors(status).isNotEmpty()
            val adviceSaysTerminal = SagaObligationStanding.of(status).phase == SagaActionPhase.TERMINAL

            assertEquals(
                !engineCanMove,
                adviceSaysTerminal,
                "$status: the engine can move it further=$engineCanMove, " +
                    "and the advice calls it terminal=$adviceSaysTerminal",
            )
        }
    }

    @Test
    fun `a promise needs a person exactly when the next step is that person's decision`() {
        CompensationObligationStatus.entries.forEach { status ->
            val standing = SagaObligationStanding.of(status)

            assertEquals(
                standing.next == SagaNextAction.Decide,
                standing.needsOperator,
                "$status: next=${standing.next}, needsOperator=${standing.needsOperator}",
            )
        }
    }
}
