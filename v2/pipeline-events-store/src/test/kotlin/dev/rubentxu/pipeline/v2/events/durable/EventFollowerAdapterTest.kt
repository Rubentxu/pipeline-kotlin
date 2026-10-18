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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
 *
 * ## Why this class carries a class-level [Timeout]
 *
 * A follow handle with no terminal condition is ALIVE, not finished: its `hasNext()` blocks in
 * the poll loop until the operator closes it. A row that mistakes "the tail is drained" for "the
 * follow is over" therefore does not fail — it blocks, and it blocks the whole test JVM, taking
 * every class in the module with it. That is not hypothetical: row 7 used to ask a
 * never-terminating follow for the `Completed` that `UntilSequence` and `RunFinished` alone can
 * produce, and the module produced exactly one XML out of forty-four before dying on it.
 *
 * The timeout is the amplifier's own fix. It converts a wrong row from a module-wide wedge into
 * one red row with a name, which is the difference between a five-minute diagnosis and a
 * forty-five-minute one.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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

    /**
     * Budget for row 7b's concurrency assertions: how long to wait for the consumer to park, and
     * how long to wait after `close()` for it to leave.
     *
     * Deliberately far below the 60 s poll interval that row sets. That gap IS the assertion: a
     * `close()` that fails to signal the idle condition leaves the consumer parked for the whole
     * interval, which every one of these waits misses.
     */
    private val BUDGET_MILLIS = 5_000L

    /**
     * The poll cadence this class uses for every follow it opens.
     *
     * It is a constant rather than a literal because the value `0` used to appear 16 times here,
     * and `0` is not a faster test — it is a configuration the product now refuses.
     *
     * `pollIntervalMs` is the wait BETWEEN polls of a live follow. At `0` the wait is skipped
     * entirely, so a follow that has run out of history re-polls in a tight loop against a live
     * SQLite connection. That is not merely wasteful: the loop sits inside a native call, where
     * the thread does not observe the interrupt flag, so neither `@Timeout` nor a stop signal can
     * preempt it. It was measured here — 331 s of wall clock and 298 s of CPU with the worker
     * parked in `NativeDB.column_name_utf8`, growing, while the class-level `@Timeout(30)` had
     * long since been declared and never fired.
     *
     * The follower therefore requires a POSITIVE interval. This constant is the smallest value
     * that satisfies it; `1` is chosen because it keeps the test fast and still turns the spin
     * into an await, which is what makes the row interruptible.
     */
    private val LIVE_POLL_MS = 1L

    /**
     * Poll until [thread] is parked in a wait, or [budgetMillis] elapses. Returns false on timeout.
     *
     * A poll with a deadline, not a sleep: `Thread.sleep` here would be waiting for a condition,
     * and would be a fixed guess at how long the other thread takes to reach it.
     */
    private fun awaitBlocked(thread: Thread, budgetMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.WAITING, Thread.State.TIMED_WAITING -> return true
                // The thread finished without parking: hasNext() returned on its own, so there is
                // no sleep to interrupt and the caller's close() proves nothing.
                Thread.State.TERMINATED -> return false
                else -> Thread.onSpinWait()
            }
        }
        return false
    }

    // -------------------------------------------------------------- 1: unknown runId

    @Test
    fun `1 unknown runId produces Refused UnknownRun as the first event`(@TempDir tempDir: Path) {
        // Open a store but append no events — the run is unknown to
        // this store and `hasRun` answers false.
        val (store, follower) = freshFollower(tempDir, emptyList())
        try {
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = LIVE_POLL_MS))
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
    fun `2 known run with empty page after resume reports the cursor and no page event`(@TempDir tempDir: Path) {
        // A real Sqlite store seeded with exactly one row; the follow opens with
        // `after = that row's sequence`, so the first read returns an EMPTY page with hasMore=false.
        //
        // An empty page queues no `Page` event, so the only thing this run can produce is the Live
        // state change carrying the resume cursor. That is all this row claims.
        //
        // It does NOT claim the follow stays alive: that is the falsifier's job, and it cannot be
        // claimed here. Asking for the event AFTER the quiet tail is what blocks, and a count limit
        // cannot observe an absence — `drain(limit = K)` truncates a K+1th event, so asserting
        // "size == K" after a hypothetical `Completed` would pass whether or not the follow
        // terminated. `SqliteEventFollowLiveTailTest.followAtNaturalTailKeepsPolling` is the
        // instrument for that property: it blocks a consumer in `hasNext()` and shows the follow
        // keeps polling.
        val events = listOf(runStarted(1L))
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(
                    pollIntervalMs = LIVE_POLL_MS,
                    maxRecords = 16,
                    after = EventCursor(runId, 1L),
                ),
            )
            try {
                // Exactly what this run produces. A limit above that is not a stricter row: it
                // re-enters hasNext() on the quiet tail and blocks.
                val out = drain(handle, limit = 1)
                assertEquals(1, out.size, "expected only the Live state change, got $out")
                val stateChange = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out[0])
                val live = assertInstanceOf(EventFollowState.Live::class.java, stateChange.state)
                assertEquals(1L, live.lastSequence, "the resume cursor sits at sequence 1")
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
                EventFollowOptions(pollIntervalMs = LIVE_POLL_MS, maxRecords = 16),
            )
            try {
                // Exactly what this run produces: Live + the single Page. See row 2 for why the
                // limit cannot be raised to prove the absence of a trailing `Completed`.
                val out = drain(handle, limit = 2)
                assertEquals(2, out.size, "expected Live + 1 Page, got $out")
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
                EventFollowOptions(pollIntervalMs = LIVE_POLL_MS, maxRecords = 2),
            )
            try {
                // The limit IS the termination condition, and it is EXACTLY what this run
                // produces: StateChanged(Live) + 3 pages (5 records at maxRecords=2). This follow
                // has no `RunFinished` and was opened without `UntilSequence`, so `hasNext()` never
                // returns false on its own — a live follow keeps polling because the run may still
                // publish. A limit above the producible count is not a stricter row, it is a row
                // that re-enters `hasNext()` on the quiet tail and blocks; see row 2.
                val out = drain(handle, limit = 4)
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
                // The quiet tail does NOT complete, but this row does not assert that: see the
                // comment at row 2 for why a count limit cannot observe an absent event, and
                // `SqliteEventFollowLiveTailTest` for the instrument that does.
                assertEquals(
                    4,
                    out.size,
                    "expected Live + 3 pages, got $out",
                )
                assertInstanceOf(EventFollowEvent.Page::class.java, out.last())
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
                    pollIntervalMs = LIVE_POLL_MS,
                    maxRecords = 16,
                    after = EventCursor(runId, 2L),
                ),
            )
            try {
                // Exactly what this run produces: Live + the single Page. See row 2 for why the
                // limit cannot be raised to prove the absence of a trailing `Completed`.
                val out = drain(handle, limit = 2)
                assertEquals(2, out.size, "expected Live + 1 Page, got $out")
                val first = assertInstanceOf(EventFollowEvent.StateChanged::class.java, out.first())
                val live = assertInstanceOf(EventFollowState.Live::class.java, first.state)
                assertEquals(2L, live.lastSequence, "Live initial state announces the resume cursor")

                val pages = out.filterIsInstance<EventFollowEvent.Page>()
                assertEquals(1, pages.size, "all 3 remaining records fit in one page")
                assertEquals(listOf(3L, 4L, 5L), pages[0].slice.decoded.map { it.sequence })
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
                    pollIntervalMs = LIVE_POLL_MS,
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
    fun `7 closing the handle drops the buffered page instead of delivering a half-page`(@TempDir tempDir: Path) {
        val events = (1L..3L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 25L, maxRecords = 16),
            )
            try {
                val it = handle.iterator()
                // ONE poll cycle fills `pending` with StateChanged(Live) + Page and the
                // iterator returns without ever reaching the idle sleep, so this call is
                // bounded by the poll, not by a terminal condition.
                assertTrue(it.hasNext(), "first hasNext must be true (state change queued)")
                assertInstanceOf(EventFollowEvent.StateChanged::class.java, it.next())
                assertTrue(it.hasNext(), "second hasNext must be true (page queued)")

                // Deliberately NOT drained: a page is sitting in `pending` right now. Closing
                // here is what makes this row about the half-page guarantee rather than about
                // an exhausted iterator — `close()` clears `pending`, so the buffered page must
                // never reach the consumer.
                //
                // What this row must NOT do is ask for a third event. This run has no
                // `RunFinished` and the follow was opened without `UntilSequence`, so `Completed`
                // is not reachable: it is reserved for exactly those two conditions. A page with
                // `hasMore=false` is "no new data right now", NOT the end of the run, because the
                // run may still publish. Asking for `Completed` here blocked this test on
                // `hasNext()` -> `idle()` FOREVER, and with no class-level @Timeout it took the
                // other 43 classes of this module down with it. The pre-F2 behaviour that queued
                // `Completed` on the first quiet page conflated snapshot with live follow and was
                // removed on purpose; this row used to assert it.
                handle.close()
                assertFalse(it.hasNext(), "close must drop the buffered page, not deliver it")
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
            // A poll interval far longer than this row's own budget, so the row can only pass
            // because close() SIGNALS the sleeping hasNext. Without the signal the consumer sits
            // in idle() for the full minute. (The previous version of this row asked for a third
            // event on the MAIN thread to reach that sleep — which meant the row was really
            // asserting that hasNext returns a third event, and it blocked for the whole minute
            // instead of testing the wake-up path.)
            val handle = follower.open(
                runId,
                EventFollowOptions(pollIntervalMs = 60_000L, maxRecords = 16),
            )
            val it = handle.iterator()
            // Consume the two events the first poll cycle queued, so the consumer's next hasNext()
            // has nothing to return and must enter the idle sleep.
            assertTrue(it.hasNext(), "the first poll cycle queues the Live state change")
            assertInstanceOf(EventFollowEvent.StateChanged::class.java, it.next())
            assertTrue(it.hasNext(), "the same cycle queues the Page")
            assertInstanceOf(EventFollowEvent.Page::class.java, it.next())

            // Park a consumer on hasNext() from another thread. This follow has no RunFinished and
            // was opened without UntilSequence, so Completed is unreachable and this call parks in
            // idle() until something wakes it.
            val observed = AtomicReference<Boolean>()
            val consumer = thread(name = "follow-consumer", isDaemon = true) {
                observed.set(it.hasNext())
            }

            // Wait for the consumer to actually be INSIDE the sleep before closing. Without this
            // the close could win the race, hasNext would return false from the `closed` check at
            // the top of its loop, and the row would pass without ever exercising the wake-up path.
            assertTrue(
                awaitBlocked(consumer, BUDGET_MILLIS),
                "the consumer never parked in the idle sleep, so close() would not be proving " +
                    "anything: this row would pass on the `closed` fast path instead",
            )

            handle.close()

            consumer.join(BUDGET_MILLIS)
            // The discrete observation, not a duration: a handle that failed to signal would leave
            // the consumer parked for the full 60_000 ms poll interval, which this join misses.
            assertFalse(
                consumer.isAlive,
                "close() must wake the sleeping hasNext; it was still parked after ${BUDGET_MILLIS}ms " +
                    "with a 60000ms poll interval, so the idle condition was never signalled",
            )
            assertFalse(observed.get(), "a closed handle must report hasNext() == false")
        } finally {
            runCatching { store.close() }
        }
    }

    // -------------------------------------------------------------- 8: single iterator

    @Test
    fun `8 iterator returns the same iterator on repeat calls (single-observer)`(@TempDir tempDir: Path) {
        val events = (1L..3L).map { runStarted(it) }
        val (store, follower) = freshFollower(tempDir, events)
        try {
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = LIVE_POLL_MS))
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
            val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = LIVE_POLL_MS, maxRecords = 16))
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
            val handle = follower.open("run-never-written", EventFollowOptions(pollIntervalMs = LIVE_POLL_MS))
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
                    pollIntervalMs = LIVE_POLL_MS,
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
    @Suppress("detekt:UnusedParameter") // tempDir is provided by JUnit @TempDir injection; the body does not touch it.
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
                EventFollowOptions(pollIntervalMs = LIVE_POLL_MS, maxRecords = 16),
            )
            try {
                // Bounded for the same reason as the rows above: this follow has no terminal
                // condition, so an unbounded drain spins on the quiet tail forever.
                val out = drain(handle, limit = 2)
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
    @Suppress("detekt:UnusedParameter") // tempDir is provided by JUnit @TempDir injection; the body uses a hand-rolled port and never touches the directory.
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
        val handle = follower.open(runId, EventFollowOptions(pollIntervalMs = LIVE_POLL_MS))
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
                    pollIntervalMs = LIVE_POLL_MS,
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
                EventFollowOptions(pollIntervalMs = LIVE_POLL_MS, maxRecords = 2),
            )
            try {
                // The limit IS the termination condition, and it is EXACTLY what this run
                // produces: StateChanged(Live) + 3 pages. This follow has no `RunFinished` and was
                // opened without `UntilSequence`, so `hasNext()` never returns false on its own —
                // a live follow keeps polling because the run may still publish. A limit above the
                // producible count is not a stricter row, it is a row that re-enters `hasNext()`
                // on the quiet tail and blocks; see row 2.
                val out = drain(handle, limit = 4)
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

    // -------------------------------------------------------------- 18: close releases mid-poll wait
    // --------------------------------------------------------------- RETIRED: 18
    //
    // `18 close releases a mid-poll wait within pollIntervalMs` was removed, for three
    // independent reasons, each of which is sufficient on its own:
    //
    //   1. DUPLICATE. Row 7b already owns this property, and owns it better. 7b parks a real
    //      consumer thread inside `idle()`, proves it got there with `awaitBlocked`, closes, and
    //      asserts the DISCRETE outcome (the thread woke and returned false). Row 18 asserted
    //      the same wake-up on a clock instead, which is exactly what HARNESS FIDELITY 3 forbids:
    //      "assert on discrete observations, never on duration, size or ordering. A millisecond
    //      threshold is a property of the machine". Two rows protecting one guarantee with
    //      different instruments is duplicated protection, not depth.
    //   2. FOSSIL PREMISE. Row 18 drained `Live + Page + Completed` from a run built out of
    //      `runStarted(1..3)` — no `RunFinished`, and opened without `UntilSequence`. Under the
    //      F2 contract (SqliteEventFollowHandle 3e) `Completed` is reserved for exactly those two
    //      conditions, so its third `assertTrue(it.hasNext())` blocked in `idle()` for the whole
    //      poll interval instead of returning. The row was not mis-asserting: it was asking for
    //      an event the contract says cannot exist, the same defect as rows 2, 3, 4, 5 and 13.
    //   3. IT WAS THE LAST WEDGE. With the class-level `@Timeout(30)` already in place this
    //      surfaced as one named red (`18 ... timed out after 30 seconds`) instead of taking the
    //      module down. That is the amplifier working, not the row being unreachable.
    //
    // Nothing is lost: the guarantee "close() releases a poll wait, and a closed handle reports
    // hasNext() == false" is asserted by row 7b and the half-page guarantee by row 7.
}
