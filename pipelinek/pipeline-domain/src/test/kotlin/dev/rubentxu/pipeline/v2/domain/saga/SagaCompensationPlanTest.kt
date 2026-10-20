package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * What a Saga owes next, and what it may write, exercised directly.
 *
 * These are pure functions over data, so every case here is a case a running pipeline cannot
 * easily produce: a crash between the dispatch and the answer, a compensation that half
 * worked, two obligations armed in the same millisecond. Each test names the behaviour it
 * protects, and each fails if the rule it protects is broken.
 */
class SagaCompensationPlanTest {

    private fun obligation(
        actionId: String = "reserve-stock",
        compensationId: String = "release-stock",
        status: CompensationObligationStatus = CompensationObligationStatus.ARMED,
        createdAtMs: Long = 1_000L,
    ) = CompensationObligation(
        sagaId = SAGA,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of(compensationId),
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("stock/$actionId"),
        kind = CompensationKind.RESTORE,
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|$compensationId|run-1|stage-0|step-3",
        status = status,
        leaseEpoch = 1L,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

    private fun snapshotOf(vararg obligations: CompensationObligation) =
        SagaSnapshot(sagaId = SAGA, obligations = obligations.toList())

    @Nested
    inner class WhatIsOwed {

        @Test
        fun `a Saga with no obligations owes nothing`() {
            val directive = SagaDecider.nextCompensation(
                snapshotOf(),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(CompensationDirective.Settled(SAGA), directive)
        }

        @Test
        fun `a confirmed effect is owed its compensation`() {
            val confirmed = obligation(status = CompensationObligationStatus.EFFECT_CONFIRMED)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(confirmed),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(CompensationDirective.Compensate(confirmed), directive)
        }

        @Test
        fun `the compensation order is the REVERSE of the arm order`() {
            val older = obligation(
                actionId = "reserve-stock",
                compensationId = "release-stock",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 1_000L,
            )
            val newer = obligation(
                actionId = "charge-card",
                compensationId = "refund-card",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 2_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(older, newer),
                CompensationPolicy.NotReconcilable,
            )

            // The newest effect is undone first: undoing the older one while the newer one is
            // still in place would destroy the state the newer compensation needs.
            assertEquals(CompensationDirective.Compensate(newer), directive)
        }

        @Test
        fun `an obligation whose promise was never used owes nothing`() {
            val armed = obligation(status = CompensationObligationStatus.ARMED)
            val confirmed = obligation(
                actionId = "charge-card",
                compensationId = "refund-card",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 2_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(armed, confirmed),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(CompensationDirective.Compensate(confirmed), directive)
        }

        @Test
        fun `an effect that had none owes nothing`() {
            val absent = obligation(status = CompensationObligationStatus.EFFECT_ABSENT)
            val confirmed = obligation(
                actionId = "charge-card",
                compensationId = "refund-card",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 2_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(absent, confirmed),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(CompensationDirective.Compensate(confirmed), directive)
        }

        @Test
        fun `a Saga whose obligations are all discharged owes nothing`() {
            val discharged = obligation(status = CompensationObligationStatus.COMPENSATED)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(discharged),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(CompensationDirective.Settled(SAGA), directive)
        }
    }

    @Nested
    inner class WhatIsUncertain {

        @Test
        fun `an unknown effect is reconciled when the provider can be asked`() {
            val unknown = obligation(status = CompensationObligationStatus.EFFECT_UNKNOWN)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(unknown),
                CompensationPolicy.Reconcilable,
            )

            assertEquals(CompensationDirective.Reconcile(unknown), directive)
        }

        @Test
        fun `an unknown effect with nothing that can query it needs a human`() {
            val unknown = obligation(status = CompensationObligationStatus.EFFECT_UNKNOWN)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(unknown),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                CompensationDirective.NeedsIntervention(unknown, InterventionReason.UNKNOWN_EFFECT),
                directive,
            )
        }

        @Test
        fun `an effect dispatched without an answer is uncertain, not absent`() {
            // The crash window: the call left the process and nobody recorded what it did.
            val pending = obligation(status = CompensationObligationStatus.EFFECT_PENDING)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(pending),
                CompensationPolicy.NotReconcilable,
            )

            // NOT "nothing is owed": the effect may have happened, and a promise that quietly
            // stopped being owed is how a Saga loses a resource.
            assertEquals(
                CompensationDirective.NeedsIntervention(pending, InterventionReason.UNKNOWN_EFFECT),
                directive,
            )
        }

        @Test
        fun `a compensation dispatched without an answer is never applied again`() {
            val pending = obligation(status = CompensationObligationStatus.COMPENSATION_PENDING)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(pending),
                CompensationPolicy.Reconcilable,
            )

            assertEquals(CompensationDirective.Reconcile(pending), directive)
        }

        @Test
        fun `a timed-out effect is unknown, not failed`() {
            // A timeout says nothing about whether the provider applied the effect, so the Saga
            // does NOT skip the compensation it owes.
            val timedOut = obligation(status = CompensationObligationStatus.EFFECT_UNKNOWN)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(timedOut),
                CompensationPolicy.Reconcilable,
            )

            assertEquals(CompensationDirective.Reconcile(timedOut), directive)
            assertEquals(
                CompensationObligationStatus.EFFECT_UNKNOWN,
                CompensationLifecycle.forwardStatus(
                    EffectEvidence.Unknown(UncertaintyReason.CALL_TIMED_OUT),
                ),
            )
        }

