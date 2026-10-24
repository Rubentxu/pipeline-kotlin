package dev.rubentxu.pipeline.v2.events.durable

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The one decision on whether a run's event store may be opened, and the closed set of reasons it
 * may be refused.
 * ## Why this exists
 * Every verb that reads a run opens the same kind of file, and a mistyped `--db` is the most common
 * way to ask for one that cannot be opened. Before this type existed, six construction sites
 * answered that question themselves, and they gave four different answers:
 * ```text
 * events   Error: db not found: <path>            exit 2
 * events   verify (subcommand)  same, written twice
 * observe  java.sql.SQLException + stack trace   exit 1
 * inspect  java.sql.SQLException + stack trace   exit 1
 * run      java.sql.SQLException + stack trace   exit 1
 * ```
 * Two sites had the right answer written out, four had none, and no reader of the event store
 * Consulted the other kind of site. That is the shape from `AGENTS.md`: a second authority
 * exists, so improving it changes nothing for the verbs that never asked it.
 * Exit `1` is the harder half of the defect. In this CLI `1` means "the pipeline FAILED" — a run
 * that executed and did not succeed. A path that was never open executed nothing, so the same
 * code was reporting two incompatible things and only the stack trace distinguished them.
 * ## The axis that decides the rules
 * [EventStorePurpose] exists because the rule genuinely differs by caller and the difference is
 * data, not a branch to be repeated per verb. A verb that READS a finished run is being pointed at
 * a file that must already exist; a verb that WRITES INTO a finished run is adding a fact to a
 * store that must already exist; a verb that STARTS a run is creating that store, and therefore
 * owns the place it is about to write into. A single `if (!Files.exists(...))` cannot express
 * those three without a boolean flag at each call site.
 * ## What the producer owns
 * The third purpose is the one that was missing, and it was missing in a way that made two
 * directories of the same kind behave differently. This product creates the directory it writes
 * into when that directory does not exist: the control root of a run is created on demand, and so
 * is the directory the SQLite driver extracts its native library into. The store's own directory
 * was the exception, and it refused instead — so `--db` under a directory that was not there yet
 * was answered with a refusal, while `--control-root` under the very same directory was created.
 * The refusal was also wrong for a relative name: `--db events.db` names a file in the working
 * directory, which exists, and it was refused for having "no directory" at all.
 * So the producer now creates the directory that holds its store, the same way it creates the
 * others, and a reader is still refused because a reader may not create the place it reads from.
 * ## Pure decision, effectful admission
 * [refusalFor] is a total function over two closed ADTs and touches no filesystem, so the whole
 * rule set is exercisable without a disk ([PathObservation] carries the observation as a value
 * rather than as two booleans, which would admit states that cannot occur). [admitEventStore] is
 * the effectful shell around it: probe, decide, make the place ready, then open.
 * ## The boundary of the conversion
 * `java.sql.SQLException` is an operational outcome here — SQLite refusing a path is a thing that
 * happens to operators — and all four measured conditions raise it (missing directory, not a
 * database, read-only directory, path is a directory). `IllegalStateException` from the WAL
 * verification in [SqliteConnectionFactory] is a programmer/invariant defect and is deliberately
 * left to escape: converting it would make a broken build look like a bad argument.
*/
public sealed interface EventStoreRefusal {

    /** A read was asked for a file that is not there. */
    public data class Absent(public val path: String) : EventStoreRefusal

    /** The directory that would have to hold the file is not there, so the file cannot be made. */
    public data class NoDirectory(public val path: String) : EventStoreRefusal

    /** The path is present and SQLite still will not open it. [detail] is SQLite's own words. */
    public data class NotReadable(public val path: String, public val detail: String) : EventStoreRefusal

    /**
     * The file is a database, but it holds no event history at all: it has no `events` table.
     *
     * A reader cannot create that table — it has no business writing to a file it was only
     * asked to look at — so it says the file holds no history instead of answering "this run
     * has no events", which would point an operator at the run instead of at the file.
     */
    public data class NoEventHistory(public val path: String) : EventStoreRefusal
}

/** What the caller intends to do with the store. Decides which absences are legitimate. */
public sealed interface EventStorePurpose {

    /** Read a run that already happened: the file must exist. */
    public data object Read : EventStorePurpose

    /**
     * Write into the store of a run that already happened: the file must exist.
     *
     * This is the lane of `cancel` and `recover`, which add a fact to a run's journal. They are
     * not starting that run, so the place they write into is not theirs to create.
     */
    public data object Write : EventStorePurpose

    /**
     * Start a run: the store, and the directory that holds it, may not exist yet.
     *
     * This is the producer's lane. It is the only one that may create the directory it is about
     * to write into, because it is the one that is bringing the run into existence.
     */
    public data object Produce : EventStorePurpose
}

/**
 * The lane a store opened for this purpose works in.
 *
 * This is the whole answer to "may the caller change the file": a READER of a run's journal is
 * a view over a file the PRODUCER owns, so it opens the file read-only and the database engine
 * refuses every write it could attempt. A WRITER is the producer's lane and owns the file.
 *
 * Pure, and therefore decidable without a disk.
 */
public fun EventStorePurpose.storeMode(): EventStoreMode = when (this) {
    EventStorePurpose.Read -> EventStoreMode.ReadOnly
    EventStorePurpose.Write -> EventStoreMode.Writable
    EventStorePurpose.Produce -> EventStoreMode.Writable
}

