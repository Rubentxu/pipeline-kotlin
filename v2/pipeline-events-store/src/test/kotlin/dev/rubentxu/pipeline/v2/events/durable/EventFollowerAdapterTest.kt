package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.follow.EventFollowUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * M1-C — real-store contract test for the [SqliteEventFollower] backed by
 * [SqliteEventStore] + [EventRecordReadPortStoreAdapter] on a `@TempDir`.
 *
 * ## Why this is in `:pipeline-events-store/src/test/...`
 *
 * The M1-C implementation lives in `:pipeline-events-store`, alongside
 * the Sqlite store it composes. The test therefore lives in the same
 * module and constructs a real [SqliteEventStore] against a `@TempDir`
 * — no in-memory doubles for the page-authority path. The follower
 * wires the M1-A read port through the production adapter
 * `(store, store::hasRun, store::tailSequence)`, so the test exercises
 * the production composition end-to-end.
 *
 * ## What these twelve cases pin
 *
 * Each case pins one property of the design, in the order the M1-C
 * test plan names it:
 *
 *  1. unknown runId → first event is
 *     `Refused(UnknownRun(runId))`,
 *  2. known run with empty history → after the first poll the follow
 *     emits `Completed` (the natural-end semantic the §4 composition
 *     rule names for an empty run; the consumer decides whether to
 *     reopen with `after = nextCursor`),
 *  3. known run with typed events → `StateChanged(Live(0))` first,
 *     then `Page(slice, Live(latestSeq))` for each polled page,
 *  4. `maxRecords` caps records per poll cycle; subsequent cycles
 *     deliver the remaining records,
 *  5. `after: EventCursor` resumes strictly after the named cursor,
 *     skipping earlier events,
 *  6. cursor past the tail surfaces as `Refused(UnknownRun(runId))`
 *     (the closed event-follow refusal hierarchy has no separate
 *     `CursorBeyondTail` case),
 *  7. closing the handle during a poll stops the iterator at the next
 *     `hasNext` call (no half-page delivered),
 *  8. single-iterator-per-handle: `iterator()` returns the same
 *     iterator on repeat calls,
 *  9. `RunFinished` observation → `StateChanged(RunFinished)` then
 *     `Completed`,
 * 10. unknown runId is refused at the follow boundary on the real
 *     Sqlite-backed adapter (the M1-A `hasRun` authority is honoured
 *     by the follow),
 * 11. a sealed run (`RunFinished` observed) reaches `Completed` once
 *     the tail is drained, exercising the [EventFollowUntil.UntilSequence]
 *     early-stop path,
 * 12. the polling cadence pins `FOLLOW_IDLE_MILLIS = 25L` and the
 *     adapter honours it as the default for [EventFollowOptions.pollIntervalMs].
 */
class EventFollowerAdapterTest {

    private val runId = "run-m1-c-real"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun id(n: Int) = "evt-$n"

    private fun runStarted(seq: Long): DomainEvent =
        RunStarted(id(seq.toInt()), runId, seq, at, "/ws/x.pipeline.kts")

    private fun stageStarted(seq: Long, stageIndex: Int, name: String): DomainEvent =
        StageStarted(id(seq.toInt()), runId, seq, at, stageIndex, name)

    private fun stageFinished(seq: Long, stageIndex: Int, name: String, outcome: String): DomainEvent =
        StageFinished(id(seq.toInt()), runId, seq, at, stageIndex, name, outcome)

