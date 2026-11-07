package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

/**
 * Factory for SQLite connections configured for WAL journal mode.
 *
 * ## WAL mode
 *
 * The PRODUCER's door ([open]) gives every connection it opens:
 * - `PRAGMA journal_mode = WAL` — enables Write-Ahead Logging for concurrent
 *   readers and a single writer, improving durability and read concurrency.
 * - `PRAGMA synchronous = NORMAL` — balances performance and safety;
 *   WAL mode with NORMAL is durable under process crashes.
 *
 * ## Verification
 *
 * The factory verifies that WAL mode was successfully enabled by querying
 * `PRAGMA journal_mode` and throwing [IllegalStateException] if the result
 * is not `"wal"`. This prevents silent fallback to DELETE mode which would
 * bypass the durability guarantees of the operation journal.
 *
 * ## The two doors
 *
 * [open] is the producer's door and [openReadOnly] is the reader's. The reader's door opens the
 * file in a mode the engine refuses to write through, and it sets neither the journal mode nor
 * the synchronous setting: the journal mode is a write to the database header, and the reader was
 * only asked to look at the file.
 */
object SqliteConnectionFactory {

    /**
     * How long the producer's door waits for a lock the engine holds.
     */
    const val DEFAULT_BUSY_TIMEOUT_MS: Long = 5_000

    /**
     * The error code SQLite reports a locked database with.
     *
     * Measured against the shipped driver (sqlite-jdbc 3.46.1.3): the driver hands the SQLite
     * result code over as the JDBC error code, so the lock is told apart from every other
     * failure without reading the driver's message. A driver that reported nothing here would
     * leave the door refusing the way it refuses today: with the engine's own words.
     */
    private const val SQLITE_BUSY = 5

