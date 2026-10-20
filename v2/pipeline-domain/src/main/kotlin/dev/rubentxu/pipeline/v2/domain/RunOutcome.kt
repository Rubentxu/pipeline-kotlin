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

    /**
     * The word this outcome is written as on the Event Plane.
     *
     * ## Why the token lives HERE and not in the writer
     *
     * The Event Plane carries the run's outcome as a `String`, so somebody has to translate
     * between the type and the word. It used to be done in TWO places, in opposite directions:
     * the writer spelled the outcome out by hand, and the reader kept its own list of spellings.
     * Two lists of one vocabulary is two authorities, and they drift the first time a case is
     * added — at which point the reader stops understanding a run that the writer still believes
     * it described.
     *
     * So the vocabulary is declared once, next to the type it names, and both sides use it:
     * the writer writes [wireToken], the reader matches against the constants below. Adding a
     * case to this interface breaks the writer at compile time instead of silently in production.
     */
    val wireToken: String
        get() = when (this) {
            Success -> SUCCESS_TOKEN
            Unstable -> UNSTABLE_TOKEN
            is Failure -> FAILURE_TOKEN
            Aborted -> ABORTED_TOKEN
        }

    companion object {

        /** The word a successful run is written as. */
        const val SUCCESS_TOKEN: String = "success"

        /** The word an unstable run is written as. */
        const val UNSTABLE_TOKEN: String = "unstable"

        /** The word a failed run is written as. */
        const val FAILURE_TOKEN: String = "failure"

        /** The word an aborted run is written as. */
        const val ABORTED_TOKEN: String = "aborted"

        /**
         * Every word this build reads, for a diagnostic on a word it does not.
         *
         * Derived from the constants rather than written out again, for the reason
         * [dev.rubentxu.pipeline.v2.domain.FailureKind.supportedTokens] gives: a second list of
         * one vocabulary is a second authority, and the two drift the first time someone adds
         * a constant.
         */
        val supportedTokens: Set<String> = setOf(SUCCESS_TOKEN, UNSTABLE_TOKEN, FAILURE_TOKEN, ABORTED_TOKEN)
    }
}
