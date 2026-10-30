package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that reads the wrapper's state from the two facts the launch holds is a decision, and
 * a decision made of facts can be wrong without anybody noticing.
 *
 * **The guarantee.** The wrapper's state is read from what the launch OBSERVED — the cookie the
 * wrapper publishes and the process the launcher started — and never inferred from the absence of
 * a file. A wrapper that is up and has not published its identity yet is a wrapper that is
 * starting, not a wrapper that never arrived; a published identity does not outlive the wrapper's
 * death, because it says the wrapper STARTED and not that it is still there; and a wrapper that is
 * gone carries its own exit code when the platform reported one, because the process that failed
 * is the one that knows why it failed.
 *
 * **The observable capacity it serves.** A reader of a durable run can tell a step that is still
 * starting from a step whose wrapper died, and can read WHY it died. Before this, the launch
 * established the wrapper's readiness with a probe process and a fixed hundred milliseconds, and
 * then read the wrapper's state from the cookie's absence: a wrapper that had not written its
 * cookie yet was reported as a wrapper that never started, and its terminal said so. The reader
 * was told a falsehood about a step that was, in fact, about to run.
 *
 * **Why this instrument and not another.** The rule is a pure function of two facts, so the
 * cheapest instrument that can fail is a call to it. A test that launched a real wrapper would
 * cost a process, a clock and a filesystem, and it would say THAT the reading broke, never WHICH
 * of its branches broke. The rows below are arranged so that each DECISION of the rule owns one
 * row: a change to the decision that the published identity alone proves life turns exactly one
 * row red and leaves the rest green.
 *
 * **The focal mutation.** Inverting the availability condition — letting the published identity
 * stand for the wrapper's life, so that the rule stops asking whether the wrapper is still there
 * — must turn EXACTLY ONE row red: the row that says a wrapper which published its identity and
 * is gone ends, carrying its own exit code. The wrapper that is up and identified, the wrapper that
 * is up and still starting, the wrapper that is gone with a code and the wrapper that is gone
 * without one all stay green. A mutation that turns more than one row red is not focal; one that
 * turns none is not evidence.
 *
 * **What this does not freeze.** That the cookie holds the wrapper's pid, that the handle the
 * launcher keeps IS the wrapper, and that the poll asks this rule on every iteration: those are
 * observed where they happen — the first two in the launch, the last in the poll — not here. What
 * is checked here is the verdict the facts feed, which is the part that can be wrong while every
 * observation succeeds.
 */
class ShellAvailabilityTest {

    @Test
    fun `a wrapper that published its identity is identified`() {
        val availability = shellAvailability(
            identityPublished = true,
            wrapperEnded = false,
        )

        assertEquals(
            ShellAvailability.Identified,
            availability,
            "a wrapper that published its identity was not read as identified",
        )
    }

    @Test
    fun `a published identity does not outlive the wrapper's death`() {
        // The wrapper removes the cookie only at the end of its script, so a cookie that outlives
        // the wrapper is a wrapper that was killed in the middle of its script. The identity says it
        // STARTED; it does not say it is still there. Reading the identity as proof of life kept
        // the wait open until the ceiling and reported that death as a timeout, losing the code
        // the wrapper left with — the reason the execution failed.
        val availability = shellAvailability(
            identityPublished = true,
            wrapperEnded = true,
            wrapperExitCode = 3,
        )

        assertEquals(
            ShellAvailability.Ended(3),
            availability,
            "a wrapper that was gone was read as one that was still identified",
        )
    }

    @Test
    fun `a wrapper that is up and has not published its identity yet is running`() {
        // The wrapper writes its cookie as its first act, so the window between the launch and
        // that write is real. Reading the wrapper as gone inside that window reported a wrapper
        // that was on its way as one that never arrived.
        val availability = shellAvailability(
            identityPublished = false,
            wrapperEnded = false,
        )

        assertEquals(
            ShellAvailability.Running,
            availability,
            "a wrapper that was still up was read as one that had ended",
        )
    }

    @Test
    fun `a wrapper that is gone ends, carrying its own exit code`() {
        val availability = shellAvailability(
            identityPublished = false,
            wrapperEnded = true,
            wrapperExitCode = 3,
        )

        assertEquals(
            ShellAvailability.Ended(3),
            availability,
            "the code the wrapper exited with did not travel with its ending",
        )
    }

    @Test
    fun `a wrapper that is gone and left no code ends without one`() {
        // A code nobody observed is never invented. The reason then says only what is known.
        val availability = shellAvailability(
            identityPublished = false,
            wrapperEnded = true,
        )

        assertEquals(
            ShellAvailability.Ended(null),
            availability,
            "an exit code was invented for a wrapper that left none",
        )
    }
}
