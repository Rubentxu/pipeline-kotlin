package dev.rubentxu.pipeline.v2.events.durable

/**
 * What an event store is allowed to do with the database file it was opened on.
 *
 * ## Why the mode exists
 * A reader that opens the producer's database with a writer's permissions IS a writer. The
 * `events` verb used to open the same store the producer opens: its constructor creates the
 * schema, migrates the journal, backfills `run_id` and starts a writer thread, and it holds a
 * write-capable connection for as long as it lives. An observer that only wanted to READ was
 * writing to the producer's file.
 *
 * That is the coupling a reader must not have. An observer is a VIEW over a journal
 * the producer owns: killing the view must not signal the owner, and the view must not change
 * what the owner is writing. A view that writes is not a view.
 *
 * ## The two lanes
 * - [Writable] is the producer's lane. It creates the schema, appends facts and owns the file.
 * - [ReadOnly] is the reader's lane. The database engine itself refuses every write, so the
 *   guarantee is enforced BELOW this code and not by discipline.
 *
 * The mode is decided by [EventStorePurpose], which is what the caller says it intends to do;
 * [EventStorePurpose.storeMode] is the whole mapping and it is pure.
 */
public sealed interface EventStoreMode {

    /** The producer's lane: it creates the schema, appends facts, and owns the file. */
    public data object Writable : EventStoreMode

    /** The reader's lane: it reads committed history and cannot change a byte of the file. */
    public data object ReadOnly : EventStoreMode

    /**
     * Whether a store in this mode may change the database file.
     *
     * The whole reader lane hangs on this answer: [ReadOnly] says no, so the store creates no
     * schema, starts no writer, and refuses to append.
     */
    public fun mayWrite(): Boolean = when (this) {
        Writable -> true
        ReadOnly -> false
    }
}
