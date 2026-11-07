package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The port in front of the store: what it refuses, and how many times it asks.
 *
 * ## The guarantee
 *
 * A consumer of [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort] is answered from ONE
 * read of the store, and the answer it gets is one of three: a page, a refusal that names the
 * fact that stopped it, or a storage failure. Two properties make that answer trustworthy:
 *
 *  - it is taken from ONE instant of the run, so the tail and the page cannot disagree;
 *  - the refusals keep their meaning, because the distinction between a run that does not exist,
 *    a run that exists but has not written yet, and a cursor past the end of the history is the
 *    difference between "read it", "it is empty" and "you are asking for something that is not
 *    there".
 *
 * ## Why this instrument
 *
 * The port's collaborator is a SEAM ([EventRunSnapshotReader]), so the whole story can be told with
 * a fake that counts the asks and answers DIFFERENTLY on the second ask. That second answer is
 * the point: a port that asked twice — once for the tail, once for the page — would serve the
 * second answer, and the consumer would be handed a page cut against a tail that was not the one
 * the page came with. No store, no disk and no SQLite are needed to see that, and a test with a
 * real store could not tell one read from two.
 *
 * ## The fakes buy
 *
 * The fake reader stands in for the world outside the port (the durable store). Nothing inside the
 * port is faked: the refusals, their order and the way the declared-run authority is consulted are
 * the product's own code.
 */
class EventRecordReadPortStoreAdapterTest {

    private val runId = "v2:run:adapter"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * A reader that answers with a prepared list of snapshots and COUNTS the asks.
     *
     * The last answer repeats, so a port that asks more than once is answered with something
     * different from what it would have got from one ask — which is what makes the count
     * observable in the RESULT and not only in the counter.
     */
    private class CountingReader(private val answers: List<EventRunSnapshot>) : EventRunSnapshotReader {
        var asks: Int = 0
            private set

        override fun snapshotOf(runId: String, after: EventCursor?, limit: Int): EventRunSnapshot {
            val answer = answers.getOrElse(asks) { answers.last() }
            asks++
            return answer
        }
    }

    private fun page(vararg sequences: Long): EventRecordSlice =
        EventRecordSlice(
            records = sequences.map { sequence ->
                EventRecordRead.Decoded(
                    RunStarted(
                        eventId = "evt-$sequence",
                        runId = runId,
                        sequence = sequence,
                        occurredAt = startedAt,
                        scriptPath = "pipeline.kts",
                    ),
                )
            },
            nextCursor = EventCursor(runId, sequences.lastOrNull() ?: 0L),
            hasMore = false,
        )

    private fun eventIds(slice: EventRecordSlice): List<String?> = slice.records.map {
        when (it) {
            is EventRecordRead.Decoded -> it.event.eventId
            is EventRecordRead.Undecodable -> it.eventId
        }
    }

    @Test
    @DisplayName("the port asks the store ONCE, and answers from that one answer")
    fun thePortAsksTheStoreOnce() {
        // The first answer is the truth; the second is a DIFFERENT run's tail and page. A port
        // that asked twice would serve the second answer.
        val reader = CountingReader(
            listOf(
                EventRunSnapshot(tail = 1L, page = page(1L)),
                EventRunSnapshot(tail = 99L, page = page(99L)),
            ),
        )
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        assertEquals(1, reader.asks, "a port read must ask the store exactly once")
        val served = result as EventRecordReadResult.Page
        assertEquals(
            listOf("evt-1"),
            eventIds(served.slice),
            "the port served a page that did not come from the answer it was given",
        )
    }

