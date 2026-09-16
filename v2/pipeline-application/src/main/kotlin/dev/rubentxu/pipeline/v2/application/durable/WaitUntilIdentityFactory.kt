package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Materializes the canonical waitUntil identities used for durable journal
 * persistence and reconciliation.
 *
 * Mirrors [RetryIdentityFactory]. The waitUntil aggregate has a simpler identity
 * model than retry: it polls the same body repeatedly until the predicate succeeds
 * or the backoff ceiling is reached.
 *
 * ## Determinism
 * All inputs that drive identity are pure values (no clock, no random).
 * Two calls with the same `(runId, stageIndex, stepIndex, parentBodyPath)` produce
 * byte-identical strings.
 */
object WaitUntilIdentityFactory {

    /**
     * Canonical waitUntil CONTROL operation identifier.
     *
     * Stable across poll attempts: every attempt's control row for one waitUntil
     * logical invocation shares this opId.
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
}
