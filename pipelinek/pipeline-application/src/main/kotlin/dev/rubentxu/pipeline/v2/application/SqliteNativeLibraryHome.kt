package dev.rubentxu.pipeline.v2.application

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the SQLite JDBC driver is allowed to put its native library.
 *
 * The driver extracts `libsqlitejdbc.so` into a temporary directory when it
 * loads, and it extracts it into the directory named by the system property
 * `org.sqlite.tmpdir` when that property is set — and into the shared
 * `java.io.tmpdir` when it is not. Measured, with the JVM alive: without this
 * property a run leaves a pair of files —the library and its lock— loose in
 * the root of the system temporary directory, where nobody can tell whose they
 * are; with it, the pair lands in a directory this process owns.
 *
 * The driver deletes the pair when the JVM exits normally. It does not get
 * that chance when the process is killed: a forced termination leaves the pair
 * behind. That is the case the name matters for — a residue in a directory
 * called `pipelinek-sqlite-…` can be traced back to the process that made it,
 * and a residue loose in the shared temporary directory cannot.
 *
 * ## Why it is set here and not at the connection
 * The property is read when the library is extracted, which happens before the
 * first connection. Setting it per connection would be too late for the first
 * one, so it belongs to the process, and the process is the CLI.
 *
 * ## What it refuses to do
 * It does not overwrite a property somebody else set: an operator who pinned
 * `org.sqlite.tmpdir` on purpose keeps their choice. And if the directory
 * cannot be created, the property is left alone — a run must not fail because
 * its temporary directory could not be prepared.
 */
internal object SqliteNativeLibraryHome {

    /** The system property the driver reads. Named once, so it is not a string to be retyped. */
    const val PROPERTY: String = "org.sqlite.tmpdir"

    /** The prefix of the directory this object creates. */
    const val DIRECTORY_PREFIX: String = "pipelinek-sqlite-"

    /**
     * Points the driver at a directory this process owns.
     *
     * @return the directory the driver will extract into, or `null` when nothing was changed —
     *   because a property was already set, or because the directory could not be created.
     */
    fun configure(): Path? {
        if (System.getProperty(PROPERTY) != null) return null
        val home = try {
            Files.createTempDirectory(DIRECTORY_PREFIX)
        } catch (e: IOException) {
            return null
        }
        System.setProperty(PROPERTY, home.toString())
        Runtime.getRuntime().addShutdownHook(
            Thread({ deleteQuietly(home) }, "pipelinek-sqlite-cleanup"),
        )
        return home
    }

    /**
     * Removes the directory and everything under it.
     *
     * Failures are swallowed on purpose: this runs while the process is
     * exiting, and a temporary file that could not be deleted is not a reason
     * to change the exit code of a run that already succeeded.
     */
    private fun deleteQuietly(home: Path) {
        try {
            if (!Files.exists(home)) return
            Files.walk(home).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (e: IOException) {
            // Nothing to do: the directory is temporary and the process is leaving.
        }
    }
}
