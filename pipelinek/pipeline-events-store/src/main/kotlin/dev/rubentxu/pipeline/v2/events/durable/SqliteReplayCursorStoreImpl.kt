package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.runtime.durable.BranchExecutionResult
import dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.runtime.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.runtime.durable.StageIndex

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
 * ## mitigation
 *
 * [advance] must ONLY be called after
 * [OperationJournal.append] has returned successfully. Calling it before
 * the append succeeds could result in a cursor pointing to an operation
 * that was not actually persisted.
 *
 * ## The connections
 *
 * The cursor BORROWS its connections from the durable lane's pools, like the
 * journal does: it writes through the write pool and reads through the read
 * pool, whose door is one the database engine refuses to write through.
 *
 * The door was the expensive part: opening a connection costs more than the
 * statement it carries, and this cursor advances once per settled operation of
 * a run, so a connection per operation was a cost that grew with the run. A
 * borrowed connection belongs to ONE operation at a time and comes back when
 * the operation ends, so the cost of the door is paid once per connection.
 *
 * @param connections the pools the cursor borrows its connections from.
 *
 */
internal class SqliteReplayCursorStoreImpl(
    private val connections: DurableConnections,
    private val clock: Clock,
) : ReplayCursorStore {
    /**
     * Loads the replay cursor for a given [runId].
     *
     * @param runId The run identifier.
     * @return The [ReplayCursor] if found, or `null` if no cursor exists for this run.
     */
    override fun load(runId: String): ReplayCursor? = connections.reads.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT run_id, last_op_id, stage_index, saved_at
            FROM replay_cursor
            WHERE run_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@withConnection null
                ReplayCursor(
                    runId = rs.getString(1),
                    lastOpId = rs.getString(2),
                    stageIndex = rs.getInt(3),
                    savedAt = rs.getLong(4),
                )
            }
        }
    }

    /**
     * Advances the replay cursor to the given [opId] and [stageIndex].
     *
     * This operation is idempotent: if the cursor already points to the same
     * or a later position, the update is a no-op.
     *
     * ## mitigation reminder
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

        connections.writes.withConnection { conn ->
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
        }
    }

    /**
     * Advances the replay cursor past a parallel frame.
     *
     * Computes `max(branchResults.map { it.stageIndex })` and atomically
     * writes the new cursor position using the same idempotent CAS semantics
     * as [advance].
     *
     * ## Concurrency note
     *
     * Full concurrency stress testing for this join barrier is deferred.
     * The current implementation relies on SQLite's transaction isolation and the
     * idempotent ON CONFLICT clause for safety, but production concurrency
     * should be validated with UatDurable010 before promotion.
     *
     * @param frame The parallel frame that just completed.
     * @param branchResults The results of each branch.
     * @return The new [StageIndex] after advancing.
     */
    override fun advancePastParallelFrame(
        runId: String,
        frame: ParallelFrame,
        branchResults: List<BranchExecutionResult>,
        explicitMaxStageIndex: Int?,
    ): StageIndex {
        // Compute max stage index from branchResults, or use explicit value if provided
        val maxStageIndex = explicitMaxStageIndex
            ?: branchResults.maxOfOrNull { it.stageIndex }
            ?: 0

        connections.writes.withConnection { conn ->
            // Derive cursor keys from runId:
            // run_id for the join barrier is "$runId:parallel"
            // last_op_id is "$runId:parallel-completed"
            val parallelCursorKey = "$runId:parallel"
            val parallelCompletedKey = "$runId:parallel-completed"

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
                ps.setString(1, parallelCursorKey)
                ps.setString(2, parallelCompletedKey)
                ps.setInt(3, maxStageIndex)
                ps.setLong(4, clock.now().toEpochMilli())
                ps.executeUpdate()
            }
        }
        return StageIndex(maxStageIndex)
    }
}
