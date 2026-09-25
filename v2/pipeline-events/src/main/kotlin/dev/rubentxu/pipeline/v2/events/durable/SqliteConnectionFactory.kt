package dev.rubentxu.pipeline.v2.events.durable

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
 * ## Parent directory creation
 *
 * SQLite + the JDBC driver do not create the parent directory of the
 * database file; they abort with a generic `SQLException: path to '…'
 * does not exist`. This factory creates the parent directory if it is
 * missing, idempotently, before opening the connection. Explicit
 * permission / invalid-path / initialization errors are propagated
 * unchanged.
 *
 * @see <a href="design.md §E4-03">Design §E4-03</a>
 */
object SqliteConnectionFactory {

    /**
     * Opens a new SQLite connection with WAL mode enabled and verified.
     *
     * If the parent directory of [file] does not exist, it is created
     * with [Files.createDirectories] before opening the connection.
     *
     * @param file Path to the SQLite database file.
     * @return A JDBC [Connection] with WAL mode active.
     * @throws IllegalStateException if WAL mode is not successfully enabled.
     * @throws java.io.IOException if the parent directory cannot be created
     *         (permission denied, invalid path, or other filesystem error).
     */
    fun open(file: String): Connection {
        val dbPath = Paths.get(file)
        ensureParentDirectory(dbPath)
        val connection = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            connection.createStatement().use { stmt ->
                // Enable WAL journal mode.
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute("PRAGMA synchronous = NORMAL")
                // M3-R4.1 T-04: wait up to 5 seconds when SQLITE_BUSY encountered.
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
     * Ensures the parent directory of [dbPath] exists; creates it if missing.
     *
     * No-op when the parent already exists as a directory. If the parent
     * exists but is NOT a directory (e.g. a regular file with the same name),
     * this method throws an explicit [java.nio.file.FileSystemException]
     * rather than letting the JDBC driver surface an opaque `SQLException`.
     *
     * Propagates any explicit filesystem error from [Files.createDirectories]
     * (permission denied, invalid path, etc.) without masking them.
     *
     * If [dbPath] has no parent (e.g. a bare filename), this is a no-op.
     */
    private fun ensureParentDirectory(dbPath: Path) {
        val parent = dbPath.parent ?: return
        when {
            Files.exists(parent) -> {
                if (!Files.isDirectory(parent)) {
                    throw java.nio.file.FileSystemException(
                        parent.toString(),
                        null,
                        "parent path exists but is not a directory"
                    )
                }
            }
            else -> Files.createDirectories(parent)
        }
    }
}
