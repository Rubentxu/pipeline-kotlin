package dev.rubentxu.pipeline.v2.application.durable.waituntil

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilPredicateOutcome

/**
 * How the answer of ONE poll is read as the predicate's verdict.
 *
 * ## Why the reading is a function of its own
 *
 * The poll's answer arrives as a body outcome, and the predicate's verdict is a different
 * thing: a body that failed did not satisfy the predicate, and a body that was cancelled did
 * not fail either. Something has to translate one into the other, and when that translation
 * is written inline at the call site it acquires two shapes it should not have.
 *
 * It used to carry a nullable carrier for the body's own outcome and a final branch that
 * could not be reached: the nullable made "the body was cancelled" and "the body said
 * something nobody has a name for" the same value, and the unreachable branch said out loud
 * that the algebra had a case nobody had decided what to do with.
 *
 * ## What it answers
 *
 * - a body that succeeded satisfies the predicate;
 * - a body that failed does not, and its failure IS the reason;
 * - a body that came back unstable did not settle the predicate, and the reason is said in
 *   the engine's own vocabulary rather than left to the caller;
 * - a body that was cancelled did not answer at all, and the cancellation reason is carried
 *   as it is: the poll was stopped, and that is not a failed predicate.
 *
 * Total over the closed body algebra, and every branch says what it decided.
 */
internal fun waitUntilPredicateOutcome(outcome: BodyOutcome): WaitUntilPredicateOutcome = when (outcome) {
    is BodyOutcome.Cancelled -> WaitUntilPredicateOutcome.Cancelled(reason = outcome.reason)

    is BodyOutcome.Completed -> when (val result = outcome.outcome) {
        is StepOutcome.Success -> WaitUntilPredicateOutcome.Satisfied

        is StepOutcome.Failure -> WaitUntilPredicateOutcome.Failed(result.failure)

        is StepOutcome.Unstable -> WaitUntilPredicateOutcome.Failed(
            PipelineFailure(
                FailureKind.SCRIPT,
                "waitUntil body marked unstable",
            ),
        )
    }
}
