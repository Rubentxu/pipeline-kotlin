package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PipelineFailure

/**
 * Result of a single waitUntil predicate evaluation (body execution).
 *
 * Design §14.3 — the predicate outcome and the body execution are INDEPENDENT
 * concerns:
 * - `Satisfied` (predicate emitted true) = loop exit = completed
 * - `Unsatisfied` (predicate emitted false) = NOT a failure = re-enter body
 * - `Failed(failure)` (body step failed) = a failure = propagate failure
 * - `Cancelled(reason)` = explicit cancellation
 *
 * This ADT is closed. No exit-code fold, no event-as-authority.
 */
sealed interface WaitUntilPredicateOutcome {

    /** Predicate emitted true — loop terminates successfully. */
    data object Satisfied : WaitUntilPredicateOutcome

    /** Predicate emitted false — loop continues (NOT a failure). */
    data object Unsatisfied : WaitUntilPredicateOutcome

    /**
     * Body step failed with a typed failure — loop propagates failure (NOT re-entry).
     *
     * This case is the crux of the exit-code fold prohibition: a body step that
     * fails is NOT conflated with a predicate that returned false. They are
     * independent signals with different outcomes.
     */
    data class Failed(val failure: PipelineFailure) : WaitUntilPredicateOutcome

    /** Loop was explicitly cancelled. */
    data class Cancelled(
        val reason: dev.rubentxu.pipeline.v2.domain.step.CancellationReason,
    ) : WaitUntilPredicateOutcome
}
