package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.sql.Connection

/**
 * SQLite-backed event store using JDK 21 stdlib java.sql.
 * Each operation opens a fresh connection that auto-commits and closes,
 * ensuring data is immediately visible to subsequent readers.
 *  Variants
 * This store supports all event variants in addition to variants
 * - [AgentResolved][dev.rubentxu.pipeline.v2.events.AgentResolved]
 * - [ParallelBranchStarted][dev.rubentxu.pipeline.v2.events.ParallelBranchStarted]
 * - [ParallelBranchFinished][dev.rubentxu.pipeline.v2.events.ParallelBranchFinished]
 * - [RetryAttemptStarted][dev.rubentxu.pipeline.v2.events.RetryAttemptStarted]
 * - [RetryAttemptFinished][dev.rubentxu.pipeline.v2.events.RetryAttemptFinished]
 * - [TimeoutScheduled][dev.rubentxu.pipeline.v2.events.TimeoutScheduled]
 * New variants are decoded via the [JsonEventLog][dev.rubentxu.pipeline.v2.events.durable.JsonEventLog]
 * `kind` discriminator — no schema migration required.
 *  Extension
 * This store also creates the [operation_journal][dev.rubentxu.pipeline.v2.events.durable.OperationJournalSchema]
 * and [replay_cursor][dev.rubentxu.pipeline.v2.events.durable.OperationJournalSchema] tables
 * via [SqliteConnectionFactory] with WAL mode enabled.
 *  The two lanes
 * A store is opened in one of two [EventStoreMode]s. The WRITER's lane creates the schema,
 * appends facts and owns the file. The READER's lane opens the same file through a connection
 * the database engine refuses to write through, creates no schema, migrates nothing, backfills
 * nothing and starts no writer thread — so an observer of a live run cannot change that run's
 * journal, and cannot hold a write lock on it either.
 *
 * ## The three owners
 *
 * This class is the FACADE, and it decides nothing: it owns the file, the lane, and the hand-off
 * between three owners that each own exactly one thing.
 *
 * - [SqliteEventWriter] — the WRITING of an event: the sequence authority, the bounded queue, the
 *   single writer thread and the commit that makes a fact durable.
 * - [SqliteEventReader] — the READING of an event and its projection: the page cut, the cursor,
 *   and the refusal a row that cannot be decoded earns.
 * - [SqliteEventSchema] — the SCHEMA of the file: the tables, the uniqueness index and the
 *   migrations.
 *
 * The split is STRUCTURAL, not behavioural: the SQL, the order of the operations and the
 * observable output of every verb that reads events are the same before and after the cut.
 *
 * ## What the facade does NOT decide
 *
 * It does not decide which operations a reader is shown: that is the consumer's decision, made by
 * the selection authority that already exists, and this class only hands it the history it reads.
 */
class SqliteEventStore(
    private val file: String,
    private val mode: EventStoreMode = EventStoreMode.Writable,
) : EventSink, AutoCloseable {

    /**
     * The store's DOOR. Every owner opens its connections through it, so the lane is decided in
     * ONE place: the producer's door sets the journal mode and verifies WAL, and the reader's door
     * is one the database engine refuses to write through.
     */
    private val connections: () -> Connection = { openEventStoreConnection(file, mode) }

    /**
     * The reading owner. It exists in BOTH lanes: a writer can read back what it wrote, and a
     * reader can only read.
     */
    private val reader = SqliteEventReader(connections, mode)

    /**
     * The writing owner. It exists only in the producer's lane: a reader's store holds no writer
     * connection, starts no writer thread, and can never append.
     */
    private val writer: SqliteEventWriter? =
        if (mode.mayWrite()) SqliteEventWriter(connections) else null

    /**
     * Whether this store holds an event history at all.
     *
     * The answer is computed ONCE, by the reading owner, and never changes afterwards: a WRITABLE
     * store creates the table, and a READ-ONLY store asks the file instead —and a reader cannot
     * create the table to find out.
     */
    val holdsEventHistory: Boolean get() = reader.holdsEventHistory

    override fun append(event: DomainEvent) {
        appendAssigned(event)
    }

    override fun appendAssigned(event: DomainEvent): DomainEvent {
        // A reader that appends would be a writer wearing a reader's clothes: it would
        // write to the producer's journal from a connection the producer does not own.
        // The refusal is loud and immediate, not a silent no-op.
        check(mode.mayWrite()) {
            "this event store is read-only and cannot append facts to $file"
        }
        return requireWriter().appendAssigned(event)
    }

    /**
     * Flush barrier: blocks until every event enqueued BEFORE this call is
     * durably committed.
     *
     * A read-only store never queues a write, so there is nothing to commit and nothing to wait
     * for. Enqueuing a barrier here would strand it: no writer would ever release it, and the
     * caller would burn the whole 60 s bound for a store that never had anything to flush.
     */
    fun flush() {
        writer?.flush()
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> = reader.eventsFor(runId)

    fun hasRun(runId: String): Boolean = reader.hasRun(runId)

    fun tailSequence(runId: String): Long? = reader.tailSequence(runId)

    override fun readRecords(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventRecordSlice = reader.readRecords(runId, after, limit)

    /**
     * Closes the store: flush barrier, then stops the writer and releases
     * the persistent connection. Close is idempotent.
    */
    override fun close() {
        writer?.close()
    }

    /**
     * Exposes the underlying connection factory for use by [OperationJournal]
     * and [ReplayCursorStore].
     * This is intentionally internal — it is only used within the durable
     * execution subsystem that shares the same SQLite database file.
    */
    fun underlyingConnectionFactory(): () -> Connection = connections

    /**
     * Exposes the database file path for use by [SqliteOperationJournalImpl]
     * to enable cross-instance [DbLock] synchronization.
    */
    fun databasePath(): String = file

    /**
     * The writing owner of a store that is allowed to write.
     */
    private fun requireWriter(): SqliteEventWriter = requireNotNull(writer) {
        "a writable store holds a writer"
    }
}

/**
 * Opens a connection to the store's file in the lane the store was opened in.
 *
 * The mode decides which door this is: the producer's door sets the journal mode and verifies
 * WAL, and the reader's door is one the database engine refuses to write through. Every owner
 * opens its connections here, so the lane is decided in ONE place.
 */
internal fun openEventStoreConnection(file: String, mode: EventStoreMode): Connection = when (mode) {
    EventStoreMode.Writable -> SqliteConnectionFactory.open(file)
    EventStoreMode.ReadOnly -> SqliteConnectionFactory.openReadOnly(file)
}
