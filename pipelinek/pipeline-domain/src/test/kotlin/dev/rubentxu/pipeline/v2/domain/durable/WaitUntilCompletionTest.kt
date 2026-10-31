package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The guarantee: the waitUntil terminal is ONE algebra, and every surface a consumer can read
 * — the durable status, the wire token and the typed failure — is a projection of it.
 *
 * The durable branch used to have no reachable terminal at all, so the deadline case existed in
 * the algebra but nothing ever produced it. That is the shape this file guards: the case that
 * ends a wait is reached, and it says the same thing in all three surfaces, so a reader of the
 * wire and a reader of the typed result cannot disagree about how the wait ended.
 *
 * The second row holds the historical wire schema. The event contract is frozen, so a second
 * terminal appearing in the algebra has to be a visible change to that schema, never a new
 * literal that only the wire knows about.
 */
class WaitUntilCompletionTest {

    @Test
    fun `the deadline terminal projects the timeout on all three surfaces`() {
        val completion = WaitUntilCompletion.DeadlineExceeded(attempt = 3, ceilingMs = 200L)

        assertEquals(OperationStatus.FAILED_TIMEOUT, completion.durableStatus, "the durable status is the projection")
        assertEquals("deadline-exceeded", completion.wireOutcome, "the wire token is the projection")

        val outcome = completion.toStepOutcome()
        val failure = assertInstanceOf(
            StepOutcome.Failure::class.java,
            outcome,
            "the deadline terminal must produce a typed failure",
        ).failure
        assertEquals(FailureKind.TIMEOUT, failure.kind, "the kind is the projection")
        assertEquals(
            "waitUntil deadline exceeded at poll 3 (200ms backoff ceiling)",
            failure.message,
            "the message names the poll that ran at the ceiling and the ceiling itself",
        )
    }

    @Test
    fun `the wire schema holds exactly the three historical tokens`() {
        assertEquals(
            setOf("completed", "deadline-exceeded", "aborted"),
            WaitUntilCompletionWireOutcomes,
            "a second terminal has to be a visible change to the frozen schema",
        )
    }
}
