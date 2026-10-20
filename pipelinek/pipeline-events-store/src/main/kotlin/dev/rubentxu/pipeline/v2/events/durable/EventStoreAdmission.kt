package dev.rubentxu.pipeline.v2.events.durable

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
 * a file that must already exist; a verb that STARTS a run is creating that file, so its absence is
 * the normal case and only its DIRECTORY has to exist. A single `if (!Files.exists(...))` cannot
 * express both without a boolean flag at each call site.
 * ## Pure decision, effectful admission
 * [refusalFor] is a total function over two closed ADTs and touches no filesystem, so the whole
 * rule set is exercisable without a disk ([PathObservation] carries the observation as a value
 * rather than as two booleans, which would admit states that cannot occur). [admitEventStore] is the
 * effectful shell around it: probe, decide, then open.
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
}

/** What the caller intends to do with the store. Decides which absences are legitimate. */
public sealed interface EventStorePurpose {

    /** Read a run that already happened: the file must exist. */
    public data object Read : EventStorePurpose

    /** Start writing a run: the file may be absent, but the directory holding it may not. */
    public data object Write : EventStorePurpose
}

/** The outcome of asking for a store: the real store, or the reason there is none. */
public sealed interface EventStoreAdmission {
    public data class Admitted(public val store: SqliteEventStore) : EventStoreAdmission
    public data class Refused(public val refusal: EventStoreRefusal) : EventStoreAdmission
}

/** What the filesystem says about a path, before anything tries to open it. */
public sealed interface PathObservation {
    /** Some ancestor of the path is not a directory. */
    public data object NoDirectory : PathObservation

    /** The directory exists and the file does not. */
    public data object NoFile : PathObservation

    /** The path exists and is not obviously unusable. */
    public data object Present : PathObservation
}

/**
 * The refusal rule, as a total pure function.
 * Returns `null` when the store may be opened. Three inputs, no I/O, no default branch: every
 * combination of a closed observation and a closed purpose is stated, so adding a purpose forces
 * this function to be revisited rather than silently reusing another purpose's rule.
*/
public fun refusalFor(
    observation: PathObservation,
    path: String,
    purpose: EventStorePurpose,
): EventStoreRefusal? = when (observation) {
    PathObservation.NoDirectory -> EventStoreRefusal.NoDirectory(path)
    PathObservation.NoFile -> when (purpose) {
        // A run that is about to be written creates its own file; refusing here would break every
        // first run, which is the most common run there is.
        EventStorePurpose.Write -> null
        EventStorePurpose.Read -> EventStoreRefusal.Absent(path)
    }
    PathObservation.Present -> null
}

/** Probes the filesystem for the observation the pure rule consumes. */
public fun observePath(path: String): PathObservation {
    val parsed = try {
        Path.of(path)
    } catch (_: InvalidPathException) {
        // An unparseable path has no directory that could hold it and no file that could be read.
        return PathObservation.NoDirectory
    }
    if (Files.isDirectory(parsed)) return PathObservation.Present
    if (Files.exists(parsed)) return PathObservation.Present
    // A file nested under a missing parent is absent for BOTH purposes, but naming the directory is
    // what SQLite names, and it is the actionable half for an operator who has to create it.
    val parent = parsed.parent ?: return PathObservation.NoDirectory
    return if (Files.isDirectory(parent)) PathObservation.NoFile else PathObservation.NoDirectory
}

/**
 * Opens the store at [path], or refuses it with a reason.
 * The only sanctioned way to build a [SqliteEventStore] for a verb. Anything that constructs one
 * directly answers the admission question a second time, and the two answers will disagree.
*/
public fun admitEventStore(path: String, purpose: EventStorePurpose): EventStoreAdmission {
    refusalFor(observePath(path), path, purpose)?.let { return EventStoreAdmission.Refused(it) }
    return try {
        EventStoreAdmission.Admitted(SqliteEventStore(path))
    } catch (e: java.sql.SQLException) {
        EventStoreAdmission.Refused(
            EventStoreRefusal.NotReadable(path, e.message ?: e.javaClass.simpleName),
        )
    }
}