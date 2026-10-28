package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.RunLeaseObservationPort
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the introspection plane is allowed to say about a run.
 *
 * ## The guarantee under trial
 *
 * The introspection plane answers about the RUN. The run's terminal fact is
 * the event lane's `RunFinished` bookend — the same bookend the writer writes
 * and the same one the cancel writes.
 *
 * It used to be the journal's first row whose status is terminal, and that is
 * a fact about an OPERATION. An operation reaches a terminal state long
 * before the run does: a run whose first step succeeded and whose second
 * step is still running already has a terminal row, so the plane reported a
 * half-finished run as finished. That is the same defect the control adapter
 * fixed, and it stood here precisely because nothing consumed this adapter.
 *
 * ## The two halves of the guarantee
 *
 * 1. A terminal OPERATION row does not end a RUN. The row and the run are
 *    different subjects and the plane must not confuse them.
 * 2. The RUN's own bookend does end it, and the outcome the plane reports is
 *    the book's outcome, not a re-derivation from a journal row.
 *
 * ## Why the stores answer from memory
 *
 * The adapter's read side is a set of narrow ports, and this test wires them
 * to in-memory answers: the production `InMemoryOperationJournal` and
 * `InMemoryReplayCursorStore`, plus small fakes for the ports that have no
 * in-memory implementation. Nothing here touches a disk, a socket or a
 * clock that moves. That is what makes this a unit test of a decision rather
 * than a second harness of the stores.
 */
class RuntimeIntrospectionPortStoreAdapterTest {

    private val clock: Clock = FixedClock
    private val runId: String = "run-under-trial"

    /**
     * The defect row: an operation reached a terminal state, the run did not.
     *
     * Before the fix this returned `Terminal`, which is a lie about the run:
     * the run is still going.
     */
    @Test
    fun `una fila terminal de operacion no termina el run`() {
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.SUCCEEDED)),
            laneEvents = emptyList(),
        )

        val observation = adapter.inspect(runId).observation()

        assertInstanceOf(
            RuntimeObservation.Running::class.java,
            observation,
            "a run with a terminal OPERATION row and no bookend is still running; " +
                "the operation ended, the run did not",
        )
    }

    /**
     * The run's own bookend ends the run, and its outcome is the outcome the
     * plane reports.
     */
    @Test
    fun `el bookend del run lo termina con su propio outcome`() {
        val finishedAt = Instant.parse("2026-10-21T09:30:00Z")
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.SUCCEEDED)),
            laneEvents = listOf(bookend(outcome = RunOutcome.SUCCESS_TOKEN, at = finishedAt)),
        )

        val observation = adapter.inspect(runId).observation()

        val terminal = assertInstanceOf(RuntimeObservation.Terminal::class.java, observation)
        assertEquals(TerminalObservation.Succeeded(finishedAt.toEpochMilli()), terminal.terminal)
        assertEquals(finishedAt.toEpochMilli(), terminal.terminalAtMs)
    }

    /**
     * A failed run is reported as failed, with the book's own vocabulary.
     */
    @Test
    fun `un bookend de fallo se reporta como fallo`() {
        val finishedAt = Instant.parse("2026-10-21T09:45:00Z")
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.FAILED)),
            laneEvents = listOf(bookend(outcome = RunOutcome.FAILURE_TOKEN, at = finishedAt)),
        )

        val observation = adapter.inspect(runId).observation()

        val terminal = assertInstanceOf(RuntimeObservation.Terminal::class.java, observation)
        assertEquals(
            TerminalObservation.Failed(RunOutcome.FAILURE_TOKEN, finishedAt.toEpochMilli()),
            terminal.terminal,
        )
    }

    /**
     * A run that ended badly enough to be aborted — which is what the cancel
     * writes — is reported as aborted, not as a failure.
     */
    @Test
    fun `un bookend abortado se reporta como abortado`() {
        val finishedAt = Instant.parse("2026-10-21T09:50:00Z")
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.ABORTED)),
            laneEvents = listOf(bookend(outcome = RunOutcome.ABORTED_TOKEN, at = finishedAt)),
        )

        val observation = adapter.inspect(runId).observation()

        val terminal = assertInstanceOf(RuntimeObservation.Terminal::class.java, observation)
        assertEquals(
            TerminalObservation.Aborted(finishedAt.toEpochMilli()),
            terminal.terminal,
        )
    }

    /**
     * The plane is read-only by construction: reading a run's state writes
     * nothing.
     *
     * The journal and the cursor are the production in-memory stores, so a
     * write would be visible in their sizes; the frame index counts the
     * declarations it was asked for.
     */
    @Test
    fun `la lectura no muta nada`() {
        val journal = InMemoryOperationJournal(clock)
        val cursors = InMemoryReplayCursorStore(clock)
        val frames = CountingFrameIndex()
        val adapter = adapter(
            journal = journal,
            cursors = cursors,
            frames = frames,
            journalRows = listOf(operation(status = OperationStatus.SUCCEEDED)),
            laneEvents = listOf(bookend(outcome = RunOutcome.SUCCESS_TOKEN, at = Instant.EPOCH)),
        )

        adapter.inspect(runId).observation()

        assertEquals(1, journal.size(), "the journal gained a row: the read wrote")
        assertEquals(0, frames.declarations, "the frame index gained a stream: the read wrote")
        assertNull(cursors.load(runId), "the replay cursor moved: the read wrote")
    }

