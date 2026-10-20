package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import java.sql.Connection
import kotlin.concurrent.withLock

/**
 * The SQLite implementation of [SagaObligationStore].
 *
 * ## Same database, same authority
 *
 * The obligations go into the run's own SQLite file — the one that already holds the event log
 * and the operation journal — through the same connection factory. There is no second database
 * and no second lock: a promise to compensate is as durable as the events that record it, and
 * it is reconstructed from the same place after a restart.
 *
 * ## The commit boundary
 *
 * [arm] writes with a single statement. SQLite commits that statement when it returns, so the
 * moment [arm] answers, the obligation is committed — not "written to the log" and not "queued
 * for the next flush". That is the boundary the Saga contract needs: a forward effect may only
 * run behind a promise that is already durable, so the answer to `arm` is the answer to "may I
 * proceed?".
 *
 * The same boundary closes a Saga: [moveAll] moves every obligation of a Saga in ONE
 * statement, so a Saga that is kept is kept for good and one that is undone is undone for
 * good. There is no middle state in which a resumed run finds half a Saga and compensates the
 * half that was left.
 *
 * ## The fencing
 *
 * A writer that lost the run to a takeover must not be able to arm obligations afterwards. The
 * guard is a CONDITION inside the INSERT itself — the epoch check and the write are one
 * statement, so there is no window between reading the epoch and writing the row:
 *
 * ```sql
 * INSERT INTO saga_obligation (...)
 * SELECT ... WHERE (SELECT MAX(lease_epoch) ...) <= ?  -- this writer's epoch
 * ON CONFLICT (...) DO NOTHING
 * ```
 *
 * The epoch of the newest obligation recorded for the run is the run's high-water mark. A
 * writer whose epoch is below it has been superseded, and its write is refused rather than
 * applied. The lease FILE remains the authority on who owns the run; this guard is the durable
 * half of the same check, and it holds even if the file was replaced between the two.
 */
