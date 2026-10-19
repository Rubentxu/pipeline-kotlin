package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.saga.ArmRejection
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationOperations
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.SagaDecider
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationIdentity
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationPhase
import dev.rubentxu.pipeline.v2.domain.saga.SagaDecision
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.SagaArmPersistence
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationRow
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationStore
import java.util.UUID

/**
 * The per-RUN Saga seam the dispatcher needs to build an invocation-bound intent port.
 *
 * Two facts, and both are per run rather than per step: the durable store that holds the
 * obligations, and the lease epoch of the process that owns the run. The epoch is what fences
 * a writer that lost the run to a takeover: the store refuses a write whose epoch is behind
 * the run's high-water mark.
 */
data class SagaCompensationBinding(
    val store: SagaObligationStore,
    val leaseEpoch: Long,
)

/**
 * The runtime implementation of the Saga intent port.
 *
 * ## Bound to one invocation
 *
 * The instance carries the run, the forward operation it belongs to and the lease epoch of the
 * writer. A plugin receives it through the capability bridge and cannot name a different run:
 * the run is not an argument of any operation, it is part of the object. That is what makes
 * "the context is bound to the invocation" a property of the type rather than a convention that
 * a well-behaved plugin is asked to respect.
 *
 * ## The commit boundary
 *
 * [arm] writes the obligation and only answers once the write has COMMITTED. The write is a
 * single SQLite statement against the run's own database, so when [arm] returns
 * [CompensationArmOutcome.Armed] the promise is already durable — it survives a crash of this
 * process, and a later process finds it by reading the same store.
 *
 * The event comes AFTER the commit, never before. An event that announced a promise which then
 * failed to persist would be a promise nobody could keep, so the order is not negotiable: the
 * durable row is the promise, and the event is its notification.
 */
class SagaCompensationOperations(
    private val runId: String,
    private val forwardOpId: String,
    private val leaseEpoch: Long,
    private val store: SagaObligationStore,
    private val eventSink: EventSink,
    private val clock: Clock,
) : CompensationOperations {

    override fun arm(declaration: CompensationDeclaration): CompensationArmOutcome {
        if (!SagaIntentContract.supports(declaration.intentVersion)) {
            return CompensationArmOutcome.Rejected(
                ArmRejection.UnsupportedIntentVersion(declaration.intentVersion),
            )
        }

        val now = clock.now()
        val nowMs = now.toEpochMilli()
        val requested = CompensationObligation(
            sagaId = declaration.sagaId,
            actionId = declaration.actionId,
            compensationId = declaration.compensationId,
            planFingerprint = declaration.planFingerprint,
            resourceRef = declaration.resourceRef,
            kind = declaration.kind,
            intentVersion = declaration.intentVersion,
            forwardOpId = forwardOpId,
            compensationOpId = SagaOperationIdentity.derive(
                forwardOpId = forwardOpId,
                phase = SagaOperationPhase.COMPENSATE,
                compensationId = declaration.compensationId,
            ),
            status = CompensationObligationStatus.ARMED,
            leaseEpoch = leaseEpoch,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )

        return when (val persistence = store.arm(requested.toRow())) {
            is SagaArmPersistence.Inserted -> {
                // The obligation is committed. Only now is it honest to say so out loud.
                emitArmed(requested)
                SagaDecider.armOutcome(existing = null, requested = requested)
            }

            is SagaArmPersistence.Existing ->
                SagaDecider.armOutcome(
                    existing = persistence.row.toObligation(),
                    requested = requested,
                )

            is SagaArmPersistence.Fenced -> CompensationArmOutcome.Rejected(
                ArmRejection.LeaseSuperseded(
                    heldEpoch = leaseEpoch,
                    currentEpoch = persistence.currentEpoch,
                ),
            )
        }
    }

    override fun read(key: CompensationKey): CompensationObligation? =
        store.find(runId, key)?.toObligation()

    override fun decide(
        obligation: CompensationObligation?,
        evidence: EffectEvidence,
        policy: CompensationPolicy,
    ): SagaDecision = SagaDecider.decide(obligation, evidence, policy)

    /**
     * Announces the committed obligation.
     *
     * Emitted ONLY for the write that created the row. A second arm of the same promise is the
     * same promise, and announcing it twice would put two notifications in the plane for one
     * obligation. If this emission fails, the obligation still stands: the row is the truth and
     * the run is reconstructed from the store.
     */
    private fun emitArmed(obligation: CompensationObligation) {
        eventSink.append(
            CompensationArmed(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = clock.now(),
                sagaId = obligation.sagaId.value,
                actionId = obligation.actionId.value,
                compensationId = obligation.compensationId.value,
                planFingerprint = obligation.planFingerprint.hex,
                intentVersion = obligation.intentVersion,
                resourceRef = obligation.resourceRef.value,
                compensationOpId = obligation.compensationOpId,
                leaseEpoch = obligation.leaseEpoch,
            ),
        )
    }

    private fun CompensationObligation.toRow(): SagaObligationRow = SagaObligationRow(
        runId = runId,
        sagaId = sagaId,
        actionId = actionId,
        compensationId = compensationId,
        intentVersion = intentVersion,
        planFingerprint = planFingerprint,
        resourceRef = resourceRef,
        kind = kind,
        status = status,
        forwardOpId = forwardOpId,
        compensationOpId = compensationOpId,
        leaseEpoch = leaseEpoch,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        receiptRef = null,
    )

    private fun SagaObligationRow.toObligation(): CompensationObligation = CompensationObligation(
        sagaId = sagaId,
        actionId = actionId,
        compensationId = compensationId,
        planFingerprint = planFingerprint,
        resourceRef = resourceRef,
        kind = kind,
        intentVersion = intentVersion,
        forwardOpId = forwardOpId,
        compensationOpId = compensationOpId,
        status = status,
        leaseEpoch = leaseEpoch,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}
