package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordRejection
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.time.Instant

/**
 * The Saga PLAN port, exercised against the store that keeps no file.
 *
 * ## What this file is for
 *
 * The guarantee under test is the ALGORITHM of the plan port: what a Saga owes, what a
 * compensation discharges, which transition is refused, and which writer is fenced out. None
 * of those answers is a fact about a file — they are decisions the port takes — so they are
 * asked of the VOLATILE store the product already ships, the one a run without `--db` binds.
 * The store is the product's, not a double of it.
 *
 * ## Where the durability went
 *
 * The other half of the port has its own home: the state a LATER process reads is the state
 * that SURVIVES, and that is a fact about the file, so the durable store's own test
 * (SqliteSagaObligationStoreTest) asks it what it leaves behind once its writer is gone. The
 * two files answer two different questions, and neither one is asked twice.
 */
class SagaPlanOperationsAdapterTest {

    private val store = InMemorySagaObligationStore()

    private fun plan(leaseEpoch: Long = 1L) = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = leaseEpoch,
        store = store,
        clock = FixedClock,
    )

    /**
     * Arms the promise through the INTENT port, so the two seams are exercised together: the
     * promise the plan port moves is the promise the intent port made.
     */
    private fun arm(leaseEpoch: Long = 1L) {
        val operations = SagaCompensationOperations(
            runId = "run-1",
            forwardOpId = "run-1|stage-0|step-3",
            leaseEpoch = leaseEpoch,
            store = store,
            eventSink = NullEventSink,
            clock = FixedClock,
        )
        val outcome = operations.arm(
            CompensationDeclaration(
                sagaId = SAGA,
                actionId = SagaActionId.of("reserve-stock"),
                compensationId = CompensationId.of("release-stock"),
                planFingerprint = PLAN_A,
                resourceRef = ResourceRef.of("stock/reservation-42"),
                kind = CompensationKind.RESTORE,
            ),
        )
        assertInstanceOf<CompensationArmOutcome.Armed>(outcome)
    }

    @Test
    fun `a Saga with nothing armed owes nothing`() {
        val directive = plan().next(SAGA, CompensationPolicy.NotReconcilable)

        assertEquals(CompensationDirective.Settled(SAGA), directive)
    }

    @Test
    fun `a confirmed effect is owed, and the compensation discharges it`() {
        arm()
        val plan = plan()

        plan.beginForward(KEY)
        plan.recordForward(KEY, EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-1")))

        val owed = plan.next(SAGA, CompensationPolicy.NotReconcilable)
        assertInstanceOf<CompensationDirective.Compensate>(owed)

        plan.beginCompensation(KEY)
        plan.recordCompensation(KEY, CompensationObservation.Confirmed(EffectReceiptRef.of("rcpt-2")))

        val after = plan.next(SAGA, CompensationPolicy.NotReconcilable)
        assertEquals(CompensationDirective.Settled(SAGA), after)
    }

    @Test
    fun `the receipt that was reported is the receipt that comes back`() {
        arm()
        val plan = plan()

        val recorded = plan.recordForward(KEY, EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-1")))

        val outcome = assertInstanceOf<SagaRecordOutcome.Recorded>(recorded)
        assertEquals(CompensationObligationStatus.EFFECT_CONFIRMED, outcome.obligation.status)
        assertEquals("rcpt-1", outcome.receiptRef?.value)
    }

    @Test
    fun `a compensation that was never owed is refused`() {
        arm()
        val plan = plan()

        // The forward effect was never confirmed, so there is nothing to compensate: writing
        // "compensated" here would be a compensation of something that never happened.
        val outcome = plan.recordCompensation(KEY, CompensationObservation.Confirmed(null))

        val refused = assertInstanceOf<SagaRecordOutcome.Refused>(outcome)
        val illegal = assertInstanceOf<SagaRecordRejection.IllegalTransition>(refused.reason)
        assertEquals(CompensationObligationStatus.ARMED, illegal.from)
        assertEquals(CompensationObligationStatus.COMPENSATED, illegal.to)
    }

    @Test
    fun `a key that was never armed is refused`() {
        val outcome = plan().recordForward(KEY, EffectEvidence.Absent)

        val refused = assertInstanceOf<SagaRecordOutcome.Refused>(outcome)
        assertEquals(SagaRecordRejection.UnknownObligation, refused.reason)
    }

    @Test
    fun `a writer that lost the run is refused`() {
        arm(leaseEpoch = 5L)
        val stale = plan(leaseEpoch = 2L)

        val outcome = stale.recordForward(KEY, EffectEvidence.Absent)

        val refused = assertInstanceOf<SagaRecordOutcome.Refused>(outcome)
        val superseded = assertInstanceOf<SagaRecordRejection.LeaseSuperseded>(refused.reason)
        assertEquals(2L, superseded.heldEpoch)
        assertEquals(5L, superseded.currentEpoch)
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val KEY = CompensationKey(
            sagaId = SAGA,
            actionId = SagaActionId.of("reserve-stock"),
            compensationId = CompensationId.of("release-stock"),
        )
    }
}
