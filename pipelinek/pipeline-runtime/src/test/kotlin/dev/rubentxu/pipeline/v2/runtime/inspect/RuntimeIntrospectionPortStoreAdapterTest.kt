package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.RunId
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
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.runtime.durable.RunLeaseObservationPort
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
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
import dev.rubentxu.pipeline.v2.runtime.durable.FencingToken
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
     * Paging a run's structure with the adapter's OWN cursor covers every
     * operation exactly once.
     *
     * The cursor the adapter hands out names the position of the next
     * operation in the list it just read; a consumer that walks page by page
     * with that cursor must see every operation once, in order, with no gap
     * and no repeat. A cursor derived from a COUNT instead — rows delivered,
     * page size, an off-by-one on the last included ordinal — splits, loses
     * or repeats operations on every page after the first.
     *
     * The walk is bounded by the operation count on purpose: a cursor that
     * never closes would otherwise hang the consumer. The bound cuts the
     * walk short and the final assertion names it — the walk must end
     * because the adapter closed the cursor, never because the guard ran
     * out.
     */
    @Test
    fun `la paginacion del runStructure cubre cada operacion exactamente una vez`() {
        val opCount = 5
        val adapter = adapter(
            journalRows = (1..opCount).map { n ->
                operation(id = "op-$n", status = OperationStatus.SUCCEEDED)
            },
            laneEvents = emptyList(),
        )

        var cursor: StructureCursor? = null
        val walk = mutableListOf<List<String>>()
        while (walk.size <= opCount) {
            val result = assertInstanceOf(
                RunStructureResult.Structure::class.java,
                adapter.runStructure(runId, StructureRunOptions(pageSize = 2, cursor = cursor)),
            )
            val stages = assertInstanceOf(RunStructure.Stages::class.java, result.structure)
            walk += stages.stages
                .flatMap { stage -> stage.steps }
                .flatMap { step -> step.operations }
                .map { it.operationId }
            cursor = result.cursor
            if (cursor == null) break
        }

        assertEquals(
            listOf(
                listOf("op-1", "op-2"),
                listOf("op-3", "op-4"),
                listOf("op-5"),
            ),
            walk,
            "walking the adapter's own cursors repeated, lost or split an operation: $walk",
        )
        assertNull(
            cursor,
            "the walk ended on its guard, not on the adapter closing the cursor; " +
                "a cursor that never closes hangs every paging consumer",
        )
    }

    /**
     * An operation whose worker died is a step that failed, not a step that
     * never started.
     *
     * The step state used to be reduced by comparing the durable status as a
     * STRING, and the comparison did not know the LOST case: the worker died
     * mid-flight, the outcome is unknown, and the step was reported as
     * [StepState.Pending] — a step that never began. Unknown never means
     * success, so the step reads as failed.
     */
    @Test
    fun `una operacion LOST se informa como paso fallido`() {
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.LOST)),
            laneEvents = emptyList(),
        )

        val state = stepStateOf(adapter)

        assertEquals(
            StepState.Failed(OperationStatus.LOST.name),
            state,
            "a lost operation's outcome is unknown, and a step whose outcome is unknown " +
                "is a failed step, not a step that never started",
        )
    }

    /**
     * An operation that ran and declared itself unstable is a step that is
     * unstable, not a step that never started.
     *
     * UNSTABLE is a case of its own in the durable vocabulary, and the domain
     * refuses to collapse it into a failure: the work ran and asked for
     * attention. Reporting it as a failure would say the opposite of what the
     * run said, and reporting it as pending would say the step never began.
     */
    @Test
    fun `una operacion UNSTABLE se informa como paso inestable`() {
        val adapter = adapter(
            journalRows = listOf(operation(status = OperationStatus.UNSTABLE)),
            laneEvents = emptyList(),
        )

        val state = stepStateOf(adapter)

        assertEquals(
            StepState.Unstable,
            state,
            "the work ran and asked for attention: that is an unstable step, " +
                "neither a failed one nor one that never started",
        )
    }

    /**
     * A run whose lease is held by another live process is refused with that
     * fact, and the plane does not take the lease over.
     *
     * The plane asks the pure decider what it WOULD do; a live owner in the way
     * is the answer "somebody else holds it". The refusal names the holder and
     * the token, so an operator sees who carries the authority — and the plane
     * reports it instead of acting on it.
     */
    @Test
    fun `una lease viva de otro dueno se responde con quien la tiene`() {
        val adapter = adapter(
            journalRows = emptyList(),
            laneEvents = emptyList(),
            lease = HeldLease,
        )

        val result = adapter.inspect(runId)

        val refused = assertInstanceOf(RuntimeIntrospectionResult.Refused::class.java, result)
        val held = assertInstanceOf(IntrospectionRefusal.LeaseHeldByAnother::class.java, refused.refusal)
        assertEquals("another-process", held.heldBy)
        assertEquals(7L, held.fencingToken)
    }

    /**
     * A run nobody knows is answered as EMPTY, not as a failure and not as a
     * fact about a run that does not exist.
     *
     * The plane distinguishes "the run is in flight and the substrate has
     * nothing yet" from "the substrate could not be read". Reporting the first
     * as a refusal would send an operator chasing a store failure that never
     * happened; reporting it as a terminal fact would invent a run's end.
     *
     * The reason names WHICH emptiness it is: no lease record at all is a run
     * nobody has heard of, which is a different fact from a run that exists and
     * has written nothing yet.
     */
    @Test
    fun `un run que nadie conoce se responde vacio y no como fallo`() {
        val adapter = adapter(
            journalRows = emptyList(),
            laneEvents = emptyList(),
            lease = UnknownLease,
        )

        val result = adapter.inspect(runId)

        val observation = assertInstanceOf(RuntimeIntrospectionResult.Observation::class.java, result)
        val empty = assertInstanceOf(RuntimeObservation.LiveButEmpty::class.java, observation.observation)
        assertTrue(
            empty.reason.contains("no lease record"),
            "a run nobody knows is not the same emptiness as a known run that has " +
                "written nothing yet, and the reason says which one this is: ${empty.reason}",
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
        lease: RunLeaseObservationPort = KnownLease,
    ): RuntimeIntrospectionPortStoreAdapter {
        journalRows.forEach { journal.append(it, deadlineMs = null) }
        return RuntimeIntrospectionPortStoreAdapter(
            eventReads = LaneEvents(laneEvents),
            tails = NoTails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = lease,
        )
    }

    private fun operation(id: String = "op-1", status: OperationStatus): RerunOperation = RerunOperation(
        id = id,
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

    /**
     * The state of the one synthetic step the structure view carries.
     */
    private fun stepStateOf(adapter: RuntimeIntrospectionPortStoreAdapter): StepState {
        val result = assertInstanceOf(
            RunStructureResult.Structure::class.java,
            adapter.runStructure(runId, StructureRunOptions(pageSize = 10)),
        )
        val stages = assertInstanceOf(RunStructure.Stages::class.java, result.structure)
        return stages.stages.single().steps.single().state
    }

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
        override fun isKnown(runId: RunId): Boolean = true
        override fun observe(runId: RunId): LeaseRecord? = null
    }

    /**
     * A run the lease has heard of, held by another process that is still live.
     */
    private object HeldLease : RunLeaseObservationPort {
        override fun isKnown(runId: RunId): Boolean = true
        override fun observe(runId: RunId): LeaseRecord = LeaseRecord(
            runId = runId,
            ownerId = RunOwnerId.of("another-process"),
            fencingToken = dev.rubentxu.pipeline.v2.runtime.durable.FencingToken.of(7L),
            ownerAlive = true,
        )
    }

    /**
     * A run the lease has never heard of.
     */
    private object UnknownLease : RunLeaseObservationPort {
        override fun isKnown(runId: RunId): Boolean = false
        override fun observe(runId: RunId): LeaseRecord? = null
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
