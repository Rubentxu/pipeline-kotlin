package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Why the durable state of a run could not be read.
 *
 * The vocabulary is closed because every case answers the same question — "why is there
 * nothing to show?" — and an operator who gets an empty answer without a reason reads it as
 * "there is nothing owed", which is the one reading this product must never give by accident.
 */
sealed interface SagaInspectionRefusal {

    /** There is no database at that path. */
    data class NoSuchDatabase(val path: String) : SagaInspectionRefusal

    /** The file exists but is not a pipeline store: it has no event log. */
    data class NotAStore(val path: String) : SagaInspectionRefusal

    /** The store has no saga obligation table, so it cannot say anything about a saga. */
    data class NoSagaObligations(val path: String) : SagaInspectionRefusal

    /** A row of the durable state could not be read, and this is which one and why. */
    data class Unreadable(val path: String, val detail: String) : SagaInspectionRefusal
}

/**
 * What a read of the durable saga state found.
 */
sealed interface SagaInspectionRead {

    /**
     * The durable state was read.
     *
     * @param rows the promises of the run, oldest first.
     * @param events what the run announced about itself, in the order it announced it.
     */
    data class Read(
        val rows: List<SagaObligationRow>,
        val events: List<DomainEvent>,
    ) : SagaInspectionRead {

        /** Whether the run itself is in this store. */
        val runExists: Boolean get() = events.isNotEmpty()
    }

    /** The durable state could not be read, and this is why. */
    data class Refused(val refusal: SagaInspectionRefusal) : SagaInspectionRead
}

/**
 * Reads the durable state of a run WITHOUT changing anything.
 *
 * ## Why this exists next to the store
 *
 * The store that arms and settles the promises opens the database for writing, because writing
 * is what it is for. A reader that wants to answer "what does this run still owe?" has no
 * business writing, and a reader that shared the store's connection would inherit a schema
 * migration, a writer thread and a write-ahead it never asked for. So this reader opens the
 * SAME database file through a connection the engine of SQLite itself refuses to write
 * through, and asks it for nothing but rows.
 *
 * ## What it reads, and why both
 *
 * The `saga_obligation` table, where the engine writes each promise BEFORE the effect it
 * promises to undo is allowed to happen. That table is the authority for what is owed: a
 * promise can exist without the event that announced it (a crash between the write and the
 * announcement) and never the other way round, so a reader that asked the event log would
 * miss promises that exist, and a reader that asked the console would miss them more often.
 *
 * And the run's own announcements, for the one thing the promise table cannot say: how the
 * saga ended. A saga that stopped and left a promise owed is a promise that waits for a
 * person, and the table does not record that the walk stopped — only that the promise is
 * still there. Those announcements are read with the decoder of the module that owns them,
 * and turned into a verdict with the projection that module publishes, so this reader has no
 * opinion of its own about what an announcement means.
 *
 * ## What it never reads
 *
 * The console, the output plane, and every other text a run leaves behind. Those are what a
 * reader falls back to when the durable state cannot answer, and a durable state that CAN
 * answer is worth more than a transcript that can be truncated or interleaved.
 */
class SqliteSagaObligationReader(private val dbPath: String) {

    /**
     * Reads the durable state of [runId].
     */
    fun read(runId: String): SagaInspectionRead {
        val path = Paths.get(dbPath)
        if (!Files.isRegularFile(path)) {
            return SagaInspectionRead.Refused(SagaInspectionRefusal.NoSuchDatabase(dbPath))
        }
        return try {
            DriverManager.getConnection(readOnlyUrl(path)).use { connection ->
                readThrough(connection, runId)
            }
        } catch (e: SQLException) {
            SagaInspectionRead.Refused(
                SagaInspectionRefusal.Unreadable(dbPath, e.message ?: e.javaClass.simpleName),
            )
        }
    }

    private fun readThrough(connection: Connection, runId: String): SagaInspectionRead {
        if (!tableExists(connection, EVENTS_TABLE)) {
            return SagaInspectionRead.Refused(SagaInspectionRefusal.NotAStore(dbPath))
        }
        if (!tableExists(connection, SAGA_OBLIGATION_TABLE)) {
            return SagaInspectionRead.Refused(SagaInspectionRefusal.NoSagaObligations(dbPath))
        }
        val events = readEvents(connection, runId)
            ?: return SagaInspectionRead.Refused(
                SagaInspectionRefusal.Unreadable(dbPath, "a row of the run's history could not be read"),
            )
        val rows = connection.prepareStatement(SELECT_RUN_OBLIGATIONS).use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { rs ->
                val found = mutableListOf<SagaObligationRow>()
                while (rs.next()) {
                    found.add(SagaObligationRowCodec.read(rs))
                }
                found
            }
        }
        return SagaInspectionRead.Read(rows, events)
    }

    /**
     * The run's announcements, in the order they were made.
     *
     * A row that cannot be read STOPS the read instead of being skipped: a history with a hole
     * in it is not a history, and a reader that skipped the hole would answer about a run whose
     * ending it never saw.
     */
    private fun readEvents(connection: Connection, runId: String): List<DomainEvent>? =
        connection.prepareStatement(SELECT_RUN_EVENTS).use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { rs ->
                val decoded = mutableListOf<DomainEvent>()
                while (rs.next()) {
                    val kind = rs.getString("kind")
                    val payload = rs.getString("payload")
                    when (val row = JsonEventLog.decodeStoredRow(payload, kind)) {
                        is StoredRowDecode.Accepted -> decoded.add(row.event)
                        is StoredRowDecode.Refused -> return null
                    }
                }
                decoded
            }
        }

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.prepareStatement(SELECT_TABLE).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rs ->
                rs.next()
            }
        }

    /**
     * The URL of a connection the database engine refuses to write through.
     *
     * The read-only mode is enforced by SQLite itself, not by this code remembering not to
     * write: a statement that would change the database fails with a refusal instead of
     * succeeding quietly.
     */
    private fun readOnlyUrl(path: Path): String = sagaReadOnlyUrl(path)

    private companion object {
        const val EVENTS_TABLE = "events"
        const val SAGA_OBLIGATION_TABLE = "saga_obligation"

        const val SELECT_TABLE = """
            SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?
        """

        const val SELECT_RUN_EVENTS = """
            SELECT kind, payload FROM events
            WHERE run_id = ?
            ORDER BY sequence
        """

        const val SELECT_RUN_OBLIGATIONS = """
            SELECT * FROM saga_obligation
            WHERE run_id = ?
            ORDER BY created_at_ms, saga_id, action_id, compensation_id
        """
    }
}

/**
 * The URL of a connection that cannot change the database at [path].
 *
 * The mode is a property of the connection and not of the code that opens it, so a statement
 * that would write fails with a refusal from the database engine itself.
 */
internal fun sagaReadOnlyUrl(path: Path): String =
    "jdbc:sqlite:${path.toAbsolutePath().toUri()}?mode=ro"
