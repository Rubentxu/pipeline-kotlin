package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The rule that decides whether a Saga may be closed WELL.
 *
 * ## Why this table and not a comment
 *
 * The decision is pure, so every case here is a case a running pipeline cannot easily produce:
 * an obligation whose effect nobody established, a Saga that was already undone, a promise whose
 * compensation is still in flight. Each test names the behaviour it protects, and each fails if
 * the rule it protects is broken.
 *
 * ## What the rule has to get right
 *
 * A completion CLAIMS that the Saga ended well. The claim may only be made when the durable
 * state can say so too — otherwise the engine stamps a success over a world it does not
 * understand, and a later process undoes half of it.
 */
class SagaDeciderCompletionTest {

    private fun obligation(
        actionId: String,
        status: CompensationObligationStatus,
        createdAtMs: Long = 1_000L,
    ) = CompensationObligation(
        sagaId = SAGA,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of("undo-$actionId"),
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("resource/$actionId"),
        kind = CompensationKind.RESTORE,
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        forwardOpId = "run-1|stage-0|step-$actionId",
        compensationOpId = "saga-v1|compensate|undo-$actionId|run-1|stage-0|step-$actionId",
        status = status,
        leaseEpoch = 1L,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

    private fun snapshotOf(vararg obligations: CompensationObligation) = SagaSnapshot(
        sagaId = SAGA,
        obligations = obligations.toList(),
    )

    @Nested
    inner class WhatCanBeCompleted {

        @Test
        fun `a Saga whose effects were confirmed can be completed, and keeps them`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.EFFECT_CONFIRMED)),
            )

            val completable = assertInstanceOf<SagaCompletionDecision.Completable>(decision)
            assertEquals(listOf("reserve"), completable.kept.map { it.value })
            // The promise that has to be voided: the one whose effect was confirmed.
            assertEquals(
                listOf("reserve"),
                completable.discharge.map { it.actionId.value },
            )
        }

        @Test
        fun `a promise that was never used does not block the closing`() {
            // The forward was never dispatched: there is nothing to keep and nothing to undo, so
            // the Saga may close with that action simply absent.
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.ARMED)),
            )

            val completable = assertInstanceOf<SagaCompletionDecision.Completable>(decision)
            assertEquals(emptyList<String>(), completable.kept.map { it.value })
            assertEquals(emptyList<String>(), completable.discharge.map { it.actionId.value })
        }

        @Test
        fun `an effect that had no effect does not block the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.EFFECT_ABSENT)),
            )

            assertInstanceOf<SagaCompletionDecision.Completable>(decision)
        }

        @Test
        fun `a Saga that was already completed keeps what it kept, and discharges nothing`() {
            // The second completion of the same Saga: the effect is already kept, so there is
            // nothing to void. The report still says what the Saga kept, because a consumer
            // asking twice deserves the same answer.
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.EFFECT_KEPT)),
            )

            val completable = assertInstanceOf<SagaCompletionDecision.Completable>(decision)
            assertEquals(listOf("reserve"), completable.kept.map { it.value })
            assertEquals(emptyList<String>(), completable.discharge.map { it.actionId.value })
        }
    }

    @Nested
    inner class WhatBlocksIt {

        @Test
        fun `an effect nobody established blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.EFFECT_UNKNOWN)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.UNKNOWN_EFFECT, refused.reason)
        }

        @Test
        fun `an effect that was dispatched but not answered blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.EFFECT_PENDING)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.UNKNOWN_EFFECT, refused.reason)
        }

        @Test
        fun `a Saga that was already undone cannot be completed`() {
            // Its effects are gone. Completing it would claim a Saga whose resources do not
            // exist, and a run that says "success" after that is a lie about the world.
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.COMPENSATED)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.SAGA_ALREADY_COMPENSATED, refused.reason)
        }

        @Test
        fun `a compensation in flight blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.COMPENSATION_PENDING)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.COMPENSATION_UNCERTAIN, refused.reason)
        }

        @Test
        fun `a compensation that failed blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.COMPENSATION_FAILED)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.COMPENSATION_FAILED, refused.reason)
        }

        @Test
        fun `a compensation nobody could confirm blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.COMPENSATION_UNCERTAIN)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.COMPENSATION_UNCERTAIN, refused.reason)
        }

        @Test
        fun `an obligation that waits for a human blocks the closing`() {
            val decision = SagaDecider.completion(
                snapshotOf(obligation("reserve", CompensationObligationStatus.NEEDS_INTERVENTION)),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals(InterventionReason.UNKNOWN_EFFECT, refused.reason)
        }
    }

    @Nested
    inner class WhichOneIsNamed {

        @Test
        fun `the newest unsettled obligation is the one that is named`() {
            // The compensation walk goes newest first, and so does this one: the newest unsettled
            // obligation is the one that needs attention, and the others depend on it.
            val decision = SagaDecider.completion(
                snapshotOf(
                    obligation("reserve", CompensationObligationStatus.EFFECT_UNKNOWN),
                    obligation(
                        actionId = "charge",
                        status = CompensationObligationStatus.COMPENSATION_FAILED,
                        createdAtMs = 2_000L,
                    ),
                ),
            )

            val refused = assertInstanceOf<SagaCompletionDecision.Refused>(decision)
            assertEquals("charge", refused.obligation.actionId.value)
            assertEquals(InterventionReason.COMPENSATION_FAILED, refused.reason)
        }

        @Test
        fun `one unsettled obligation blocks the whole Saga`() {
            // The others are fine and the Saga still cannot close: a completion claims the Saga
            // ended well, and one unknown effect is enough to make that claim unsayable.
            val decision = SagaDecider.completion(
                snapshotOf(
                    obligation("reserve", CompensationObligationStatus.EFFECT_CONFIRMED),
                    obligation("charge", CompensationObligationStatus.EFFECT_UNKNOWN),
                ),
            )

            assertInstanceOf<SagaCompletionDecision.Refused>(decision)
        }
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
    }
}
