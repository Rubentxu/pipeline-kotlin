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
 * A store is opened in one of two [EventStoreMode]s. The lane decides who may WRITE: the WRITER's
 * lane creates the schema, appends facts and owns the file; the READER's lane creates no schema,
 * migrates nothing, backfills nothing and starts no writer thread.
 *
 * The lane does NOT decide how a READ is opened. Every read goes through the store's READING door,
 * in both lanes, and that door is one the database engine refuses to write through — so a reader
 * cannot change that run's journal, and cannot hold a write lock on it either, whether it is
 * observing a run from another process or reading back the run its own producer wrote.
 *
 * ## The owners
 *
 * This class is the FACADE, and it decides nothing: it owns the file, the lane, and the hand-off
 * between the owners that each own exactly one thing.
 *
 * - [SqliteEventWriter] — the WRITING of an event: the sequence authority, the bounded queue, the
 *   single writer thread and the commit that makes a fact durable.
 * - [SqliteEventReader] — the READING of an event and its projection: the page cut, the cursor,
 *   the snapshot that reads a run's tail and its page at one instant, and the refusal a row that
 *   cannot be decoded earns.
 * - [SqliteEventSchema] — the SCHEMA of the file: the tables, the uniqueness index and the
 *   migrations.
 * - [DurableConnections] — the CONNECTIONS the durable stores borrow: the pool they write through
 *   and the pool they read through, each lent to one operation at a time.
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
    /**
     * The PRODUCER's door. The owners that WRITE open their connections through it: the writer,
     * the schema, the operation journal and the replay cursor. It sets the journal mode and
     * verifies WAL, because a writer that does not persist durably is a defect.
     *
     * It is a PARAMETER because the number of connections a read opens is a fact about this
     * store's cost, and a fact that cannot be counted cannot be improved: a caller that counts
     * the opens sees what a read really costs, and a reader that opens one connection per read
     * cannot hide behind a statement it happened to batch.
     */
    private val connections: () -> Connection = { openEventStoreConnection(file, mode) },
    /**
     * The READING door. Every read goes through it, in BOTH lanes, and it is one the database
     * engine refuses to write through.
     *
     * It is a PARAMETER for the same reason the producer's door is one, and for one more: a
     * guarantee nobody can observe is a guarantee nobody can check. A caller that wraps this door
     * holds the connection a read actually used, and can ask the ENGINE whether that connection
     * may write. Without the seam, "the reader cannot write" would be a claim about the code
     * instead of a fact about the run.
     */
    readerConnections: () -> Connection = { openEventStoreReaderConnection(file) },
) : EventSink, EventRunSnapshotReader, AutoCloseable {

    /**
     * The writing owner. It exists only in the producer's lane: a reader's store holds no writer
     * connection, starts no writer thread, and can never append.
     *
     * It is built BEFORE the reading owner, and the order is the point: the writer creates the
     * file and the schema, and the reading door is one the engine refuses to create a file
     * through. A reader built first would ask a file that is not there yet, and the store would
     * fail to open a journal it is about to create.
     */
    private val writer: SqliteEventWriter? =
        if (mode.mayWrite()) SqliteEventWriter(connections) else null

    /**
     * The reading owner. It exists in BOTH lanes: a writer can read back what it wrote, and a
     * reader can only read.
     */
    private val reader = SqliteEventReader(readerConnections, mode)

    /**
     * The DURABLE lane's connections: the pools the journal and the other durable stores borrow.
     *
     * They live here because this store owns the FILE and the two doors to it, and because this
     * store is closed at every exit of the product: a pool of connections needs an owner that
     * closes it, and the owner of the file is the one that already had to be closed.
     *
     * The connections are opened lazily, so a store that is never asked a durable question never
     * touches the file through them.
     */
    internal val durableConnections = DurableConnections.over(
        writeDoor = connections,
        readDoor = readerConnections,
    )

    /**
     * Whether this store holds an event history at all.
     *
     * The answer is computed ONCE, by the reading owner, and never changes afterwards: a WRITABLE
     * store creates the table, and a READ-ONLY store asks the file instead —and a reader cannot
     * create the table to find out.
     */
    val holdsEventHistory: Boolean get() = reader.holdsEventHistory

    /**
     * Whether this store may change the database file.
     *
     * The durable stores built over this one are told, because a store that creates its schema
     * on construction turns a reader of the substrate into a writer of it: the engine refuses
     * the write, and the refusal arrives in the engine's words, which name the engine and not
     * the table that is missing.
     */
    internal val mayWrite: Boolean get() = mode.mayWrite()

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

    /**
     * Makes the event [eventId] durable, and says so by returning.
     *
     * ## Why the barrier is asked about ONE fact
     *
     * Because the barrier reports EVERY refusal this store has accumulated,
     * and a store that shares its lane with other producers accumulates
     * refusals that are not its own. A producer that asked the lane in
     * general would learn that somebody else's fact is missing, and would
     * conclude that its own fact is missing too — when it is durable, and a
     * reader can see it.
     *
     * So the store asks the only question the producer has: is MY fact among
     * the refused? A refusal of another fact is not this fact's failure, and
     * the store says nothing about it: the lane still reports it at the next
     * barrier, and the producer that owns it hears it there.
     *
     * ## Why a DEAD lane is asked about the fact too
     *
     * Because a lane that dies on a LATER fact leaves the facts it committed
     * before it there, durable and visible. A barrier that answered "the lane
     * is dead" about a fact a reader can see would say "your fact is missing"
     * about a fact that is in the log, and the producer would answer its
     * caller that the write failed — while the run it closed is closed. The
     * two answers would contradict each other about the same fact, which is
     * the one thing a barrier may not do.
     *
     * So when the lane is dead, the store asks the READER whether the fact
     * is there. A fact a reader can see is durable, and the barrier says so.
     *
     * ## What a failure means
     *
     * That THIS fact is not in the log. The caller that asked for the fact to
     * be durable did not get it, and the honest answer is to say so.
     */
    fun makeDurable(eventId: String) {
        try {
            flush()
        } catch (refused: EventAppendRefusedException) {
            if (refused.refusals.any { it.event.eventId == eventId }) throw refused
        } catch (dead: IllegalStateException) {
            // The lane is dead and it may have died on a later fact. The
            // reader decides whether THIS fact survived it.
            if (!reader.containsEvent(eventId)) throw dead
        }
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> = reader.eventsFor(runId)

    override fun readRecords(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventRecordSlice = reader.readRecords(runId, after, limit)

    /**
     * The run's tail and the page after the cursor, read at ONE instant.
     *
     * The store answers this question because it is the store's question: the tail is a durable
     * fact about the run, and the page is cut from the same durable history. A reader that asked
     * for the two separately would get two instants, and a run that moved between them would be
     * described by two facts that cannot both be true.
     */
    override fun snapshotOf(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): EventRunSnapshot = reader.snapshotOf(runId, after, limit)

    /**
     * Closes the store: flush barrier, then stops the writer and releases
     * the persistent connection, then closes the durable lane's connections. Close is idempotent.
     *
     * ## Why the durable lane closes even when the writer does not
     *
     * Because the writer's close can REPORT something —an append the
     * database refused and that no barrier had reported yet— and a report
     * that stopped the closing would leave the durable lane's connections
     * open. The store that owns a file has to release it, and the refusal
     * that travels out of the close is about a fact of the log, not about
     * the file: the file is released either way, and the refusal is still
     * said.
    */
    override fun close() {
        try {
            writer?.close()
        } finally {
            durableConnections.close()
        }
    }

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
 * that WRITES opens its connections here, so the lane is decided in ONE place.
 */
internal fun openEventStoreConnection(file: String, mode: EventStoreMode): Connection = when (mode) {
    EventStoreMode.Writable -> SqliteConnectionFactory.open(file)
    EventStoreMode.ReadOnly -> SqliteConnectionFactory.openReadOnly(file)
}

/**
 * Opens the store's READING door: one the database engine refuses to write through.
 *
 * ## Why the same door in both lanes
 *
 * The lane decides who may WRITE. It does not decide how a READ is opened, because a read is a
 * read: the producer reading back the run it wrote is reading, and a reader is reading, and the
 * engine has no reason to let either of them change the journal.
 *
 * The producer's lane used to open its reads through the producer's door, and that door sets
 * `PRAGMA journal_mode = WAL` — a WRITE to the database header when the mode differs — and
 * verifies the mode with a fourth statement. The producer's own writer had already put the file
 * in WAL, so the header did not change in practice; what that arrangement left was a reader whose
 * connection the engine ALLOWED to write, held in place by the accident of the file's current
 * mode rather than by any property of the door. A guarantee that rests on an accident is not a
 * guarantee: the day the file is not in WAL yet, the same read would have converted it.
 *
 * ## What this door buys
 *
 * The engine refuses every write through it — DML and DDL alike — with `SQLITE_READONLY`, and it
 * refuses the WAL recovery that a write-capable connection would perform on a journal left behind
 * by a writer that died. So a read cannot change the journal's mode, cannot change its content,
 * and cannot take a write lock on it, in either lane.
 *
 * Measured against the shipped driver (sqlite-jdbc 3.46.1.3): this door reads a WAL journal that
 * a live producer holds open, reads it while that producer holds a write transaction, reads it
 * after the producer closed cleanly, and reads a journal that is not in WAL mode — in every case
 * without changing a byte of the file.
 */
internal fun openEventStoreReaderConnection(file: String): Connection =
    SqliteConnectionFactory.openReadOnly(file)
