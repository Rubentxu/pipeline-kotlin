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
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordRejection
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The Saga PLAN port, exercised against a real SQLite file.
 *
 * The file is real on purpose: the guarantee under test is that the STATE a plugin reports is
 * the state that survives, and that the answer a later process gets is derived from that
 * state rather than from anything the first process remembered.
 */
class SagaPlanOperationsAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store() = SqliteSagaObligationStore(
        connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
        dbPath = dbPath(),
    )

    private fun plan(leaseEpoch: Long = 1L) = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = leaseEpoch,
        store = store(),
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
            store = store(),
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
    fun `the receipt that was reported is the receipt that is durable`() {
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

    @Test
    fun `the state a later process reads is the state the first one wrote`() {
        arm()
        plan().recordForward(KEY, EffectEvidence.Unknown(UncertaintyReason.ACK_LOST))

        // A DIFFERENT adapter over the SAME file: this is what a process that resumes the run
        // after a crash gets to see.
        val directive = plan().next(SAGA, CompensationPolicy.NotReconcilable)

        val intervention = assertInstanceOf<CompensationDirective.NeedsIntervention>(directive)
        assertEquals(InterventionReason.UNKNOWN_EFFECT, intervention.reason)
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
