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
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
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

    private fun storesOf(store: SqliteEventStore): DurableStores =
        DurableStores.open(store, tempDir.resolve("control"), FixedClock())

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

    private fun row(index: Int): SagaObligationRow = SagaObligationRow(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-$index"),
        compensationId = CompensationId.of("release-stock"),
        intentVersion = 1,
        planFingerprint = SagaPlanFingerprint("a".repeat(64)),
        resourceRef = ResourceRef.of("stock/reservation-$index"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-$index",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-$index",
        leaseEpoch = 1L,
        createdAtMs = 1_000L + index,
        updatedAtMs = 1_000L + index,
        receiptRef = null,
        ownerPluginId = "example.saga",
    )

    private fun key(index: Int): CompensationKey = CompensationKey(
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("action-$index"),
        compensationId = CompensationId.of("release-stock"),
    )
}
