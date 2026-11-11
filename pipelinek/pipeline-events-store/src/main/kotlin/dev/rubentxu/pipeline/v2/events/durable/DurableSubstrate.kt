package dev.rubentxu.pipeline.v2.events.durable

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/**
 * The PREPARATION of the durable substrate the service serves.
 *
 * ## Why this is a step of its own
 *
 * Because the service reads the substrate it is given and never creates one:
 * a service that creates the substrate it is about to read is a service that
 * writes, and what it would answer about is not what the operator named. The
 * file is therefore created HERE, by an explicit step, and the service finds
 * it there — or refuses to start and says what is missing.
 *
 * ## What "prepared" means
 *
 * That the file is there, that it holds the schema of the events AND the
 * table of the promises, and that the two were created in ONE transaction: a
 * substrate with the events table and without the table of the promises is
 * served, but it is a half state, and a half state is what a preparation that
 * failed looks like.
 *
 * ## What it does NOT do
 *
 * It does not invent a store of its own, and it does not decide where the
 * substrate lives: the caller names the path, and the preparation makes THAT
 * path a substrate.
 */
public sealed interface DurableSubstratePreparation {

    /**
     * The substrate is there and it is ready to be served.
     *
     * @property path where the substrate is.
     * @property created whether this call created the file. It is false when
     *   the file was already there: the preparation is idempotent, and a second
     *   call says that it found the substrate instead of pretending it built
     *   one again.
     * @property tables the tables the substrate carries, read back from the
     *   file after the transaction committed.
     */
    public data class Ready(
        val path: String,
        val created: Boolean,
        val tables: List<String>,
    ) : DurableSubstratePreparation

    /** The substrate could not be prepared, and this says why. */
    public data class Refused(val refusal: DurableSubstrateRefusal) : DurableSubstratePreparation
}

/**
 * Why the durable substrate could not be prepared.
 *
 * Each case names a DIFFERENT remedy, which is why they are cases and not one
 * sentence with the engine's words in it: an operator who reads "the database
 * is held by somebody else" waits, and an operator who reads "what is there is
 * not a database" looks at the file.
 */
public sealed interface DurableSubstrateRefusal {

    /** The path cannot name a file: the machine cannot parse it. */
    public data class InvalidPath(val path: String) : DurableSubstrateRefusal

    /**
     * The directory that would hold the file is not a directory, or could not
     * be made.
     */
    public data class NoDirectory(val path: String) : DurableSubstrateRefusal

    /** What is at that place is not a database the engine can open. */
    public data class NotADatabase(val path: String, val detail: String) : DurableSubstrateRefusal

    /** The database could not be opened for writing. */
    public data class NotWritable(val path: String, val detail: String) : DurableSubstrateRefusal

    /** Another connection holds the database, and the window of waiting ran out. */
    public data class HeldByAnother(val path: String, val detail: String) : DurableSubstrateRefusal
}

/**
 * Prepares the durable substrate at [path], and says what it did.
 *
 * ## What it does
 *
 * It makes the directory that would hold the file, opens the file through the
 * producer's door, and creates the shape of the substrate in ONE transaction:
 * the events table and its uniqueness index, the tables of the durable
 * operation journal, and the table of the promises. The transaction is
 * committed before this function answers, so the answer is a fact about the
 * file and not an intention.
 *
 * ## What it is safe to do twice
 *
 * Every statement it runs is idempotent, so preparing a substrate that is
 * already there changes nothing and answers that it was already there.
 *
 * ## What it leaves behind when it fails
 *
 * Nothing that looks like a substrate. The transaction is rolled back, and a
 * file that THIS call created is removed together with the sidecars the engine
 * left beside it. A file that was already there belongs to the operator, and
 * a failed preparation leaves it exactly as it was.
 *
 * @param path where the substrate is.
 */