    @Test
    @DisplayName("a run the event plane never held is refused as a run that does not exist")
    fun aRunTheEventPlaneNeverHeldIsRefused() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = null, page = page())))
        var declaredAsks = 0
        val port = EventRecordReadPortStoreAdapter(
            reader = reader,
            runDeclared = { declaredAsks++; false },
        )

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals("UnknownRun", refused.refusal::class.simpleName)
        assertEquals(1, declaredAsks, "the declared-run authority must be consulted to decide this")
    }

    @Test
    @DisplayName("a run that was declared and has written nothing is served an empty page")
    fun aDeclaredRunWithNoEventsIsServedAnEmptyPage() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = null, page = page())))
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { true })

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val served = result as EventRecordReadResult.Page
        assertEquals(
            emptyList<String>(),
            eventIds(served.slice),
            "a declared run with no events must be served an empty page, not a refusal",
        )
    }

    @Test
    @DisplayName("the declared-run authority is not consulted when the event plane holds rows")
    fun theDeclaredRunAuthorityIsNotConsultedWhenThereAreRows() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = 1L, page = page(1L))))
        var declaredAsks = 0
        val port = EventRecordReadPortStoreAdapter(
            reader = reader,
            runDeclared = { declaredAsks++; true },
        )

        port.readRecords(runId, null, EventQuery.All, 10)

        assertEquals(
            0,
            declaredAsks,
            "the event plane's own answer must be enough when it holds rows",
        )
    }

    @Test
    @DisplayName("a cursor past the tail is refused, and the refusal names the tail")
    fun aCursorPastTheTailIsRefused() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = 5L, page = page(1L, 5L))))
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, EventCursor(runId, 9L), EventQuery.All, 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals("CursorBeyondTail", refused.refusal::class.simpleName)
        assertTrue(
            refused.refusal.toString().contains("9") && refused.refusal.toString().contains("5"),
            "the refusal must name the requested sequence and the tail, got: ${refused.refusal}",
        )
    }

    @Test
    @DisplayName("a cursor of a run with no rows is refused as a cursor past the tail")
    fun aCursorOfAnEmptyRunIsRefused() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = null, page = page())))
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { true })

        val result = port.readRecords(runId, EventCursor(runId, 0L), EventQuery.All, 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals(
            "CursorBeyondTail",
            refused.refusal::class.simpleName,
            "a run with no rows has no tail, and any cursor is past it",
        )
    }

    @Test
    @DisplayName("a row the store could not read travels in the page, with its own sequence")
    fun anUnreadableRowTravelsInThePage() {
        val unreadable = EventRecordRead.Undecodable(
            sequence = 7L,
            kind = "RunStarted",
            eventId = "evt-7",
            reason = UndecodableReason.MalformedPayload("the payload is not a row this binary can read"),
        )
        val reader = CountingReader(
            listOf(
                EventRunSnapshot(
                    tail = 7L,
                    page = EventRecordSlice(
                        records = listOf(unreadable),
                        nextCursor = EventCursor(runId, 7L),
                        hasMore = false,
                    ),
                ),
            ),
        )
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val served = result as EventRecordReadResult.Page
        assertEquals(
            listOf(unreadable),
            served.slice.records,
            "a row the store could not read must travel in the page with the sequence it has",
        )
    }

    @Test
    @DisplayName("a store that fails is refused as a storage failure, not as a missing run")
    fun aStoreThatFailsIsRefusedAsAStorageFailure() {
        val reader = object : EventRunSnapshotReader {
            override fun snapshotOf(runId: String, after: EventCursor?, limit: Int): EventRunSnapshot =
                error("the disk went away")
        }
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals(
            "StorageError",
            refused.refusal::class.simpleName,
            "a failed read must not be reported as a missing run",
        )
    }

    @Test
    @DisplayName("a cursor from another run is refused before the store is asked")
    fun aCursorFromAnotherRunIsRefusedBeforeTheStoreIsAsked() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = 1L, page = page(1L))))
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, EventCursor("v2:run:other", 1L), EventQuery.All, 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals("StorageError", refused.refusal::class.simpleName)
        assertEquals(
            0,
            reader.asks,
            "a cursor that belongs to another run is a contract error, and it costs no read",
        )
    }

    @Test
    @DisplayName("a query the store cannot answer is refused before the store is asked")
    fun aQueryTheStoreCannotAnswerIsRefusedBeforeTheStoreIsAsked() {
        val reader = CountingReader(listOf(EventRunSnapshot(tail = 1L, page = page(1L))))
        val port = EventRecordReadPortStoreAdapter(reader = reader, runDeclared = { false })

        val result = port.readRecords(runId, null, EventQuery.ByKind("RunStarted"), 10)

        val refused = result as EventRecordReadResult.Refused
        assertEquals("StorageError", refused.refusal::class.simpleName)
        assertEquals(0, reader.asks, "a refused query must cost no read")
    }
}
