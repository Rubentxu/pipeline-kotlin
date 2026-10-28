package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract

/**
 * The VOLATILE store of Saga compensation obligations: the same port, held in memory.
 *
 * ## Why this exists
 *
 * A Saga is a promise about compensation, and the promise has to be kept BY THE SAME RULES
 * whether the run has a durable store or not. Before this store, the in-memory branch of the
 * CLI bound NO saga store at all: the Saga capabilities were never exposed, and a script that
 * declares them was refused at admission. The same script therefore behaved differently with
 * and without `--db`, which is exactly the contradiction forbids — "storage choice must
 * not select a different execution algorithm".
 *
 * The refusal was not wrong in itself (a promise nobody can keep is worse than no promise), but
 * it was an accident of WIRING, not a decision: the capability was missing because nobody
 * composed a store, and it failed with a diagnostic that said nothing about `--db`. A decision
 * that is not written down is a decision nobody can rely on.
 *
 * ## What it does NOT give
 *
 * Durability. An obligation armed here dies with the process, exactly like the journal and the
 * event log of the in-memory branch. What it gives is the ALGORITHM: the same fencing, the
 * same idempotence, the same bulk move and therefore the same verdicts. A Saga that runs in
 * memory compensates in memory, and the run says which world it lived in.
 *
 * ## The rules it keeps, and why they are the same ones
 *
 * The rules are the ones [SagaObligationStore] declares, and they are not a promise but a
 * measurement: [SagaObligationStoreContractTest] asks this store and the durable one the same
 * questions and fails when the answers differ. They used to differ — this store kept the arm
 * moment verbatim, so two promises armed in the same millisecond came back in an order the
 * durable store never produces, and the compensation order is the reverse of the arm order —
 * and it accepted an intent version this build does not honour, which is a fail-open where the
 * durable path fails closed.
 *
 * - **Fencing**: a writer whose epoch is below the run's high-water mark (the newest
 *   `lease_epoch` among the run's rows) is refused, because it lost the run to a takeover.
 *   The high-water mark is derived from the ROWS, never from a separate counter, so the mark
 *   and the promises can never drift apart.
 * - **Idempotence**: arming the same key twice does not overwrite the first promise and does
 *   not fail. Which promise is acceptable is the DOMAIN's judgement, not the store's.
 * - **The bulk move**: closing a Saga moves ALL of its obligations in one step, so the Saga is
 *   either closed or it is not — never half closed.
 */
class InMemorySagaObligationStore : SagaObligationStore {

    /**
     * One lock for the whole store.
     *
     * The durable store gets its serialisation from a single SQLite writer; the volatile one
     * has to say so explicitly, because a run may arm an obligation from a worker thread while
     * the engine reads the plan from another. A concurrent map alone would not do: the fencing
     * decision and the write it guards have to be ONE step, exactly as the durable store's
     * guard is one statement.
     */
    private val lock = Any()

    /** The rows of every run, in the order they were armed. */
    private val rows: MutableMap<StoreKey, SagaObligationRow> = LinkedHashMap()

    /** The durable key of an obligation: the run plus the key inside it. */
    private data class StoreKey(val runId: String, val key: CompensationKey)

    /**
     * The run's high-water mark: the newest `lease_epoch` among its rows, or -1 when it has
     * none. -1 rather than 0 because epoch 0 is a real epoch (the one a run without a lease
     * has), and a writer must be able to arm its first obligation.
     */
    private fun highWaterMark(runId: String): Long =
        rows.values
            .filter { it.runId == runId }
            .maxOfOrNull { it.leaseEpoch }
            ?: -1L

    /**
     * The moment the next promise of this Saga is armed at.
     *
     * The arm moment IS the arm order, and the compensation order is the reverse of it, so two
     * promises armed in the same millisecond must still get a defined order — and the order
     * they were armed in, not the order their identifiers happen to sort into.
     *
     * The rule is the one the durable store applies inside its INSERT: the moment is the
     * later of what the caller asked for and one past the newest moment of the Saga. It drifts
     * from the wall clock by at most a millisecond, and only when two arms collide.
     */
    private fun nextArmMoment(row: SagaObligationRow): Long {
        val newestOfSaga = rows.values
            .filter { it.runId == row.runId && it.sagaId == row.sagaId }
            .maxOfOrNull { it.createdAtMs }
        return maxOf(row.createdAtMs, (newestOfSaga ?: 0L) + 1L)
    }