/** The outcome of asking for a store: the real store, or the reason there is none. */
public sealed interface EventStoreAdmission {
    public data class Admitted(public val store: SqliteEventStore) : EventStoreAdmission
    public data class Refused(public val refusal: EventStoreRefusal) : EventStoreAdmission
}

/** What the filesystem says about a path, before anything tries to open it. */
public sealed interface PathObservation {

    /** The path exists and is not obviously unusable. */
    public data object Present : PathObservation

    /** The directory that would hold the file exists, and the file does not. */
    public data object NoFile : PathObservation

    /**
     * The directory that would hold the file does not exist.
     *
     * This is the one absence a producer can answer by acting: the directory it is about to
     * write into is its own to create.
     */
    public data object MissingDirectory : PathObservation

    /**
     * Nothing can hold the file there: what is at that place is not a directory, or the path
     * cannot be parsed at all. No caller can fix this by creating anything.
     */
    public data object NotADirectory : PathObservation
}

/**
 * The refusal rule, as a total pure function.
 * Returns `null` when the store may be opened. Four inputs, no I/O, no default branch: every
 * combination of a closed observation and a closed purpose is stated, so adding a purpose forces
 * this function to be revisited rather than silently reusing another purpose's rule.
*/
public fun refusalFor(
    observation: PathObservation,
    path: String,
    purpose: EventStorePurpose,
): EventStoreRefusal? = when (observation) {
    PathObservation.Present -> null
    PathObservation.NoFile -> when (purpose) {
        // A run that is about to be written creates its own file; refusing here would break every
        // first run, which is the most common run there is.
        EventStorePurpose.Write -> null
        EventStorePurpose.Produce -> null
        EventStorePurpose.Read -> EventStoreRefusal.Absent(path)
    }
    PathObservation.MissingDirectory -> when (purpose) {
        // The producer owns the place it writes into, and creates it. A reader and a writer of a
        // finished run are pointing at a store that has to be there already.
        EventStorePurpose.Produce -> null
        EventStorePurpose.Write -> EventStoreRefusal.NoDirectory(path)
        EventStorePurpose.Read -> EventStoreRefusal.NoDirectory(path)
    }
    PathObservation.NotADirectory -> EventStoreRefusal.NoDirectory(path)
}

/**
 * The directory that would hold the file at [path], or `null` when there is none to name.
 *
 * A relative name has no parent component, and it lands in the working directory, which is a
 * directory like any other. Resolving it here is what stops a perfectly usable name from being
 * reported as having no directory at all.
 */
private fun holdingDirectory(path: String): Path? = try {
    val parsed = Path.of(path)
    parsed.parent ?: parsed.toAbsolutePath().parent
} catch (_: InvalidPathException) {
    null
}

/** Probes the filesystem for the observation the pure rule consumes. */
public fun observePath(path: String): PathObservation {
    val parsed = try {
        Path.of(path)
    } catch (_: InvalidPathException) {
        // An unparseable path has no directory that could hold it and no file that could be read.
        return PathObservation.NotADirectory
    }
    if (Files.isDirectory(parsed)) return PathObservation.Present
    if (Files.exists(parsed)) return PathObservation.Present
    val parent = holdingDirectory(path) ?: return PathObservation.NotADirectory
    if (Files.isDirectory(parent)) return PathObservation.NoFile
    // A parent that is there but is not a directory is not a missing directory: creating
    // anything there is impossible, and the two answers are not the same to the operator.
    return if (Files.exists(parent)) PathObservation.NotADirectory else PathObservation.MissingDirectory
}

/**
 * Opens the store at [path], or refuses it with a reason.
 * The only sanctioned way to build a [SqliteEventStore] for a verb. Anything that constructs one
 * directly answers the admission question a second time, and the two answers will disagree.
 *
 * The purpose decides the lane the store works in ([EventStorePurpose.storeMode]): a reader
 * opens the file read-only, so it cannot change the producer's journal, and a writer owns it.
*/
public fun admitEventStore(path: String, purpose: EventStorePurpose): EventStoreAdmission {
    val observation = observePath(path)
    refusalFor(observation, path, purpose)?.let { return EventStoreAdmission.Refused(it) }
    // The producer's place. This product creates the directories it writes into — the control
    // root of a run, the directory the SQLite driver extracts its library into — and the store's
    // directory is the same kind of thing, so it is created here too rather than refused.
    if (purpose == EventStorePurpose.Produce && observation == PathObservation.MissingDirectory) {
        val parent = holdingDirectory(path)
            ?: return EventStoreAdmission.Refused(EventStoreRefusal.NoDirectory(path))
        try {
            Files.createDirectories(parent)
        } catch (e: IOException) {
            // The directory could not be made. Saying so is more useful than letting SQLite
            // fail later with the same fact in a different wording.
            return EventStoreAdmission.Refused(
                EventStoreRefusal.NotReadable(path, e.message ?: e.javaClass.simpleName),
            )
        }
    }
    return try {
        val store = SqliteEventStore(path, purpose.storeMode())
        if (!store.holdsEventHistory) {
            store.close()
            EventStoreAdmission.Refused(EventStoreRefusal.NoEventHistory(path))
        } else {
            EventStoreAdmission.Admitted(store)
        }
    } catch (e: java.sql.SQLException) {
        EventStoreAdmission.Refused(
            EventStoreRefusal.NotReadable(path, e.message ?: e.javaClass.simpleName),
        )
    }
}