        @Test
        fun `a cancelled effect is unknown too`() {
            val cancelled = obligation(status = CompensationObligationStatus.EFFECT_UNKNOWN)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(cancelled),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                CompensationDirective.NeedsIntervention(cancelled, InterventionReason.UNKNOWN_EFFECT),
                directive,
            )
            assertEquals(
                CompensationObligationStatus.EFFECT_UNKNOWN,
                CompensationLifecycle.forwardStatus(
                    EffectEvidence.Unknown(UncertaintyReason.CALL_CANCELLED),
                ),
            )
        }
    }

    @Nested
    inner class WhatStopsTheSaga {

        @Test
        fun `a failed compensation stops the Saga and says so`() {
            // The failed compensation is the NEWEST one: it is the one the Saga owes next, and
            // it is the one that blocks the rest.
            val failed = obligation(
                actionId = "charge-card",
                compensationId = "refund-card",
                status = CompensationObligationStatus.COMPENSATION_FAILED,
                createdAtMs = 2_000L,
            )
            val older = obligation(
                actionId = "reserve-stock",
                compensationId = "release-stock",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 1_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(failed, older),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                CompensationDirective.NeedsIntervention(failed, InterventionReason.COMPENSATION_FAILED),
                directive,
            )
        }

        @Test
        fun `an uncertain compensation stops the Saga and says so`() {
            val uncertain = obligation(status = CompensationObligationStatus.COMPENSATION_UNCERTAIN)

            val directive = SagaDecider.nextCompensation(
                snapshotOf(uncertain),
                CompensationPolicy.NotReconcilable,
            )

            assertEquals(
                CompensationDirective.NeedsIntervention(
                    uncertain,
                    InterventionReason.COMPENSATION_UNCERTAIN,
                ),
                directive,
            )
        }

        @Test
        fun `a blocked compensation keeps the older ones untouched`() {
            val failed = obligation(
                actionId = "charge-card",
                compensationId = "refund-card",
                status = CompensationObligationStatus.COMPENSATION_FAILED,
                createdAtMs = 2_000L,
            )
            val older = obligation(
                actionId = "reserve-stock",
                compensationId = "release-stock",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 1_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(older, failed),
                CompensationPolicy.NotReconcilable,
            )

            // The older compensation is NOT run: it would be run against a state that the failed
            // one did not restore.
            val intervention = assertInstanceOf<CompensationDirective.NeedsIntervention>(directive)
            assertEquals(failed, intervention.obligation)
        }
    }

    @Nested
    inner class Order {

        @Test
        fun `two obligations armed in the same millisecond are ordered by their ids`() {
            val first = obligation(
                actionId = "a-action",
                compensationId = "a-compensation",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 5_000L,
            )
            val second = obligation(
                actionId = "b-action",
                compensationId = "b-compensation",
                status = CompensationObligationStatus.EFFECT_CONFIRMED,
                createdAtMs = 5_000L,
            )

            val directive = SagaDecider.nextCompensation(
                snapshotOf(first, second),
                CompensationPolicy.NotReconcilable,
            )

            // Arbitrary, but FIXED: the same durable state must always produce the same answer.
            assertEquals(CompensationDirective.Compensate(second), directive)
        }

        @Test
        fun `a snapshot holds the obligations of ONE saga only`() {
            val foreign = obligation().copy(sagaId = SagaId.of("another-saga"))

            val failure = runCatching { snapshotOf(foreign) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `a promise may be dispatched, confirmed and compensated`() {
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.ARMED,
                    CompensationObligationStatus.EFFECT_PENDING,
                ),
            )
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.EFFECT_PENDING,
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                ),
            )
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                    CompensationObligationStatus.COMPENSATED,
                ),
            )
        }

        @Test
        fun `a promise whose effect was never confirmed cannot become compensated`() {
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.ARMED,
                    CompensationObligationStatus.COMPENSATED,
                ),
            )
        }

        @Test
        fun `a discharged promise is not reopened`() {
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATED,
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                ),
            )
        }

        @Test
        fun `a compensation that failed is not turned into an indeterminate one`() {
            // NEEDS_INTERVENTION says "the outcome is indeterminate". A compensation that is
            // known to have FAILED is not indeterminate, and losing that fact would leave the
            // operator unable to tell whether the resource is back.
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATION_FAILED,
                    CompensationObligationStatus.NEEDS_INTERVENTION,
                ),
            )
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATION_UNCERTAIN,
                    CompensationObligationStatus.NEEDS_INTERVENTION,
                ),
            )
        }

        @Test
        fun `writing the state an obligation already holds is allowed`() {
            // A retry after a crash writes the same fact twice, and refusing that would make
            // the write path fragile for no reason. The states that mean "a call is on its way
            // out" are the exception, and they have their own cases below.
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                ),
            )
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATED,
                    CompensationObligationStatus.COMPENSATED,
                ),
            )
        }

        @Test
        fun `an effect that is on its way out is not authorised a second time`() {
            // EFFECT_PENDING is the write-ahead of the forward effect: the call LEFT the
            // process and its answer has not arrived. Re-writing it after a crash is not a
            // retry, it is a second authorisation — and the resumed process would dispatch
            // the SAME call again. The effect would happen twice, and the row would look
            // exactly like the row of a first attempt.
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.EFFECT_PENDING,
                    CompensationObligationStatus.EFFECT_PENDING,
                ),
            )
        }

        @Test
        fun `a compensation that is on its way out is not run a second time`() {
            // The same for the compensation. "The compensation left and its answer was lost"
            // is not "the compensation did not happen", and a second authorisation would undo
            // the resource twice.
            assertFalse(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATION_PENDING,
                    CompensationObligationStatus.COMPENSATION_PENDING,
                ),
            )
        }

        @Test
        fun `a call that is on its way out may still be answered`() {
            // Refusing the re-write must not freeze the obligation: the answer that finally
            // arrives is a legitimate successor, and the resumed process is the one that
            // brings it.
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.EFFECT_PENDING,
                    CompensationObligationStatus.EFFECT_CONFIRMED,
                ),
            )
            assertTrue(
                CompensationLifecycle.allows(
                    CompensationObligationStatus.COMPENSATION_PENDING,
                    CompensationObligationStatus.COMPENSATED,
                ),
            )
        }

        @Test
        fun `the evidence decides the state`() {
            assertEquals(
                CompensationObligationStatus.EFFECT_CONFIRMED,
                CompensationLifecycle.forwardStatus(
                    EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-1")),
                ),
            )
            assertEquals(
                CompensationObligationStatus.EFFECT_ABSENT,
                CompensationLifecycle.forwardStatus(EffectEvidence.Absent),
            )
            assertEquals(
                CompensationObligationStatus.EFFECT_ABSENT,
                CompensationLifecycle.forwardStatus(EffectEvidence.NotAttempted),
            )
            assertEquals(
                CompensationObligationStatus.EFFECT_UNKNOWN,
                CompensationLifecycle.forwardStatus(
                    EffectEvidence.Unknown(UncertaintyReason.ACK_LOST),
                ),
            )
        }

        @Test
        fun `the observation decides the state, and only a confirmed one has a receipt`() {
            assertEquals(
                CompensationObligationStatus.COMPENSATED,
                CompensationLifecycle.compensationStatus(
                    CompensationObservation.Confirmed(EffectReceiptRef.of("rcpt-2")),
                ),
            )
            assertEquals(
                EffectReceiptRef.of("rcpt-2"),
                CompensationLifecycle.receiptOf(
                    CompensationObservation.Confirmed(EffectReceiptRef.of("rcpt-2")),
                ),
            )
            assertEquals(
                CompensationObligationStatus.COMPENSATION_FAILED,
                CompensationLifecycle.compensationStatus(
                    CompensationObservation.Failed("provider refused"),
                ),
            )
            assertEquals(
                null,
                CompensationLifecycle.receiptOf(CompensationObservation.Failed("provider refused")),
            )
            assertEquals(
                CompensationObligationStatus.COMPENSATION_UNCERTAIN,
                CompensationLifecycle.compensationStatus(
                    CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST),
                ),
            )
        }
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
    }
}
