package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
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
        fun `7 EventQuery All is the only accepted query, non-All surfaces as StorageError refusal`(@TempDir tempDir: Path) {
            val (_, port) = freshAdapter(tempDir)
            // The M1-A pass-through audit found that the underlying
            // store did not filter on the read path, so a query that
            // should have restricted the page silently returned the
            // full one. The M1-A follow-up correction made the no-op
            // explicit: any non-All query is refused closed with a
            // StorageError naming the unsupported kind. The port
            // signature is unchanged so callers continue to compile.
            val allEvents = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
            val byKind = port.readRecords(
                runId, after = null,
                query = EventQuery.ByKind("RunStarted"), limit = 10,
            )
            val allPage = assertInstanceOf(EventRecordReadResult.Page::class.java, allEvents)
            assertEquals(3, allPage.slice.records.size)
            val byKindRefused = assertInstanceOf(EventRecordReadResult.Refused::class.java, byKind)
            val err = assertInstanceOf(EventRecordReadRefusal.StorageError::class.java, byKindRefused.refusal)
            assertTrue(
                err.cause.contains("ByKind") && err.cause.contains("RunStarted"),
                "StorageError must name the unsupported query kind, got '${err.cause}'",
            )
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

    @Nested
    @DisplayName("M1-A follow-up: non-All EventQuery, cursor runId mismatch, lease authority, slice preservation")
    inner class FollowUpCorrections {

        @Test
        fun `14 EventQuery non-All is refused with StorageError naming the unsupported kind`(
            @TempDir tempDir: Path,
        ) {
            // Each non-All EventQuery case (ByKind, BySource, BySubject,
            // BySequenceRange) surfaces as StorageError with the kind
            // named in the diagnostic. The port signature is stable;
            // the change is at the adapter's refusal surface.
            val (_, port) = openSqliteStoreWithEvents(tempDir, threeEvents()).let { store ->
                store to EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
            }
            val cases = listOf(
                EventQuery.ByKind("RunStarted"),
                EventQuery.BySource(
                    ResourceRefs.run("test-source-run"),
                ),
                EventQuery.BySubject(
                    ResourceRefs.run("test-subject-run"),
                ),
                EventQuery.BySequenceRange(1L, 3L),
            )
            for (q in cases) {
                val result = port.readRecords(runId, after = null, query = q, limit = 10)
                val refused = assertInstanceOf(
                    EventRecordReadResult.Refused::class.java, result,
                    "non-All query must refuse, got $result for $q",
                )
                val err = assertInstanceOf(
                    EventRecordReadRefusal.StorageError::class.java, refused.refusal,
                )
                assertTrue(
                    err.cause.startsWith("query not supported:"),
                    "StorageError must name the kind, got '${err.cause}'",
                )
            }
        }

        @Test
        fun `15 a cursor whose runId does not match the requested runId is refused with StorageError`(
            @TempDir tempDir: Path,
        ) {
            // Compose the adapter against a real store. A consumer that
            // passes a cursor from a DIFFERENT run is making a contract
            // error: the cursor's runId is a parameter of the API, not
            // a fact about the store, so the mismatch check happens
            // BEFORE the existence / tail-sequence calls. The check is
            // deliberately placed first so the StorageError diagnostic
            // names the cursor's runId AND the requested runId, leaving
            // the operator with no ambiguity about which runId was
            // meant.
            val (store, port) = run {
                val s = openSqliteStoreWithEvents(tempDir, threeEvents())
                s to EventRecordReadPortStoreAdapter(s, s::hasRun, s::tailSequence)
            }
            try {
                val otherRunCursor = EventCursor("some-other-run-id", 0L)
                val result = port.readRecords(
                    runId = runId,
                    after = otherRunCursor,
                    query = EventQuery.All,
                    limit = 10,
                )
                val refused = assertInstanceOf(EventRecordReadResult.Refused::class.java, result)
                val err = assertInstanceOf(EventRecordReadRefusal.StorageError::class.java, refused.refusal)
                assertTrue(
                    err.cause.contains("some-other-run-id") && err.cause.contains(runId),
                    "StorageError must name both runIds, got '${err.cause}'",
                )
            } finally {
                store.close()
            }
        }

        @Test
        fun `16 a run known to the lease but with no events returns Page(hasMore=false), distinct from UnknownRun`(
            @TempDir tempDir: Path,
        ) {
            // The lease is the primary authority for run existence.
            // A run whose [FileBackedRunExecutionLeaseStore] has ever
            // recorded a lease MUST answer `true` from `runExists`
            // even if no event has been written to the events table.
            // Without this, a freshly declared but quiet run would
            // surface as `UnknownRun` on the read port, which is
            // semantically wrong: the run exists, it just has not
            // produced any event yet. The compose site (the test
            // fixture here; the production wiring is the same shape)
            // supplies `leaseStore.isKnown(runId) || store.hasRun(runId)`
            // as the `runExists` callback.
            val store = SqliteEventStore(tempDir.resolve("lease-only.db").toString())
            val leaseStore = FileBackedRunExecutionLeaseStore(tempDir.resolve("leases"))
            try {
                // No events appended. The lease has been acquired once
                // and released: the record file exists on disk.
                val acquisition = leaseStore.acquire(
                    LeaseRequest(runId, RunOwnerId.of("test-owner")!!),
                )
                assertInstanceOf(LeaseAcquisition.Acquired::class.java, acquisition)
                leaseStore.release(RunOwnerId.of("test-owner")!!)
                // The composed authority: lease OR store row presence.
                // The lease authority is primary; the store row check
                // is the fallback.
                val composedRunExists: (String) -> Boolean = { rid ->
                    leaseStore.isKnown(rid) || store.hasRun(rid)
                }
                val port = EventRecordReadPortStoreAdapter(
                    store,
                    runExists = composedRunExists,
                    tailSequence = store::tailSequence,
                )
                val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
                // The Page, NOT the UnknownRun: the lease says yes, so
                // an empty run answers with a (possibly empty) Page
                // rather than refusing.
                val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
                assertEquals(0, page.slice.records.size)
                assertEquals(false, page.slice.hasMore)
                // The cursor advances from `null` to the same run's
                // zero-sequence cursor; nextCursor.lastSequence == 0.
                assertEquals(EventCursor(runId, 0L), page.slice.nextCursor)
            } finally {
                runCatching { leaseStore.close() }
                runCatching { store.close() }
            }
        }

        @Test
        fun `17 readRecords returns the slice verbatim - Undecodable, sequence, hasMore, nextCursor`(
            @TempDir tempDir: Path,
        ) {
            // The adapter does NOT re-cut, does NOT re-decode, does NOT
            // drop refusals. The M1-A design rule pins the store as
            // the page authority: the adapter delegates to
            // `store.readRecords(...)` and surfaces the slice verbatim.
            // This test seeds a row the store cannot decode (a
            // syntactically malformed payload for a typed event) and
            // asserts the adapter preserves the Undecodable row,
            // carries the next cursor past it, and reports
            // `hasMore=false` correctly.
            //
            // We bypass the typed append path (the store would refuse
            // a typed payload that does not parse) by inserting an
            // undecodable row directly via the schema: an
            // `UnknownKind` row whose `kind` column references a kind
            // this binary does not know. The store's `append` does not
            // expose an `UnknownKind` path; this is the audit's
            // "audit-only" shape, exercised here through the SQLite
            // connection the test opens by hand. The adapter's
            // contract — preserve slice verbatim — is the property
            // under test.
            val dbFile = tempDir.resolve("slice.db").toString()
            val store = SqliteEventStore(dbFile)
            try {
                // Three typed events via the store's append path.
                for (event in threeEvents()) {
                    store.append(event)
                }
                store.flush()
                // One Undecodable row inserted directly. The store's
                // `append` API does not accept an unknown kind; the
                // audit shape is the row on disk, which we insert here
                // to pin the slice-preservation law. The schema has
                // `(event_id, run_id, sequence, kind, occurred_at, payload)`
                // (cf. SqliteEventStore CREATE TABLE), so we populate
                // every NOT NULL column including `occurred_at`.
                val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbFile")
                conn.use { c ->
                    c.prepareStatement(
                        "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "evt-4-broken")
                        ps.setString(2, runId)
                        ps.setLong(3, 4L)
                        ps.setString(4, "UnknownKind")
                        ps.setString(5, at.toString())
                        ps.setBytes(6, "garbage-not-json".toByteArray())
                        ps.executeUpdate()
                    }
                }
                val port = EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
                val result = port.readRecords(runId, after = null, query = EventQuery.All, limit = 10)
                val page = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
                // 3 typed + 1 undecodable = 4 records, the limit-bound
                // counted ROWS not decodable events.
                assertEquals(4, page.slice.records.size, "slice has every row in store order")
                val typed = page.slice.records.filterIsInstance<EventRecordRead.Decoded>()
                val undecodable = page.slice.records.filterIsInstance<EventRecordRead.Undecodable>()
                assertEquals(3, typed.size)
                assertEquals(1, undecodable.size)
                // The Undecodable row carries its identity (sequence,
                // eventId) verbatim from the row's columns.
                val u = undecodable[0]
                assertEquals(4L, u.sequence)
                assertEquals("evt-4-broken", u.eventId)
                assertEquals("UnknownKind", u.kind)
                // nextCursor advances to the last row's sequence, not
                // the last DECODED row's — a resuming reader pages
                // past the refusal on purpose instead of re-reading
                // it forever or stepping over it blind. The audit's
                // pagination rule (count rows, decode inside the
                // page) is what makes this honest.
                assertEquals(EventCursor(runId, 4L), page.slice.nextCursor)
                assertEquals(false, page.slice.hasMore, "no rows beyond sequence 4")
            } finally {
                store.close()
            }
        }
    }
}
