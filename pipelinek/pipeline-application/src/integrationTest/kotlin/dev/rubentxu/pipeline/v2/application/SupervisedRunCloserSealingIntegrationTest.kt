package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The closer of a run whose child was killed, and the order in which it says the run is over.
 *
 * ## The guarantee under trial
 *
 * That a run is never closed and unsealed at the same time: the output of the run is sealed BEFORE
 * its terminal is written, and a seal that cannot be written leaves the run without a terminal.
 *
 * ## Why the sealer is a double and the store is not
 *
 * Because the question is ORDER, and order is observable only against the real store: the double
 * asks the store, at the moment it is asked to seal, whether the terminal is already there. A
 * double that recorded "I was called" would pass for a closer that sealed after it closed.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SupervisedRunCloserSealingIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path

    private val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        (admission as EventStoreAdmission.Admitted).store.close()
    }

    @Test
    fun `the output of the run is sealed before its terminal is written`() {
        val sealer = RecordingSealer(SupervisedRunSeal.Sealed(1)) { holdsTerminal(RUN) }
        val closer = EventStoreSupervisedRunCloser(db.toString(), clock, sealer)

        val closure = closer.closeWithoutTerminal(RUN, SupervisedRunEnd.TimedOut)

        assertInstanceOf(SupervisedRunClosure.Closed::class.java, closure)
        assertEquals(listOf(RUN), sealer.asked, "the closer asked for the seal of the run it closed")
        assertEquals(
            listOf(false),
            sealer.terminalHeldWhenAsked,
            "the terminal was NOT in the store when the seal was asked for: the run is never " +
                "closed and unsealed at the same time",
        )
        assertTrue(holdsTerminal(RUN), "once sealed, the closer wrote the terminal of the run")
    }

    @Test
    fun `a seal that is refused leaves the run without a terminal and says why`() {
        val sealer = RecordingSealer(SupervisedRunSeal.Refused("the disk is full")) { holdsTerminal(RUN) }
        val closer = EventStoreSupervisedRunCloser(db.toString(), clock, sealer)

        val closure = closer.closeWithoutTerminal(RUN, SupervisedRunEnd.TimedOut)

        val refused = assertInstanceOf(SupervisedRunClosure.Refused::class.java, closure)
        assertTrue(
            refused.detail.contains("the disk is full"),
            "the refusal carries the reason the seal gave: '${refused.detail}'",
        )
        assertTrue(
            !holdsTerminal(RUN),
            "a run whose output could not be sealed stays OPEN: a terminal over an open stream " +
                "is the run that is closed and unsealed at once",
        )
    }

    @Test
    fun `a run that already holds a terminal is not sealed`() {
        val sealer = RecordingSealer(SupervisedRunSeal.Sealed(1)) { holdsTerminal(RUN) }
        val first = EventStoreSupervisedRunCloser(db.toString(), clock, sealer)
        first.closeWithoutTerminal(RUN, SupervisedRunEnd.TimedOut)
        sealer.asked.clear()

        val closure = first.closeWithoutTerminal(RUN, SupervisedRunEnd.Cancelled)

        assertEquals(SupervisedRunClosure.AlreadyEnded, closure)
        assertEquals(
            emptyList<String>(),
            sealer.asked,
            "the run was closed by whoever wrote its terminal, and the seal is theirs",
        )
    }

    @Test
    fun `the terminal the closer writes is aborted when the child was killed`() {
        val closer = EventStoreSupervisedRunCloser(
            db.toString(),
            clock,
            RecordingSealer(SupervisedRunSeal.Sealed(0)) { false },
        )

        closer.closeWithoutTerminal(RUN, SupervisedRunEnd.TimedOut)

        assertEquals(RunOutcome.ABORTED_TOKEN, terminalOutcome(RUN))
    }

    /** Whether the store holds a terminal for [runId], asked through a door of its own. */
    private fun holdsTerminal(runId: String): Boolean = terminalOutcome(runId) != null

    private fun terminalOutcome(runId: String): String? {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        return try {
            store.eventsFor(runId).filterIsInstance<RunFinished>().lastOrNull()?.outcome
        } finally {
            store.close()
        }
    }

    /**
     * A sealer that answers [answer] and writes down, each time it is asked, whether the terminal
     * of the run was already in the store.
     */
    private class RecordingSealer(
        private val answer: SupervisedRunSeal,
        private val terminalHeld: () -> Boolean,
    ) : SupervisedRunOutputSealer {

        val asked = mutableListOf<String>()

        val terminalHeldWhenAsked = mutableListOf<Boolean>()

        override fun sealOutputOf(runId: String): SupervisedRunSeal {
            asked.add(runId)
            terminalHeldWhenAsked.add(terminalHeld())
            return answer
        }
    }

    private companion object {
        /** The run the closer is asked about. */
        const val RUN: String = "run-1"
    }
}
