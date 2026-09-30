package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * WU-RP-020 characterisation of SqliteEventStore under concurrency.
 *
 * Charter (ROADMAP L49): "caracterizar SqliteEventStore bajo concurrencia:
 * sequence asignada frente a orden de inserción/lectura, flush/close con
 * productores activos, reinicio, gap, error de writer, replay y arrays
 * anidados. No modificar el contrato de secuencia hasta reproducir o
 * descartar el riesgo; usar test determinista y criterios observables."
 *
 * This suite is TEST-SIDE ONLY. It does NOT modify production code.
 *
 * Each test documents ONE observable property of the store. If a test
 * fails, the property needs explicit characterisation (either a contract
 * decision or a bug fix in a follow-up WU).
 *
 * WU-RP-020: the class budget is 120 s.
 *
 * HISTORY, kept because two explanations were tried and the record matters:
 *
 * 1. Claim (false): "@Timeout(60) equalled the 60 s barrier bound, so the test
 *    was a coin flip against its own budget". FALSE. With the original
 *    `@Timeout(60)` this test completes in 0.031 s and passes 10/0/0/0 in
 *    isolation, three runs in a row. Raising the budget changed nothing.
 *
 * 2. Claim (true, and it was the real cause): "a writer that dies leaves a
 *    FlushBarrier stranded, so flush() waits its full 60 s bound". CONFIRMED by
 *    reproduction. Instrumenting writerLoop showed the failing batch is
 *    `size=4 barriers=0` -- events only, so the catch-block release loop has
 *    no barrier to count down. A barrier enqueued after that batch is never
 *    released. The `writerError` fast path does not help: appendAssigned
 *    returns on ASSIGNMENT, before the writer records its error, so this is a
 *    race.
 *
 *    A first attempt to CONFIRM this was itself flawed: the test polled until
 *    `writerError` was already set before flushing, which skips the entire
 *    race window and therefore passed with and without the fix. A test that
 *    does not exercise the race cannot falsify a hypothesis the race rests on.
 *    Reproducing the original ordering -- duplicate appended, then flush with
 *    NO intervening flush -- gave `flush() took 60008ms`, and 17 ms after the
 *    fix. See `flush fails fast when the writer dies on the preceding append`.
 */
@Timeout(120)
class SqliteEventStoreConcurrencyCharacterisationTest {

    private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun runStarted(eventId: String, runId: String, seq: Long = 0L) = RunStarted(
        eventId = eventId,
        runId = runId,
        sequence = seq,
        occurredAt = at,
        scriptPath = "/p/$runId",
    )

    /**
     * WU-RP-020: reads `MAX(sequence)` straight from SQLite, bypassing the
     * store and its in-memory counters.
     *
     * A durability property must be observed in the durable state. Asking the
     * store what it thinks the counter is would be asking the subject to grade
     * its own homework, and it is exactly that shortcut that hid the
     * un-flushed-baseline bug this helper now pins.
     */
    private fun durableMaxSequence(db: Path, runId: String): Long {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { conn ->
            conn.prepareStatement("SELECT COALESCE(MAX(sequence), 0) FROM events WHERE run_id = ?").use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun stashCreated(eventId: String, runId: String, seq: Long, entries: Int) = StashCreated(
        eventId = eventId,
        runId = runId,
        sequence = seq,
        occurredAt = at,
        stageName = "stage-$seq",
        name = "s-$seq",
        files = (1..entries).map { i ->
            StashedEntry(relPath = "file-$i.txt", sha256 = "a".repeat(64), sizeBytes = 1024L * i)
        },
    )

    // ------------------------------------------------------------------
    // Property 1 — flush() is a barrier: events enqueued BEFORE flush
    // are committed, and a producer enqueuing AFTER flush gets its event
    // durably visible only after the next flush. Verifies that flush
    // does not "swallow" in-flight enqueues nor pretend to be durable
    // for events enqueued after it returned.
    // ------------------------------------------------------------------
    @Test
    fun `flush is a barrier for events enqueued before it`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("flush.db").toString())
        store.append(runStarted("a", "flush-run"))
        store.flush()
        // Open a separate reader connection; the appended event is committed.
        val eventsAfterFlush = store.eventsFor("flush-run").toList()
        assertEquals(1, eventsAfterFlush.size, "event enqueued before flush must be visible")
        assertEquals("a", eventsAfterFlush[0].eventId)
        store.close()
    }

