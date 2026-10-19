package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint

/**
 * One durable row of a Saga compensation obligation.
 *
 * ## Why this is a row and not the domain value
 *
 * The domain value ([dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation]) is what
 * crosses into plugin hands and it deliberately has no run in it. The row has one, because the
 * durable key is `(run_id, saga_id, action_id, compensation_id)` and the store needs the run to
 * address it. Keeping the two apart is what stops a plugin-visible type from becoming a handle
 * on another run's data.
 *
 * The row carries the SAME vocabulary as the domain value — the typed ids, the typed kind, the
 * typed status — so that nothing has to be re-parsed out of a string and nothing can be stored
 * in a state the domain does not know.
 */
data class SagaObligationRow(
    val runId: String,
    val sagaId: SagaId,
    val actionId: SagaActionId,
    val compensationId: CompensationId,
    val intentVersion: Int,
    val planFingerprint: SagaPlanFingerprint,
    val resourceRef: ResourceRef,
    val kind: CompensationKind,
    val status: CompensationObligationStatus,
    val forwardOpId: String,
    val compensationOpId: String,
    val leaseEpoch: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val receiptRef: EffectReceiptRef?,
)

/**
 * What an [SagaObligationStore.arm] call actually did.
 *
 * The store does not decide whether a second arm is acceptable: it records what happened at the
 * storage level and leaves the judgement to the pure decider. That split is deliberate — the
 * idempotency rule ("the same promise is fine, a different promise under the same key is a
 * contradiction") is a rule about MEANING, and meaning does not belong in a JDBC adapter.
 */
sealed interface SagaArmPersistence {

    /** The row was written by this call. */
    data class Inserted(val row: SagaObligationRow) : SagaArmPersistence

    /** A row was already stored under the key; this call changed nothing. */
    data class Existing(val row: SagaObligationRow) : SagaArmPersistence

    /**
     * Nothing was written and nothing is stored under the key: the write was refused because a
     * newer epoch already owns the run.
     *
     * [currentEpoch] is the run's high-water mark at the moment of refusal, so the caller can
     * report WHICH epoch superseded it rather than only that it was superseded.
     */
    data class Fenced(val currentEpoch: Long) : SagaArmPersistence
}

/**
 * The durable store of Saga compensation obligations.
 *
 * ## Same authority, no second coordination
 *
 * The obligations live in the SAME SQLite database as the run's event log and operation journal,
 * and are written by the same process that writes them. There is no second store, no second
 * transaction model and no second source of truth: an obligation that is not in this table was
 * never promised, and an obligation in this table is the promise, whatever the event log says.
 */
interface SagaObligationStore {

    /**
     * Records [row] under its key IF the key is free, and reports what the store holds.
     *
     * The call is idempotent by construction: arming the same key twice does not overwrite the
     * first promise and does not fail. The write is also FENCED: a writer whose lease epoch is
     * older than the newest epoch already recorded for the run is refused, because that writer
     * has lost the run to a takeover.
     */
    fun arm(row: SagaObligationRow): SagaArmPersistence

    /** The row stored under [runId]/[key], or `null` when there is none. */
    fun find(
        runId: String,
        key: dev.rubentxu.pipeline.v2.domain.saga.CompensationKey,
    ): SagaObligationRow?

    /** Every obligation recorded for [runId], oldest first. */
    fun listForRun(runId: String): List<SagaObligationRow>
}
