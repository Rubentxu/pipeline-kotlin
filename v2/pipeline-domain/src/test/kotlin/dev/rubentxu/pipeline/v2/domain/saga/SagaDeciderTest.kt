package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The rules a Saga decides by, exercised directly.
 *
 * These are pure functions over data, so every case here is a case the running pipeline cannot
 * easily produce: an obligation that was never armed, an effect whose outcome is unknown, a
 * promise made twice under two different plans. Each test names the behaviour it protects, and
 * each fails if the rule it protects is broken.
 */
class SagaDeciderTest {

    private fun obligation(
        planFingerprint: SagaPlanFingerprint = PLAN_A,
        status: CompensationObligationStatus = CompensationObligationStatus.ARMED,
    ) = CompensationObligation(
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("reserve-stock"),
        compensationId = CompensationId.of("release-stock"),
        planFingerprint = planFingerprint,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
        status = status,
        leaseEpoch = 1L,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Nested
    inner class Arming {

        @Test
        fun `an empty key arms the promise`() {
            val requested = obligation()

            val outcome = SagaDecider.armOutcome(existing = null, requested = requested)

            assertEquals(CompensationArmOutcome.Armed(requested), outcome)
        }

        @Test
        fun `the same key under the same plan is the same promise`() {
            val requested = obligation()
            val existing = obligation()

            val outcome = SagaDecider.armOutcome(existing = existing, requested = requested)

            assertEquals(CompensationArmOutcome.AlreadyArmed(existing), outcome)
        }

        @Test
        fun `the same key under a different plan is a contradiction and keeps the first promise`() {
            val existing = obligation(planFingerprint = PLAN_A)
            val requested = obligation(planFingerprint = PLAN_B)

            val outcome = SagaDecider.armOutcome(existing = existing, requested = requested)

            val divergence = assertInstanceOf<CompensationArmOutcome.PlanDivergence>(outcome)
            assertEquals(existing, divergence.existing)
            assertEquals(PLAN_B, divergence.requestedFingerprint)
        }

        @Test
        fun `the moment of arming does not make a promise a different one`() {
            val existing = obligation()
            // Same promise, armed later: only the timestamps differ.
            val requested = existing.copy(createdAtMs = 9_999L, updatedAtMs = 9_999L)

            val outcome = SagaDecider.armOutcome(existing = existing, requested = requested)

            assertEquals(CompensationArmOutcome.AlreadyArmed(existing), outcome)
        }
    }

    @Nested
    inner class Forward {

        @Test
        fun `an armed promise lets the forward effect proceed`() {
            val decision = SagaDecider.decide(
                obligation = obligation(),
                evidence = EffectEvidence.NotAttempted,
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                SagaDecision.Forward(ReconciliationDecision.ProceedForward),
                decision,
            )
        }

        @Test
        fun `without a promise the forward effect does not proceed`() {
            val decision = SagaDecider.decide(
                obligation = null,
                evidence = EffectEvidence.NotAttempted,
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                SagaDecision.Forward(
                    ReconciliationDecision.DenyForward(ForwardDenial.OBLIGATION_NOT_ARMED),
                ),
                decision,
            )
        }

        @Test
        fun `an unknown effect is reconciled when the provider can be queried`() {
            val decision = SagaDecider.decide(
                obligation = obligation(),
                evidence = EffectEvidence.Unknown(UncertaintyReason.ACK_LOST),
                policy = CompensationPolicy.Reconcilable,
            )

            assertEquals(SagaDecision.Forward(ReconciliationDecision.Reconcile), decision)
        }

        @Test
        fun `an unknown effect with nothing that can query it needs a human`() {
            val decision = SagaDecider.decide(
                obligation = obligation(),
                evidence = EffectEvidence.Unknown(UncertaintyReason.ACK_LOST),
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                SagaDecision.Forward(
                    ReconciliationDecision.NeedsIntervention(InterventionReason.UNKNOWN_EFFECT),
                ),
                decision,
            )
        }
    }

    @Nested
    inner class Compensation {

        @Test
        fun `a confirmed effect leaves the obligation owed`() {
            val decision = SagaDecider.decide(
                obligation = obligation(),
                evidence = EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-1")),
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(SagaDecision.Compensate(CompensationVerdict.Pending), decision)
        }

        @Test
        fun `an effect that had none closes the obligation as not required`() {
            val decision = SagaDecider.decide(
                obligation = obligation(),
                evidence = EffectEvidence.Absent,
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(SagaDecision.Compensate(CompensationVerdict.NotRequired), decision)
        }

        @Test
        fun `an effect that happened with no promise behind it needs a human`() {
            val decision = SagaDecider.decide(
                obligation = null,
                evidence = EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-1")),
                policy = CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                SagaDecision.Forward(
                    ReconciliationDecision.NeedsIntervention(
                        InterventionReason.OBLIGATION_NOT_ARMED,
                    ),
                ),
                decision,
            )
        }
    }

    private companion object {
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val PLAN_B = SagaPlanFingerprint("b".repeat(64))
    }
}
