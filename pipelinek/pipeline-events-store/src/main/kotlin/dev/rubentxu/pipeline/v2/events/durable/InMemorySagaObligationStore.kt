package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.SagaId

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

    override fun arm(row: SagaObligationRow): SagaArmPersistence = synchronized(lock) {
        val mark = highWaterMark(row.runId)
        if (mark > row.leaseEpoch) {
            return@synchronized SagaArmPersistence.Fenced(mark)
        }
        val key = StoreKey(row.runId, CompensationKey(row.sagaId, row.actionId, row.compensationId))
        val stored = rows[key]
        if (stored != null) {
            return@synchronized SagaArmPersistence.Existing(stored)
        }
        rows[key] = row
        SagaArmPersistence.Inserted(row)
    }

    override fun updateOutcome(update: SagaObligationUpdate): SagaUpdatePersistence = synchronized(lock) {
        val mark = highWaterMark(update.runId)
        if (mark > update.leaseEpoch) {
            return@synchronized SagaUpdatePersistence.Fenced(mark)
        }
        val key = StoreKey(update.runId, update.key)
        val stored = rows[key] ?: return@synchronized SagaUpdatePersistence.NotFound
        if (isForeignOwner(stored, update.ownerPluginId)) {
            return@synchronized SagaUpdatePersistence.ForeignOwner(stored)
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
        val mark = highWaterMark(runId)
        if (mark > leaseEpoch) {
            return@synchronized SagaBulkMove.Fenced(mark)
        }
        val keys = rows.keys.filter { it.runId == runId && it.key.sagaId == sagaId }
        val moved = rows.keys.filter {
            val row = rows[it] ?: return@filter false
            row.runId == runId && row.sagaId == sagaId && row.status == from
        }
        // The bulk move is all or nothing: one promise that belongs to another plugin stops the
        // whole move, instead of leaving the Saga half-closed.
        val foreign = moved
            .mapNotNull { rows[it] }
            .firstOrNull { isForeignOwner(it, ownerPluginId) }
        if (foreign != null) {
            return@synchronized SagaBulkMove.ForeignOwner(foreign)
        }
        for (key in moved) {
            val row = rows[key] ?: continue
            rows[key] = row.copy(status = to, updatedAtMs = updatedAtMs)
        }
        val remaining = keys.count { key -> rows[key]?.status == from }
        SagaBulkMove.Applied(moved = moved.size, remaining = remaining)
    }

    override fun find(runId: String, key: CompensationKey): SagaObligationRow? =
        synchronized(lock) { rows[StoreKey(runId, key)] }

    override fun listForRun(runId: String): List<SagaObligationRow> = synchronized(lock) {
        rows.values.filter { it.runId == runId }
    }
}
