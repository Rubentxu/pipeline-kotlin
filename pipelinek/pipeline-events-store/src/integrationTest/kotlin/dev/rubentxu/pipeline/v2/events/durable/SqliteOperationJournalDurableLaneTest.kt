package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The durable lane of the operation journal: the connections it borrows, the fence it keeps and
 * the end it has.
 *
 * ## The guarantee
 *
 * The journal does not open a connection per operation. It borrows one from the pool of the
 * durable lane and gives it back, so the cost of the door is paid once per connection instead of
 * once per operation — and a connection belongs to ONE operation at a time, so two threads never
 * share it.
 *
 * Around that, four promises the change could have broken and did not have to:
 *
 * - the READS go through a door the database engine refuses to write through, in both lanes;
 * - the FENCE still holds: two instances over the same file serialize their writes;
 * - the store's CLOSE really ends the durable lane: a write after it is refused loudly, and a
 *   store opened later sees what the closed one wrote;
 * - a DAMAGED durable state is refused loudly instead of being answered with an empty journal.
 *
 * ## What a borrowed connection changes about the damage
 *
 * A connection that is borrowed and given back keeps the state it read, so a file damaged AFTER
 * the connection was opened is not necessarily seen by the next read: in WAL mode the rows live in
 * the write-ahead log, and the damage to the main file is masked until the log is checkpointed.
 *
 * The damage is refused where it can be seen, which is where it matters: when the file is OPENED.
 * A process that starts over a damaged database is told, and a row whose payload this build
 * cannot read is refused by the read that meets it. A live writer that meets a damaged file fails
 * at its commit, which is loud as well.
 *
 * ## Why this is an INTEGRATION test
 *
 * The subjects are decisions of the DATABASE ENGINE — whether a connection may write, whether a
 * corrupted file is refused — and of the FILESYSTEM — whether a store that closed leaves a file
 * another store can read. A double would answer whatever it was told, which is exactly the fact
 * under test.
 *
 * ## What the fakes buy
 *
 * Nothing in the product is faked: the store, the journal, the pools, the schema, the file and
 * the engine are the real ones. The test owns only the seams the store already offers — the
 * doors it opens, where a connection can be counted or kept — and the clock.
 */
class SqliteOperationJournalDurableLaneTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "v2:run:durable-lane"

    /**
     * A clock that says the same instant, so the rows this test writes are comparable.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private fun dbPath(name: String = "pipeline.db"): String = tempDir.resolve(name).toString()

    private fun operation(id: String): RerunOperation = RerunOperation(
        id = id,
        fingerprint = Fingerprint(String.format(Locale.ROOT, "%064x", id.hashCode().toLong() and 0xFFFFFFFFL)),
        input = OperationInput(
            stepId = "step-0",
            params = emptyMap(),
            runId = runId,
            attempt = 1,
        ),
        output = OperationOutput(JsonPrimitive("done"), durationMs = 1L, finishedAt = 1_767_225_600_000L),
        status = OperationStatus.SUCCEEDED,
        attempt = 1,
    )

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

    private fun journalOf(store: SqliteEventStore): OperationJournal =
        DurableStores.open(store, tempDir.resolve("control"), FixedClock()).journal

    @Test
    fun `the journal does not open a connection per operation`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val journal = journalOf(store)

        val writesBefore = doors.writeOpens.get()
        val readsBefore = doors.readOpens.get()

        repeat(50) { index ->
            journal.append(operation("op-$index"), deadlineMs = null)
        }
        repeat(50) { index ->
            journal.get("op-${index % 10}")
        }

        assertEquals(
            1,
            doors.writeOpens.get() - writesBefore,
            "fifty writes must borrow ONE connection, not open fifty: the door is what the " +
                "measurement found expensive",
        )
        assertEquals(
            1,
            doors.readOpens.get() - readsBefore,
            "fifty reads must borrow ONE connection, not open fifty",
        )
        store.close()
    }

    @Test
    fun `the engine refuses to write through the connection a journal read used`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val journal = journalOf(store)
        journal.append(operation("op-1"), deadlineMs = null)

        doors.readConnections.clear()
        assertNotNull(journal.get("op-1"), "the row is there; the question is the door it was read with")

        val readConnection = doors.readConnections.last()
        val refusal = assertThrows(SQLException::class.java) {
            readConnection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO operation_journal (op_id, fingerprint, status, kind, attempt, " +
                        "input, output, started_at, created_at, updated_at, run_id) " +
                        "VALUES ('op-x', 'ff', 'SUCCEEDED', 'RERUN', 1, '{}', NULL, 0, 0, 0, '$runId')",
                )
            }
        }
        assertTrue(
            refusal.message?.contains("readonly", ignoreCase = true) == true,
            "the engine must refuse the write for being read-only, got: ${refusal.message}",
        )
        store.close()
    }

    @Test
    fun `a write after the store is closed is refused loudly`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val journal = journalOf(store)
        journal.append(operation("op-1"), deadlineMs = null)
        store.close()

        val refusal = assertThrows(IllegalStateException::class.java) {
            journal.append(operation("op-2"), deadlineMs = null)
        }
        assertTrue(
            refusal.message?.contains("closed") == true,
            "the refusal must say the lane is closed, got: ${refusal.message}",
        )
    }

    @Test
    fun `a store opened after the closed one reads what it wrote`() {
        val file = dbPath()
        val first = CountedDoors(file).store()
        val firstJournal = journalOf(first)
        firstJournal.append(operation("op-1"), deadlineMs = null)
        first.close()

        val second = CountedDoors(file).store()
        val secondJournal = journalOf(second)

        val recovered = secondJournal.get("op-1")
        assertNotNull(recovered, "the row the closed store wrote must be there for the next one")
        assertEquals("op-1", requireNotNull(recovered).id)
        second.close()
    }

    @Test
    fun `two stores over the same file keep every write`() {
        val file = dbPath()
        val first = CountedDoors(file).store()
        val second = CountedDoors(file).store()
        val firstJournal = journalOf(first)
        val secondJournal = journalOf(second)

        val perStore = 40
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val threads = listOf(firstJournal, secondJournal).map { journal ->
            Thread {
                start.await()
                repeat(perStore) { index ->
                    runCatching { journal.append(operation("op-${journal.hashCode()}-$index"), null) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(0, failures.get(), "no write may fail: the fence is what keeps them apart")
        val rows = firstJournal.listForRun(runId) + secondJournal.listForRun(runId)
        assertEquals(
            2 * perStore,
            rows.distinctBy { it.id }.size,
            "every write must be there exactly once, from both sides",
        )
        first.close()
        second.close()
    }

    @Test
    fun `two stores that do not share the in-process fence keep every write`() {
        // The two stores spell the same file DIFFERENTLY on purpose, so the in-process fence --
        // which is keyed by the path -- does not join them. That is the shape of two PROCESSES:
        // each one has its own lock, its own connections and its own door, and the only thing
        // they share is the file. A real second process is not spawned here because the test
        // lanes forbid reaching outside the process (AGENTS.md section 2); the fence that a
        // second process would not have is exactly the one left out on purpose.
        val directory = tempDir.resolve("shared").also { java.nio.file.Files.createDirectories(it) }
        val firstFile = directory.resolve("pipeline.db").toString()
        val secondFile = directory.resolve(".").resolve("pipeline.db").toString()

        val first = CountedDoors(firstFile).store()
        val second = CountedDoors(secondFile).store()
        val firstJournal = journalOf(first)
        val secondJournal = journalOf(second)

        val perStore = 40
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val threads = listOf(firstJournal, secondJournal).map { journal ->
            Thread {
                start.await()
                repeat(perStore) { index ->
                    runCatching { journal.append(operation("op-${journal.hashCode()}-$index"), null) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(0, failures.get(), "no write may fail: the database's own locking is what holds")
        val seenBySecond = secondJournal.listForRun(runId).size
        assertEquals(
            2 * perStore,
            seenBySecond,
            "the second store must see the $perStore rows the first one wrote and its own $perStore: " +
                "it saw $seenBySecond, so the two spellings are not naming ONE file",
        )
        val rows = firstJournal.listForRun(runId) + secondJournal.listForRun(runId)
        assertEquals(
            2 * perStore,
            rows.distinctBy { it.id }.size,
            "every write must be there exactly once, from both sides",
        )
        first.close()
        second.close()
    }

    @Test
    fun `a corrupted file is refused loudly instead of answering an empty journal`() {
        val file = dbPath()
        val store = CountedDoors(file).store()
        val journal = journalOf(store)
        journal.append(operation("op-1"), deadlineMs = null)
        store.close()

        // The durable state is replaced by something that is not a database at all.
        java.nio.file.Files.write(java.nio.file.Path.of(file), "not a database at all".toByteArray())

        val refusal = assertThrows(SQLException::class.java) {
            val broken = CountedDoors(file).store()
            broken.use { brokenStore -> journalOf(brokenStore).get("op-1") }
        }
        assertTrue(
            refusal.message?.contains("not a database", ignoreCase = true) == true ||
                refusal.message?.contains("malformed", ignoreCase = true) == true,
            "the refusal must name the damage, got: ${refusal.message}",
        )
    }

    @Test
    fun `a row whose payload is damaged is refused loudly`() {
        val doors = CountedDoors(dbPath())
        val store = doors.store()
        val journal = journalOf(store)

        // A row written by hand, with a payload this build cannot read. Everything else about the
        // row is well formed, so the refusal can only come from the payload.
        SqliteConnectionFactory.open(doors.file).use { connection ->
            connection.prepareStatement(
                "INSERT INTO operation_journal (op_id, fingerprint, status, kind, attempt, input, " +
                    "output, started_at, created_at, updated_at, run_id) " +
                    "VALUES ('op-broken', '${String.format(Locale.ROOT, "%064x", 1L)}', 'SUCCEEDED', 'RERUN', 1, " +
                    "'not json at all', NULL, 0, 0, 0, '$runId')",
            ).use { it.executeUpdate() }
        }

        assertThrows(SerializationException::class.java) {
            journal.get("op-broken")
        }
        store.close()
    }
}