/** A clock that does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-10-21T10:00:00Z")
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private fun adapter(
        journalRows: List<RerunOperation>,
        laneEvents: List<RunFinished>,
        journal: InMemoryOperationJournal = InMemoryOperationJournal(clock),
        cursors: InMemoryReplayCursorStore = InMemoryReplayCursorStore(clock),
        frames: CountingFrameIndex = CountingFrameIndex(),
    ): RuntimeIntrospectionPortStoreAdapter {
        journalRows.forEach { journal.append(it, deadlineMs = null) }
        return RuntimeIntrospectionPortStoreAdapter(
            eventReads = LaneEvents(laneEvents),
            tails = NoTails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = KnownLease,
        )
    }

    private fun operation(status: OperationStatus): RerunOperation = RerunOperation(
        id = "op-1",
        fingerprint = Fingerprint("a".repeat(64)),
        input = OperationInput(
            stepId = "step-1",
            params = emptyMap(),
            runId = runId,
            attempt = 1,
        ),
        output = null,
        status = status,
        attempt = 1,
    )

    private fun bookend(outcome: String, at: Instant): RunFinished = RunFinished(
        eventId = "evt-run-finished",
        runId = runId,
        sequence = 1L,
        occurredAt = at,
        outcome = outcome,
        diagnostics = emptyList(),
    )

    /**
     * The observation, or a failure that says what came back instead.
     */
    private fun RuntimeIntrospectionResult.observation(): RuntimeObservation = when (this) {
        is RuntimeIntrospectionResult.Observation -> observation
        is RuntimeIntrospectionResult.Refused -> error("the plane refused instead of observing: $refusal")
    }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /**
     * The event lane, answering with the run's events.
     */
    private class LaneEvents(private val events: List<RunFinished>) : EventRecordReadPort {
        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult = EventRecordReadResult.Page(
            EventRecordSlice(
                records = events.map { EventRecordRead.Decoded(it) },
                nextCursor = EventCursor(runId, events.lastOrNull()?.sequence ?: 0L),
                hasMore = false,
            ),
        )
    }

    /**
     * A run the lease has heard of, held by nobody.
     */
    private object KnownLease : RunLeaseObservationPort {
        override fun isKnown(runId: String): Boolean = true
        override fun observe(runId: String): LeaseRecord? = null
    }

    /**
     * No output streams, and a count of the ones a read tried to declare.
     */
    private class CountingFrameIndex : OutputFrameIndex {
        var declarations: Int = 0
            private set

        override fun declareStream(stream: OutputStreamId, channel: OutputChannel) {
            declarations++
        }

        override fun append(stream: OutputStreamId, channel: OutputChannel, from: Long, to: Long): OutputFrame =
            error("the introspection plane must not append frames")

        override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> = emptyList()

        override fun lastOrdinal(runId: String): Long? = null

        override fun streamsOfRun(runId: String): List<OutputStreamId> = emptyList()

        override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()
    }

    /**
     * No output tail, because the run under trial writes none.
     */
    private object NoTails : OutputTailPort {
        override fun tailState(stream: OutputStreamId): OutputTailState? = null
    }
}
