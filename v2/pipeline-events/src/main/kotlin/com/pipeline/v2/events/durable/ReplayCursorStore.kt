package com.pipeline.v2.events.durable

import com.pipeline.v2.domain.durable.Clock
import java.sql.Connection

/**
 * Interface for persistent replay cursor storage.
 *
 * ## M3-R1 → M3-R2 Contract
 *
 * This interface is stable for M3-R2 consumption per [design.md §8].
 *
 * @see <a href="design.md §E4-04">Design §E4-04</a>
 */
interface ReplayCursorStore {
    /**
     * Loads the replay cursor for a given [runId].
     *
     * @param runId The run identifier.
     * @return The [ReplayCursor] if found, or `null` if no cursor exists for this run.
     */
    fun load(runId: String): ReplayCursor?

    /**
     * Advances the replay cursor to the given [opId] and [stageIndex].
     *
     * This operation is idempotent: if the cursor already points to the same
     * or a later position, the update is a no-op.
     *
     * ## R-C mitigation reminder
     *
     * This function MUST be called only AFTER [OperationJournal.append]
     * has returned successfully. Do NOT call it before the append.
     *
     * @param runId      The run identifier.
     * @param opId       The ID of the operation that was just successfully journaled.
     * @param stageIndex The stage index at which execution should resume.
     */
    fun advance(runId: String, opId: String, stageIndex: Int)
}

/**
 * SQLite-backed implementation of [ReplayCursorStore].
 *
 * ## Idempotency
 *
 * [advance] is idempotent: calling it multiple times with the same arguments
 * has no additional effect. This is critical for correctness when
 * [advance] is called after [OperationJournal.append] in the same transaction
 * or across a crash-restart boundary.
 *
 * ## R-C mitigation
 *
 * Per [design.md §R-C], [advance] must ONLY be called after
 * [OperationJournal.append] has returned successfully. Calling it before
 * the append succeeds could result in a cursor pointing to an operation
 * that was not actually persisted.
 *
 * @param connectionFactory A factory function that returns an open [Connection]
 *                           obtained from [SqliteConnectionFactory.open].
 *
 * @see <a href="design.md §E4-04">Design §E4-04</a>
 */
class SqliteReplayCursorStoreImpl(
    private val connectionFactory: () -> Connection,
    private val clock: Clock,
) : ReplayCursorStore {
    /**
     * Loads the replay cursor for a given [runId].
     *
     * @param runId The run identifier.
     * @return The [ReplayCursor] if found, or `null` if no cursor exists for this run.
     */
    override fun load(runId: String): ReplayCursor? {
        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                SELECT run_id, last_op_id, stage_index, saved_at
                FROM replay_cursor
                WHERE run_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return ReplayCursor(
                        runId = rs.getString(1),
                        lastOpId = rs.getString(2),
                        stageIndex = rs.getInt(3),
                        savedAt = rs.getLong(4),
                    )
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Advances the replay cursor to the given [opId] and [stageIndex].
     *
     * This operation is idempotent: if the cursor already points to the same
     * or a later position, the update is a no-op.
     *
     * ## R-C mitigation reminder
     *
     * This function MUST be called only AFTER [OperationJournal.append]
     * has returned successfully. Do NOT call it before the append.
     *
     * @param runId      The run identifier.
     * @param opId       The ID of the operation that was just successfully journaled.
     * @param stageIndex The stage index at which execution should resume.
     */
    override fun advance(runId: String, opId: String, stageIndex: Int) {
        require(stageIndex >= 0) { "stageIndex must be >= 0, got $stageIndex" }

        val conn = connectionFactory()
        try {
            conn.prepareStatement(
                """
                INSERT INTO replay_cursor (run_id, last_op_id, stage_index, saved_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET
                    last_op_id = excluded.last_op_id,
                    stage_index = excluded.stage_index,
                    saved_at = excluded.saved_at
                WHERE excluded.stage_index >= replay_cursor.stage_index
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.setString(2, opId)
                ps.setInt(3, stageIndex)
                ps.setLong(4, clock.now().toEpochMilli())
                ps.executeUpdate()
            }
        } finally {
            conn.close()
        }
    }
}
