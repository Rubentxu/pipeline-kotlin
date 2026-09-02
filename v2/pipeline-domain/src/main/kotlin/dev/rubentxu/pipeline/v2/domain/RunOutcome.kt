package dev.rubentxu.pipeline.v2.domain

/**
 * Typed outcome of a complete pipeline run. Produced exactly once per
 * invocation, by [RunOutcomeReducer.reduce], so that all callers converge on
 * a single semantic authority.
 *
 * Replaces the historical "success" / "failure" / "unstable" / "completed" /
 * "aborted" raw strings that previously lived at the run boundary and made
 * post-mortem reasoning brittle.
 *
 * The closed set is: [Success], [Unstable], [Failure], [Aborted].
 * - [Success]: every step succeeded.
 * - [Unstable]: at least one step was [StepOutcome.Unstable] and no step failed.
 * - [Failure]: at least one step was [StepOutcome.Failure]; carries the first
 *   observed typed [PipelineFailure].
 * - [Aborted]: the run was cancelled or interrupted before reaching a normal
 *   terminal state. Set explicitly by the orchestrator, never derived by
 *   [RunOutcomeReducer].
 *
 * @see StepOutcome
 * @see PipelineFailure
 * @see RunOutcomeReducer
 */
sealed interface RunOutcome {
    data object Success : RunOutcome
    data object Unstable : RunOutcome
    data class Failure(val failure: PipelineFailure) : RunOutcome
    data object Aborted : RunOutcome
}
