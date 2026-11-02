package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockAcquired
import dev.rubentxu.pipeline.v2.events.LockReleased
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StepAdmissionObserved
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The store's WRITING owner: the event's sequence, the bounded queue, the single writer thread
 * and the commit that makes a fact durable.
 *
 * ## What this owns
 *
 * The WRITE side of the durable event log, and nothing else:
 *
 * - the per-run SEQUENCE authority — the counter that stamps a fact, and the seed that makes it
 *   continue the run's durable history instead of restarting at 1;
 * - the bounded QUEUE that accepts a fact and hands it to the writer thread, blocking when the
 *   producer outpaces the durable writer (bounded and lossless: silent loss is not an option);
 * - the single WRITER THREAD that drains the queue in batches and commits each batch inside one
 *   transaction, so the COMMIT is the durable unit;
 * - the FLUSH barrier, which is a wait for the writer to make progress and not a promise about
 *   the future;
 * - the REFUSAL of an event the database would not take, and the report that tells the producer
 *   that the event is missing from the log.
 *
 * ## What this is NOT
 *
 * It is not a reader: nothing here reads history back, and nothing here decides what a reader may
 * see. It is not a schema: the tables and the migrations belong to [SqliteEventSchema]. It is not
 * the store's contract: [SqliteEventStore] is the facade, and this owner is invisible behind it.
 *
 * ## Why the sequence is decided HERE and not by the reader
 *
 * `appendAssigned` stamps the sequence on the PRODUCER thread and enqueues a few instructions
 * later, so two producers can enqueue in the opposite order to the one they were assigned. The
 * counter is therefore the authority, and the read model is never consulted for write metadata.
 * That is why the counter lives in this owner and the read lane has no opinion about it.
 */
