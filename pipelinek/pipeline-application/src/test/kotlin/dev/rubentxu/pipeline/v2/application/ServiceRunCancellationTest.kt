package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.control.CancelOutcome
import dev.rubentxu.pipeline.v2.runtime.control.CancelReason
import dev.rubentxu.pipeline.v2.runtime.control.CancelRefusal
import dev.rubentxu.pipeline.v2.runtime.control.RuntimeControlPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cancellation of a run, over the socket.
 *
 * ## The guarantee under trial
 *
 * That the cancellation ends the CHILD before the port writes the terminal,
 * and that every refusal of the port is answered with the NAME of the fact
 * that stopped it.
 *
 * ## The capacity this serves
 *
 * A client that asks the service to cancel a run needs the run to be CLOSED
 * and not merely killed: a process that was killed without a terminal is a
 * run the product cannot answer about, and a client that reads an EOF where
 * the product guarantees a fact cannot tell a cancelled run from a broken
 * service.
 *
 * ## Why the two owners are faked here
 *
 * Because what is under trial is the CONVERSATION between the two: which of
 * them is asked first, and what the answer of the port becomes. Whether a
 * real process dies and a real lease is taken over is a fact about the
 * machine, and the integration lane is where it is observed.
 */
class ServiceRunCancellationTest {

    @Test
    fun `the child is ended before the terminal is written`() {
        val events = mutableListOf<String>()
        val cancellation = cancellation(events, CancelOutcome.Cancelled(AttemptId(1), 1700L, 2))

        val outcome = cancellation.cancel("run-1", null)

        assertEquals(
            listOf("end:run-1", "cancel:run-1"),
            events,
            "the PROCESS is ended first: the terminal can only be written when the lease is free",
        )
        val cancelled = assertInstanceOf(ServiceCancelOutcome.Cancelled::class.java, outcome)
        assertEquals(1700L, cancelled.terminalAtMs, "the answer carries the terminal of the run")
    }

    @Test
    fun `a cancellation without a reason carries the product's own word for it`() {
        val reasons = mutableListOf<CancelReason>()
        val cancellation = cancellation(mutableListOf(), CancelOutcome.Cancelled(AttemptId(1), 1700L, 0), reasons)

        cancellation.cancel("run-1", null)

        assertEquals(
            listOf(CancelReason.UserRequested),
            reasons,
            "a cancellation without a reason is still a cancellation with a reason",
        )
    }

    @Test
    fun `a cancellation with a reason carries the words of the operator`() {
        val reasons = mutableListOf<CancelReason>()
        val cancellation = cancellation(mutableListOf(), CancelOutcome.Cancelled(AttemptId(1), 1700L, 0), reasons)

        cancellation.cancel("run-1", "the operator changed their mind")

        assertEquals(
            listOf(CancelReason.Annotated("the operator changed their mind")),
            reasons,
            "the reason is recorded verbatim in the run's own history",
        )
    }

    @Test
    fun `a run that was already closed is answered with the terminal that is already there`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.AlreadyCancelled(AttemptId(1), 1700L),
        )

        val outcome = cancellation.cancel("run-1", null)

        val already = assertInstanceOf(ServiceCancelOutcome.AlreadyCancelled::class.java, outcome)
        assertEquals(
            1700L,
            already.terminalAtMs,
            "a cancellation repeated is an answer and not an error: the run is closed, once",
        )
    }

    @Test
    fun `a run that nobody knows is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.UnknownRun("run-1")),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals("the-run-is-unknown", refused.reason, "the refusal travels with its NAME")
    }

    @Test
    fun `a run that already finished is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.RunTerminal("success")),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals(
            "the-run-is-already-terminal",
            refused.reason,
            "a run that reached a terminal of its own is not cancelled again",
        )
    }

    @Test
    fun `a run that another process publishes is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.LeaseHeldByAnother("owner-9", 3L)),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals(
            "the-lease-is-held-by-another",
            refused.reason,
            "a cancel never crosses a live lease, and the refusal says so",
        )
        assertTrue(
            refused.detail.contains("owner-9"),
            "the refusal names the authority that holds the run: ${refused.detail}",
        )
    }

    @Test
    fun `a run whose journal cannot be written is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.JournalUnavailable("the disk is full")),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals(
            "the-journal-is-unavailable",
            refused.reason,
            "a cancellation that cannot write its terminal fails closed",
        )
    }

    @Test
    fun `a run whose storage failed is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.StorageError("the store is locked")),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals("the-storage-failed", refused.reason, "the refusal names the storage that failed")
    }

    @Test
    fun `a run in a state that cannot be cancelled is refused by name`() {
        val cancellation = cancellation(
            mutableListOf(),
            CancelOutcome.Refused(CancelRefusal.IncompatibleRunState("the run is between steps")),
        )

        val outcome = cancellation.cancel("run-1", null)

        val refused = assertInstanceOf(ServiceCancelOutcome.Refused::class.java, outcome)
        assertEquals(
            "the-run-state-is-incompatible",
            refused.reason,
            "a state that is neither live nor terminal has its own name",
        )
    }

    @Test
    fun `a run that this service does not supervise is still cancelled by the port`() {
        val events = mutableListOf<String>()
        val cancellation = cancellation(events, CancelOutcome.Cancelled(AttemptId(1), 1700L, 0), ended = false)

        val outcome = cancellation.cancel("run-1", null)

        assertEquals(
            listOf("end:run-1", "cancel:run-1"),
            events,
            "the child is always asked for, and the port closes the run whether there " +
                "was a child here or not: that is the reconciliation",
        )
        assertInstanceOf(ServiceCancelOutcome.Cancelled::class.java, outcome)
    }

    /**
     * The cancellation, over the [events] it writes.
     */
    private fun cancellation(
        events: MutableList<String>,
        outcome: CancelOutcome,
        reasons: MutableList<CancelReason> = mutableListOf(),
        ended: Boolean = true,
    ): ServiceRunCancellation = ServiceRunCancellation(
        ender = object : ServiceRunEnder {
            override fun end(runId: String): Boolean {
                events += "end:$runId"
                return ended
            }
        },
        control = object : RuntimeControlPort {
            override fun cancel(runId: String, reason: CancelReason): CancelOutcome {
                events += "cancel:$runId"
                reasons += reason
                return outcome
            }
        },
    )
}
