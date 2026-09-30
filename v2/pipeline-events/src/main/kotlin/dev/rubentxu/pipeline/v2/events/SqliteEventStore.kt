package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.events.durable.OperationJournalSchema
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * SQLite-backed event store using JDK 21 stdlib java.sql.
 * Each operation opens a fresh connection that auto-commits and closes,
 * ensuring data is immediately visible to subsequent readers.
 *
 * ## M2-R1 Variants
 * This store supports all M2-R1 event variants in addition to M1-R3 variants:
 * - [AgentResolved][dev.rubentxu.pipeline.v2.events.AgentResolved]
 * - [ParallelBranchStarted][dev.rubentxu.pipeline.v2.events.ParallelBranchStarted]
 * - [ParallelBranchFinished][dev.rubentxu.pipeline.v2.events.ParallelBranchFinished]
 * - [RetryAttemptStarted][dev.rubentxu.pipeline.v2.events.RetryAttemptStarted]
 * - [RetryAttemptFinished][dev.rubentxu.pipeline.v2.events.RetryAttemptFinished]
 * - [TimeoutScheduled][dev.rubentxu.pipeline.v2.events.TimeoutScheduled]
 *
 * New variants are decoded via the [JsonEventLog][dev.rubentxu.pipeline.v2.events.JsonEventLog]
 * `kind` discriminator — no schema migration required.
 *
 * ## M3-R1 Extension
 * This store also creates the [operation_journal][dev.rubentxu.pipeline.v2.events.durable.OperationJournalSchema]
 * and [replay_cursor][dev.rubentxu.pipeline.v2.events.durable.OperationJournalSchema] tables
 * via [SqliteConnectionFactory] with WAL mode enabled.
 */
class SqliteEventStore(private val file: String) : EventSink, AutoCloseable {

    private val sequenceCounters = ConcurrentHashMap<String, AtomicLong>()

    // ------------------------------------------------------------------
    // WU-LPR-042: persistent connection + single-writer batched appends.
    //
    // Overload policy (explicit, honest): appends enqueue on a bounded
    // queue drained by ONE writer thread that commits in batches inside a
    // single transaction. If the queue fills (producer outpacing durable
    // writer), append() BLOCKS — bounded + lossless; silent loss is not an
    // option. The durable unit is the SQLite COMMIT (WAL, synchronous=
    // NORMAL: durable under process crash). flush() is a barrier: blocks
    // until every enqueued event is committed.
    // ------------------------------------------------------------------

    /**
     * Closed ADT for the single-writer queue: each case carries only what
     * it needs (no nullable-sentinel DomainEvent, no flag bag).
     */
    private sealed interface PendingWrite {
        /** A real event to insert. */
        data class Event(val event: DomainEvent) : PendingWrite

        /** Barrier: released after every prior entry is committed. */
        data class FlushBarrier(val done: java.util.concurrent.CountDownLatch) : PendingWrite

        /** Terminal: stop the writer loop. */
        data object Stop : PendingWrite
    }

    private val appendQueue = java.util.concurrent.ArrayBlockingQueue<PendingWrite>(10_000)
    private lateinit var writerThread: Thread
    @Volatile private var writerError: Throwable? = null

    // Prepared once on the persistent writer connection; created in init
    // AFTER schema creation (the table must exist first).
    private val writerConnection: Connection = freshConnection()
    private lateinit var insertStatement: java.sql.PreparedStatement

    private fun freshConnection(): Connection =
        SqliteConnectionFactory.open(file)

    private fun withConnection(block: (Connection) -> Unit) {
        val conn = freshConnection()
        try {
            block(conn)
        } finally {
            conn.close()
        }
    }

