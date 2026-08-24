package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
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

    /**
     * Advances the replay cursor past a parallel frame, using the maximum
     * stage index from all branch results as the next cursor position.
     *
     * This is the join barrier for parallel frames (ADR-0035). It computes
     * `max(branchResults.map { it.stageIndex })` and atomically writes
     * the new cursor position using the same idempotent CAS semantics as [advance].
     *
     * @param frame The parallel frame that just completed.
     * @param branchResults The results of each branch, containing the stage index.
     * @return The new [StageIndex] after advancing.
     */
    fun advancePastParallelFrame(frame: ParallelFrame, branchResults: List<BranchExecutionResult>): StageIndex
}

/**
 * Result of a single branch execution, used by [ReplayCursorStore.advancePastParallelFrame].
 *
 * This is a minimal data class defined in the events module to avoid a dependency
 * from events on the step-sdk runtime module (where [BranchResult] lives).
 *
 * @property branchIndex The index of the branch.
 * @property stageIndex The stage index after this branch completed.
 */
data class BranchExecutionResult(
    val branchIndex: Int,
    val stageIndex: Int,
)

/**
 * A wrapper around [Int] representing a stage index in the replay cursor.
 */
@JvmInline
value class StageIndex(val value: Int)

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

    /**
     * Advances the replay cursor past a parallel frame.
     *
     * Computes `max(branchResults.map { it.stageIndex })` and atomically
     * writes the new cursor position using the same idempotent CAS semantics
     * as [advance].
     *
     * ## Concurrency note (ADR-0035)
     *
     * Full concurrency stress testing for this join barrier is deferred to M3-R4.3.
     * The current implementation relies on SQLite's transaction isolation and the
     * idempotent ON CONFLICT clause for safety, but production concurrency
     * should be validated with UatDurable010 before promotion.
     *
     * @param frame The parallel frame that just completed.
     * @param branchResults The results of each branch.
     * @return The new [StageIndex] after advancing.
     */
    override fun advancePastParallelFrame(
        frame: ParallelFrame,
        branchResults: List<BranchExecutionResult>,
    ): StageIndex {
        // Compute max stage index from all branch results (join barrier - ADR-0035)
        val maxStageIndex = branchResults
            .maxOfOrNull { it.stageIndex }
            ?: 0

        val conn = connectionFactory()
        try {
            // Use parent opId format: "runId-s{stageIndex}-{stepIndex}"
            // For parallel frame, we use the parent frame's opId (without branch suffix)
            val parentOpId = "parallel-frame"

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
                ps.setString(1, parentOpId)
                ps.setString(2, "parallel-frame-completed")
                ps.setInt(3, maxStageIndex)
                ps.setLong(4, clock.now().toEpochMilli())
                ps.executeUpdate()
            }
        } finally {
            conn.close()
        }
        return StageIndex(maxStageIndex)
    }
}
