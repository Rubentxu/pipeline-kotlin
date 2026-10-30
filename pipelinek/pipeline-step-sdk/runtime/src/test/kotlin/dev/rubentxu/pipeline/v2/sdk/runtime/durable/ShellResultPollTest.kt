package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The rule that says when the wait for a durable shell's result ends, and what it ends with, is
 * a decision over facts, and the decision is where this can be wrong without anybody noticing.
 *
 * **The guarantee.** An execution that publishes no result is never reported as an exit code, and
 * an execution that was asked not to time out is not abandoned by a clock nobody set. The wait ends
 * when the wrapper publishes a result, when it ends without publishing one, when the execution
 * expires, or when the deadline's ceiling elapses — and each of those says what it means: an exit
 * with the published code, a loss, or a timeout. When the wrapper ends without publishing, the
 * code it left with travels with that ending.
 *
 * **The observable capacity it serves.** A reader of a durable run can tell a step that finished
 * from a step whose outcome could not be established, and can read the reason the wrapper gave for
 * dying. Before this, the whole of that rested on inline arithmetic inside one executor method:
 * the poll had a ceiling of an hour for an execution with no deadline, and when the ceiling was
 * reached the executor published the exit code -1, a number no wrapper ever wrote. The only way to
 * observe that was to wait an hour, and the test that pinned it was green.
 *
 * **Why this instrument and not another.** The rule is a pure function of facts — what the result
 * file holds, what the launch observed about the wrapper, whether the execution expired, whether
 * the ceiling elapsed — so the cheapest instrument that can fail is a call to it. A test that
 * launched a real child would cost a process, a clock and a filesystem, and it would say THAT the
 * wait broke, never WHICH of its rules broke. The wrapper's state arrives here as the reading the
 * launch made, so this file checks the wait's verdict and the launch's rule is checked where it
 * lives, in [ShellAvailabilityTest].
 *
 * **What this does not freeze.** That the executor gathers the right facts, that the wrapper
 * publishes the result before it removes the cookie, and that the reconciler reads the same
 * control directory: those are observed where they happen, not here. What is checked here is the
 * verdict those facts feed, which is the part that can be wrong while every observation succeeds.
 */
class ShellResultPollTest {

    @Test
    fun `a published result ends the wait with its exit code`() {
        val outcome = shellPollOutcome(
            result = ShellResultRead.Code(exitCode = 7),
            availability = ShellAvailability.Identified,
            expired = false,
            ceilingReached = false,
        )

        val published = assertInstanceOf(
            ShellPollOutcome.Published::class.java,
            outcome,
            "a published result did not end the wait",
        )
        assertEquals(7, published.exitCode, "the published exit code did not reach the outcome")
    }

    @Test
    fun `a result that is not an exit code does not end the wait as an exit`() {
        // The protocol promises an exit code in that file. Anything else is a record of some
        // other protocol, and reading it as this one's success is the fabrication the reconciler
        // refuses too.
        val outcome = shellPollOutcome(
            result = ShellResultRead.NotAnExitCode,
            availability = ShellAvailability.Identified,
            expired = false,
            ceilingReached = false,
        )

        assertInstanceOf(
            ShellPollOutcome.ResultNotAnExitCode::class.java,
            outcome,
            "a result that is not an exit code was read as a result",
        )
    }

    @Test
    fun `a wrapper that ended without publishing ends the wait`() {
        // The wrapper publishes the result BEFORE it removes the cookie, so a wrapper that is gone
        // with no result published never reached the publication.
        val outcome = shellPollOutcome(
            result = ShellResultRead.Absent,
            availability = ShellAvailability.Ended(null),
            expired = false,
            ceilingReached = false,
        )

        assertInstanceOf(
            ShellPollOutcome.WrapperEnded::class.java,
            outcome,
            "a wrapper that ended without publishing kept the wait open",
        )
    }

    @Test
    fun `the code the wrapper exited with travels with its ending`() {
        // The process that failed is the one that knows why it failed, so the code is carried and
        // not reconstructed. A terminal that said only "the wrapper ended" left the reader to
        // guess between a script that could not be found and a host that refused to run it.
        val outcome = shellPollOutcome(
            result = ShellResultRead.Absent,
            availability = ShellAvailability.Ended(3),
            expired = false,
            ceilingReached = false,
        )

        val ended = assertInstanceOf(
            ShellPollOutcome.WrapperEnded::class.java,
            outcome,
            "a wrapper that ended without publishing kept the wait open",
        )
        assertEquals(3, ended.exitCode, "the code the wrapper left with did not travel")
    }

    @Test
    fun `a running wrapper with nothing published keeps the wait open`() {
        val outcome = shellPollOutcome(
            result = ShellResultRead.Absent,
            availability = ShellAvailability.Running,
            expired = false,
            ceilingReached = false,
        )

        assertInstanceOf(
            ShellPollOutcome.KeepWaiting::class.java,
            outcome,
            "the wait was closed while the wrapper was still running",
        )
    }

    @Test
    fun `the expiry wins over a published result`() {
        // The child that published the code was killed. A wrapper that keeps writing after its
        // child died does not get to decide whether the execution succeeded.
        val outcome = shellPollOutcome(
            result = ShellResultRead.Code(exitCode = 0),
            availability = ShellAvailability.Identified,
            expired = true,
            ceilingReached = false,
        )

        assertInstanceOf(
            ShellPollOutcome.Expired::class.java,
            outcome,
            "an expired execution was published as something other than an expiry",
        )
    }

    @Test
    fun `the expiry wins over the wrapper's absence`() {
        val outcome = shellPollOutcome(
            result = ShellResultRead.Absent,
            availability = ShellAvailability.Ended(null),
            expired = true,
            ceilingReached = false,
        )

        assertInstanceOf(
            ShellPollOutcome.Expired::class.java,
            outcome,
            "an expired execution was published as a loss",
        )
    }

    @Test
    fun `the ceiling ends the wait that nothing else did`() {
        val outcome = shellPollOutcome(
            result = ShellResultRead.Absent,
            availability = ShellAvailability.Running,
            expired = false,
            ceilingReached = true,
        )

        assertInstanceOf(
            ShellPollOutcome.CeilingElapsed::class.java,
            outcome,
            "the ceiling did not end the wait",
        )
    }
}