class SqliteSagaObligationStore(
    private val connectionFactory: () -> Connection,
    private val dbPath: String,
) : SagaObligationStore {

    init {
        ensureSchema()
    }

    override fun arm(row: SagaObligationRow): SagaArmPersistence {
        require(row.runId.isNotBlank()) { "runId must not be blank" }
        require(row.leaseEpoch >= 0) { "leaseEpoch must not be negative, got ${row.leaseEpoch}" }
        SagaIntentContract.requireSupported(row.intentVersion)

        return DbLock.forPath(dbPath).withLock {
            val connection = connectionFactory()
            try {
                val inserted = connection.prepareStatement(INSERT_OBLIGATION).use { statement ->
                    statement.setString(1, row.runId)
                    statement.setString(2, row.sagaId.value)
                    statement.setString(3, row.actionId.value)
                    statement.setString(4, row.compensationId.value)
                    statement.setInt(5, row.intentVersion)
                    statement.setString(6, row.planFingerprint.hex)
                    statement.setString(7, row.resourceRef.value)
                    statement.setString(8, row.kind.name)
                    statement.setString(9, row.status.name)
                    statement.setString(10, row.forwardOpId)
                    statement.setString(11, row.compensationOpId)
                    statement.setLong(12, row.leaseEpoch)
                    // The arm timestamp is the ARM ORDER, and it is made strictly increasing
                    // per Saga inside the statement itself: two obligations armed in the same
                    // millisecond still get a defined order, and that order is the one they
                    // were armed in.
                    //
                    // It matters because the compensation order IS the reverse of the arm
                    // order, and a tie broken by the identifiers would compensate in an order
                    // the plugin never declared. The cost is at most a millisecond of drift
                    // against the wall clock, and only when two arms collide.
                    statement.setLong(13, row.createdAtMs)
                    statement.setString(14, row.runId)
                    statement.setString(15, row.sagaId.value)
                    statement.setLong(16, row.updatedAtMs)
                    if (row.receiptRef == null) {
                        statement.setNull(17, java.sql.Types.VARCHAR)
                    } else {
                        statement.setString(17, row.receiptRef.value)
                    }
                    // The guard's two parameters: the run whose high-water mark is read, and the
                    // epoch of this writer.
                    statement.setString(18, row.runId)
                    statement.setLong(19, row.leaseEpoch)
                    statement.executeUpdate()
                }

                val stored = selectByKey(connection, row.runId, row.sagaId, row.actionId, row.compensationId)
                when {
                    // The guard refused the write and nothing is stored under the key: this
                    // writer's epoch is behind the run's high-water mark.
                    stored == null -> SagaArmPersistence.Fenced(currentEpochOf(connection, row.runId))
                    inserted > 0 -> SagaArmPersistence.Inserted(stored)
                    else -> SagaArmPersistence.Existing(stored)
                }
            } finally {
                connection.close()
            }
        }
    }

    override fun updateOutcome(update: SagaObligationUpdate): SagaUpdatePersistence {
        require(update.runId.isNotBlank()) { "runId must not be blank" }
        require(update.leaseEpoch >= 0) {
            "leaseEpoch must not be negative, got ${update.leaseEpoch}"
        }

        return DbLock.forPath(dbPath).withLock {
            val connection = connectionFactory()
            try {
                val updated = connection.prepareStatement(UPDATE_OBLIGATION).use { statement ->
                    statement.setString(1, update.status.name)
                    if (update.receiptRef == null) {
                        statement.setNull(2, java.sql.Types.VARCHAR)
                    } else {
                        statement.setString(2, update.receiptRef.value)
                    }
                    statement.setLong(3, update.updatedAtMs)
                    statement.setString(4, update.runId)
                    statement.setString(5, update.key.sagaId.value)
                    statement.setString(6, update.key.actionId.value)
                    statement.setString(7, update.key.compensationId.value)
                    // The guard's two parameters: the run whose high-water mark is read, and the
                    // epoch of this writer.
                    statement.setString(8, update.runId)
                    statement.setLong(9, update.leaseEpoch)
                    statement.executeUpdate()
                }

                if (updated > 0) {
                    val stored = selectByKey(
                        connection,
                        update.runId,
                        update.key.sagaId,
                        update.key.actionId,
                        update.key.compensationId,
                    )
                    // The row was just written, so it is there. Reading it back is what makes the
                    // answer the truth on disk rather than the truth this process believes.
                    return@withLock SagaUpdatePersistence.Updated(
                        stored ?: error("the obligation vanished in the same transaction"),
                    )
                }

                // Nothing was written. Either there is no promise under that key, or the guard
                // refused this writer. The difference matters: one of them is a missing
                // obligation and the other is a lost lease.
                val stored = selectByKey(
                    connection,
                    update.runId,
                    update.key.sagaId,
                    update.key.actionId,
                    update.key.compensationId,
                )
                if (stored == null) {
                    SagaUpdatePersistence.NotFound
                } else {
                    SagaUpdatePersistence.Fenced(currentEpochOf(connection, update.runId))
                }
            } finally {
                connection.close()
            }
        }
    }

    override fun moveAll(
        runId: String,
        sagaId: SagaId,
        from: CompensationObligationStatus,
        to: CompensationObligationStatus,
        updatedAtMs: Long,
        leaseEpoch: Long,
    ): SagaBulkMove {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(leaseEpoch >= 0) { "leaseEpoch must not be negative, got $leaseEpoch" }

        return DbLock.forPath(dbPath).withLock {
            val connection = connectionFactory()
            try {
                val moved = connection.prepareStatement(MOVE_ALL_OBLIGATIONS).use { statement ->
                    statement.setString(1, to.name)
                    statement.setLong(2, updatedAtMs)
                    statement.setString(3, runId)
                    statement.setString(4, sagaId.value)
                    statement.setString(5, from.name)
                    // The guard's two parameters: the run whose high-water mark is read, and the
                    // epoch of this writer.
                    statement.setString(6, runId)
                    statement.setLong(7, leaseEpoch)
                    statement.executeUpdate()
                }

                // What the database says NOW, not what the caller intended: the rows that are
                // still in the state the move started from. A caller that needs them all moved
                // reads this number.
                val remaining = countInStatus(connection, runId, sagaId, from)

                if (moved == 0 && remaining > 0) {
                    // Nothing was written and the rows are still there: the guard refused this
                    // writer. The epoch says whether that is what happened, rather than a race
                    // with somebody else.
                    val current = currentEpochOf(connection, runId)
                    if (current > leaseEpoch) {
                        return@withLock SagaBulkMove.Fenced(current)
                    }
                }

                SagaBulkMove.Applied(moved = moved, remaining = remaining)
            } finally {
                connection.close()
            }
        }
    }

    override fun find(runId: String, key: CompensationKey): SagaObligationRow? {
        val connection = connectionFactory()
        try {
            return selectByKey(connection, runId, key.sagaId, key.actionId, key.compensationId)
        } finally {
            connection.close()
        }
    }

    override fun listForRun(runId: String): List<SagaObligationRow> {
        val connection = connectionFactory()
        try {
            connection.prepareStatement(SELECT_RUN_OBLIGATIONS).use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { rs ->
                    val rows = mutableListOf<SagaObligationRow>()
                    while (rs.next()) {
                        rows.add(readRow(rs))
                    }
                    return rows
                }
            }
        } finally {
            connection.close()
        }
    }

    /**
     * Creates the table if it is not there yet.
     *
     * Additive and idempotent: a database written by an older build does not have the table,
     * and one written by this build already does. The statement never touches an existing
     * table, so it cannot disturb a run that is already in flight.
     */
    private fun ensureSchema() {
        DbLock.forPath(dbPath).withLock {
            val connection = connectionFactory()
            try {
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_OBLIGATION_TABLE)
                    statement.execute(CREATE_OBLIGATION_RUN_INDEX)
                }
            } finally {
                connection.close()
            }
        }
    }

    /**
     * The newest lease epoch recorded for [runId], or `-1` when the run has no obligations.
     *
     * Read only for the diagnostic that accompanies a refusal; the guard that actually refuses
     * the write lives inside the INSERT.
     */
    private fun currentEpochOf(connection: Connection, runId: String): Long {
        connection.prepareStatement(SELECT_RUN_EPOCH).use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong(1) else -1L
            }
        }
    }

    private fun selectByKey(
        connection: Connection,
        runId: String,
        sagaId: SagaId,
        actionId: SagaActionId,
        compensationId: CompensationId,
    ): SagaObligationRow? {
        connection.prepareStatement(SELECT_BY_KEY).use { statement ->
            statement.setString(1, runId)
            statement.setString(2, sagaId.value)
            statement.setString(3, actionId.value)
            statement.setString(4, compensationId.value)
            statement.executeQuery().use { rs ->
                return if (rs.next()) readRow(rs) else null
            }
        }
    }

    /**
     * How many obligations of [sagaId] are in [status] right now.
     *
     * Read in the same connection as the write it describes, so the number is the state the
     * database holds after that write and not a second, later, guess.
     */
    private fun countInStatus(
        connection: Connection,
        runId: String,
        sagaId: SagaId,
        status: CompensationObligationStatus,
    ): Int {
        connection.prepareStatement(COUNT_STATUS_OBLIGATIONS).use { statement ->
            statement.setString(1, runId)
            statement.setString(2, sagaId.value)
            statement.setString(3, status.name)
            statement.executeQuery().use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    /**
     * Reads one row.
     *
     * Fails closed on anything this build does not understand: an unknown intent version, an
     * unknown status or an unknown kind raises instead of being coerced into a default. A
     * promise whose meaning cannot be established is not a promise this build may honour.
     */
    private fun readRow(rs: java.sql.ResultSet): SagaObligationRow {
        val intentVersion = rs.getInt("intent_version")
        SagaIntentContract.requireSupported(intentVersion)
        val receipt = rs.getString("receipt_ref")
        return SagaObligationRow(
            runId = rs.getString("run_id"),
            sagaId = SagaId.of(rs.getString("saga_id")),
            actionId = SagaActionId.of(rs.getString("action_id")),
            compensationId = CompensationId.of(rs.getString("compensation_id")),
            intentVersion = intentVersion,
            planFingerprint = SagaPlanFingerprint(rs.getString("plan_fingerprint")),
            resourceRef = ResourceRef.of(rs.getString("resource_ref")),
            kind = CompensationKind.valueOf(rs.getString("compensation_kind")),
            status = CompensationObligationStatus.valueOf(rs.getString("status")),
            forwardOpId = rs.getString("forward_op_id"),
            compensationOpId = rs.getString("compensation_op_id"),
            leaseEpoch = rs.getLong("lease_epoch"),
            createdAtMs = rs.getLong("created_at_ms"),
            updatedAtMs = rs.getLong("updated_at_ms"),
            receiptRef = receipt?.let { EffectReceiptRef.of(it) },
        )
    }

    private companion object {

        const val CREATE_OBLIGATION_TABLE = """
            CREATE TABLE IF NOT EXISTS saga_obligation (
                run_id TEXT NOT NULL,
                saga_id TEXT NOT NULL,
                action_id TEXT NOT NULL,
                compensation_id TEXT NOT NULL,
                intent_version INTEGER NOT NULL,
                plan_fingerprint TEXT NOT NULL,
                resource_ref TEXT NOT NULL,
                compensation_kind TEXT NOT NULL,
                status TEXT NOT NULL,
                forward_op_id TEXT NOT NULL,
                compensation_op_id TEXT NOT NULL,
                lease_epoch INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                receipt_ref TEXT,
                PRIMARY KEY (run_id, saga_id, action_id, compensation_id)
            )
        """

        const val CREATE_OBLIGATION_RUN_INDEX = """
            CREATE INDEX IF NOT EXISTS ix_saga_obligation_run
            ON saga_obligation (run_id, status)
        """

        /**
         * The conditional insert.
         *
         * The trailing WHERE is the fencing guard and the ON CONFLICT is the idempotency guard;
         * both live in the same statement as the write, so neither can be won by a race between
         * a check and an insert.
         *
         * The arm timestamp is made strictly increasing PER SAGA inside this statement: it is
         * the wall clock, or one millisecond later than the newest arm of the same Saga when
         * both landed in the same millisecond. That is what makes the arm order — and
         * therefore the compensation order — the order the promises were actually made in,
         * instead of an accident of the identifiers.
         */
        const val INSERT_OBLIGATION = """
            INSERT INTO saga_obligation (
                run_id, saga_id, action_id, compensation_id, intent_version, plan_fingerprint,
                resource_ref, compensation_kind, status, forward_op_id, compensation_op_id,
                lease_epoch, created_at_ms, updated_at_ms, receipt_ref
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                MAX(?, COALESCE((
                    SELECT MAX(created_at_ms) FROM saga_obligation
                    WHERE run_id = ? AND saga_id = ?
                ), 0) + 1),
                ?, ?
            WHERE (
                SELECT COALESCE(MAX(lease_epoch), -1) FROM saga_obligation WHERE run_id = ?
            ) <= ?
            ON CONFLICT (run_id, saga_id, action_id, compensation_id) DO NOTHING
        """

        const val SELECT_BY_KEY = """
            SELECT * FROM saga_obligation
            WHERE run_id = ? AND saga_id = ? AND action_id = ? AND compensation_id = ?
        """

        /**
         * The conditional update.
         *
         * The trailing WHERE is the fencing guard, in the same statement as the write, so a
         * writer that lost the run cannot win a race between a check and an update. The row's
         * OWN lease epoch is not touched: it belongs to the promise, not to the state.
         */
        const val UPDATE_OBLIGATION = """
            UPDATE saga_obligation
            SET status = ?, receipt_ref = ?, updated_at_ms = ?
            WHERE run_id = ? AND saga_id = ? AND action_id = ? AND compensation_id = ?
              AND (
                SELECT COALESCE(MAX(lease_epoch), -1) FROM saga_obligation WHERE run_id = ?
              ) <= ?
        """

        const val SELECT_RUN_OBLIGATIONS = """
            SELECT * FROM saga_obligation
            WHERE run_id = ?
            ORDER BY created_at_ms, saga_id, action_id, compensation_id
        """

        /**
         * The bulk move.
         *
         * ONE statement, so ONE transaction: every obligation of the Saga that is in the old
         * state moves, or none does. A Saga that is closed is closed for good — a process that
         * died in the middle of closing it leaves no half-closed Saga behind for a resumed run
         * to undo.
         *
         * The trailing WHERE is the fencing guard, in the same statement as the write, so a
         * writer that lost the run cannot win a race between a check and an update. The row's
         * OWN lease epoch is not touched: it belongs to the promise, not to the state, and the
         * receipt is not touched either: it belongs to the effect, which is still there.
         */
        const val MOVE_ALL_OBLIGATIONS = """
            UPDATE saga_obligation
            SET status = ?, updated_at_ms = ?
            WHERE run_id = ? AND saga_id = ? AND status = ?
              AND (
                SELECT COALESCE(MAX(lease_epoch), -1) FROM saga_obligation WHERE run_id = ?
              ) <= ?
        """

        const val COUNT_STATUS_OBLIGATIONS = """
            SELECT COUNT(*) FROM saga_obligation
            WHERE run_id = ? AND saga_id = ? AND status = ?
        """

        const val SELECT_RUN_EPOCH = """
            SELECT COALESCE(MAX(lease_epoch), -1) FROM saga_obligation WHERE run_id = ?
        """
    }
}
