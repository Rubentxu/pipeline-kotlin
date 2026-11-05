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
 * ## The vocabulary of a RESULT, and where each word is pronounced
 *
 * This type owns the vocabulary of a run's RESULT: the four words
 * [SUCCESS_TOKEN], [UNSTABLE_TOKEN], [FAILURE_TOKEN] and [ABORTED_TOKEN], and
 * no other. The Event Plane carries the result as a `String`, so a word
 * crosses the wire and the case is recovered from it by [parse] — the single
 * boundary where a written word becomes the typed result.
 *
 * - **To the operator**: `MainSagaInspectCli` pronounces success, unstable,
 *   aborted and "failure (kind) — message"; the inspection transcript
 *   pronounces success, unstable and failure.
 * - **Only inside**: the telemetry projection counts a run under the class its
 *   word names, and the runtime projects the result onto the terminal state it
 *   observed.
 *
 * ## The other vocabulary, which is not this one
 *
 * The terminal state a runtime OBSERVES is a different closed set, owned by
 * `TerminalObservation` in the runtime API: it has a `cancelled` that this
 * vocabulary does not have, and it calls `succeeded` what this one calls
 * `success`. The two answer different questions — what the run ENDED as, and
 * what was SEEN — and a reader that swaps them reads a cancelled run as a
 * failed one. `LegacyOutcomeMapper` is a third, older boundary: it accepts the
 * historical spellings for data written by a build that no longer runs, and it
 * is not a second way to read a current run.
 *
 * @see StepOutcome
 * @see PipelineFailure
 * @see RunOutcomeReducer
 */
sealed interface RunOutcome {
    /**
     * The run ended and every step succeeded.
     *
     * There is nothing to inspect: the run holds no failure and no
     * unfinished business.
     */
    data object Success : RunOutcome

    /**
     * The run ended with at least one unstable step and no failed one.
     *
     * An unstable step reported trouble it got past, so the run is not a
     * failure. A caller that reads every run as pass or fail must give
     * this case a verdict of its own: whether an unstable run is good
     * enough is the caller's policy, not the runtime's.
     */
    data object Unstable : RunOutcome

    /**
     * The run ended with at least one failed step.
     *
     * It holds the failure that was observed first in declaration order,
     * not in completion order, so the same run always reports the same
     * cause.
     */
    data class Failure(val failure: PipelineFailure) : RunOutcome

    /**
     * The run was cancelled or interrupted before it reached a normal end.
     *
     * The orchestrator states it; the reducer does not derive it. A caller
     * that sees it knows the run stopped rather than that it failed, and
     * whether to resume it is the caller's decision.
     */
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

    /**
     * The vocabulary a run's result is written in.
     *
     * The four words live here, next to the cases they name, and both ends
     * of the event plane use them: the writer writes the case's token and
     * the reader recovers the case with [parse]. A word this build does not
     * know is answered with `null` and never with a default, because a word
     * it cannot read is not a failed run.
     */
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
         * The failure a bare [FAILURE_TOKEN] carries.
         *
         * The word names that the run failed; it does not say how. The failure detail
         * lives in the run's own failure fact, and this carrier says exactly that
         * instead of inventing a kind the word never carried.
         */
        private val UNATTRIBUTED_FAILURE: PipelineFailure = PipelineFailure(
            kind = FailureKind.UNKNOWN,
            message = "the run outcome word 'failure' names the outcome but not the cause; " +
                "the failure detail is in the run's own failure fact",
        )

        /**
         * Every word this build reads, and the case it names.
         *
         * Derived from the constants rather than written out again, for the reason
         * [dev.rubentxu.pipeline.v2.domain.FailureKind.supportedTokens] gives: a second list of
         * one vocabulary is a second authority, and the two drift the first time someone adds
         * a constant.
         */
        private val BY_TOKEN: Map<String, RunOutcome> = mapOf(
            SUCCESS_TOKEN to Success,
            UNSTABLE_TOKEN to Unstable,
            FAILURE_TOKEN to Failure(UNATTRIBUTED_FAILURE),
            ABORTED_TOKEN to Aborted,
        )

        /** Every word this build reads, for a diagnostic on a word it does not. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /**
         * The single boundary where a declared word becomes the typed result.
         *
         * The Event Plane carries the result as a `String`, so the word crosses the wire
         * and the case is recovered from it here. Returns **null** outside the
         * vocabulary, and deliberately does NOT fall back to a default: a word this build
         * cannot read is not a failed run, it is a word it cannot read, and a reader that
         * turns the second into the first asserts a failure that was never observed.
         */
        fun parse(token: String): RunOutcome? = BY_TOKEN[token]
    }
}
