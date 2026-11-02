package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A refused append is a fact about ONE event, not about the store.
 *
 * ## The guarantee
 * When the database refuses one event, three things are true at once and all three must hold:
 *
 * - only that event is missing from the log;
 * - the events around it are durable;
 * - the writer is still there and still takes writes.
 *
 * The guarantee needs the isolation to be PER EVENT. A batch-wide rollback would undo up to 511
 * events of OTHER runs with the guilty one, and the writer would have no way to tell which row
 * was guilty: the whole store would be dead for the rest of the run.
 *
 * ## What is faked, and why
 * Nothing. The instrument is the REAL SQLite engine, on an in-memory shared-cache database, so
 * the savepoint semantics under test are the ones the product ships.
 *
 * A fake connection was considered and rejected: the savepoint IS the behaviour under test, so a
 * fake would have to re-implement SQLite's rollback-to-savepoint rules to be useful, and a test
 * that checks the code against a model of the code proves the model. The database engine already
 * exists as a dependency, and `mode=memory&cache=shared` keeps the whole test off the disk.
 *
 * ## The refusal is real, not staged
 * The refused event repeats the sequence of an event already written in the same run, so the
 * database's own uniqueness index rejects it. Nothing in the writer is told to fail.
 */
class SqliteEventWriterRefusalIsolationTest {

    @Test
    @DisplayName("un append rechazado no se lleva por delante al escritor ni a los eventos de alrededor")
    fun `a refused append does not take the writer or the events around it down`() {
        val databaseName = "refusal-isolation-${System.nanoTime()}"
        val connections = {
            DriverManager.getConnection("jdbc:sqlite:file:$databaseName?mode=memory&cache=shared")
        }
        val writer = SqliteEventWriter(connections)
        try {
            val first = writer.appendAssigned(event("e1"))
            // The same run and the same sequence as the event above: the database refuses it.
            writer.appendAssigned(event("e2", sequence = first.sequence))
            writer.appendAssigned(event("e3"))

            val refusal = assertThrows(EventAppendRefusedException::class.java) { writer.flush() }
            assertEquals(listOf("e2"), refusal.refusals.map { it.event.eventId })

            // The events around the refused one are durable.
            assertEquals(listOf("e1", "e3"), committedEventIds(connections))

            // The writer survived: it still takes writes, and what it takes is durable.
            writer.appendAssigned(event("e4"))
            assertThrows(EventAppendRefusedException::class.java) { writer.flush() }
            assertEquals(listOf("e1", "e3", "e4"), committedEventIds(connections))
        } finally {
            // close() flushes, and a refusal is reported there: the writer still has to stop.
            runCatching { writer.close() }
        }
    }

    private fun event(id: String, sequence: Long = 0L): RunStarted = RunStarted(
        eventId = id,
        runId = RUN_ID,
        sequence = sequence,
        occurredAt = Instant.EPOCH,
        scriptPath = "refusal-isolation",
    )

    /**
     * The event ids the database holds, in sequence order.
     *
     * The connection is opened and closed here, inside the read: a reader that holds the shared
     * cache open while the writer appends would ask the database for a lock the writer needs.
     */
    private fun committedEventIds(connections: () -> Connection): List<String> {
        connections().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT event_id FROM events ORDER BY sequence").use { rows ->
                    val ids = ArrayList<String>()
                    while (rows.next()) {
                        ids.add(rows.getString(1))
                    }
                    return ids
                }
            }
        }
    }

    private companion object {
        const val RUN_ID = "run-refusal-isolation"
    }
}
