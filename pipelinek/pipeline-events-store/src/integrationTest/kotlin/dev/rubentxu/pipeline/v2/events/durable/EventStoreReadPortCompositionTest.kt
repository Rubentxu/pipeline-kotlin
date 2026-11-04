package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The store and the public read port, composed the way the product composes
 * them.
 *
 * ## The guarantee
 * A consumer that reads through [EventRecordReadPort] sees the events the
 * store holds, in the order the store assigned, and it is told apart from a
 * run that does not exist. The two modules have to agree for that to be
 * true: the store assigns the sequence, the adapter turns a cursor and a
 * limit into a page, and the adapter's refusals are decided with the store's
 * own facts.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * Each module has its own unit tests, and they pass: the store assigns
 * sequences, and the adapter refuses a cursor past the tail. What neither of
 * them can see is whether the two FIT — that the adapter's `tailSequence`
 * callback and the store's `tailSequence` method mean the same thing, and
 * that a cursor the adapter hands back is a cursor the store accepts.
 *
 * That fit is what this test composes. Both modules are the REAL ones: the
 * [InMemoryEventStore] and the [EventRecordReadPortStoreAdapter]. What is
 * faked is the world outside them — the store is in memory rather than on
 * disk, and the clock is a fixed [Instant] rather than the wall clock — and
 * nothing that belongs to the modules under test is faked.
 *
 * ## What the fakes buy
 * The in-memory store is the fake that replaces the filesystem. It is a
 * production class of this module, not a test double, which is what makes
 * the composition worth exercising: the SQLite store and the in-memory one
 * implement the same [dev.rubentxu.pipeline.v2.events.EventStore] contract,
 * and the adapter is written against that contract and not against either
 * implementation.
 */
class EventStoreReadPortCompositionTest {

    private val runId = "v2:run:integration"
    private val otherRunId = "v2:run:other"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    /** The store, the adapter, and the two authorities the adapter asks. */
    private fun composition(): Pair<InMemoryEventStore, EventRecordReadPort> {
        val store = InMemoryEventStore()
        val port = EventRecordReadPortStoreAdapter(
            store = store,
            runExists = { id -> store.hasRun(id) },
            tailSequence = { id -> store.tailSequence(id) },
        )
        return store to port
    }

    private fun started(sequence: Long = 0L) = RunStarted(
        eventId = "evt-$sequence",
        runId = runId,
        sequence = sequence,
        occurredAt = startedAt,
        scriptPath = "pipeline.kts",
    )

    private fun finished(sequence: Long = 0L) = RunFinished(
        eventId = "evt-finish-$sequence",
        runId = runId,
        sequence = sequence,
        occurredAt = startedAt,
        outcome = "succeeded",
        diagnostics = emptyList(),
    )

    @Test
    fun `the events the store holds are the events the port serves`() {
        val (store, port) = composition()
        store.append(started())
        store.append(finished())

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
        assertEquals(
            listOf("RunStarted", "RunFinished"),
            page.slice.decoded.map { it.kind },
            "the port served a different history than the store holds",
        )
    }

    @Test
    fun `the cursor the port hands back is a cursor the store accepts`() {
        val (store, port) = composition()
        store.append(started())
        store.append(finished())
        store.append(started(sequence = 3L))

        val first = assertInstanceOf(
            EventRecordReadResult.Page::class.java,
            port.readRecords(runId, null, EventQuery.All, 1),
        )

        assertTrue(first.slice.hasMore, "a page that left events behind must say there are more")

        val second = assertInstanceOf(
            EventRecordReadResult.Page::class.java,
            port.readRecords(runId, first.slice.nextCursor, EventQuery.All, 10),
        )

        assertEquals(
            listOf("RunFinished", "RunStarted"),
            second.slice.decoded.map { it.kind },
            "the cursor the port handed back did not resume where the store is",
        )
    }

    @Test
    fun `a run the store never saw is refused and not served as an empty page`() {
        val (_, port) = composition()

        val result = port.readRecords(otherRunId, null, EventQuery.All, 10)

        val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
        assertEquals(
            "UnknownRun",
            refused.refusal::class.simpleName,
            "a run the store never saw must be refused, not served as an empty page",
        )
    }

    @Test
    fun `a cursor past the tail is refused and not served as an empty page`() {
        val (store, port) = composition()
        store.append(started())

        val result = port.readRecords(
            runId,
            EventCursor(runId = runId, lastSequence = 99L),
            EventQuery.All,
            10,
        )

        val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
        assertEquals(
            "CursorBeyondTail",
            refused.refusal::class.simpleName,
            "a cursor past the tail must be refused, not served as an empty page",
        )
    }
}