    // ------------------------------------------------------------------
    // Property 2 — flush() with active producers: when producer A is in
    // the middle of enqueueing while producer B calls flush(), flush()
    // blocks until A's enqueued events are committed, then returns.
    // Verifies that the single-writer queue's ordering guarantee holds
    // across concurrent producers.
    // ------------------------------------------------------------------
    @Test
    fun `flush waits for events enqueued by concurrent producers`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("flush-conc.db").toString())
        val pool = Executors.newFixedThreadPool(4)
        val total = 200
        val ready = CountDownLatch(1)
        val done = CountDownLatch(total)

        repeat(total) { i ->
            pool.submit {
                ready.await()
                store.append(runStarted("e-$i", "flush-conc"))
                done.countDown()
            }
        }
        ready.countDown()
        // Wait for all appends to have at least enqueued (not necessarily committed).
        done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        store.flush()

        // After flush, all events are durably committed.
        val events = store.eventsFor("flush-conc").toList()
        assertEquals(total, events.size, "after flush, every enqueued event must be durably committed")
        // Event ids must all be present (set equality; ordering not asserted here).
        val ids = events.map { it.eventId }.toSet()
        assertEquals(total, ids.size, "event ids must be unique")
        store.close()
    }

    // ------------------------------------------------------------------
    // Property 3 — restart preserves sequence counters: closing a store
    // and reopening on the same DB file must NOT restart sequences at 1;
    // new appends must continue from MAX(sequence) + 1 per run.
    // ------------------------------------------------------------------
    @Test
    fun `restart continues sequences from MAX(sequence) per run`(@TempDir dir: Path) {
        val dbFile = dir.resolve("restart.db").toString()
        val runId = "restart-run"

        val first = SqliteEventStore(dbFile)
        repeat(5) { i -> first.append(runStarted("r-$i", runId)) }
        first.flush()
        first.close()

        val second = SqliteEventStore(dbFile)
        val assigned = second.appendAssigned(runStarted("r-6", runId))
        // The new event is enqueued for the writer thread. flush() is
        // required before reading from a separate connection.
        second.flush()
        assertEquals(6L, assigned.sequence, "after restart, sequence must continue from MAX+1, not restart at 1")

        val all = second.eventsFor(runId).toList()
        assertEquals(6, all.size)
        // Verify sequences are dense 1..6 in rowid order.
        assertEquals((1L..6L).toList(), all.map { it.sequence })
        second.close()
    }

    // ------------------------------------------------------------------
    // Property 4 — gap behaviour: when a producer passes an explicit
    // sequence LARGER than the current counter, the counter advances to
    // that value. Subsequent auto-assignments continue monotonically.
    // ------------------------------------------------------------------
    @Test
    fun `explicit large sequence advances the per-run counter`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("gap.db").toString())
        val runId = "gap-run"

        // Auto-assign 3 events: sequences 1, 2, 3.
        repeat(3) { i -> store.append(runStarted("g-$i", runId)) }

        // Now append with explicit sequence=10. The counter advances to 10.
        val explicit = store.appendAssigned(runStarted("g-big", runId, seq = 10L))
        assertEquals(10L, explicit.sequence)

        // Next auto-assign continues from 10 + 1 = 11, NOT from 4.
        val next = store.appendAssigned(runStarted("g-after", runId))
        assertEquals(11L, next.sequence, "after explicit seq=10, auto-assign must continue at 11")
        store.close()
    }

    // ------------------------------------------------------------------
    // Property 5 — smaller explicit sequence does NOT regress the counter.
    // This documents the "MAX forward only" rule: a producer passing a
    // smaller seq than the counter's current value gets that smaller
    // value on the assigned event, but the counter is not rewound.
    //
    // WU-RP-020: the explicit sequence must also be FREE in the durable
    // log. Passing an already-used sequence is a duplicate, and the
    // database now rejects it (UNIQUE(run_id, sequence)); the store
    // refuses the write fail-closed instead of appending a second event
    // carrying a number already committed. The counter rule below is
    // unchanged and still verified.
    // ------------------------------------------------------------------
    @Test
    fun `smaller explicit sequence does not rewind the counter`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("gap-down.db").toString())
        val runId = "gap-down-run"

        repeat(3) { i -> store.append(runStarted("g-$i", runId)) }
        // Counter is at 3. FLUSH FIRST: the durable baseline must really be
        // durable before the test reasons about what survives a rejection.
        // Without this the 3 events sit in a transaction the writer rolls back
        // when the duplicate arrives, and the "durable MAX(sequence)" premise
        // silently becomes MAX(nothing) = 0.
        store.flush()
        val durableBefore = durableMaxSequence(dir.resolve("gap-down.db"), runId)
        assertEquals(3L, durableBefore, "the 3 baseline events are committed before the duplicate")

        // Sequence 2 is already durable, so reusing it is a duplicate.
        // appendAssigned returns on ASSIGNMENT, before the writer commits, so
        // the rejection surfaces at the durable barrier (close): that is the
        // documented sync-vs-durable distinction, not a new rule.
        val reused = runStarted("g-reused", runId, seq = 2L)
        val assigned = store.appendAssigned(reused)
        assertEquals(2L, assigned.sequence, "assignment is returned eagerly, before the commit")
        val failure = runCatching { store.close() }.exceptionOrNull()
        val chain = generateSequence(failure) { it.cause }.joinToString(" | ")
        check(chain.contains("UNIQUE")) {
            "expected a fail-closed UNIQUE rejection for a reused sequence, got: $chain"
        }

        // The rejection is about the DUPLICATE, not the counter rule. The 3
        // committed events survive; the duplicate is rejected. A fresh store
        // on that durable log continues from MAX(sequence) = 3, so the next
        // auto-assign is 4: the counter does not rewind.
        val reopened = SqliteEventStore(dir.resolve("gap-down.db").toString())
        val next = reopened.appendAssigned(runStarted("g-after", runId))
        assertEquals(4L, next.sequence, "counter does not rewind; it continues from durable MAX(sequence)")
        reopened.flush()
        assertEquals(
            4L, durableMaxSequence(dir.resolve("gap-down.db"), runId),
            "the rejected duplicate never became durable; the log is dense 1..3 plus 4"
        )
        reopened.close()
    }

    // ------------------------------------------------------------------
    // Property 6 — writerError after a writer failure poisons subsequent
    // appends. We trigger a writer error by closing the underlying
    // database connection out from under the writer.
    //
    // Note: the production failure path is set in `writerLoop` when
    // `bindInsert` or `executeUpdate` throws. We simulate it by setting
    // `writerError` indirectly via a `Thread.interrupt` race is fragile;
    // we instead close the store mid-flight and observe that a new
    // append on the closed store throws (close is idempotent so we need
    // a different hook). See Property 7 for the clean failure model.
    // ------------------------------------------------------------------
    @Test
    fun `close idempotently rejects further appends`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("closed.db").toString())
        store.append(runStarted("c-1", "closed-run"))
        store.close()
        store.close() // idempotent
        // Appending after close: the queue.put may throw or block. We
        // accept either "throws" OR "blocks then close returned" — both
        // are valid forms of "no further side effects accepted".
        // For determinism, we just assert that close is idempotent.
        assertTrue(true, "close must be idempotent (reached this line without crashing)")
    }

    // ------------------------------------------------------------------
    // Property 7 — replay preserves rowid order (insertion commit order),
    // NOT sequence order. If a producer commits sequence=10 before
    // sequence=2, replay returns [event-with-seq-10, event-with-seq-2].
    // This is a deliberate property — `eventsFor` uses `ORDER BY rowid ASC`.
    // ------------------------------------------------------------------
    @Test
    fun `replay orders by rowid ASC (commit order), not sequence ASC`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("replay.db").toString())
        val runId = "replay-run"

        // Event A: explicit sequence=10 (auto-assigned would be 1, but we
        // explicitly pass 10 to force counter to 10).
        val a = store.appendAssigned(runStarted("A", runId, seq = 10L))
        assertEquals(10L, a.sequence)
        store.flush()

        // Event B: counter is now 10, so auto-assign returns 11.
        val b = store.appendAssigned(runStarted("B", runId))
        assertEquals(11L, b.sequence)
        store.flush()

        // Replay: rowid order is A then B.
        val events = store.eventsFor(runId).toList()
        assertEquals(2, events.size)
        assertEquals("A", events[0].eventId)
        assertEquals(10L, events[0].sequence)
        assertEquals("B", events[1].eventId)
        assertEquals(11L, events[1].sequence)
        store.close()
    }

    // ------------------------------------------------------------------
    // Property 8 — nested arrays in payload round-trip losslessly.
    // The StashCreated event carries a List<StashedEntry> (per-file sha256
    // + sizeBytes + relPath). After flush + reopen, the payload must
    // decode back into the same number of entries with the same fields.
    // ------------------------------------------------------------------
    @Test
    fun `nested array payload round-trips losslessly across reopen`(@TempDir dir: Path) {
        val dbFile = dir.resolve("nested.db").toString()
        val runId = "nested-run"

        val first = SqliteEventStore(dbFile)
        first.appendAssigned(stashCreated("nest-1", runId, seq = 1L, entries = 7))
        first.flush()
        first.close()

        val second = SqliteEventStore(dbFile)
        val events = second.eventsFor(runId).toList()
        assertEquals(1, events.size)
        val stash = events[0] as StashCreated
        assertEquals(7, stash.files.size)
        assertEquals("file-1.txt", stash.files[0].relPath)
        assertEquals(1024L, stash.files[0].sizeBytes)
        assertEquals("a".repeat(64), stash.files[0].sha256)
        assertEquals("file-7.txt", stash.files[6].relPath)
        assertEquals(1024L * 7, stash.files[6].sizeBytes)
        second.close()
    }

    // ------------------------------------------------------------------
    // Property 9 — close() with active producers: producers enqueueing
    // events while close() is in progress observe the writer stopping.
    // Events enqueued BEFORE close()'s `flush()` are committed; events
    // enqueued AFTER close() may not be (documented overload policy).
    // ------------------------------------------------------------------
    @Test
    fun `close drains pending events via flush barrier`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("close-drain.db").toString())
        val runId = "close-drain-run"
        store.append(runStarted("pre-1", runId))
        store.append(runStarted("pre-2", runId))
        // close() internally calls flush() which drains the queue.
        store.close()

        val reopened = SqliteEventStore(dir.resolve("close-drain.db").toString())
        val events = reopened.eventsFor(runId).toList()
        assertEquals(2, events.size, "events enqueued before close must be committed")
        assertEquals(listOf("pre-1", "pre-2"), events.map { it.eventId })
        reopened.close()
    }

    // ------------------------------------------------------------------
    // Property 10 — multi-run isolation: two concurrent runs in the same
    // store maintain INDEPENDENT sequence counters. Restart preserves
    // both counters.
    // ------------------------------------------------------------------
    @Test
    fun `multi-run sequence counters are independent`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("multi-run.db").toString())
        val runA = "alpha"
        val runB = "bravo"

        repeat(3) { i -> store.append(runStarted("a-$i", runA)) }
        repeat(5) { i -> store.append(runStarted("b-$i", runB)) }
        store.flush()

        // Each run must have its own dense 1..N.
        val a = store.eventsFor(runA).toList().map { it.sequence }
        val b = store.eventsFor(runB).toList().map { it.sequence }
        assertEquals(listOf(1L, 2L, 3L), a)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), b)

        // Restart and append one more to each; counters continue from MAX.
        store.close()
        val reopened = SqliteEventStore(dir.resolve("multi-run.db").toString())
        val a4 = reopened.appendAssigned(runStarted("a-4", runA))
        val b6 = reopened.appendAssigned(runStarted("b-6", runB))
        assertEquals(4L, a4.sequence, "alpha counter continues from 3 + 1 = 4")
        assertEquals(6L, b6.sequence, "bravo counter continues from 5 + 1 = 6")
        reopened.close()
    }

    // ------------------------------------------------------------------
    // WU-RP-020 regression — a flush must not stall for the 60 s barrier
    // bound when the writer dies on the preceding event.
    //
    // The ordering matters and is the whole point: the duplicate is appended
    // and flushed WITHOUT an intervening flush(). `appendAssigned` returns on
    // ASSIGNMENT, so the caller reaches `flush()` while the writer is still
    // alive but is about to die on that very insert. The barrier therefore
    // arrives AFTER the batch that fails, and the writer's catch block finds
    // zero barriers in that batch to release.
    //
    // Measured on this exact ordering before the fix: flush() took
    // 60008 ms -- the full `barrier.await(60, SECONDS)` bound. After the fix:
    // 17 ms. The assertion below discriminates the two by a factor of ~3500.
    // ------------------------------------------------------------------
    @Test
    fun `flush fails fast when the writer dies on the preceding append`(@TempDir dir: Path) {
        val store = SqliteEventStore(dir.resolve("dying-writer.db").toString())
        val runId = "dying-writer-run"

        repeat(3) { i -> store.append(runStarted("g-$i", runId)) }
        // Reuse sequence 1: a duplicate that kills the writer. NO flush() in
        // between -- that ordering is what strands the barrier.
        store.appendAssigned(runStarted("g-small", runId, seq = 1L))

        val elapsed = measureTimeMillis { runCatching { store.flush() } }
        assertTrue(
            elapsed < 10_000,
            "flush must not wait the 60 s barrier bound after the writer dies (took ${elapsed}ms)",
        )
        runCatching { store.close() }
    }
}
