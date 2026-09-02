package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame

/**
 * In-memory [ReplayCursorStore]: the storage-free counterpart of
 * [SqliteReplayCursorStoreImpl] with mirrored semantics.
 *
 * Part of the LF-0208 storage-pluggable spine: choosing volatile storage
 * never selects a different execution algorithm.
 *
 * ## Mirrored semantics
 *
 * - [advance]: upsert per run id with the same CAS rule as the SQLite
 *   `WHERE excluded.stage_index >= replay_cursor.stage_index` — an
 *   advance to an EARLIER stage index is a no-op (idempotency).
 * - [advancePastParallelFrame]: join barrier keyed `$runId:parallel` /
 *   `$runId:parallel-completed` (ADR-0035), same CAS rule, returns the
 *   [StageIndex] it advanced to.
 * - [load]: `null` when no cursor exists for the run.
 */
class InMemoryReplayCursorStore(
    private val clock: Clock,
) : ReplayCursorStore {

    private val cursors = java.util.concurrent.ConcurrentHashMap<String, ReplayCursor>()

    override fun load(runId: String): ReplayCursor? = cursors[runId]

    override fun advance(runId: String, opId: String, stageIndex: Int) {
        require(stageIndex >= 0) { "stageIndex must be >= 0, got $stageIndex" }
        cursors.merge(
            runId,
            ReplayCursor(runId, opId, stageIndex, clock.now().toEpochMilli()),
        ) { existing, candidate ->
            if (candidate.stageIndex >= existing.stageIndex) candidate else existing
        }
    }

    override fun advancePastParallelFrame(
        runId: String,
        frame: ParallelFrame,
        branchResults: List<BranchExecutionResult>,
        explicitMaxStageIndex: Int?,
    ): StageIndex {
        val maxStageIndex = explicitMaxStageIndex
            ?: branchResults.maxOfOrNull { it.stageIndex }
            ?: 0

        // ADR-0035 §Decision: cursor keys derived from the runId.
        val parallelCursorKey = "$runId:parallel"
        val parallelCompletedKey = "$runId:parallel-completed"
        advance(parallelCursorKey, parallelCompletedKey, maxStageIndex)
        return StageIndex(maxStageIndex)
    }
}
