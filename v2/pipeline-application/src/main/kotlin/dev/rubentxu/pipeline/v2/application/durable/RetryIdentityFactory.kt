package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Materializes the canonical retry identities used for durable journal
 * persistence and reconciliation.
 *
 * ADR-0075 §2 — the retry logical invocation has TWO levels of identity
 * at the [OpId] level:
 *  - **Control identity**: stable across attempts; one canonical opId per
 *    retry logical invocation. Used to persist per-attempt control rows
 *    (the durable record of the retry aggregate's state at attempt N).
 *    The (opId, attempt) pair locates a single row in
 *    [dev.rubentxu.pipeline.v2.events.durable.OperationJournal].
 *  - **Child identity**: unique per (retry, attempt, childIndex) triple.
 *    Used to persist the canonical child effect's durable facts; it is
 *    exactly the (runId, stageIndex, stepIndex, branchIndex=null,
 *    bodyPath=parentBodyPath + attempt BlockSegment +
 *    child BlockSegment) tuple the canonical child dispatch already
 *    produces.
 *
 * ## Why control and child opIds MUST NOT collide
 * Both identities share the same (runId, stageIndex, stepIndex) prefix.
 * The control opId's bodyPath is the bare `parentBodyPath` (no per-attempt
 * segment, no child segment). The child opId's bodyPath always carries the
 * attempt BlockSegment. The journal's (opId, attempt) lookup distinguishes
 * them by the `attempt` column — control rows are persisted with their
 * per-attempt ordinal; child rows are persisted with attempt=1 in the
 * current schema (they differentiate attempts via the bodyPath's attempt
 * BlockSegment).
 *
 * ## Determinism
 * All inputs that drive identity are pure values (no clock, no random).
 * Two calls with the same `(runId, stageIndex, stepIndex, parentBodyPath,
 * attempt, childIndex, childPluginStepId)` produce byte-identical strings.
 */
object RetryIdentityFactory {

    /**
     * Canonical marker appended to the bodyPath of each child opId to
     * record which retry attempt produced the child durable fact.
     */
    private val RETRY_ATTEMPT_MARKER: PluginStepId = PluginStepId("retry-attempt")

    /**
     * Canonical retry CONTROL operation identifier.
     *
     * Stable across attempts: every attempt's control row for one retry
     * logical invocation shares this opId. The journal's (opId, attempt)
     * pair locates a specific row.
     */
    fun controlOperationId(
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
    ): String {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(stageIndex >= 0) { "stageIndex must be >= 0, got $stageIndex" }
        require(stepIndex >= 0) { "stepIndex must be >= 0, got $stepIndex" }
        return OpId(
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            branchIndex = null,
            bodyPath = parentBodyPath,
        ).format()
    }

    /**
     * Canonical retry CHILD operation identifier for a specific
     * (attempt, childIndex, childPluginStepId) triple.
     */
    fun childOperationId(
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        attempt: Int,
        childIndex: Int,
        childPluginStepId: PluginStepId,
    ): String {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(stageIndex >= 0) { "stageIndex must be >= 0, got $stageIndex" }
        require(stepIndex >= 0) { "stepIndex must be >= 0, got $stepIndex" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        require(childIndex >= 0) { "childIndex must be >= 0, got $childIndex" }
        return OpId(
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            branchIndex = null,
            bodyPath = parentBodyPath +
                BlockSegment(attempt, RETRY_ATTEMPT_MARKER) +
                BlockSegment(childIndex, childPluginStepId),
        ).format()
    }
}
