package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationBodyDigest
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaOwnershipVerdict
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOrigin
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOwnership

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
    /**
     * The plugin that armed this promise.
     *
     * `null` when the build that wrote the row did not record it: such a promise cannot prove a
     * conflict, so it is not treated as owned by anyone.
     */
    val ownerPluginId: String?,
    /**
     * The shape of the body this promise is paid with.
     *
     * `null` when the build that wrote the row did not measure one — a promise that names no
     * body, or a promise armed by a build that did not keep digests. Such a promise cannot
     * prove a divergence, so it does not block anything.
     */
    val bodyDigest: CompensationBodyDigest? = null,
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
 * The durable write that moves ONE obligation to a new state.
 *
 * ## Why a write and not a whole row
 *
 * The row is the store's business: it carries the run, the plan and the timestamps of the
 * moment the promise was made, and none of that changes when the promise is discharged. What
 * changes is the STATE and the receipt that belongs with it, so the write carries exactly
 * those two facts plus the epoch that fences it.
 */
data class SagaObligationUpdate(
    val runId: String,
    val key: dev.rubentxu.pipeline.v2.domain.saga.CompensationKey,
    val status: CompensationObligationStatus,
    val receiptRef: EffectReceiptRef?,
    val updatedAtMs: Long,
    val leaseEpoch: Long,
    /**
     * The plugin that writes, when it states one.
     *
     * A writer that names a plugin only writes to promises that plugin owns. A writer that names
     * nobody is not claiming ownership, so nothing is contradicted.
     */
    val ownerPluginId: String? = null,
)

/**
 * What an [SagaObligationStore.updateOutcome] call actually did.
 *
 * As with the arm, the store reports the STORAGE outcome and leaves the judgement to the
 * domain: whether the move is legal is decided by
 * [dev.rubentxu.pipeline.v2.domain.saga.CompensationLifecycle], not by a JDBC adapter.
 */
sealed interface SagaUpdatePersistence {

    /** The row now holds the new state. */
    data class Updated(val row: SagaObligationRow) : SagaUpdatePersistence

    /** No row is stored under that key, so nothing was written. */
    data object NotFound : SagaUpdatePersistence

    /**
     * Nothing was written because the promise belongs to another plugin.
     *
     * The row is the truth about who holds the promise, so the refusal can name it.
     */
    data class ForeignOwner(val row: SagaObligationRow) : SagaUpdatePersistence

    /**
     * Nothing was written because a newer epoch already owns the run.
     *
     * [currentEpoch] is the run's high-water mark at the moment of refusal, so the caller can
     * report WHICH epoch superseded it rather than only that it was superseded.
     */
    data class Fenced(val currentEpoch: Long) : SagaUpdatePersistence
}

/**
 * What an [SagaObligationStore.moveAll] call actually did.
 *
 * As with the arm and the single update, the store reports the STORAGE outcome and leaves the
 * judgement to the domain: whether the move is legal is decided by
 * [dev.rubentxu.pipeline.v2.domain.saga.CompensationLifecycle], not by a JDBC adapter.
 */
sealed interface SagaBulkMove {

    /**
     * The statement ran.
     *
     * @param moved how many rows changed.
     * @param remaining how many rows of the Saga are STILL in the state the move started from.
     * A caller that needs every row moved reads this number, not the intention behind the
     * call: it is what the database says, not what the caller hoped.
     */
    data class Applied(val moved: Int, val remaining: Int) : SagaBulkMove

    /**
     * Nothing was written: one of the obligations of the Saga belongs to another plugin.
     *
     * The bulk move is all or nothing, so one promise that is not the writer's stops the whole
     * move: a Saga is not closed halfway.
     */
    data class ForeignOwner(val row: SagaObligationRow) : SagaBulkMove

    /** Nothing was written: a newer epoch already owns the run. */
    data class Fenced(val currentEpoch: Long) : SagaBulkMove
}

/**
 * Whether the promise belongs to a plugin other than the one that writes.
 *
 * The judgement is the DOMAIN's: this is the rule about who owns a promise, and it lives in one
 * place so that the durable store and the volatile one cannot drift apart.
 */
