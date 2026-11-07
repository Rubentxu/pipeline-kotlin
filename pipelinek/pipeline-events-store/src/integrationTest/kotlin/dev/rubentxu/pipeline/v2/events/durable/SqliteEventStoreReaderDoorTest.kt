package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The door the PRODUCER's own reads go through.
 *
 * ## The guarantee
 *
 * A read cannot write the journal it reads. Not because this code remembers not to, and not
 * because the file happens to be in a certain state, but because the database engine refuses the
 * write: the connection a read opens is one the engine will not let write through.
 *
 * That guarantee used to rest on an accident. The producer's lane opened its reads through the
 * PRODUCER's door, and that door sets `PRAGMA journal_mode = WAL` — a write to the database header
 * — so the reader's connection was one the engine ALLOWED to write. Nothing wrote through it, so
 * nothing was corrupted; the journal did not even change, because the producer's own writer had
 * already put the file in WAL mode. What was left was a reader that could have written and was
 * kept from it by the state of the file rather than by its own door.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 *
 * The subject is a decision of the DATABASE ENGINE, so the engine has to be the one that answers.
 * A double would answer whatever it was told to answer, which is exactly the fact under test.
 * `Connection.isReadOnly()` cannot stand in for it either: the shipped driver reports `false` for
 * every door, read-only or not.
 *
 * ## What the fakes buy
 *
 * Nothing in the store is faked: the store, the writer, the schema, the transaction and the file
 * are the real ones. The only thing the test owns is the store's DOOR — the seam where the
 * connection a read opens can be kept and inspected after the read is over.
 */
class SqliteEventStoreReaderDoorTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = "v2:run:door"
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun started(eventId: String) = RunStarted(
        eventId = eventId,
        runId = runId,
        sequence = 0L,
        occurredAt = startedAt,
        scriptPath = "pipeline.kts",
    )

    private fun dbPath(): String = tempDir.resolve("pipeline.db").toString()

    /**
     * A connection the read closes and the test keeps.
     *
     * The read owns the connection and closes it when it is done; the test needs to ask the engine
     * a question about it AFTER that, because the question is about the door the read used.
     */
    private class KeptOpen(private val real: Connection) : Connection by real {
        override fun close() {
            // The test closes the connection it owns, when the test is done with it.
        }
    }

    @Test
    @DisplayName("the engine refuses to write through the connection a read used")
    fun theEngineRefusesToWriteThroughTheConnectionAReadUsed() {
        val file = dbPath()
        val opened = mutableListOf<Connection>()
        val store = SqliteEventStore(
            file,
            EventStoreMode.Writable,
            readerConnections = {
                val connection = openEventStoreReaderConnection(file)
                opened += connection
                KeptOpen(connection)
            },
        )
        store.append(started("evt-1"))
        store.flush()
        val port = EventRecordReadPortStoreAdapter(reader = store, runDeclared = { false })

        port.readRecords(runId, null, EventQuery.All, 10)

        assertTrue(opened.isNotEmpty(), "the read must have opened a connection to be judged")
        val refusal = assertThrows(SQLException::class.java) {
            opened.last().createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES ('evt-x', '$runId', 99, 'RunStarted', '$startedAt', '[]')",
                )
            }
        }
        assertTrue(
            refusal.message?.contains("readonly", ignoreCase = true) == true,
            "the engine must refuse the write for being read-only, got: ${refusal.message}",
        )
        opened.forEach { it.close() }
        store.close()
    }

    @Test
    @DisplayName("the producer's read still returns what the producer wrote")
    fun theProducersReadStillReturnsWhatTheProducerWrote() {
        val file = dbPath()
        val store = SqliteEventStore(file, EventStoreMode.Writable)
        store.append(started("evt-1"))
        store.flush()
        val port = EventRecordReadPortStoreAdapter(reader = store, runDeclared = { false })

        val result = port.readRecords(runId, null, EventQuery.All, 10)

        val served = assertInstanceOf(EventRecordReadResult.Page::class.java, result)
        assertEquals(
            listOf("evt-1"),
            served.slice.records.map { record ->
                when (record) {
                    is dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded -> record.event.eventId
                    is dev.rubentxu.pipeline.v2.events.EventRecordRead.Undecodable -> record.eventId
                }
            },
            "the producer must read back the run it wrote, through the door that cannot write",
        )
        store.close()
    }
}
