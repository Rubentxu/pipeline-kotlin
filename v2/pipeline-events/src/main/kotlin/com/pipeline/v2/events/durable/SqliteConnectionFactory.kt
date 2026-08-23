package com.pipeline.v2.events.durable

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
 * @see <a href="design.md §E4-03">Design §E4-03</a>
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
}
