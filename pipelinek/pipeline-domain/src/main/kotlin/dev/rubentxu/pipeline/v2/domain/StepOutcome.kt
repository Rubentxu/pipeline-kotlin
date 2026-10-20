package dev.rubentxu.pipeline.v2.domain

/**
 * Typed outcome of a single step. Replaces the historical "success" / "failure" /
 * "unstable" / "completed" raw strings scattered across the runtime.
 * The hierarchy is closed: the only valid outcomes are [Success], [Unstable],
 * and a [Failure] carrying a typed [PipelineFailure]. Anything that previously
 * used a bare string at a step boundary is a migration gap that must be closed
 * before merging into this contract.
 * Use [RunOutcomeReducer] to fold a sequence of [StepOutcome] into the
 * canonical [RunOutcome] for the whole run.
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

    /**
     * The word this outcome is written as on the Event Plane.
     *
     * ## Why the token lives HERE and not in the writer
     *
     * The Event Plane carries a step's verdict as a `String`, so somebody has to translate between
     * the type and the word. It used to be done in TWO places, in opposite directions: the writer
     * spelled the outcome out by hand, and the reader kept its own list of spellings. Two lists of
     * one vocabulary are two authorities, and they drift the first time a case is added — at which
     * point the reader stops understanding a step that the writer still believes it described.
     *
     * So the vocabulary is declared once, next to the type it names, and both sides use it: the
     * writer writes [wireToken], the reader matches against the constants below. Adding a case to
     * this interface breaks the writer at compile time instead of silently in production.
     *
     * This is the same shape [RunOutcome.wireToken] already has, and for the same reason: the step
     * and the run are the same vocabulary spelled at two levels.
     */
    val wireToken: String
        get() = when (this) {
            Success -> SUCCESS_TOKEN
            Unstable -> UNSTABLE_TOKEN
            is Failure -> FAILURE_TOKEN
        }

    companion object {

        /** The word a successful step is written as. */
        const val SUCCESS_TOKEN: String = "success"

        /** The word an unstable step is written as. */
        const val UNSTABLE_TOKEN: String = "unstable"

        /** The word a failed step is written as. */
        const val FAILURE_TOKEN: String = "failure"

        /**
         * Every word this build writes at a step boundary, for a diagnostic on a word it does not.
         *
         * Derived from the constants rather than written out again, for the reason
         * [dev.rubentxu.pipeline.v2.domain.FailureKind.supportedTokens] gives: a second list of one
         * vocabulary is a second authority, and the two drift the first time someone adds a constant.
         */
        val supportedTokens: Set<String> = setOf(SUCCESS_TOKEN, UNSTABLE_TOKEN, FAILURE_TOKEN)
    }
}
