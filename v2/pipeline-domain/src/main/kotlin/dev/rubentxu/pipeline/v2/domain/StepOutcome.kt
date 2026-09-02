package dev.rubentxu.pipeline.v2.domain

/**
 * Typed outcome of a single step. Replaces the historical "success" / "failure" /
 * "unstable" / "completed" raw strings scattered across the runtime.
 *
 * The hierarchy is closed: the only valid outcomes are [Success], [Unstable],
 * and a [Failure] carrying a typed [PipelineFailure]. Anything that previously
 * used a bare string at a step boundary is a migration gap that must be closed
 * before merging into this contract.
 *
 * Use [RunOutcomeReducer] to fold a sequence of [StepOutcome] into the
 * canonical [RunOutcome] for the whole run.
 *
 * @see PipelineFailure
 * @see RunOutcome
 * @see RunOutcomeReducer
 */
sealed interface StepOutcome {
    /** The step completed without producing a failure or unstable marker. */
    data object Success : StepOutcome

    /**
     * The step completed but with a non-fatal warning (e.g. `warnError` matched
     * a known pattern and the script returned 0). Does NOT carry a [PipelineFailure]
     * because the contract of "unstable" is that no actionable failure was observed.
     */
    data object Unstable : StepOutcome

    /** The step did not complete successfully. Carries the typed [PipelineFailure]. */
    data class Failure(val failure: PipelineFailure) : StepOutcome
}
