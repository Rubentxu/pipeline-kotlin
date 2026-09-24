package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Tests for [SqliteConnectionFactory], focused on the
 * "parent directory creation" contract added when fixing the work-around
 * that the WU-RP-043 external verifier had to implement locally.
 *
 * The contract:
 *   - if the parent directory does NOT exist, it is created;
 *   - if it already exists, no error;
 *   - if it CANNOT be created (permission denied, invalid path), the
 *     factory propagates the explicit [java.io.IOException] instead of
 *     the opaque SQLite `SQLException: path to '…' does not exist`.
 */
@DisplayName("SqliteConnectionFactory — parent directory contract")
class SqliteConnectionFactoryParentDirectoryTest {

    @TempDir
    lateinit var tempDir: Path

    private val createdFiles = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        createdFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    @DisplayName("creates missing parent directory before opening the connection")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `creates missing parent directory`() {
        val nestedDir = tempDir.resolve("a/b/c")
        val dbFile = nestedDir.resolve("db.sqlite")
        createdFiles.add(dbFile)

        assertTrue(!nestedDir.exists(), "precondition: nested dir must not exist")

        val conn = SqliteConnectionFactory.open(dbFile.toString())
        try {
            assertNotNull(conn, "connection must not be null")
            // Sanity: WAL mode is active.
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("wal", rs.getString(1))
                }
            }
        } finally {
            conn.close()
        }

        assertTrue(nestedDir.exists(), "parent directory must be created")
        assertTrue(dbFile.exists(), "database file must exist")
    }

    @Test
    @DisplayName("no-op when parent directory already exists")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `no-op when parent already exists`() {
        val existingDir = tempDir.resolve("existing")
        Files.createDirectories(existingDir)
        val dbFile = existingDir.resolve("db.sqlite")
        createdFiles.add(dbFile)

        // Verify the directory existed BEFORE we called open(). Its mtime may
        // change afterwards because SQLite writes WAL/SHM sidecar files into
        // the parent; the contract is "do not recreate the directory", not
        // "do not modify the directory at all".
        assertTrue(existingDir.exists())
        assertTrue(Files.isDirectory(existingDir))

        val conn = SqliteConnectionFactory.open(dbFile.toString())
        try {
            assertNotNull(conn)
        } finally {
            conn.close()
        }

        // The directory must STILL exist as a directory (not replaced).
        assertTrue(existingDir.exists(), "parent directory must still exist")
        assertTrue(Files.isDirectory(existingDir), "parent must still be a directory")
        assertTrue(dbFile.exists(), "database file must be created inside")
    }

    @Test
    @DisplayName("propagates an IOException when parent cannot be created")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `propagates IOException when parent cannot be created`() {
        // Create a regular file, then try to create the connection under a
        // path whose parent is that file (so the parent cannot be a directory).
        val blocker = tempDir.resolve("blocker")
        Files.writeString(blocker, "i am a regular file, not a directory")
        createdFiles.add(blocker)

        // Trying to open a database under <blocker>/db.sqlite must throw,
        // and the exception must NOT be the opaque SQLite SQLException
        // (which is what the original buggy behaviour produced).
        // The current contract surfaces an IOException / FileSystemException.
        val dbFile = blocker.resolve("db.sqlite")
        try {
            val conn = SqliteConnectionFactory.open(dbFile.toString())
            conn.close()
            fail<Unit>("expected a FileSystemException because parent is a regular file, but open() succeeded")
        } catch (e: java.nio.file.FileSystemException) {
            // Expected: explicit filesystem-level IOException subclass.
            assertTrue(e.message?.contains("blocker", ignoreCase = true) == true,
                       "error should mention the blocker path, got: ${e.message}")
        } catch (e: java.io.IOException) {
            // Other IOExceptions are also acceptable as long as they are
            // explicit filesystem errors, not the opaque SQLite SQLException.
            assertTrue(
                e.message?.contains("blocker", ignoreCase = true) == true ||
                    e.message?.contains("Not a directory", ignoreCase = true) == true,
                "error should be a filesystem-level IOException, got: ${e.javaClass.name}: ${e.message}"
            )
        }
    }

    @Test
    @DisplayName("bare filename (no parent) is a no-op (does not try to create CWD)")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `bare filename is a no-op`() {
        // A bare filename has no parent path; ensureParentDirectory must be a
        // no-op. It must NOT try to create the JVM's CWD as a directory.
        //
        // The contract is: passing a basename must NOT cause the factory to
        // attempt to create CWD as a directory. The JDBC driver will resolve
        // the basename against whatever the underlying JVM considers the
        // CWD (typically the test worker's CWD, which Gradle sandboxes in
        // its own tmp dir).
        //
        // We capture the bare-name file path that would be created, and
        // ensure cleanup happens in the finally block.
        val bareName = "rp-043-bare-${System.nanoTime()}.sqlite"
        val conn = SqliteConnectionFactory.open(bareName)
        try {
            assertNotNull(conn)
        } finally {
            conn.close()
            // Cleanup: best-effort delete of the bare-name file and any
            // SQLite sidecar files. We try multiple CWD candidates since
            // Gradle workers may have their own CWD.
            val cwd = System.getProperty("user.dir") ?: "."
            listOf("", cwd).distinct().forEach { prefix ->
                listOf(bareName, "$bareName-wal", "$bareName-shm",
                       "$bareName-journal").forEach { candidate ->
                    runCatching { java.nio.file.Files.deleteIfExists(
                        java.nio.file.Paths.get(prefix, candidate)) }
                }
            }
        }
    }
}
