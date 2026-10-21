package dev.rubentxu.pipeline.v2.events.durable

import java.sql.Connection
import java.sql.DriverManager

/**
 * Factory for SQLite connections configured for WAL journal mode.
 *
 * ## WAL mode
 *
 * Every connection opened through this factory has:
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
 */
object SqliteConnectionFactory {

    /**
     * Opens a new SQLite connection with WAL mode enabled and verified.
     *
     * @param file Path to the SQLite database file.
     * @return A JDBC [Connection] with WAL mode active.
     * @throws IllegalStateException if WAL mode is not successfully enabled.
     */
    fun open(file: String): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            connection.createStatement().use { stmt ->
                // Enable WAL journal mode.
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute("PRAGMA synchronous = NORMAL")
                // Wait up to 5 seconds when SQLITE_BUSY encountered.
                stmt.execute("PRAGMA busy_timeout = 5000")

                // Verify WAL mode is actually active.
                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                    if (rs.next()) {
                        val mode = rs.getString(1)
                        require(mode == "wal") {
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
