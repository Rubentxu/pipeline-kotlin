package dev.rubentxu.pipeline.v2.application

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Where the native library of the SQLite driver lands, and who is answerable for it.
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
     * Points the driver at a directory that exists.
     *
     * The decision is a pure function of two facts — what the operator named, and whether that
     * name is a directory — so it can be read and checked without a disk. The three cases are
     * three different answers to the same question, and each one says who is answerable for the
     * directory afterwards.
     *
     * @return the directory the driver will extract into, or `null` when nothing was changed —
     *   because no directory could be prepared.
     */
    fun configure(): Path? {
        val named = operatorNamedDirectory(System.getProperty(PROPERTY))
        val namedIsDirectory = named != null && Files.isDirectory(named)
        return when (val home = decideNativeLibraryHome(named, namedIsDirectory)) {
            is NativeLibraryHome.Borrowed ->
                // The property already names a directory that is there. Nothing is changed and
                // nothing is removed: the operator's choice is kept exactly as it was written.
                home.directory
            is NativeLibraryHome.Created -> {
                // The operator named a place that does not exist yet. Making it exist is what
                // their instruction means: the driver creates no directories of its own, and a
                // run that dies because a directory is missing is a run that died for a reason
                // nobody was told. The property keeps naming the same place, so it is not
                // rewritten either.
                createDirectory(home.directory) ?: ownDirectory()
            }
            NativeLibraryHome.Owned -> ownDirectory()
        }
    }

    /**
     * A directory of the product's own, and the property pointing at it.
     *
     * The name is the product's, so a residue left by a killed process can be traced back to the
     * process that made it. This is the only directory the process removes: everything else was
     * named by somebody else, and a directory somebody else named is theirs even when this
     * process had to create it.
     */
    private fun ownDirectory(): Path? {
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
     * Creates a directory the operator named, and nothing else.
     *
     * A path that cannot be created — a name that is already a file, a place this process may
     * not write — is not an error: it is a place the library cannot live, and the caller falls
     * back to a directory of our own.
     */
    private fun createDirectory(directory: Path): Path? = try {
        Files.createDirectories(directory)
    } catch (e: IOException) {
        null
    }

    /**
     * Removes the directory and everything under it.
     *
     * Failures are swallowed on purpose: this runs while the process is exiting, and a temporary
     * file that could not be deleted is not a reason to change the exit code of a run that
     * already succeeded.
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

/**
 * Where the native library lands, and who is answerable for the directory afterwards.
 *
 * The three cases are the three answers to one question: *does the operator's value name a
 * directory the driver can extract into?*
 */
internal sealed interface NativeLibraryHome {

    /**
     * The operator named this directory and it already exists: the driver is pointed at it and
     * the directory is left exactly as it is.
     */
    data class Borrowed(val directory: Path) : NativeLibraryHome

    /**
     * The operator named this directory and it does not exist yet: it is created, the driver is
     * pointed at it, and it is left there afterwards — the name is the operator's.
     */
    data class Created(val directory: Path) : NativeLibraryHome

    /**
     * Nobody named a usable directory: one of the product's own is made, and removed when the
     * process ends.
     */
    data object Owned : NativeLibraryHome
}

/**
 * The directory the operator named, or `null` when they named none.
 *
 * A value that names nothing is not a choice: an empty string, a string of blanks, and a name
 * the filesystem cannot even spell are all the absence of an instruction, and treating them as
 * an instruction is how the driver ends up extracting into the working directory — the one
 * place the library must never land.
 */
internal fun operatorNamedDirectory(raw: String?): Path? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    return try {
        Path.of(value)
    } catch (e: InvalidPathException) {
        null
    }
}

/**
 * Where the library goes, given what the operator named and whether that name is a directory.
 *
 * The rule is pure: it asks two questions and answers with a value. A directory that does not
 * exist is not a place the driver can extract into — the driver creates no directories — so a
 * name that is not there yet is made, and the operator's intent is what is honoured. And when
 * there is no usable name at all, the product makes a directory of its own.
 */
internal fun decideNativeLibraryHome(named: Path?, namedIsDirectory: Boolean): NativeLibraryHome = when {
    named == null -> NativeLibraryHome.Owned
    namedIsDirectory -> NativeLibraryHome.Borrowed(named)
    else -> NativeLibraryHome.Created(named)
}
