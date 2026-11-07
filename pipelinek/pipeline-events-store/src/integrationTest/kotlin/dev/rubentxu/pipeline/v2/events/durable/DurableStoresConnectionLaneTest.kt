package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.runtime.durable.SagaObligationRow
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The connection lane of the durable STORES: the replay cursor and the Saga obligations.
 *
 * ## The guarantee
 *
 * The stores of the durable lane BORROW their connections, the way the journal does: they write
 * through the lane's write pool and read through its read pool, whose door is one the database
 * engine refuses to write through.
 *
 * The door was the expensive part. Opening a connection costs more than the statement it
 * carries, and these stores are asked a question per operation of a run — the cursor advances
 * once per settled operation, and a Saga arms one promise per action — so a connection per
 * operation was a cost that grew with the run.
 *
 * ## Why the number of openings is the instrument
 *
 * The store's doors are already parameters, so a test can count what the product OPENED. The
 * guarantee is about GROWTH: the count must not grow with the number of operations. A test that
 * only counted the openings of a fixed batch would pass for a lane that opened one connection
 * per batch, so this one multiplies the operations by ten and asks that the count not move.
 *
 * ## Why this is an INTEGRATION test
 *
 * The subjects are the DATABASE and the FILESYSTEM: a real SQLite file, the real engine, the
 * real pools. A double would answer whatever it was told, which is exactly the fact under test.
 *
 * ## What the fakes buy
 *
 * Nothing in the product is faked: the store, the journal, the cursor, the Saga obligations, the
 * pools, the schema, the file and the engine are the real ones. The test owns only the seams the
 * store already offers — the doors it opens, where a connection can be counted or kept — and the
 * clock.
 */
class DurableStoresConnectionLaneTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * A clock that says the same instant, so the rows this test writes are comparable.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    /**
     * The store, with its two doors counted.
     *
     * The counts are the instrument: they say how many connections the product OPENED, and the
     * guarantee is about that number not growing with the number of operations.
     */
    private class CountedDoors(
        val file: String,
        val writeOpens: AtomicInteger = AtomicInteger(),
        val readOpens: AtomicInteger = AtomicInteger(),
        val readConnections: MutableList<Connection> = mutableListOf(),
    ) {
        fun store(): SqliteEventStore = SqliteEventStore(
            file = file,
            mode = EventStoreMode.Writable,
            connections = {
                writeOpens.incrementAndGet()
                SqliteConnectionFactory.open(file)
            },
            readerConnections = {
                readOpens.incrementAndGet()
                openEventStoreReaderConnection(file).also { readConnections += it }
            },
        )
    }

    private fun dbPath(): String = tempDir.resolve("pipeline.db").toString()

    private fun storesOf(store: SqliteEventStore, control: String = "control"): DurableStores =
        DurableStores.open(store, tempDir.resolve(control), FixedClock())

    @Test
    fun `the cursor borrows its connections instead of opening one per operation`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val stores = storesOf(store)

        // The lane's doors, counted before a single operation. The sibling stores borrow the
        // same pools, so the pool's connections may already be open: a BORROWED connection is
        // not an OPENED one, and the count of opened connections is what this measures.
        val writesBefore = doors.writeOpens.get()
        val readsBefore = doors.readOpens.get()

        repeat(50) { index -> stores.cursors.advance("run-1", "op-$index", index) }
        repeat(50) { index -> stores.cursors.load("run-$index") }

        val writesAfterFifty = doors.writeOpens.get()
        val readsAfterFifty = doors.readOpens.get()

        assertTrue(
            writesAfterFifty - writesBefore <= 1,
            "fifty advances must borrow the lane's ONE write connection, not open fifty: the door " +
                "is what the measurement found expensive",
        )
        assertTrue(
            readsAfterFifty - readsBefore <= 1,
            "fifty loads must borrow the lane's ONE read connection, not open fifty",
        )

        // Ten times the operations must not open ten times the connections. This is the part
        // that cannot be satisfied by a coincidence: the count has to be the SAME, not merely
        // small, because the pool lends what it has and opens nothing more.
        repeat(500) { index -> stores.cursors.advance("run-1", "op-more-$index", index) }
        repeat(500) { index -> stores.cursors.load("run-more-$index") }

        assertEquals(
            writesAfterFifty,
            doors.writeOpens.get(),
            "ten times the operations must not open ten times the connections: the cursor " +
                "borrows the lane's ONE write connection and gives it back",
        )
        assertEquals(
            readsAfterFifty,
            doors.readOpens.get(),
            "ten times the operations must not open ten times the connections: the cursor " +
                "borrows the lane's ONE read connection and gives it back",
        )
        store.close()
    }

    @Test
    fun `the saga obligations borrow their connections instead of opening one per operation`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val stores = storesOf(store)

        val writesBefore = doors.writeOpens.get()
        val readsBefore = doors.readOpens.get()

        repeat(50) { index -> stores.sagaObligations.arm(row(index)) }
        repeat(50) { index -> stores.sagaObligations.find("run-1", key(index)) }
        repeat(50) { stores.sagaObligations.listForRun("run-1") }

        val writesAfterFifty = doors.writeOpens.get()
        val readsAfterFifty = doors.readOpens.get()

        assertTrue(
            writesAfterFifty - writesBefore <= 1,
            "fifty arms must borrow the lane's ONE write connection, not open fifty: the door " +
                "is what the measurement found expensive",
        )
        assertTrue(
            readsAfterFifty - readsBefore <= 1,
            "fifty reads must borrow the lane's ONE read connection, not open fifty",
        )

        repeat(500) { index -> stores.sagaObligations.arm(row(index + 50)) }
        repeat(500) { index -> stores.sagaObligations.find("run-1", key(index)) }
        repeat(500) { stores.sagaObligations.listForRun("run-1") }

        assertEquals(
            writesAfterFifty,
            doors.writeOpens.get(),
            "ten times the operations must not open ten times the connections: the obligations " +
                "borrow the lane's ONE write connection and give it back",
        )
        assertEquals(
            readsAfterFifty,
            doors.readOpens.get(),
            "ten times the operations must not open ten times the connections: the obligations " +
                "borrow the lane's ONE read connection and give it back",
        )
        store.close()
    }

    @Test
    fun `the reads of the durable stores go through a door the engine refuses to write through`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val stores = storesOf(store)
        stores.sagaObligations.arm(row(0))
        stores.cursors.advance("run-1", "op-0", 0)

        // Only the reads from here on are the ones under question.
        doors.readConnections.clear()
        assertNotNull(
            stores.cursors.load("run-1"),
            "the cursor reads the position it wrote; the question is the door it read with",
        )
        assertNotNull(
            stores.sagaObligations.find("run-1", key(0)),
            "the obligations read the promise they wrote; the question is the door it read with",
        )

        val readConnection = doors.readConnections.last()
        val refusal = assertThrows(SQLException::class.java) {
            readConnection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM saga_obligation")
            }
        }
        assertTrue(
            refusal.message?.contains("readonly", ignoreCase = true) == true,
            "the engine must refuse the write for being read-only, got: ${refusal.message}",
        )
        store.close()
    }

    @Test
    fun `two threads that advance two positions lose no write`() {
        // The cursor's advance is the one write of this lane that does NOT take the file lock:
        // it writes a row of its own, and the lock that fences the journal's writes is not its
        // to take. So two threads advancing two positions on ONE store is the shape that the
        // pool's own promise has to hold: a lent connection belongs to one operation at a time,
        // and the two positions are two different rows.
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val stores = storesOf(store)

        val perThread = 30
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val threads = (0 until 2).map { side ->
            Thread {
                start.await()
                repeat(perThread) { index ->
                    runCatching { stores.cursors.advance("run-$side", "op-$side-$index", index) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(
            0,
            failures.get(),
            "two threads on one store must not be handed the same connection: the pool lends it " +
                "to one operation at a time",
        )
        assertEquals(
            perThread - 1,
            stores.cursors.load("run-0")?.stageIndex,
            "the position of the first run is the one its last advance wrote",
        )
        assertEquals(
            perThread - 1,
            stores.cursors.load("run-1")?.stageIndex,
            "and the second run's position is its own, not the first one's",
        )
        store.close()
    }

    @Test
    fun `two instances that do not share the in-process fence keep every promise`() {
        // The two stores spell the same file DIFFERENTLY on purpose, so the in-process fence --
        // which is keyed by the path -- does not join them. That is the shape of two PROCESSES:
        // each one has its own lock, its own connections and its own door, and the only thing
        // they share is the file. A real second process is not spawned here because the test
        // lanes forbid reaching outside the process (AGENTS.md section 2); the fence that a
        // second process would not have is exactly the one left out on purpose.
        val directory = tempDir.resolve("shared").also { Files.createDirectories(it) }
        val firstFile = directory.resolve("pipeline.db").toString()
        val secondFile = directory.resolve(".").resolve("pipeline.db").toString()

        val first = CountedDoors(firstFile).store()
        val second = CountedDoors(secondFile).store()
        val firstStores = storesOf(first, control = "control-first")
        val secondStores = storesOf(second, control = "control-second")

        val perStore = 25
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val threads = listOf(firstStores to "first", secondStores to "second").map { (stores, side) ->
            Thread {
                start.await()
                repeat(perStore) { index ->
                    runCatching { stores.sagaObligations.arm(row(index, side)) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(
            0,
            failures.get(),
            "no write may fail: the database's own locking is what holds",
        )
        val seenBySecond = secondStores.sagaObligations.listForRun("run-1").size
        assertEquals(
            2 * perStore,
            seenBySecond,
            "the second store must see the $perStore promises the first one armed and its own " +
                "$perStore: it saw $seenBySecond, so the two spellings are not naming ONE file",
        )
        val rows = firstStores.sagaObligations.listForRun("run-1") +
            secondStores.sagaObligations.listForRun("run-1")
        assertEquals(
            2 * perStore,
            rows.distinctBy { it.actionId.value }.size,
            "every promise must be there exactly once, from both sides",
        )
        first.close()
        second.close()
    }

    private fun row(index: Int, side: String = "one"): SagaObligationRow = SagaObligationRow(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-$side-$index"),
        compensationId = CompensationId.of("release-stock"),
        intentVersion = 1,
        planFingerprint = SagaPlanFingerprint("a".repeat(64)),
        resourceRef = ResourceRef.of("stock/reservation-$side-$index"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-$side-$index",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-$side-$index",
        leaseEpoch = 1L,
        createdAtMs = 1_000L + index,
        updatedAtMs = 1_000L + index,
        receiptRef = null,
        ownerPluginId = "example.saga",
    )

    private fun key(index: Int): CompensationKey = CompensationKey(
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-one-$index"),
        compensationId = CompensationId.of("release-stock"),
    )
}