public fun prepareDurableSubstrate(path: String): DurableSubstratePreparation {
    val parsed = try {
        Path.of(path)
    } catch (_: InvalidPathException) {
        return DurableSubstratePreparation.Refused(DurableSubstrateRefusal.InvalidPath(path))
    }
    val absolute = parsed.toAbsolutePath().normalize()

    // The place the substrate lives is the preparation's own to create: this is
    // the producer's lane, and the directory it writes into is not there
    // because nobody has written there yet.
    val parent = absolute.parent
    if (parent != null && !Files.isDirectory(parent)) {
        if (Files.exists(parent)) {
            return DurableSubstratePreparation.Refused(
                DurableSubstrateRefusal.NoDirectory(parent.toString()),
            )
        }
        try {
            Files.createDirectories(parent)
        } catch (_: IOException) {
            return DurableSubstratePreparation.Refused(
                DurableSubstrateRefusal.NoDirectory(parent.toString()),
            )
        }
    }

    val existed = Files.exists(absolute)
    val tables = try {
        prepareIn(absolute)
    } catch (failure: Exception) {
        // The file this call created is removed, and only that one: a file
        // that was already there belongs to the operator.
        if (!existed) removeCreated(absolute)
        return DurableSubstratePreparation.Refused(refusalOf(failure, absolute))
    }

    return DurableSubstratePreparation.Ready(
        path = absolute.toString(),
        created = !existed,
        tables = tables,
    )
}

/**
 * Creates the shape of the substrate in ONE transaction, and reads back what
 * the file carries.
 *
 * The transaction is what leaves no half state: the events schema and the
 * table of the promises are committed together or not at all, so there is no
 * moment in which the file carries one of them and not the other.
 */
private fun prepareIn(absolute: Path): List<String> {
    val connection = SqliteConnectionFactory.open(absolute.toString())
    try {
        connection.autoCommit = false
        try {
            SqliteEventSchema.createOn(connection)
            SagaObligationSchema.create(connection)
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = true
        }
        return tablesIn(connection)
    } finally {
        runCatching { connection.close() }
    }
}

/**
 * The tables the substrate carries, read back from the file.
 *
 * Read AFTER the transaction committed, so the answer is what the file holds
 * and not what the preparation meant to write.
 */
private fun tablesIn(connection: Connection): List<String> =
    connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { rows ->
            buildList {
                while (rows.next()) add(rows.getString(1))
            }
        }
    }

/**
 * Removes the file this call created, and the sidecars the engine left beside it.
 *
 * A preparation that failed must not leave behind something that LOOKS like a
 * substrate: an operator who finds a database where the substrate should be
 * reads it as a substrate that is there, and the service refuses it with a
 * refusal about a table that is missing — a fact about a file this call
 * created.
 *
 * The file is removed only when THIS call created it. A file that was already
 * there belongs to the operator, and a failed preparation rolls its
 * transaction back and leaves that file exactly as it was.
 */
private fun removeCreated(path: Path) {
    listOf(path, Path.of("$path-wal"), Path.of("$path-shm")).forEach { candidate ->
        runCatching { Files.deleteIfExists(candidate) }
    }
}

/**
 * The refusal the engine's own words become.
 *
 * The code is what the engine reported, and not a word of the message: the
 * message of a driver is not a contract, and the code is. The message travels
 * along as the DETAIL, so nothing the engine said is lost.
 *
 * A failure that is not the engine's own —the door refusing to give a
 * connection at all— is a refusal to write, because that is what it means:
 * the file could not be opened for the preparation.
 */
private fun refusalOf(failure: Exception, path: Path): DurableSubstrateRefusal {
    val detail = failure.message ?: failure.javaClass.simpleName
    if (failure !is SQLException) {
        return DurableSubstrateRefusal.NotWritable(path.toString(), detail)
    }
    return when (failure.errorCode) {
        SQLITE_NOT_A_DATABASE -> DurableSubstrateRefusal.NotADatabase(path.toString(), detail)
        SQLITE_BUSY -> DurableSubstrateRefusal.HeldByAnother(path.toString(), detail)
        else -> DurableSubstrateRefusal.NotWritable(path.toString(), detail)
    }
}

/** The code the engine reports a file that is not a database with. */
private const val SQLITE_NOT_A_DATABASE = 26

/** The code the engine reports a locked database with. */
private const val SQLITE_BUSY = 5
