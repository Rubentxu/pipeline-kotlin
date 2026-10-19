package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationLifecycle
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaDecider
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordRejection
import dev.rubentxu.pipeline.v2.domain.saga.SagaSnapshot
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationUpdate
import dev.rubentxu.pipeline.v2.events.durable.SagaUpdatePersistence

/**
 * The runtime implementation of the Saga PLAN port.
 *
 * ## Bound to one run
 *
 * The instance carries the run and the lease epoch of the process that owns it. A plugin
 * cannot name a different run: the run is not an argument of any operation, it is part of the
 * object. The epoch is what fences a writer that lost the run to a takeover — the store
 * refuses the write rather than letting two processes believe they both moved the same
 * obligation.
 *
 * ## The decision is derived, not stored
 *
 * [next] reads the durable obligations and hands them to [SagaDecider.nextCompensation]. The
 * adapter decides nothing: the rule about what is owed lives in the domain, where it can be
 * exercised directly, and the adapter's job is to make sure the rule is applied to the state
 * that is REALLY on disk.
 *
 * ## The write path is checked twice
 *
 * A record is refused BEFORE it is written when the lifecycle does not allow the move, and the
 * store refuses it AGAIN if the writer has lost the lease. The first check is about meaning —
 * "a promise whose forward effect was never confirmed cannot become compensated" — and the
 * second is about ownership. Neither of them can be worked around by the other.
 */
class SagaPlanOperationsAdapter(
    private val runId: String,
    private val leaseEpoch: Long,
    private val store: SagaObligationStore,
    private val clock: Clock,
) : SagaPlanOperations {

    override fun next(sagaId: SagaId, policy: CompensationPolicy) =
        SagaDecider.nextCompensation(snapshotOf(sagaId), policy)

    override fun beginForward(key: CompensationKey): SagaRecordOutcome =
        moveTo(key, CompensationObligationStatus.EFFECT_PENDING, receiptRef = null)

    override fun recordForward(key: CompensationKey, evidence: EffectEvidence): SagaRecordOutcome =
        moveTo(
            key = key,
            status = CompensationLifecycle.forwardStatus(evidence),
            receiptRef = CompensationLifecycle.receiptOf(evidence),
        )

    override fun beginCompensation(key: CompensationKey): SagaRecordOutcome =
        moveTo(key, CompensationObligationStatus.COMPENSATION_PENDING, receiptRef = null)

    override fun recordCompensation(
        key: CompensationKey,
        observation: CompensationObservation,
    ): SagaRecordOutcome = moveTo(
        key = key,
        status = CompensationLifecycle.compensationStatus(observation),
        receiptRef = CompensationLifecycle.receiptOf(observation),
    )

    /**
     * The durable obligations of [sagaId], as the decision sees them.
     *
     * The run's rows are the whole truth: an obligation that is not there was never promised,
     * and one that is there is the promise whatever the event log says.
     */
    private fun snapshotOf(sagaId: SagaId): SagaSnapshot = SagaSnapshot(
        sagaId = sagaId,
        obligations = store.listForRun(runId)
            .filter { it.sagaId == sagaId }
            .map { it.toObligation() },
    )

    /**
     * Moves the obligation under [key] to [status], if the lifecycle allows it.
     */
    private fun moveTo(
        key: CompensationKey,
        status: CompensationObligationStatus,
        receiptRef: EffectReceiptRef?,
    ): SagaRecordOutcome {
        val existing = store.find(runId, key)
            ?: return SagaRecordOutcome.Refused(SagaRecordRejection.UnknownObligation)

        if (!CompensationLifecycle.allows(existing.status, status)) {
            return SagaRecordOutcome.Refused(
                SagaRecordRejection.IllegalTransition(existing.status, status),
            )
        }

        val update = SagaObligationUpdate(
            runId = runId,
            key = key,
            status = status,
            receiptRef = receiptRef,
            updatedAtMs = clock.now().toEpochMilli(),
            leaseEpoch = leaseEpoch,
        )

        return when (val persistence = store.updateOutcome(update)) {
            is SagaUpdatePersistence.Updated -> SagaRecordOutcome.Recorded(
                obligation = persistence.row.toObligation(),
                receiptRef = persistence.row.receiptRef,
            )

            is SagaUpdatePersistence.NotFound ->
                SagaRecordOutcome.Refused(SagaRecordRejection.UnknownObligation)

            is SagaUpdatePersistence.Fenced -> SagaRecordOutcome.Refused(
                SagaRecordRejection.LeaseSuperseded(
                    heldEpoch = leaseEpoch,
                    currentEpoch = persistence.currentEpoch,
                ),
            )
        }
    }
}