    /**
     * Opens a new SQLite connection with WAL mode enabled and verified.
     *
     * ## The wait, and the statement it does not reach
     *
     * The door declares a window — [DEFAULT_BUSY_TIMEOUT_MS] by default — and the engine honours
     * it for the statements that ask for a lock: a write against a journal another writer holds
     * waits until the window is over.
     *
     * The change of the journal mode is NOT one of those statements. Measured against the shipped
     * driver (sqlite-jdbc 3.46.1.3): SQLite does not consult the wait when the journal mode
     * changes, so the change fails at once with `SQLITE_BUSY` however long the window is, and
     * declaring the wait before the change does not change that. The door therefore asks for the
     * lock itself — the engine's own handler is what waits — and makes the change with the lock
     * in hand.
     *
     * When the lock does not free up inside the window, the door refuses with a [SQLException]
     * that names the lock, the window that ran out and the way out of it.
     *
     * @param file Path to the SQLite database file.
     * @param busyTimeoutMs How long the door waits for a lock the engine holds.
     * @return A JDBC [Connection] with WAL mode active.
     * @throws IllegalStateException if WAL mode is not successfully enabled.
     * @throws SQLException if the journal was held by another connection for the whole window.
     */
    fun open(file: String, busyTimeoutMs: Long = DEFAULT_BUSY_TIMEOUT_MS): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            connection.createStatement().use { stmt ->
                // The window comes first, so that every statement below is inside it.
                stmt.execute("PRAGMA busy_timeout = $busyTimeoutMs")
                // Enable WAL journal mode.
                switchToWal(stmt, file, busyTimeoutMs)
                stmt.execute("PRAGMA synchronous = NORMAL")

                // Verify WAL mode is actually active.
                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                    if (rs.next()) {
                        val mode = rs.getString(1)
                        // The journal mode is a state of the database, and not
                        // an argument of the caller, so the refusal is a state
                        // and not an invalid argument.
                        check(mode == "wal") {
                            "Expected WAL journal mode but got '$mode'. " +
                                "WAL mode is required for operation journal durability."
                        }
                    }
                }
            }
            return connection
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    /**
     * Puts the journal in WAL mode, waiting for the lock the change cannot wait for.
     *
     * The engine does not consult the wait when the journal mode changes, so the change fails at
     * once when another writer holds the file. The door asks for the lock itself: the engine's
     * handler is what waits, and it waits exactly the window the door declares. The change is
     * then made with the lock in hand.
     */
    private fun switchToWal(stmt: Statement, file: String, busyTimeoutMs: Long) {
        try {
            stmt.execute("PRAGMA journal_mode = WAL")
            return
        } catch (first: SQLException) {
            if (first.errorCode != SQLITE_BUSY) throw first
        }

        // The lock is asked for, and the engine's handler is what waits for it.
        try {
            stmt.execute("BEGIN IMMEDIATE")
            stmt.execute("ROLLBACK")
        } catch (waited: SQLException) {
            if (waited.errorCode != SQLITE_BUSY) throw waited
            throw lockedAway(file, busyTimeoutMs, waited)
        }

        try {
            stmt.execute("PRAGMA journal_mode = WAL")
        } catch (second: SQLException) {
            if (second.errorCode != SQLITE_BUSY) throw second
            throw lockedAway(file, busyTimeoutMs, second)
        }
    }

    /**
     * The refusal for a journal that stayed locked for the whole window.
     *
     * The refusal is readable on purpose: whoever reads it has to know that the file was held by
     * somebody else, how long the door waited, and what to do about it. The engine's own words
     * travel with it, so nothing the engine said is lost.
     */
    private fun lockedAway(file: String, busyTimeoutMs: Long, cause: SQLException): SQLException =
        SQLException(
            "the journal '$file' was held by another connection for the whole $busyTimeoutMs ms " +
                "window the producer's door waits; stop the writer that holds it, or try again " +
                "when it is done (the engine said: ${cause.message})",
            cause,
        )

    /**
     * Opens a connection the database engine refuses to write through.
     *
     * ## Why a separate door
     * [open] is the producer's door: it sets the journal mode and verifies WAL, because a
     * writer that does not persist durably is a defect. A reader needs none of that and must
     * not have it — `PRAGMA journal_mode = WAL` is a WRITE to the database header, so a reader
     * that used the producer's door changed the file it was only asked to look at.
     *
     * ## What `mode=ro` buys
     * The connection is opened with SQLite's read-only flag, so the engine refuses every write
     * — DML and DDL alike — with `SQLITE_READONLY`. The guarantee is therefore enforced by the
     * database and not by this code remembering not to write.
     *
     * Measured against the shipped driver (sqlite-jdbc 3.46.1.3): a read-only connection opens
     * a WAL database that another process holds open, reads it while the writer is alive, reads
     * it after the writer was killed without a checkpoint, and reads it after the writer closed
     * cleanly. Every write is refused by the engine.
     *
     * ## The path, escaped
     * `mode=ro` is a URI, and a URI ends its path at the first `?` or `#`. A database file whose
     * name contains one of those characters would otherwise be opened as a DIFFERENT file — a
     * reader pointed at the wrong journal, reading another run's history. The three characters
     * that carry that meaning (`%`, `#`, `?`) are percent-encoded here; everything else, spaces
     * included, is passed through as SQLite accepts it.
     *
     * @param file Path to the SQLite database file.
     * @return A JDBC [Connection] that cannot write.
     */
    fun openReadOnly(file: String): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:file:${escapeForUri(file)}?mode=ro")
        try {
            connection.createStatement().use { stmt ->
                // Wait up to 5 seconds when SQLITE_BUSY encountered.
                stmt.execute("PRAGMA busy_timeout = 5000")
            }
            return connection
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    /**
     * Percent-encodes the characters that would otherwise end the path of a SQLite URI.
     *
     * `%` goes first: it is the escape character itself, and encoding it after the others
     * would encode the `%` this method has just written.
     */
    private fun escapeForUri(path: String): String =
        path.replace("%", "%25").replace("#", "%23").replace("?", "%3F")
}
