package dev.rubentxu.pipeline.v2.runtime.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that decides whether a cancel may proceed.
 *
 * The instrument here is the cheapest one that can fail: a pure function over
 * four observed facts, with no store, no clock and no filesystem. The adapter
 * that gathers the facts and the E2E that exercises the whole verb are the
 * instruments for the parts this cannot see.
 */
class RunCancellationDecisionTest {

    @Test
    fun `a run nobody owns and that never finished may be cancelled`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = true,
                lease = RunCancellationDecision.LeaseVerdict.Held,
                terminal = null,
                cancelRecorded = false,
            ),
        )

        assertEquals(RunCancellationDecision.Decision.Proceed, decision)
    }

    @Test
    fun `a run nobody knows is refused`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = false,
                lease = RunCancellationDecision.LeaseVerdict.Held,
                terminal = null,
                cancelRecorded = false,
            ),
        )

        assertEquals(
            RunCancellationDecision.Decision.Refused(RunCancellationDecision.Refusal.UnknownRun),
            decision,
        )
    }

    @Test
    fun `a live owner is refused and named, even when the run also looks terminal`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = true,
                lease = RunCancellationDecision.LeaseVerdict.LiveOwner("cli-42", 7L),
                terminal = RunCancellationDecision.Terminal("success", 1_000L),
                cancelRecorded = false,
            ),
        )

        assertEquals(
            RunCancellationDecision.Decision.Refused(
                RunCancellationDecision.Refusal.LiveOwnerHeld("cli-42", 7L),
            ),
            decision,
        )
    }

    @Test
    fun `a run that already finished on its own is refused with its outcome`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = true,
                lease = RunCancellationDecision.LeaseVerdict.Held,
                terminal = RunCancellationDecision.Terminal("success", 1_000L),
                cancelRecorded = false,
            ),
        )

        assertEquals(
            RunCancellationDecision.Decision.Refused(
                RunCancellationDecision.Refusal.AlreadyTerminal("success"),
            ),
            decision,
        )
    }

    @Test
    fun `a run that was already cancelled is a no-op and not a refusal`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = true,
                lease = RunCancellationDecision.LeaseVerdict.Held,
                terminal = RunCancellationDecision.Terminal("aborted", 2_000L),
                cancelRecorded = true,
            ),
        )

        assertEquals(RunCancellationDecision.Decision.AlreadyCancelled(2_000L), decision)
    }

    @Test
    fun `a recorded cancel without a terminal fact finishes the job instead of closing the run`() {
        val decision = RunCancellationDecision.decide(
            RunCancellationDecision.Facts(
                runKnown = true,
                lease = RunCancellationDecision.LeaseVerdict.Held,
                terminal = null,
                cancelRecorded = true,
            ),
        )

        assertEquals(RunCancellationDecision.Decision.Proceed, decision)
    }
}
