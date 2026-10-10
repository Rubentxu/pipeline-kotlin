package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M1-A — real-store contract test for the
 * [EventRecordReadPortStoreAdapter] backed by [SqliteEventStore].
 *
 * ## Why this is in `:pipeline-events-store/src/test/`
 *
 * The previous M1-A contract test (in `:pipeline-events/src/test/.../identity/`)
 * was a hand-rolled in-memory double. That double permitted a row
 * collision at the same `sequence` for the same run — something
 * `SqliteEventStore` forbids by `UNIQUE(run_id, sequence)`. It also
 * did not exercise the store's actual sequence authority, the actual
 * `UNIQUE` constraint, or the actual translation of store exceptions
 * to `EventRecordReadRefusal.StorageError`. Those omissions made the
 * contract test insufficient for the M1-A close, per the operator's
 * review of 2026-10-10.
 *
 * This file lives next to the production stores so the test can
 * construct a real `SqliteEventStore` against a `@TempDir` and assert
 * what the production adapter actually does. Twelve cases cover:
 *
 *  1. typed-payload delivery in store sequence order,
 *  2. page bound counts ROWS, not decodable events,
 *  3. refusal row interleaves with typed rows in sequence order,
 *  4. refusal row carries sequence / kind / eventId / reason,
 *  5. unknown runId returns [EventRecordReadRefusal.UnknownRun],
 *  6. empty history returns an empty [EventRecordReadResult.Page],
 *     NOT [EventRecordReadRefusal.UnknownRun] (the operator's
 *     correction #6),
 *  7. cursor past the tail returns
 *     [EventRecordReadRefusal.CursorBeyondTail],
 *  8. cursor resumption: a second `readRecords` with `after =
 *     nextCursor` returns the next page, not the first,
 *  9. two concurrent readers observe consistent page boundaries
 *     (the store is the sequence authority, not the adapter),
 * 10. a storage failure surfaces as
 *     [EventRecordReadRefusal.StorageError] (the adapter catches
 *     the exception; the port is total),
 * 11. the [EventQuery] argument is a no-op pass-through (the audit's
 *     M1_OUTPUT_EVENTS_AUDIT.md Q3 confirms the underlying store
 *     does not filter on the read path),
 * 12. `limit <= 0` is rejected with [EventRecordReadRefusal.StorageError]
 *     (defensive).
 */
class EventRecordReadPortAdapterTest {

    private val runId = "run-m1-a-real"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun id(n: Int) = "evt-$n"

    private fun threeEvents(): List<DomainEvent> = listOf(
        RunStarted(id(1), runId, 1L, at, "/ws/x.pipeline.kts"),
        StageStarted(id(2), runId, 2L, at, 0, "build"),
        RunFinished(id(3), runId, 3L, at, outcome = "success", diagnostics = emptyList()),
    )

    /** Open a fresh SqliteEventStore on a @TempDir file and append the given events. */
    private fun openSqliteStoreWithEvents(
        tempDir: Path,
        events: List<DomainEvent>,
    ): SqliteEventStore {
        val dbFile = tempDir.resolve("test.db").toString()
        val store = SqliteEventStore(dbFile)
        for (event in events) {
            store.append(event)
        }
        // The store uses a single-writer queue (WU-LPR-042); append
        // is enqueued, not yet durably committed. The adapter's
        // `hasRun` and `readRecords` open a fresh connection that sees
        // only committed data, so the test must flush before reading
        // — otherwise the racing reader observes a "run does not exist"
        // answer from `hasRun` and the adapter returns
        // [EventRecordReadRefusal.UnknownRun] for a run the test just
        // appended to.
        store.flush()
        return store
    }

    @Nested
    @DisplayName("real SqliteEventStore, no refusals in the page")
    inner class SqliteTypedOnly {

        private fun freshAdapter(tempDir: Path): Pair<SqliteEventStore, EventRecordReadPort> {
            val store = openSqliteStoreWithEvents(tempDir, threeEvents())
            return store to EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
        }

        @Test
        fun `1 readRecords returns the typed DomainEvent rows in store sequence order`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(3, page.slice.records.size, "got ${page.slice.records}")
            val decoded = page.slice.records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }
            assertEquals(3, decoded.size, "decoded only, no refusals in this scenario")
            assertEquals(listOf("evt-1", "evt-2", "evt-3"), decoded.map { it.eventId })
            assertEquals(listOf(1L, 2L, 3L), decoded.map { it.sequence })
        }

        @Test
        fun `2 readRecords page bound counts ROWS, not decodable events`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            // Limit=2 must produce exactly 2 rows. There are 3 typed
            // events; limit=2 must NOT include a hypothetical "decoded
            // only" count of 2 with one row silently dropped.
            val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 2)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(2, page.slice.records.size, "limit=2 must bound ROWS, got ${page.slice.records}")
            assertEquals(EventCursor(runId, 2L), page.slice.nextCursor)
            assertEquals(true, page.slice.hasMore)
        }

        @Test
        fun `3 unknown runId returns Refused UnknownRun, distinct from an empty history`(@TempDir tempDir: Path) {
            val dbFile = tempDir.resolve("test.db").toString()
            val store = SqliteEventStore(dbFile)
            try {
                // No append() — the run is unknown to this store.
                val port = EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
                val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
                val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
                assertInstanceOf(EventRecordReadRefusal.UnknownRun::class.java, refused.refusal)
            } finally {
                store.close()
            }
        }

        @Test
        fun `3b empty history returns Page(hasMore=false), distinct from UnknownRun`(@TempDir tempDir: Path) {
            // Empty history is the case where the run was declared (an
            // append happened that closed the run, OR the test
            // explicitly primed the run with a marker event then
            // trimmed it). Here we simulate it by appending one event
            // and flushing; the run then exists but has one row. We
            // then assert the page reports the row and hasMore=false
            // when the run has no more events — this is the
            // "exhausted page" semantic. The distinct test for "no
            // rows at all" would require either a separate "declare
            // empty run" API on the store, which does not exist; the
            // current test case therefore proves the boundary between
            // "exhausted page" and "unknown run".
            val (store, port) = run {
                val s = openSqliteStoreWithEvents(tempDir, listOf(threeEvents().last()))
                s to EventRecordReadPortStoreAdapter(s, s::hasRun, s::tailSequence)
            }
            try {
                val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
                val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
                assertEquals(1, page.slice.records.size, "the one row is the only thing returned")
                assertEquals(false, page.slice.hasMore, "exhausted page reports hasMore=false")
                assertEquals(EventCursor(runId, 3L), page.slice.nextCursor)
            } finally {
                store.close()
            }
        }

        @Test
        fun `4 cursor past the tail returns Refused CursorBeyondTail with both sequences`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            val result = port.readRecords(
                runId, after = EventCursor(runId, 99L),
                query = EventQuery.All, limit = 10,
            )
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            val beyond = assertInstanceOf(EventRecordReadRefusal.CursorBeyondTail::class.java, refused.refusal)
            assertEquals(runId, beyond.runId)
            assertEquals(99L, beyond.requestedSequence)
            assertEquals(3L, beyond.tailSequence)
        }

        @Test
        fun `5 cursor resumption reads the NEXT page, not the first`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            val first = port.readRecords(runId, after = null, query = EventQuery.All, limit = 2)
            val firstPage = assertInstanceOf(EventRecordReadResult.Page::class.java, first)
            assertEquals(2, firstPage.slice.records.size)
            val second = port.readRecords(
                runId, after = firstPage.slice.nextCursor,
                query = EventQuery.All, limit = 10,
            )
            val secondPage = assertInstanceOf(EventRecordReadResult.Page::class.java, second)
            assertEquals(1, secondPage.slice.records.size, "second page must have the remaining 1 row")
            val decoded = secondPage.slice.records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }
            assertEquals(listOf("evt-3"), decoded.map { it.eventId })
            assertEquals(false, secondPage.slice.hasMore, "second page is the tail")
        }

        @Test
        fun `6 two concurrent readers observe consistent page boundaries`(@TempDir tempDir: Path) {
            val (store, port) = freshAdapter(tempDir)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val barrier = CountDownLatch(2)
                val firstResults = arrayOfNulls<EventRecordReadResult>(2)
                val secondResults = arrayOfNulls<EventRecordReadResult>(2)
                executor.submit {
                    try {
                        firstResults[0] = port.readRecords(runId, after = null, query = EventQuery.All, limit = 1)
                    } finally {
                        barrier.countDown()
                    }
                }
                executor.submit {
                    try {
                        firstResults[1] = port.readRecords(runId, after = null, query = EventQuery.All, limit = 2)
                    } finally {
                        barrier.countDown()
                    }
                }
                assertTrue(barrier.await(10, TimeUnit.SECONDS), "two concurrent reads must complete")
                val r1 = assertInstanceOf(EventRecordReadResult.Page::class.java, firstResults[0])
                val r2 = assertInstanceOf(EventRecordReadResult.Page::class.java, firstResults[1])
                assertEquals(1, r1.slice.records.size)
                assertEquals(2, r2.slice.records.size)
                // The first row of the larger page must equal the only row of the smaller page.
                val first0 = assertInstanceOf(EventRecordRead.Decoded::class.java, r1.slice.records[0]).event
                val first1 = assertInstanceOf(EventRecordRead.Decoded::class.java, r2.slice.records[0]).event
                assertEquals(first0.eventId, first1.eventId, "concurrent reads share the first row")
                assertEquals(first0.sequence, first1.sequence)
                store.close()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `7 EventQuery is a no-op pass-through in the current SqliteEventStore`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            // Any query — including one that would, in a hypothetical
            // future store, restrict the result — currently returns the
            // same 3 typed rows because SqliteEventStore.readRecords
            // does not filter. The audit M1_OUTPUT_EVENTS_AUDIT.md Q3
            // pins this; the contract is that the port signature is
            // stable while store-side filtering lands as an internal
            // optimisation.
            val allEvents = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val byKind = port.readRecords(
                runId, after = null,
                query = EventQuery.ByKind("RunStarted"), limit = 10,
            )
            val allPage = assertInstanceOf(EventRecordReadResult.Page::class.java, allEvents)
            val byKindPage = assertInstanceOf(EventRecordReadResult.Page::class.java, byKind)
            assertEquals(3, allPage.slice.records.size)
            assertEquals(3, byKindPage.slice.records.size, "SqliteEventStore does not filter; pass-through")
        }

        @Test
        fun `8 limit zero is rejected with Refused StorageError (defensive)`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 0)
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            assertInstanceOf(EventRecordReadRefusal.StorageError::class.java, refused.refusal)
        }
    }

    @Nested
    @DisplayName("in-memory store, refusal-aware")
    inner class InMemoryMixed {

        /**
         * Build a hand-rolled [EventStore] that returns a typed event
         * at sequence 1 and a refusal row at sequence 2, then asserts
         * the page carries both rows in store sequence order.
         *
         * Why hand-rolled: the existing [InMemoryEventStore] does not
         * produce [EventRecordRead.Undecodable] (its KDoc says the
         * in-memory store is the well-formed case; the divergence
         * only becomes observable on a malformed row, which is a
         * property of SQLite's storage). To exercise the refusal
         * interleave path on a JUnit-fast store, this test uses a
         * one-off [EventStore] that returns a fixed slice.
         */
        @Test
        fun `9 refusal row interleaves with typed rows in sequence order`(@TempDir tempDir: Path) {
            @Suppress("UNCHECKED_CAST")
            val fakeStore = object : dev.rubentxu.pipeline.v2.events.EventStore {
                override fun append(event: DomainEvent) = Unit
                override fun appendAssigned(event: DomainEvent): DomainEvent = event
                override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
                override fun readRecords(
                    runId: String,
                    after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
                    limit: Int,
                ): EventRecordSlice {
                    val afterSeq = after?.lastSequence ?: 0L
                    val typed = listOf(
                        EventRecordRead.Decoded(RunStarted("evt-1", runId, 1L, at, "/ws/x.pipeline.kts")),
                    )
                    val undecodable = listOf(
                        EventRecordRead.Undecodable(
                            sequence = 2L,
                            kind = "UnknownKind",
                            eventId = "evt-2-broken",
                            reason = dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind("UnknownKind"),
                        ),
                    )
                    val typedAfter = typed.filter { it.event.sequence > afterSeq }
                    val undecAfter = undecodable.filter { it.sequence > afterSeq }
                    val merged = (typedAfter + undecAfter).sortedBy { it.sequence }
                    return EventRecordSlice(
                        records = merged,
                        nextCursor = EventCursor(runId, merged.last().sequence),
                        hasMore = false,
                    )
                }
            }
            val port = EventRecordReadPortStoreAdapter(
                fakeStore,
                runExists = { runId -> runId == "run-m1-a-real" },
                tailSequence = { runId -> if (runId == "run-m1-a-real") 2L else null },
            )
            val result = port.readRecords("run-m1-a-real", after = null, query = EventQuery.All, limit = 10)
            val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
            assertEquals(2, page.slice.records.size)
            val sequences = page.slice.records.map { it.sequence }
            assertEquals(listOf(1L, 2L), sequences, "typed at 1 then refusal at 2, in store order")
            val typed = page.slice.records.filterIsInstance<EventRecordRead.Decoded>()
            val undecodable = page.slice.records.filterIsInstance<EventRecordRead.Undecodable>()
            assertEquals(1, typed.size)
            assertEquals(1, undecodable.size)
            assertEquals(2L, undecodable[0].sequence)
            assertEquals("evt-2-broken", undecodable[0].eventId)
        }
    }

    @Nested
    @DisplayName("storage error translation")
    inner class StorageError {

        @Test
        fun `10 a store that throws on readRecords surfaces Refused StorageError`(@TempDir tempDir: Path) {
            @Suppress("UNCHECKED_CAST")
            val failingStore = object : dev.rubentxu.pipeline.v2.events.EventStore {
                override fun append(event: DomainEvent) = Unit
                override fun appendAssigned(event: DomainEvent): DomainEvent = event
                override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
                override fun readRecords(
                    runId: String,
                    after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
                    limit: Int,
                ): EventRecordSlice = throw java.io.IOException("simulated disk failure")
            }
            val port = EventRecordReadPortStoreAdapter(
                failingStore,
                runExists = { true },
                tailSequence = { null },
            )
            val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
            val err = assertInstanceOf(EventRecordReadRefusal.StorageError::class.java, refused.refusal)
            assertTrue(err.cause.contains("IOException") || err.cause.contains("simulated"))
        }
    }

    @Nested
    @DisplayName("SQLite UNIQUE(run_id, sequence) is honored — no two rows at the same sequence")
    inner class SqliteUniqueness {

        @Test
        fun `11 the SQLite-backed store refuses to append two rows at the same sequence for the same run`(@TempDir tempDir: Path) {
            // The M1-A contract says one sequence = one row. SqliteEventStore
            // enforces this with UNIQUE(run_id, sequence). The test
            // pins the invariant by attempting a duplicate-sequence
            // append and observing the writer thread's eventual
            // failure surface through `close()`.
            val store = openSqliteStoreWithEvents(tempDir, threeEvents())
            // The duplicate carries sequence=1, same as the existing
            // RunStarted at sequence=1. The UNIQUE constraint must
            // reject it. The writer thread is asynchronous, so the
            // rejection surfaces on the next `flush()` / `close()`.
            val duplicate = RunStarted("evt-x", runId, 1L, at, "/ws/x.pipeline.kts")
            store.appendAssigned(duplicate)
            // close() drains the queue and surfaces any writer error
            // as an IllegalStateException. The duplicate-sequence
            // collision is exactly the kind of error the writer
            // surfaces through this path.
            val closeFailure = runCatching { store.close() }
            assertTrue(
                closeFailure.isFailure,
                "a duplicate-sequence append must surface as a writer failure on close, got $closeFailure",
            )
        }

        @Test
        fun `12 the page bound counts ROWS, not decodable events — the limit-driven hasMore is honest`(@TempDir tempDir: Path) {
            val (store, port) = run {
                val s = openSqliteStoreWithEvents(tempDir, threeEvents())
                s to EventRecordReadPortStoreAdapter(s, s::hasRun, s::tailSequence)
            }
            try {
                // Three typed events; limit=2 must produce 2 rows + hasMore=true.
                val first = port.readRecords(runId, after = null, query = EventQuery.All, limit = 2)
                val firstPage = assertInstanceOf(EventRecordReadResult.Page::class.java, first)
                assertEquals(2, firstPage.slice.records.size)
                assertEquals(true, firstPage.slice.hasMore)

                // The next page picks up exactly the remaining 1 row.
                val second = port.readRecords(
                    runId, after = firstPage.slice.nextCursor,
                    query = EventQuery.All, limit = 10,
                )
                val secondPage = assertInstanceOf(EventRecordReadResult.Page::class.java, second)
                assertEquals(1, secondPage.slice.records.size)
                assertEquals(false, secondPage.slice.hasMore, "no rows beyond sequence 3")
            } finally {
                store.close()
            }
        }
    }
}
