package dev.rubentxu.pipeline.v2.application.durable.waituntil

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilPredicateOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The reading of ONE poll's answer as the predicate's verdict.
 *
 * The claim under test is that the four answers the body algebra can carry are four DIFFERENT
 * verdicts, and that the reading is total: every answer is read, none of them collapses into
 * another, and none of them is read as "the body said something nobody has a name for".
 */
class WaitUntilOutcomeFoldTest {

    @Test
    fun `a body that succeeded satisfies the predicate`() {
        val outcome = waitUntilPredicateOutcome(BodyOutcome.Completed(StepOutcome.Success))

        assertEquals(WaitUntilPredicateOutcome.Satisfied, outcome)
    }

    @Test
    fun `a body that failed does not satisfy the predicate and carries its failure`() {
        val failure = PipelineFailure(FailureKind.SCRIPT, "the predicate refused")

        val outcome = waitUntilPredicateOutcome(
            BodyOutcome.Completed(StepOutcome.Failure(failure)),
        )

        val failed = outcome as WaitUntilPredicateOutcome.Failed
        assertSame(failure, failed.failure)
    }

    @Test
    fun `a body that came back unstable did not settle the predicate`() {
        val outcome = waitUntilPredicateOutcome(BodyOutcome.Completed(StepOutcome.Unstable))

        val failed = outcome as WaitUntilPredicateOutcome.Failed
        assertEquals(FailureKind.SCRIPT, failed.failure.kind)
    }

    @Test
    fun `a cancelled body is a cancellation and not a failed predicate`() {
        val outcome = waitUntilPredicateOutcome(BodyOutcome.Cancelled(CancellationReason.Deadline))

        assertEquals(
            WaitUntilPredicateOutcome.Cancelled(CancellationReason.Deadline),
            outcome,
        )
    }

    @Test
    fun `the cancellation reason travels as it is`() {
        val outcome = waitUntilPredicateOutcome(
            BodyOutcome.Cancelled(CancellationReason.ParentCancelled),
        )

        val cancelled = outcome as WaitUntilPredicateOutcome.Cancelled
        assertEquals(CancellationReason.ParentCancelled, cancelled.reason)
    }

    @Test
    fun `no body answer is read as something the fold has no name for`() {
        val outcomes = listOf(
            waitUntilPredicateOutcome(BodyOutcome.Completed(StepOutcome.Success)),
            waitUntilPredicateOutcome(
                BodyOutcome.Completed(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "no"))),
            ),
            waitUntilPredicateOutcome(BodyOutcome.Completed(StepOutcome.Unstable)),
            waitUntilPredicateOutcome(BodyOutcome.Cancelled(CancellationReason.ScopeShutDown)),
        )

        assertEquals(
            listOf(
                WaitUntilPredicateOutcome.Satisfied::class.simpleName,
                WaitUntilPredicateOutcome.Failed::class.simpleName,
                WaitUntilPredicateOutcome.Failed::class.simpleName,
                WaitUntilPredicateOutcome.Cancelled::class.simpleName,
            ),
            outcomes.map { it::class.simpleName },
        )
    }
}
