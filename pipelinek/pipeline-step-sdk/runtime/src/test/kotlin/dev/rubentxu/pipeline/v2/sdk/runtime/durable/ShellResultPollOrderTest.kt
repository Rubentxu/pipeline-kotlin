package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ORDER in which the wait gathers its facts is part of the durable shell's protocol.
 *
 * **The guarantee.** An execution that published its result is never reported as a loss. The
 * wrapper publishes its exit code BEFORE it ends, so a wrapper that is gone had already
 * published: a result read AFTER the ending can only miss a result that does not exist. A
 * result read BEFORE it leaves a window in which the publication can land, and a completion
 * read through that window is reported as a loss. A loss is retryable, so a step that completed
 * would run again and its effect would happen twice.
 *
 * **The observable capacity it serves.** A reader of a durable run can tell a step that finished
 * from a step whose outcome could not be established, even when the machine is loaded enough to
 * preempt the wait between two readings. Under load that window is wide enough to be hit, and a
 * durable run that reports a completed step as lost sends its reader into a retry that repeats
 * an effect that already happened.
 *
 * **Why this instrument and not another.** The wait reads two facts, and the order in which it
 * reads them is the whole of this, so the instrument has to drive the wait itself: the reading
 * it is given publishes its result in the instant the state is observed. The rule that turns
 * the readings into an outcome is checked where it lives, in [ShellResultPollTest]; this checks
 * the order in which the wait asks for the readings it feeds. No child is started, no file is
 * written and no clock is consulted: the reading is the only thing that has to be true.
 *
 * **What this does not freeze.** That the wrapper publishes before it ends — that is the
 * protocol's promise, written down in [ShellLaunchPreparation] — and how a control directory is
 * read, which the reading of the protocol owns.
 */
class ShellResultPollOrderTest {

    @Test
    fun `a result published while the wrapper ends is published, not lost`() {
        // The wrapper publishes its result and THEN ends. The reading that observes the ending
        // is therefore an instant at which the publication has already been made, and the result
        // read after it has to see it.
        val reading = PublishingThenEndingReading(exitCode = 0)

        val outcome = ShellResultPoll(reading, pollIntervalMs = 0).await(ceilingNanos = null) {
            false
        }

        val published = assertInstanceOf(
            ShellPollOutcome.Published::class.java,
            outcome,
            "an execution that published its result was reported as a loss",
        )
        assertEquals(0, published.exitCode, "the published exit code did not reach the outcome")
        assertTrue(
            reading.calls.indexOf("state") < reading.calls.indexOf("result"),
            "the wait read the result before the wrapper's state",
        )
    }

    @Test
    fun `a wrapper that ends without publishing anything is still a loss`() {
        // The order of the readings does not turn every ending into a success: a wrapper that
        // ended and published nothing has no exit code to report, and the honest terminal is
        // the one that says the outcome could not be established.
        val reading = EndingWithoutPublishingReading(exitCode = 0)

        val outcome = ShellResultPoll(reading, pollIntervalMs = 0).await(ceilingNanos = null) {
            false
        }

        val ended = assertInstanceOf(
            ShellPollOutcome.WrapperEnded::class.java,
            outcome,
            "a wrapper that ended without publishing was not reported as a loss",
        )
        assertEquals(0, ended.exitCode, "the code the wrapper left with did not travel")
    }

    /**
     * A reading whose publication lands in the instant the wrapper's state is observed.
     *
     * The wrapper publishes its result and THEN ends, so an observer that sees the ending sees
     * the publication too — but only if it reads the result afterwards.
     */
    private class PublishingThenEndingReading(
        private val exitCode: Int,
    ) : ShellProtocolReading {

        /** What the wait read, in the order it read it. */
        val calls = mutableListOf<String>()

        private var published = false

        override fun state(): ShellAvailability {
            calls += "state"
            published = true
            return ShellAvailability.Ended(exitCode)
        }

        override fun result(): ShellResultRead {
            calls += "result"
            return if (published) ShellResultRead.Code(exitCode) else ShellResultRead.Absent
        }
    }

    /** A reading of a wrapper that is gone and published nothing at all. */
    private class EndingWithoutPublishingReading(
        private val exitCode: Int,
    ) : ShellProtocolReading {

        override fun state(): ShellAvailability = ShellAvailability.Ended(exitCode)

        override fun result(): ShellResultRead = ShellResultRead.Absent
    }
}