    override fun arm(row: SagaObligationRow): SagaArmPersistence = synchronized(lock) {
        // The arm fails closed: a promise the engine cannot honour is refused, not stored.
        require(row.runId.isNotBlank()) { "runId must not be blank" }
        require(row.leaseEpoch >= 0) { "leaseEpoch must not be negative, got ${row.leaseEpoch}" }
        SagaIntentContract.requireSupported(row.intentVersion)

        val key = StoreKey(row.runId, CompensationKey(row.sagaId, row.actionId, row.compensationId))
        val stored = rows[key]
        if (stored != null) {
            // The promise is already there, so the caller is told what is there. Whether this
            // writer is behind the run's mark does not change THAT: the promise exists, and
            // arming the same key again is the caller's way of asking what is stored under it.
            return@synchronized SagaArmPersistence.Existing(stored)
        }
        val mark = highWaterMark(row.runId)
        if (mark > row.leaseEpoch) {
            return@synchronized SagaArmPersistence.Fenced(mark)
        }
        val stamped = row.copy(createdAtMs = nextArmMoment(row))
        rows[key] = stamped
        SagaArmPersistence.Inserted(stamped)
    }

    override fun updateOutcome(update: SagaObligationUpdate): SagaUpdatePersistence = synchronized(lock) {
        require(update.runId.isNotBlank()) { "runId must not be blank" }
        require(update.leaseEpoch >= 0) { "leaseEpoch must not be negative, got ${update.leaseEpoch}" }

        val key = StoreKey(update.runId, update.key)
        val stored = rows[key] ?: return@synchronized SagaUpdatePersistence.NotFound
        // The ownership of the promise is judged BEFORE the epoch: a promise that belongs to
        // another plugin is refused for THAT reason, and the reason a caller can act on is the
        // one that names who holds the promise.
        if (isForeignOwner(stored, update.ownerPluginId)) {
            return@synchronized SagaUpdatePersistence.ForeignOwner(stored)
        }
        val mark = highWaterMark(update.runId)
        if (mark > update.leaseEpoch) {
            return@synchronized SagaUpdatePersistence.Fenced(mark)
        }
        // Only the STATE, the RECEIPT and the moment change: the promise keeps its identity,
        // its plan and the epoch that armed it.
        val moved = stored.copy(
            status = update.status,
            receiptRef = update.receiptRef,
            updatedAtMs = update.updatedAtMs,
        )
        rows[key] = moved
        SagaUpdatePersistence.Updated(moved)
    }

    override fun moveAll(
        runId: String,
        sagaId: SagaId,
        from: CompensationObligationStatus,
        to: CompensationObligationStatus,
        updatedAtMs: Long,
        leaseEpoch: Long,
        ownerPluginId: String?,
    ): SagaBulkMove = synchronized(lock) {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(leaseEpoch >= 0) { "leaseEpoch must not be negative, got $leaseEpoch" }

        val keys = rows.keys.filter { it.runId == runId && it.key.sagaId == sagaId }
        val moving = keys.filter { rows[it]?.status == from }
        // The bulk move is all or nothing: one promise that belongs to another plugin stops the
        // whole move, instead of leaving the Saga half-closed. The ownership is judged BEFORE
        // the epoch, so the refusal names the plugin that holds the promise.
        val foreign = moving
            .mapNotNull { rows[it] }
            .firstOrNull { isForeignOwner(it, ownerPluginId) }
        if (foreign != null) {
            return@synchronized SagaBulkMove.ForeignOwner(foreign)
        }
        val mark = highWaterMark(runId)
        val moved = if (mark > leaseEpoch) {
            // The writer lost the run: it moves nothing, and every row stays where it was.
            0
        } else {
            for (key in moving) {
                val row = rows[key] ?: continue
                rows[key] = row.copy(status = to, updatedAtMs = updatedAtMs)
            }
            moving.size
        }
        // What the store says NOW, not what the caller intended: the rows that are STILL in the
        // state the move started from.
        val remaining = keys.count { key -> rows[key]?.status == from }
        if (moved == 0 && remaining > 0 && mark > leaseEpoch) {
            return@synchronized SagaBulkMove.Fenced(mark)
        }
        SagaBulkMove.Applied(moved = moved, remaining = remaining)
    }

    override fun find(runId: String, key: CompensationKey): SagaObligationRow? =
        synchronized(lock) { rows[StoreKey(runId, key)] }

    override fun listForRun(runId: String): List<SagaObligationRow> = synchronized(lock) {
        rows.values
            .filter { it.runId == runId }
            .sortedWith(
                compareBy(
                    { it.createdAtMs },
                    { it.sagaId.value },
                    { it.actionId.value },
                    { it.compensationId.value },
                ),
            )
    }
}