internal class SqliteEventWriter(
    private val connections: () -> Connection,
) : AutoCloseable {

    private val sequenceCounters = ConcurrentHashMap<String, AtomicLong>()

    // ------------------------------------------------------------------
    // Persistent connection + single-writer batched appends.
    // Overload policy (explicit, honest): appends enqueue on a bounded
    // queue drained by ONE writer thread that commits in batches inside a
    // single transaction. If the queue fills (producer outpacing durable
    // writer), append BLOCKS — bounded + lossless; silent loss is not an
    // option. The durable unit is the SQLite COMMIT (WAL, synchronous=
    // NORMAL: durable under process crash). flush is a barrier: blocks
    // until every enqueued event is committed.
    // ------------------------------------------------------------------

    /**
     * Closed ADT for the single-writer queue: each case carries only what
     * it needs (no nullable-sentinel DomainEvent, no flag bag).
*/
    private sealed interface PendingWrite {
        /** A real event to insert. */
        data class Event(val event: DomainEvent) : PendingWrite

        /**
         * Barrier: released after every prior entry is committed.
         *
         * It also CARRIES the appends the database refused before it. The barrier is the point
         * at which a producer asks whether its events are durable, and an append is ASSIGNED
         * before it is committed, so this is the first moment at which "your event was not
         * stored" can be told to the producer that enqueued it.
         */
        class FlushBarrier : PendingWrite {
            val done = java.util.concurrent.CountDownLatch(1)

            /**
             * The appends the database refused before this barrier, published by the writer just
             * before the barrier is released.
             */
            @Volatile
            var refusals: List<EventAppendRefusal> = emptyList()
        }

        /** Terminal: stop the writer loop. */
        data object Stop : PendingWrite
    }

    private val appendQueue = java.util.concurrent.ArrayBlockingQueue<PendingWrite>(10_000)
    private var writerThread: Thread? = null
    @Volatile private var writerError: Throwable? = null

    /**
     * Every append the database refused, in the order it refused them.
     *
     * The list is CUMULATIVE on purpose: a barrier reports what the store has refused since it
     * opened, not only what it refused since the previous barrier. A refusal that a later
     * barrier forgot would be a refusal the producer never heard about.
     */
    private val refusedAppends = java.util.concurrent.CopyOnWriteArrayList<EventAppendRefusal>()

    // Prepared once on the persistent writer connection; created in init
    // AFTER schema creation (the table must exist first). This owner exists
    // only in the producer's lane: a reader's store has no writer connection
    // and no insert statement, because it writes nothing and so holds no write
    // capability over the producer's file.
    private val writerConnection: Connection = connections()
    private var insertStatement: java.sql.PreparedStatement? = null
    private var savepointStatement: java.sql.Statement? = null

    init {
        // The schema is created, the journal is migrated and backfilled, and
        // only then can the insert be prepared and the writer started.
        SqliteEventSchema.prepare(connections)
        // Durable sequence truth. The per-run counters MUST be
        // seeded from SQLite (durable state), not start empty per instance.
        // Without this, a fresh store instance restarts sequences at 1 and
        // duplicates the history of any run already present in the DB.
        seedSequenceCounters()
        // Prepare the insert on the persistent connection and
        // start the single writer.
        insertStatement = writerConnection.prepareStatement(
            "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) VALUES (?, ?, ?, ?, ?, ?)"
        )
        savepointStatement = writerConnection.createStatement()
        val thread = Thread({ writerLoop() }, "sqlite-event-writer")
        thread.isDaemon = false
        thread.start()
        writerThread = thread
    }

    /**
     * Seeds [sequenceCounters] with the durable per-run MAX(sequence) from
     * the events table. Called once at construction; later appends advance
     * the in-memory counters monotonically and persist through the normal
     * write path.
*/
    private fun seedSequenceCounters() {
        val conn = connections()
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
     * Artificial per-batch writer delay (default 0 =
     * production behaviour untouched). Widens the old append/COMMIT window
     * deterministically so the sequence-publication race is reproducible in
     * tests instead of flaky in CI.
*/
    @Volatile var writerDelayMillis: Long = 0

    /**
     * The writer assigns the sequence and returns the assigned
     * event explicitly. Single counter, single authority — the read model is
     * never consulted for write metadata.
    */
    fun appendAssigned(event: DomainEvent): DomainEvent {
        writerError?.let { throw IllegalStateException("event writer failed earlier", it) }
        // Assign per-runId sequence eagerly (monotonic per run
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
                // RunCorrelated carries the
                // assigned sequence like every other durable event; it
                // must appear in the when for the assignment to take
                // effect.
                is RunCorrelated -> event.copy(sequence = assignedSequence)
                is PluginEventEmitted -> event.copy(sequence = assignedSequence)
                is CompilationStarted -> event.copy(sequence = assignedSequence)
                is CompilationFinished -> event.copy(sequence = assignedSequence)
                is RunFinished -> event.copy(sequence = assignedSequence)
                is StageStarted -> event.copy(sequence = assignedSequence)
                is StageSkipped -> event.copy(sequence = assignedSequence)
                is PostConditionSelected -> event.copy(sequence = assignedSequence)
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
                // Core.stash/core.unstash durable cross-stage data movement
                is StashCreated -> event.copy(sequence = assignedSequence)
                is StashRestored -> event.copy(sequence = assignedSequence)
                is StashFailed -> event.copy(sequence = assignedSequence)
                // Core.publishHTML durable HTML report publishing
                is HtmlReportPublished -> event.copy(sequence = assignedSequence)
                is HtmlReportSkipped -> event.copy(sequence = assignedSequence)
                is HtmlReportFailed -> event.copy(sequence = assignedSequence)
                is DirEntered -> event.copy(sequence = assignedSequence)
                is DirExited -> event.copy(sequence = assignedSequence)
                is DirDeleted -> event.copy(sequence = assignedSequence)
                is WsCleaned -> event.copy(sequence = assignedSequence)
                is CatchErrorTriggered -> event.copy(sequence = assignedSequence)
                is DirectiveAdmitted -> event.copy(sequence = assignedSequence)
            is ExecutionTargetResolved -> event.copy(sequence = assignedSequence)
                is DirectiveDenied -> event.copy(sequence = assignedSequence)
                is GateEvaluated -> event.copy(sequence = assignedSequence)
                is StageMarkedUnstable -> event.copy(sequence = assignedSequence)
                is WorkflowLoaded -> event.copy(sequence = assignedSequence)
                is WaitUntilPolled -> event.copy(sequence = assignedSequence)
                is WaitUntilCompleted -> event.copy(sequence = assignedSequence)
                is PwdResolved -> event.copy(sequence = assignedSequence)
                is UnixDetected -> event.copy(sequence = assignedSequence)
                is MilestoneReached -> event.copy(sequence = assignedSequence)
                is MilestoneAborted -> event.copy(sequence = assignedSequence)
                // / lock events
                is LockRequested -> event.copy(sequence = assignedSequence)
                is LockAcquired -> event.copy(sequence = assignedSequence)
                is LockReleased -> event.copy(sequence = assignedSequence)
                is LockSkipped -> event.copy(sequence = assignedSequence)
                is LockAcquireFailed -> event.copy(sequence = assignedSequence)
                // / input events
                is InputRequested -> event.copy(sequence = assignedSequence)
                is HttpRequestStarted -> event.copy(sequence = assignedSequence)
                is HttpResponseReceived -> event.copy(sequence = assignedSequence)
                is HttpStatusRejected -> event.copy(sequence = assignedSequence)
                is HttpRequestFailed -> event.copy(sequence = assignedSequence)
                is InputProceed -> event.copy(sequence = assignedSequence)
                is InputAborted -> event.copy(sequence = assignedSequence)
                is InputDenied -> event.copy(sequence = assignedSequence)
                is TimeoutTriggered -> event.copy(sequence = assignedSequence)
                is TimestampsEntered -> event.copy(sequence = assignedSequence)
                is TimestampsExited -> event.copy(sequence = assignedSequence)
                // durable-spine admission observation
                is StepAdmissionObserved -> event.copy(sequence = assignedSequence)
                // the armed-obligation notification carries the assigned
                // sequence like every other durable event; without this branch it would be
                // written with sequence 0 and collide on the run-sequence uniqueness index.
                is CompensationArmed -> event.copy(sequence = assignedSequence)
            is CompensationStarted -> event.copy(sequence = assignedSequence)
            is CompensationFinished -> event.copy(sequence = assignedSequence)
            is SagaTerminal -> event.copy(sequence = assignedSequence)
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
     * Release every [PendingWrite.FlushBarrier] still queued behind a
     * writer that is about to die.
     * A barrier is a wait for the writer to make progress. Once the writer has
     * failed there is no progress left to wait for, so a barrier left in the
     * queue converts an immediate fail-fast into a stall for the full
     * `barrier.await(60, SECONDS)` bound. Releasing it lets the waiter reach
     * the `writerError` path and report the real failure instead.
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
        val connection = requireNotNull(writerConnection) {
            "the writer loop only runs on a writable store"
        }
        val insert = requireNotNull(insertStatement) {
            "the writer loop only runs on a writable store"
        }
        val savepoint = requireNotNull(savepointStatement) {
            "the writer loop only runs on a writable store"
        }
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
                connection.autoCommit = false
                try {
                    for (pending in batch) {
                        when (pending) {
                            is PendingWrite.Event ->
                                insertIsolated(insert, savepoint, pending.event)
                            // The barrier publishes the refusals it covers BEFORE the commit that
                            // follows it: an event enqueued after the barrier is not part of what
                            // this barrier answers for.
                            is PendingWrite.FlushBarrier -> pending.refusals = refusedAppends.toList()
                            is PendingWrite.Stop -> {} // handled post-commit
                        }
                    }
                    connection.commit()
                    for (pending in batch) {
                        if (pending is PendingWrite.FlushBarrier) pending.done.countDown()
                    }
                } catch (e: Throwable) {
                    connection.rollback()
                    writerError = e
                    for (pending in batch) {
                        if (pending is PendingWrite.FlushBarrier) pending.done.countDown()
                    }
                    // The writer is dying and will never take another
                    // item off the queue. A FlushBarrier enqueued AFTER this
                    // batch was taken -- which is exactly what happens when a
                    // producer appends a doomed event and immediately flushes --
                    // is stranded in the queue and its waiter blocks for the
                    // full 60 s bound. Release those barriers now, so the
                    // waiter observes the writer's real error.
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

    /**
     * Inserts ONE event inside its own savepoint.
     *
     * The savepoint is what makes a refusal PER EVENT: rolling back to it undoes that row alone,
     * so the events that share the transaction with it stay in the transaction and are committed
     * with the rest of the batch. A batch-wide rollback would undo up to 511 events of OTHER runs
     * with the guilty one, and the writer would have no way to tell which row was guilty.
     *
     * A failure the savepoint cannot undo is not a row-level refusal: it means the transaction
     * itself is broken, and a broken transaction takes the writer down with it.
     */
    private fun insertIsolated(
        insert: java.sql.PreparedStatement,
        savepoint: java.sql.Statement,
        event: DomainEvent,
    ) {
        savepoint.execute("SAVEPOINT $SAVEPOINT_NAME")
        try {
            bindInsert(insert, event)
            insert.executeUpdate()
        } catch (e: Throwable) {
            try {
                savepoint.execute("ROLLBACK TO SAVEPOINT $SAVEPOINT_NAME")
                savepoint.execute("RELEASE SAVEPOINT $SAVEPOINT_NAME")
            } catch (undoFailure: Throwable) {
                // The transaction is no longer trustworthy: undoing one row failed. The refusal
                // is the CAUSE and the undo is the symptom, so the cause stays attached.
                undoFailure.addSuppressed(e)
                throw undoFailure
            }
            refusedAppends.add(EventAppendRefusal(event, e))
            return
        }
        savepoint.execute("RELEASE SAVEPOINT $SAVEPOINT_NAME")
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
     *
     * It is also where a REFUSED append is reported. The producer learns here that an event it
     * enqueued is missing from the log, which is the only moment at which the log can be said
     * to hold that event.
*/
    fun flush() {
        writerError?.let { throw IllegalStateException("event writer failed", it) }
        val barrier = PendingWrite.FlushBarrier()
        try {
            // A barrier enqueued while the
            // writer is DYING is stranded and burns the full 60 s bound.
            // The failing batch contains only events -- the writer dies on a
            // duplicate INSERT, so the catch-block release loop finds zero
            // barriers to count down (observed: "batch FAILED size=4
            // barriers=0"). The barrier enqueued by this flush therefore
            // lands in the queue AFTER the writer stopped consuming, and nobody
            // will ever release it. The `writerError` fast path above cannot
            // catch this: it is a race, and the failing append returns BEFORE
            // the writer records its error.
            // Symptom seen in a real gate: `flush()` took 60008 ms, the exact
            // barrier bound. Fix: a latch that is already counted down cannot
            // be stranded, and `await` returns immediately, so the writer's
            // real error surfaces instead of a timeout.
            val released = if (writerError != null) {
                barrier.done.countDown()
                true
            } else {
                appendQueue.put(barrier)
                barrier.done.await(60, TimeUnit.SECONDS)
            }
            if (!released) {
                // A timed-out barrier almost always means
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
        val refusals = barrier.refusals
        if (refusals.isNotEmpty()) {
            throw EventAppendRefusedException(refusals)
        }
    }

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
                writerThread?.join(10_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { insertStatement?.close() }
            runCatching { savepointStatement?.close() }
            runCatching { writerConnection.close() }
        }
    }

    private companion object {
        /**
         * The name of the savepoint that isolates ONE event's insert from the rest of the batch.
         *
         * The writer opens and closes it around every event, so the name is fixed: a name per
         * event would need a counter the writer would have to keep in step with the queue, and
         * the savepoint is closed before the next one is opened.
         */
        const val SAVEPOINT_NAME = "event_append"
    }
}

/**
 * One append the database refused: the event it would not take, and the cause it gave.
 *
 * A refusal is a fact about ONE event. It is not a fact about the store: the events around it are
 * committed and the writer keeps taking writes.
 */
internal data class EventAppendRefusal(val event: DomainEvent, val cause: Throwable)

/**
 * The refusal of one or more appends, reported when a producer asks whether its events are
 * durable.
 *
 * The report arrives at the BARRIER because that is where the durability question is asked: an
 * append is ASSIGNED before it is committed, so the barrier is the first moment at which a
 * producer can be told that an event is NOT in the log. One channel for the truth about
 * durability is worth more than a promise per event that nobody would read.
 */
internal class EventAppendRefusedException(
    val refusals: List<EventAppendRefusal>,
) : IllegalStateException(renderRefusals(refusals))

/**
 * Renders the refusals for a human: which event, in which run, and why the database said no.
 *
 * The list is bounded: a log that refused a thousand events says how many and names the first
 * few, so the diagnostic cannot become the thing that hides the failure.
 */
private fun renderRefusals(refusals: List<EventAppendRefusal>): String {
    val head = refusals.take(MAX_RENDERED_REFUSALS)
    val named = head.joinToString("; ") { refusal ->
        val event = refusal.event
        "runId=${event.runId} sequence=${event.sequence} eventId=${event.eventId} " +
            "cause=${refusal.cause.javaClass.simpleName}: ${refusal.cause.message}"
    }
    val hidden = refusals.size - head.size
    val more = if (hidden > 0) " (and $hidden more not named here)" else ""
    return "the event store refused ${refusals.size} append(s): $named$more"
}

/**
 * How many refusals a diagnostic names before it counts the rest.
 */
private const val MAX_RENDERED_REFUSALS = 10