    init {
        val conn = freshConnection()
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        event_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            // WU-RP-020: the durable sequence authority.
            //
            // The per-run counter is seeded ONCE per instance from
            // MAX(sequence), so two store instances over the same database
            // each start from the same number and then increment
            // independently. Without a uniqueness constraint BOTH inserts
            // succeed and the events table accumulates duplicate sequences,
            // breaking the per-run monotonic invariant that replay and every
            // external observer depend on (P1 bl-bl-01M3QD197Q000387ET2D4MFKR0,
            // observed rows=300 distinct=150).
            //
            // UNIQUE(run_id, sequence) makes the DATABASE the authority: a
            // writer that would duplicate a sequence is rejected by SQLite
            // itself (fail-closed, no silent corruption) instead of
            // appending a second event with a number already in the log.
            //
            // This is deliberately the MINIMAL invariant repair. It does not
            // renumber a colliding event: the single-writer queue already
            // gives a dense 1..N for one instance, and renumbering under
            // contention would open holes. Deciding which writer owns a run
            // is the separate RunExecutionLease design question.
            createSequenceUniquenessIndex(conn)
            // Create durable operation journal tables.
            OperationJournalSchema.create(conn)
            // Migrate operation_journal schema: add started_at + ended_at columns if absent.
            // Idempotent: safe to run on pre-M3-R3 databases.
            migrateOperationJournalSchema(conn)
            // M3-R4.1 T-02: backfill run_id from input JSON for pre-existing rows.
            // Idempotent: only updates rows where run_id IS NULL.
            backfillRunId(conn)
        } finally {
            conn.close()
        }
        // WU-LPR-041: durable sequence truth. The per-run counters MUST be
        // seeded from SQLite (durable state), not start empty per instance.
        // Without this, a fresh store instance restarts sequences at 1 and
        // duplicates the history of any run already present in the DB.
        seedSequenceCounters()
        // WU-LPR-042: prepare the insert on the persistent connection and
        // start the single writer.
        insertStatement = writerConnection.prepareStatement(
            "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) VALUES (?, ?, ?, ?, ?, ?)"
        )
        writerThread = Thread({ writerLoop() }, "sqlite-event-writer")
        writerThread.isDaemon = false
        writerThread.start()
    }

    /**
     * Seeds [sequenceCounters] with the durable per-run MAX(sequence) from
     * the events table. Called once at construction; later appends advance
     * the in-memory counters monotonically and persist through the normal
     * write path.
     */
    private fun seedSequenceCounters() {
        val conn = freshConnection()
        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT run_id, MAX(sequence) FROM events GROUP BY run_id"
                ).use { rs ->
                    while (rs.next()) {
                        val runId = rs.getString(1)
                        val maxSeq = rs.getLong(2)
                        sequenceCounters[runId] = AtomicLong(maxSeq)
                    }
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Idempotent schema migration for operation_journal.
     * Adds started_at INTEGER and ended_at INTEGER columns if they do not already exist.
     * Safe to call on databases created with the M3-R1 schema (before this migration).
     */
    private fun migrateOperationJournalSchema(conn: java.sql.Connection) {
        conn.createStatement().use { stmt ->
            // Check which columns exist in operation_journal
            val existingColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(operation_journal)").use { rs ->
                while (rs.next()) {
                    existingColumns.add(rs.getString("name"))
                }
            }
            // Idempotent: only ALTER if column is absent
            if (!existingColumns.contains("started_at")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN started_at INTEGER")
            }
            if (!existingColumns.contains("ended_at")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN ended_at INTEGER")
            }
            // M3-R4.1 C-032: add run_id column and index if absent
            if (!existingColumns.contains("run_id")) {
                stmt.execute("ALTER TABLE operation_journal ADD COLUMN run_id TEXT")
                stmt.execute("CREATE INDEX IF NOT EXISTS operation_journal_run_id_idx ON operation_journal(run_id)")
            }
        }
    }

    /**
     * Backfills run_id from the input JSON for pre-M3-R4.1 rows.
     * Idempotent: only updates rows where run_id IS NULL.
     * Uses json_extract to pull $.runId from the input JSON blob.
     */
    private fun backfillRunId(conn: java.sql.Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                "UPDATE operation_journal SET run_id = json_extract(input, '\$.runId') WHERE run_id IS NULL"
            )
        }
    }

    /**
     * WU-LPR-105 test seam: artificial per-batch writer delay (default 0 =
     * production behaviour untouched). Widens the old append/COMMIT window
     * deterministically so the sequence-publication race is reproducible in
     * tests instead of flaky in CI.
     */
    @Volatile var writerDelayMillis: Long = 0

    override fun append(event: DomainEvent) {
        appendAssigned(event)
    }

    /**
     * WU-RP-020: makes `UNIQUE(run_id, sequence)` the durable authority.
     *
     * Idempotent: `IF NOT EXISTS`, so opening an already-migrated database
     * is a no-op.
     *
     * A database that ALREADY carries duplicate sequences cannot satisfy the
     * constraint, and SQLite would then fail the index creation for an opaque
     * reason at some later moment. That is reported here, with the offending
     * run, instead of leaving a corrupt log that only fails later: an operator
     * gets a named cause and a remediation (drop the database, or re-run the
     * corrupted run) rather than a mystery UNIQUE violation mid-run.
     */
    private fun createSequenceUniquenessIndex(conn: java.sql.Connection) {
        // Cheap existence probe FIRST. The duplicate scan below is a full
        // GROUP BY over the whole events table; running it on every open of an
        // already-migrated database would cost a full scan to learn something
        // the index presence already answers — and a durable event log is
        // exactly the table that grows without bound. One indexed lookup, then
        // a no-op for every subsequent open.
        val alreadyMigrated = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'ux_events_run_sequence'"
            ).use { rs -> rs.next() }
        }
        if (alreadyMigrated) return

        val duplicates = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT run_id, sequence, COUNT(*) FROM events
                GROUP BY run_id, sequence HAVING COUNT(*) > 1
                ORDER BY run_id, sequence LIMIT 5
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add("runId=${rs.getString(1)} sequence=${rs.getLong(2)} count=${rs.getLong(3)}")
                    }
                }
            }
        }
        check(duplicates.isEmpty()) {
            "WU-RP-020: durable event log has duplicate (run_id, sequence) pairs, " +
                "so UNIQUE(run_id, sequence) cannot be established: ${duplicates.joinToString("; ")}. " +
                "This database was written by more than one store instance before the " +
                "constraint existed. It cannot be repaired automatically without " +
                "renumbering committed history; start a fresh database or re-run the run."
        }
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_events_run_sequence ON events(run_id, sequence)")
        }
    }

    /**
     * WU-LPR-105: the store assigns the sequence and returns the assigned
     * event explicitly. Single counter, single authority — the read model is
     * never consulted for write metadata.
     */
    override fun appendAssigned(event: DomainEvent): DomainEvent {
        writerError?.let { throw IllegalStateException("event writer failed earlier", it) }
        // Assign per-runId sequence eagerly (monotonic per run; LPR-041
        // counters seeded from durable truth at construction).
        val counter = sequenceCounters.computeIfAbsent(event.runId) { AtomicLong() }
        val assignedSequence = if (event.sequence == 0L) {
            counter.incrementAndGet()
        } else {
            val current = counter.get()
            if (event.sequence > current) {
                counter.set(event.sequence)
            }
            event.sequence
        }
        val eventWithSequence = when (event) {
                is RunStarted -> event.copy(sequence = assignedSequence)
                is CompilationStarted -> event.copy(sequence = assignedSequence)
                is CompilationFinished -> event.copy(sequence = assignedSequence)
                is RunFinished -> event.copy(sequence = assignedSequence)
                is StageStarted -> event.copy(sequence = assignedSequence)
                is StageFinished -> event.copy(sequence = assignedSequence)
                is StepStarted -> event.copy(sequence = assignedSequence)
                is StepFinished -> event.copy(sequence = assignedSequence)
                is AgentResolved -> event.copy(sequence = assignedSequence)
                is ParallelBranchStarted -> event.copy(sequence = assignedSequence)
                is ParallelBranchFinished -> event.copy(sequence = assignedSequence)
                is RetryAttemptStarted -> event.copy(sequence = assignedSequence)
                is RetryAttemptFinished -> event.copy(sequence = assignedSequence)
                is TimeoutScheduled -> event.copy(sequence = assignedSequence)
                is StepFailed -> event.copy(sequence = assignedSequence)
                is EchoOutputCaptured -> event.copy(sequence = assignedSequence)
                is CredentialBound -> event.copy(sequence = assignedSequence)
                is CredentialUsed -> event.copy(sequence = assignedSequence)
                is CredentialUnbound -> event.copy(sequence = assignedSequence)
                is GitCheckoutStarted -> event.copy(sequence = assignedSequence)
                is GitCheckoutCompleted -> event.copy(sequence = assignedSequence)
                is GitCheckoutFailed -> event.copy(sequence = assignedSequence)
                is GitPollChanged -> event.copy(sequence = assignedSequence)
                is FileWritten -> event.copy(sequence = assignedSequence)
                is FileRead -> event.copy(sequence = assignedSequence)
                is FileExistsChecked -> event.copy(sequence = assignedSequence)
                is ArtifactArchived -> event.copy(sequence = assignedSequence)
                is ArtifactArchiveFailed -> event.copy(sequence = assignedSequence)
                // WU-LPR-089 — core.stash/core.unstash durable cross-stage data movement
                is StashCreated -> event.copy(sequence = assignedSequence)
                is StashRestored -> event.copy(sequence = assignedSequence)
                is StashFailed -> event.copy(sequence = assignedSequence)
                // WU-LPR-090 — core.publishHTML durable HTML report publishing
                is HtmlReportPublished -> event.copy(sequence = assignedSequence)
                is HtmlReportSkipped -> event.copy(sequence = assignedSequence)
                is HtmlReportFailed -> event.copy(sequence = assignedSequence)
                is DirEntered -> event.copy(sequence = assignedSequence)
                is DirExited -> event.copy(sequence = assignedSequence)
                is DirDeleted -> event.copy(sequence = assignedSequence)
                is WsCleaned -> event.copy(sequence = assignedSequence)
                is CatchErrorTriggered -> event.copy(sequence = assignedSequence)
                is DirectiveAdmitted -> event.copy(sequence = assignedSequence)
                is DirectiveDenied -> event.copy(sequence = assignedSequence)
                is StageMarkedUnstable -> event.copy(sequence = assignedSequence)
                is WorkflowLoaded -> event.copy(sequence = assignedSequence)
                is WaitUntilPolled -> event.copy(sequence = assignedSequence)
                is WaitUntilCompleted -> event.copy(sequence = assignedSequence)
                is PwdResolved -> event.copy(sequence = assignedSequence)
                is UnixDetected -> event.copy(sequence = assignedSequence)
                is MilestoneReached -> event.copy(sequence = assignedSequence)
                is MilestoneAborted -> event.copy(sequence = assignedSequence)
                is TimeoutTriggered -> event.copy(sequence = assignedSequence)
                is TimestampsEntered -> event.copy(sequence = assignedSequence)
                is TimestampsExited -> event.copy(sequence = assignedSequence)
                // S2.5.7 / B1.2c3 — LB-01 durable-spine admission observation (WU-1)
                is StepAdmissionObserved -> event.copy(sequence = assignedSequence)
            }

            // Enqueue for the single writer. Bounded queue: blocks when full
            // (documented overload policy — lossless, backpressure to producer).
            try {
                appendQueue.put(PendingWrite.Event(eventWithSequence))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("interrupted while enqueueing event append", e)
            }
            return eventWithSequence
    }

    /**
     * WU-RP-020: release every [PendingWrite.FlushBarrier] still queued behind a
     * writer that is about to die.
     *
     * A barrier is a wait for the writer to make progress. Once the writer has
     * failed there is no progress left to wait for, so a barrier left in the
     * queue converts an immediate fail-fast into a stall for the full
     * `barrier.await(60, SECONDS)` bound. Releasing it lets the waiter reach
     * the `writerError` path and report the real failure instead.
     *
     * Queued [PendingWrite.Event]s are put **back**, not dropped: discarding
     * them would silently lose writes. They are unreadable in practice because
     * every later `append`/`appendAssigned` fails fast on `writerError`, and
     * the store is closed right after.
     */
    private fun releaseQueuedBarriers() {
        val queued = ArrayList<PendingWrite>(appendQueue.size)
        appendQueue.drainTo(queued)
        val events = ArrayList<PendingWrite>(queued.size)
        for (pending in queued) {
            when (pending) {
                is PendingWrite.FlushBarrier -> pending.done.countDown()
                // Re-offer so the queue still describes every write that was
                // accepted; nothing is silently lost.
                else -> events.add(pending)
            }
        }
        for (pending in events) {
            runCatching { appendQueue.put(pending) }
        }
    }

    /**
     * Single-writer loop: drains the bounded queue in batches, inserting
     * every batch inside ONE transaction. The COMMIT is the durable unit.
     * On failure, records [writerError] and releases all waiters; later
     * appends fail fast.
     */
    private fun writerLoop() {
        val batch = ArrayList<PendingWrite>(256)
        while (true) {
            try {
                val first = appendQueue.take() // blocks; Stop ends loop
                if (first is PendingWrite.Stop) {
                    return
                }
                batch.add(first)
                appendQueue.drainTo(batch, 511)
                if (writerDelayMillis > 0) Thread.sleep(writerDelayMillis)
                writerConnection.autoCommit = false
                try {
                    for (pending in batch) {
                        when (pending) {
                            is PendingWrite.Event -> {
                                bindInsert(insertStatement, pending.event)
                                insertStatement.executeUpdate()
                            }
                            is PendingWrite.FlushBarrier, PendingWrite.Stop -> {} // handled post-commit
                        }
                    }
                    writerConnection.commit()
                    for (pending in batch) {
                        if (pending is PendingWrite.FlushBarrier) pending.done.countDown()
                    }
                } catch (e: Throwable) {
                    writerConnection.rollback()
                    writerError = e
                    for (pending in batch) {
                        if (pending is PendingWrite.FlushBarrier) pending.done.countDown()
                    }
                    // WU-RP-020: the writer is dying and will never take another
                    // item off the queue. A FlushBarrier enqueued AFTER this
                    // batch was taken -- which is exactly what happens when a
                    // producer appends a doomed event and immediately flushes --
                    // is stranded in the queue and its waiter blocks for the
                    // full 60 s bound. Release those barriers now, so the
                    // waiter observes the writer's real error.
                    //
                    // Only barriers are released. Queued Events are NOT
                    // dropped: discarding them would silently lose writes, and
                    // every later append/appendAssigned already fails fast on
                    // `writerError`.
                    releaseQueuedBarriers()
                    throw e
                } finally {
                    writerConnection.autoCommit = true
                    batch.clear()
                }
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun bindInsert(ps: java.sql.PreparedStatement, event: DomainEvent) {
        ps.setString(1, event.eventId)
        ps.setString(2, event.runId)
        ps.setLong(3, event.sequence)
        ps.setString(4, event.kind)
        ps.setString(5, event.occurredAt.toString())
        ps.setString(6, JsonEventLog.encode(listOf(event)))
    }

    /**
     * Flush barrier: blocks until every event enqueued BEFORE this call is
     * durably committed. Implemented with a one-shot latch carried through
     * the queue behind the pending events (ordering guarantee of the
     * single writer).
     */
    fun flush() {
        writerError?.let { throw IllegalStateException("event writer failed", it) }
        val barrier = java.util.concurrent.CountDownLatch(1)
        try {
            // WU-RP-020 (measured, not inferred): a barrier enqueued while the
            // writer is DYING is stranded and burns the full 60 s bound.
            //
            // The failing batch contains only events -- the writer dies on a
            // duplicate INSERT, so the catch-block release loop finds zero
            // barriers to count down (observed: "batch FAILED size=4
            // barriers=0"). The barrier enqueued by this flush() therefore
            // lands in the queue AFTER the writer stopped consuming, and nobody
            // will ever release it. The `writerError` fast path above cannot
            // catch this: it is a race, and the failing append returns BEFORE
            // the writer records its error.
            //
            // Symptom seen in a real gate: `flush()` took 60008 ms, the exact
            // barrier bound. Fix: a latch that is already counted down cannot
            // be stranded, and `await` returns immediately, so the writer's
            // real error surfaces instead of a timeout.
            val released = if (writerError != null) {
                barrier.countDown()
                true
            } else {
                appendQueue.put(PendingWrite.FlushBarrier(barrier))
                barrier.await(60, TimeUnit.SECONDS)
            }
            if (!released) {
                // WU-RP-022 (finding P2): a timed-out barrier almost always means
                // the writer thread DIED on a write error (e.g. SQLITE_TOOBIG for
                // an oversized event payload). Surface the writer's real failure
                // instead of the misleading "timed out" message.
                writerError?.let { throw IllegalStateException("event writer failed during flush", it) }
                check(false) { "flush barrier timed out" }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted during flush", e)
        }
        writerError?.let { throw IllegalStateException("event writer failed during flush", it) }
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        // WU-RP-044 (M5 RSS debt): lazy row-by-row read. Materialising the full
        // list held every decoded event (including GiB-scale transcripts) in
        // memory before the caller could stream them out. The sequence owns its
        // connection and closes it at exhaustion; a partial iteration leaks the
        // connection to GC finalisation (same as the previous eager form's
        // error path), which no production caller does — all iterate fully.
        sequence {
            val conn = freshConnection()
            try {
                conn.prepareStatement(
                    "SELECT payload FROM events WHERE run_id = ? ORDER BY rowid ASC"
                ).use { ps ->
                    ps.setString(1, runId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val payload = rs.getString(1)
                            JsonEventLog.decode(payload).firstOrNull()?.let { yield(it) }
                        }
                    }
                }
            } finally {
                conn.close()
            }
        }.constrainOnce().let { return it }
    }

    /**
     * Closes the store: flush barrier, then stops the writer and releases
     * the persistent connection. Close is idempotent.
     */
    @Volatile private var closed = false
    private val closeLock = Any()

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        try {
            flush()
        } finally {
            try {
                appendQueue.put(PendingWrite.Stop)
                writerThread.join(10_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { insertStatement.close() }
            runCatching { writerConnection.close() }
        }
    }

    /**
     * Exposes the underlying connection factory for use by [OperationJournal]
     * and [ReplayCursorStore].
     *
     * This is intentionally internal — it is only used within the durable
     * execution subsystem that shares the same SQLite database file.
     */
    fun underlyingConnectionFactory(): () -> Connection = { freshConnection() }

    /**
     * Exposes the database file path for use by [SqliteOperationJournalImpl]
     * to enable cross-instance [DbLock] synchronization.
     */
    fun databasePath(): String = file
}