internal fun isForeignOwner(row: SagaObligationRow, writerPluginId: String?): Boolean {
    val stored = SagaPromiseOrigin(pluginId = row.ownerPluginId, intentVersion = row.intentVersion)
    val writer = writerPluginId?.let { SagaPromiseOrigin(pluginId = it, intentVersion = row.intentVersion) }
    val verdict = SagaPromiseOwnership.verdict(stored, writer)
    return verdict is SagaOwnershipVerdict.ForeignOwner
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
 *
 * ## The contract of the port
 *
 * The port has two implementations — the durable one and the volatile one a run without `--db`
 * binds — and the product's rule is that the storage choice must not select a different
 * execution algorithm. That rule only holds if the two implementations ANSWER THE SAME, so
 * the answers are written down here and EXECUTED against both implementations by
 * [SagaObligationStoreContractTest]. What follows is that contract; the tests of each
 * implementation pin the answers themselves.
 *
 * - **The arm is idempotent.** Arming the same key twice keeps the FIRST promise and reports
 *   it. A writer that is behind the run's epoch and asks about a key that already holds a
 *   promise is answered WITH that promise: the promise exists, and the caller is told what is
 *   there rather than that it lost the run.
 * - **The arm is fenced.** A writer whose epoch is below the run's high-water mark — the
 *   newest epoch among the run's rows — writes nothing when the key is free, and is told the
 *   epoch that superseded it.
 * - **The arm moment is strictly increasing inside a Saga.** Two promises armed in the same
 *   millisecond get different moments, in the order they were armed. The compensation order is
 *   the reverse of the arm order, so a tie broken by the identifiers would compensate in an
 *   order nobody declared.
 * - **The arm fails closed.** A blank run id, a negative epoch and an intent version this
 *   build does not honour are refused, not accepted.
 * - **The update reports why it refused.** A key with no promise behind it is `NotFound`; a
 *   promise that belongs to another plugin is `ForeignOwner`, and the refusal names the
 *   holder; a writer behind the epoch is `Fenced`. A writer that names nobody claims no
 *   ownership, so it may write a promise a plugin owns.
 * - **The bulk move is all or nothing.** One promise that belongs to another plugin stops the
 *   whole move, so a Saga is never closed halfway. A writer behind the epoch moves nothing;
 *   when there was nothing to move in the first place, the move says so instead of refusing.
 * - **The run's promises come back in a defined order**: by arm moment, then Saga, action and
 *   compensation.
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

    /**
     * Moves the obligation under [update]'s key to the state [update] carries.
     *
     * The call is FENCED exactly like the arm: a writer that lost the run to a takeover writes
     * nothing. It does NOT decide whether the move is legal — that is the domain's rule — and
     * it does not create a row that is not there: a key with no promise behind it stays
     * unpromised.
     */
    fun updateOutcome(update: SagaObligationUpdate): SagaUpdatePersistence

    /**
     * Moves EVERY obligation of [sagaId] that is in [from] to [to], in ONE statement.
     *
     * ## Why the bulk move exists
     *
     * Closing a Saga well moves ALL of its obligations at once: the effects are kept, so every
     * promise to undo them is void. Moving them one by one would leave the Saga half-closed if
     * the process died in between — some obligations kept and some still owed — and a resumed
     * run would then compensate the ones that were left, undoing part of a Saga that had
     * succeeded.
     *
     * One statement is one transaction, so the Saga is either closed or it is not.
     *
     * The call is FENCED like the others, and it does not decide whether the move is legal: the
     * caller asks for a transition the domain allows.
     */
    fun moveAll(
        runId: String,
        sagaId: SagaId,
        from: CompensationObligationStatus,
        to: CompensationObligationStatus,
        updatedAtMs: Long,
        leaseEpoch: Long,
        ownerPluginId: String? = null,
    ): SagaBulkMove

    /** The row stored under [runId]/[key], or `null` when there is none. */
    fun find(
        runId: String,
        key: dev.rubentxu.pipeline.v2.domain.saga.CompensationKey,
    ): SagaObligationRow?

    /** Every obligation recorded for [runId], oldest first. */
    fun listForRun(runId: String): List<SagaObligationRow>
}