    private fun runFinished(seq: Long, outcome: String = "success"): DomainEvent =
        RunFinished(id(seq.toInt()), runId, seq, at, outcome, emptyList())

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
        // WU-LPR-042 single-writer queue: the read ports use fresh
        // connections and see only committed data. A test that
        // follows immediately after appending must flush, otherwise
        // a racing reader sees a `hasRun == false` and the follow
        // refuses with `UnknownRun` for a run the test just wrote.
        store.flush()
        return store
    }

    /**
     * Wire the production M1-A adapter on top of the store, then
     * hand it to the M1-C follow factory with the matching
     * `runExists` callback (the M1-A convention from
     * `EventRecordReadPortStoreAdapter`).
     */
    private fun freshFollower(
        tempDir: Path,
        events: List<DomainEvent>,
    ): Pair<SqliteEventStore, SqliteEventFollower> {
        val store = openSqliteStoreWithEvents(tempDir, events)
        val port: EventRecordReadPort =
            EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
        return store to SqliteEventFollower(port = port, runExists = store::hasRun)
    }

    /**
     * Drive the iterator to exhaustion, returning every event it
     * produced. Bounded by [limit] events; tests that want a
     * non-terminating follow pass `limit = 1` and read the first
     * event with [firstEvent].
     */
    private fun drain(
        handle: dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle,
        limit: Int = 64,
    ): List<EventFollowEvent> {
        val it = handle.iterator()
        val out = ArrayList<EventFollowEvent>(limit)
        while (out.size < limit && it.hasNext()) {
            out.add(it.next())
        }
        return out
    }

    private fun firstEvent(
        handle: dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle,
    ): EventFollowEvent {
        val it = handle.iterator()
        assertTrue(it.hasNext(), "iterator must produce at least one event before this test asserts on it")
        return it.next()
    }

    // -------------------------------------------------------------- 1: unknown runId

    @Test
    fun `1 unknown runId produces Refused UnknownRun as the first event`(@TempDir tempDir: Path) {
        // Open a store but append no events — the run is unknown to
        // this store and `hasRun` answers false.
        val (store, follower) = freshFollower(tempDir, emptyList())
        try {
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = 0L))
            try {
                val first = firstEvent(handle)
                val refused = assertInstanceOf(EventFollowEvent.Refused::class.java, first)
                assertInstanceOf(EventFollowRefusal.UnknownRun::class.java, refused.refusal)
                assertEquals(runId, (refused.refusal as EventFollowRefusal.UnknownRun).runId)
                // No more events after the terminal Refused.
                assertFalse(handle.iterator().hasNext(), "terminal Refused must end the iterator")
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 2: empty run, run exists

    @Test
    fun `2 known run with empty page after resume reaches Completed after the first poll`(@TempDir tempDir: Path) {
        // A real Sqlite store seeded with exactly one row; the
        // follow opens with `after = that row's sequence`, so the
        // first read returns an empty page with hasMore=false. The
        // follow emits StateChanged(Live(1)) then Completed — the
        // natural-end semantic the §4 composition rule names for an
        // empty history at this cursor. The same semantic applies
        // for a run that was declared (hasRun=true) but has not yet
        // produced any event; the M1-A stores answer hasRun=true
        // iff the run has at least one row, so a "declared empty
        // run" is exercised here through the resume-past-the-tail
        // shape.
        val events = listOf(runStarted(1L))
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = 0L,
                    maxRecords = 16,
                    after = EventCursor(runId, 1L),
                ),
            )
            try {
                val out = drain(handle)
                // Live(1) state change first, then Completed.
                assertEquals(2, out.size, "expected Live + Completed, got $out")
                val stateChange = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[0])
                val live = assertInstanceOf(EventFollowState.Live::class.java, stateChange.state)
                assertEquals(1L, live.lastSequence, "the resume cursor sits at sequence 1")
                assertEquals(EventFollowEvent.Completed, out[1])
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 3: happy path

    @Test
    fun `3 StateChanged Live is first, then Page for each new page of typed events`(@TempDir tempDir: Path) {
        val events = listOf(
            runStarted(1L),
            stageStarted(2L, 0, "build"),
            stageFinished(3L, 0, "build", "success"),
        )
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 0L, maxRecords = 16),
            )
            try {
                val out = drain(handle)
                // Live + Page + Completed (the page has all 3 rows,
                // hasMore=false, so the natural-end emission fires).
                assertEquals(3, out.size, "expected Live + 1 Page + Completed, got $out")
                val first = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[0])
                val live = assertInstanceOf(EventFollowState.Live::class.java, first.state)
                assertEquals(0L, live.lastSequence, "the first Live state announces lastSequence=0")

                val page = assertInstanceOf(EventFollowEvent.Page::class.java, out[1])
                assertEquals(3, page.slice.records.size, "the page carries all three typed rows")
                val decoded = page.slice.decoded
                assertEquals(listOf(1L, 2L, 3L), decoded.map { it.sequence })
                // newState on the Page event reports the highest sequence the follow has emitted so far.
                val newLive = assertInstanceOf(EventFollowState.Live::class.java, page.newState)
                assertEquals(3L, newLive.lastSequence)

                assertEquals(EventFollowEvent.Completed, out[2])
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 4: maxRecords

    @Test
    fun `4 maxRecords caps records per cycle and every record is still delivered`(@TempDir tempDir: Path) {
        val events = (1L..5L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 0L, maxRecords = 2),
            )
            try {
                val out = drain(handle, limit = 64)
                val pages = out.filterIsInstance<EventFollowEvent.Page>()
                // 5 records with limit=2: ceil(5/2) = 3 pages, the last
                // one carrying a single record.
                assertEquals(3, pages.size, "expected 3 pages, got ${pages.size}")
                assertEquals(2, pages[0].slice.records.size)
                assertEquals(2, pages[1].slice.records.size)
                assertEquals(1, pages[2].slice.records.size)
                val joined = pages.flatMap { it.slice.decoded }
                assertEquals(
                    listOf(1L, 2L, 3L, 4L, 5L),
                    joined.map { it.sequence },
                    "every record must be delivered in sequence order across cycles",
                )
                // The terminal Completed is the last event.
                assertEquals(EventFollowEvent.Completed, out.last())
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 5: after (resume)

    @Test
    fun `5 after cursor resumes strictly after and skips earlier events`(@TempDir tempDir: Path) {
        val events = (1L..5L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = 0L,
                    maxRecords = 16,
                    after = EventCursor(runId, 2L),
                ),
            )
            try {
                val out = drain(handle)
                // Resume from sequence 3 strictly: the initial
                // StateChanged reports lastSequence=2 (the cursor
                // we opened with), then Page carries records 3, 4, 5,
                // then Completed.
                val first = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out.first())
                val live = assertInstanceOf(EventFollowState.Live::class.java, first.state)
                assertEquals(2L, live.lastSequence, "Live initial state announces the resume cursor")

                val pages = out.filterIsInstance<EventFollowEvent.Page>()
                assertEquals(1, pages.size, "all 3 remaining records fit in one page")
                assertEquals(listOf(3L, 4L, 5L), pages[0].slice.decoded.map { it.sequence })
                assertEquals(EventFollowEvent.Completed, out.last())
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 6: cursor past tail

    @Test
    fun `6 cursor past the tail surfaces as Refused UnknownRun`(@TempDir tempDir: Path) {
        // Five events seeded; the resume cursor sits past the
        // durable tail (the M1-A `tailSequence` returns 5). The
        // M1-A read port refuses with CursorBeyondTail; the follow
        // translates it to EventFollowRefusal.UnknownRun because
        // the closed event-follow refusal hierarchy has no separate
        // case for "past the tail".
        val events = (1L..5L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = 0L,
                    maxRecords = 16,
                    after = EventCursor(runId, 100L), // past the tail
                ),
            )
            try {
                val first = firstEvent(handle)
                val refused = assertInstanceOf(EventFollowEvent.Refused::class.java, first)
                val unknown = assertInstanceOf(EventFollowRefusal.UnknownRun::class.java, refused.refusal)
                assertEquals(runId, unknown.runId)
                assertFalse(handle.iterator().hasNext(), "terminal Refused ends the iterator")
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 7: close mid-poll

    @Test
    fun `7 closing the handle stops the iterator at the next hasNext with no half-page delivered`(@TempDir tempDir: Path) {
        val events = (1L..3L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            // Non-zero poll interval so a mid-cycle sleep would
            // otherwise keep the iterator alive; this pins the
            // close-mid-poll semantic, not just "exhausted".
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 200L, maxRecords = 16),
            )
            try {
                val it = handle.iterator()
                // First hasNext fills the pending queue with Live + Page + Completed.
                assertTrue(it.hasNext(), "first hasNext must be true (state change queued)")
                assertInstanceOf(EventFollowEvent.StateChanged::class.java, it.next())
                assertTrue(it.hasNext(), "second hasNext must be true (page queued)")
                assertInstanceOf(EventFollowEvent.Page::class.java, it.next())
                assertTrue(it.hasNext(), "third hasNext must be true (completed queued)")
                it.next()

                // Past the natural tail, the next hasNext enters the
                // poll loop and ultimately the idle sleep. Close
                // before that completes; the closed flag terminates
                // the iterator.
                handle.close()
                assertFalse(it.hasNext(), "hasNext must return false after close")
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `7b close interrupts a sleeping hasNext within one poll cycle`(@TempDir tempDir: Path) {
        val events = (1L..3L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            // Long poll interval: the test only passes if close
            // short-circuits the sleep. Without the short-circuit
            // the consumer's hasNext would block for the full 60 s.
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 60_000L, maxRecords = 16),
            )
            val it = handle.iterator()
            // Drain the buffered events so the next hasNext enters
            // the poll loop and ultimately the idle sleep.
            assertTrue(it.hasNext())
            it.next()
            assertTrue(it.hasNext())
            it.next()
            assertTrue(it.hasNext())
            it.next()
            // Close the handle. The next hasNext must return false
            // promptly because the closed flag is consulted at the
            // top of hasNext. The handle has drained the natural
            // tail (5 records, maxRecords=16, hasMore=false), so
            // the Completed was already emitted; the next hasNext
            // enters the idle sleep.
            handle.close()
            assertFalse(it.hasNext(), "iterator must stop after close")
            store.close()
        } finally {
            // Idempotent close on the store — safe even if the
            // close above already closed the store via the try block.
            runCatching { store.close() }
        }
    }

    // -------------------------------------------------------------- 8: single iterator

    @Test
    fun `8 iterator returns the same iterator on repeat calls (single-observer)`(@TempDir tempDir: Path) {
        val events = (1L..3L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = 0L))
            try {
                val first = handle.iterator()
                val second = handle.iterator()
                assertSame(first, second, "iterator() must be idempotent (one iterator per handle)")
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 9: RunFinished

    @Test
    fun `9 RunFinished observation emits StateChanged RunFinished then Completed`(@TempDir tempDir: Path) {
        val events = listOf(
            runStarted(1L),
            stageStarted(2L, 0, "build"),
            runFinished(3L, "success"),
        )
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = 0L, maxRecords = 16))
            try {
                val out = drain(handle)
                // Live, Page, StateChanged(RunFinished), Completed.
                assertEquals(4, out.size, "expected Live + Page + RunFinished + Completed, got $out")
                assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[0])
                assertInstanceOf(EventFollowEvent.Page::class.java, out[1])
                val sealed = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[2])
                assertEquals(
                    EventFollowState.RunFinished,
                    sealed.state,
                    "RunFinished observation must surface as the RunFinished state change",
                )
                assertEquals(EventFollowEvent.Completed, out[3])
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 10: real adapter UnknownRun

    @Test
    fun `10 real SqliteEventStore-backed follow refuses UnknownRun at the follow boundary`(@TempDir tempDir: Path) {
        // Construct the adapter against an empty real store and the
        // M1-C follow on top, with a runId that has never been
        // appended. The M1-A `hasRun` authority must be honoured at
        // the follow boundary: the first event is Refused
        // (UnknownRun), NOT a Page with empty records.
        val store = SqliteEventStore(tempDir.resolve("test.db").toString())
        try {
            val port: EventRecordReadPort =
                EventRecordReadPortStoreAdapter(store, store::hasRun, store::tailSequence)
            val follower = SqliteEventFollower(port = port, runExists = store::hasRun)
            val handle = follower.open("run-never-written", EventFollowOptions(pollIntervalMs = 0L))
            try {
                val first = firstEvent(handle)
                val refused = assertInstanceOf(EventFollowEvent.Refused::class.java, first)
                val unknown = assertInstanceOf(EventFollowRefusal.UnknownRun::class.java, refused.refusal)
                assertEquals("run-never-written", unknown.runId)
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 11: UntilSequence + sealed run

    @Test
    fun `11 sealed run reaches Completed via UntilSequence and the tail drain`(@TempDir tempDir: Path) {
        // Five events ending in RunFinished; consumer opens with
        // UntilSequence(lastSequence=3) so the follow terminates as
        // soon as the cursor reaches sequence 3 — i.e. once the
        // Page carrying sequences 1..3 has been emitted. The
        // follow does NOT need to drain to sequence 5 because the
        // consumer's UntilSequence bound stops it earlier.
        val events = listOf(
            runStarted(1L),
            stageStarted(2L, 0, "build"),
            stageFinished(3L, 0, "build", "success"),
            stageStarted(4L, 1, "test"),
            runFinished(5L, "success"),
        )
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = 0L,
                    maxRecords = 16,
                    until = EventFollowUntil.UntilSequence(lastSequence = 3L),
                ),
            )
            try {
                val out = drain(handle)
                // Live, Page(1..3), Completed (UntilSequence bound reached).
                assertEquals(3, out.size, "expected Live + Page + Completed, got $out")
                val first = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[0])
                val live = assertInstanceOf(EventFollowState.Live::class.java, first.state)
                assertEquals(0L, live.lastSequence)
                val page = assertInstanceOf(EventFollowEvent.Page::class.java, out[1])
                assertEquals(listOf(1L, 2L, 3L), page.slice.decoded.map { it.sequence })
                assertEquals(EventFollowEvent.Completed, out[2])
                // No RunFinished state change: UntilSequence stops
                // the follow before the RunFinished page is read.
                for (ev in out) {
                    if (ev is EventFollowEvent.StateChanged) {
                        assertFalse(
                            ev.state is EventFollowState.RunFinished,
                            "UntilSequence must stop before RunFinished is observed; got $ev",
                        )
                    }
                }
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- 12: cadence constant

    @Test
    fun `12 FOLLOW_IDLE_MILLIS is 25L and EventFollowOptions default matches the cadence`(@TempDir tempDir: Path) {
        // Pin the baseline cadence to 25L: the design says polling is
        // the wakeup transport for the first cut, and M1-B's
        // SegmentOutputFollower pins the same constant. Drift would
        // silently make one plane slower than the other.
        assertEquals(25L, SqliteEventFollower.FOLLOW_IDLE_MILLIS)
        // The default options.pollIntervalMs is 25L to match the
        // baseline (the design rule: a consumer that does not pass
        // a pollIntervalMs gets the cadence).
        val defaults = EventFollowOptions()
        assertEquals(
            SqliteEventFollower.FOLLOW_IDLE_MILLIS,
            defaults.pollIntervalMs,
            "EventFollowOptions.pollIntervalMs default must equal FOLLOW_IDLE_MILLIS",
        )
    }

    // -------------------------------------------------------------- bonus: a Page can carry a refusal row alongside typed rows

    @Test
    fun `13 Page surfaces undecodable rows in slice refusals without terminating the follow`(@TempDir tempDir: Path) {
        // Seed 3 typed events. The follow must emit them in the
        // Page; if any row happened to be undecodable it would
        // land in `slice.refusals` (carried by the M1-A read port)
        // and the follow would still surface the Page event. We
        // only assert here that the typed rows survive and the
        // Page carries no refusals in the well-formed case; the
        // refusal-preservation law is asserted in
        // EventRecordReadPortAdapterTest (case 4).
        val events = listOf(
            runStarted(1L),
            stageStarted(2L, 0, "build"),
            stageFinished(3L, 0, "build", "success"),
        )
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 0L, maxRecords = 16),
            )
            try {
                val out = drain(handle)
                val page = assertInstanceOf(EventFollowEvent.Page::class.java, out[1])
                assertEquals(3, page.slice.records.size)
                assertEquals(emptyList<EventRecordRead.Undecodable>(), page.slice.refusals)
                for (record in page.slice.records) {
                    assertInstanceOf(EventRecordRead.Decoded::class.java, record)
                }
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- bonus: refuse translation

    @Test
    fun `14 StorageError from the read port surfaces as UnknownRun (closed hierarchy)`(@TempDir tempDir: Path) {
        // Build a hand-rolled port that returns Refused(StorageError)
        // and a runExists callback that says yes. The follow must
        // translate this to Refused(UnknownRun) because the closed
        // event-follow refusal hierarchy has no StorageError case
        // — the only path is UnknownRun / RetentionLost / Cancelled.
        val port: EventRecordReadPort = object : EventRecordReadPort {
            override fun readRecords(
                runId: String,
                after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
                query: dev.rubentxu.pipeline.v2.events.identity.EventQuery,
                limit: Int,
            ): dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult =
                dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Refused(
                    dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.StorageError("simulated"),
                )
        }
        val follower = SqliteEventFollower(port = port, runExists = { true })
        val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = 0L))
        try {
            // First event: Refused(UnknownRun) — runExists says yes
            // (so the open boundary does not refuse on existence), but
            // the read port returns Refused(StorageError) on the very
            // first poll, and the follow translates that to the
            // closed-hierarchy UnknownRun at the open boundary.
            // The first event is Refused because the read port refused
            // on the first read, which is exactly the design rule
            // "the first event is either StateChanged or Refused".
            val first = firstEvent(handle)
            val refused = assertInstanceOf(EventFollowEvent.Refused::class.java, first)
            assertInstanceOf(EventFollowRefusal.UnknownRun::class.java, refused.refusal)
            assertFalse(handle.iterator().hasNext(), "the terminal Refused ends the iterator")
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- bonus: UntilRunFinished stops only on RunFinished

    @Test
    fun `15 UntilRunFinished stops the follow on RunFinished observation`(@TempDir tempDir: Path) {
        val events = listOf(
            runStarted(1L),
            stageStarted(2L, 0, "build"),
            runFinished(3L, "success"),
        )
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = 0L,
                    maxRecords = 16,
                    until = EventFollowUntil.UntilRunFinished(runId),
                ),
            )
            try {
                val out = drain(handle)
                // Live, Page(1..3), StateChanged(RunFinished), Completed.
                assertEquals(4, out.size, "expected Live + Page + RunFinished + Completed, got $out")
                val sealed = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[2])
                assertEquals(EventFollowState.RunFinished, sealed.state)
                assertEquals(EventFollowEvent.Completed, out[3])
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }

    // -------------------------------------------------------------- bonus: page cursors advance strictly

    @Test
    fun `16 nextCursor advances strictly through every delivered page`(@TempDir tempDir: Path) {
        val events = (1L..6L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 0L, maxRecords = 2),
            )
            try {
                val out = drain(handle, limit = 64)
                val pages = out.filterIsInstance<EventFollowEvent.Page>()
                assertEquals(3, pages.size, "expected 3 pages, got ${pages.size}")
                // The follow advances via slice.nextCursor, which is
                // the last row's sequence. Strict-ascending check.
                val cursors = pages.map { it.slice.nextCursor.lastSequence }
                assertEquals(listOf(2L, 4L, 6L), cursors, "nextCursor advances strictly")
                // newState.lastSequence matches the cursor.
                for ((i, page) in pages.withIndex()) {
                    val live = assertInstanceOf(EventFollowState.Live::class.java, page.newState)
                    assertEquals(
                        cursors[i],
                        live.lastSequence,
                        "Page $i newState.lastSequence must match nextCursor.lastSequence",
                    )
                }
            } finally {
                handle.close()
            }
        } finally {
            store.close()
        }
    }
}