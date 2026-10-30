package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.UndecodableEventRecordException
import java.sql.Connection

/**
 * The store's READING owner: the page cut, the cursor, the projection of a row, and the refusal a
 * row that cannot be decoded earns.
 *
 * ## What this owns
 *
 * The READ side of the durable event log, and nothing else:
 *
 * - whether the file holds a history AT ALL, asked once and answered once;
 * - the STREAM of a run's history ([eventsFor]), in sequence order, failing closed on a row it
 *   cannot decode;
 * - the PAGE ([readRecords]) — the cut on `sequence > lastSequence`, the bound that counts ROWS
 *   rather than decodable events, and the peek of one row beyond the page that proves `hasMore`
 *   instead of guessing it;
 * - the two answers the admission needs: whether a run EXISTS at all ([hasRun]) and where its TAIL
 *   is ([tailSequence]).
 *
 * ## What this is NOT
 *
 * It is not a writer: this owner opens only through the store's door, which in the reader's lane
 * is one the database engine refuses to write through. It is not a query engine: the store is the
 * SEQUENCE authority and the cut point, not a filter — a query that is not `All` is refused closed
 * by the port in front of it. And it is not the SELECTION authority: what a reader is shown is
 * decided by the consumer that asks, and never here.
 */
internal class SqliteEventReader(
    private val connections: () -> Connection,
    private val mode: EventStoreMode,
) {

    /**
     * Whether this store holds an event history at all.
     *
     * A WRITABLE store always does: it creates the table. A READ-ONLY store cannot create it,
     * so it asks the file instead —and the answer is a property of the store, computed once,
     * never assigned later.
     *
     * It is a `val` with its own initialiser, and that shape is the point. As a `var` filled
     * in by the constructor and initialised to `true`, Kotlin's initialisation order made the
     * initialiser run AFTER the constructor body and reset the probe's answer: a database
     * with no `events` table was admitted and died later with a raw `no such table` instead of
     * the refusal it was owed. A `val` computed in one place cannot be overwritten by a later
     * initialiser, so the trap is gone rather than merely avoided.
     */
    val holdsEventHistory: Boolean = probeEventHistory()

    /**
     * Asks the file whether it holds an event history, without writing anything to it.
     *
     * A WRITABLE store does not need to ask: it creates the table. A READ-ONLY store asks
     * `sqlite_master`, which is a read.
     */
    private fun probeEventHistory(): Boolean {
        if (mode.mayWrite()) return true
        val conn = connections()
        return try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'events'"
                ).use { rs -> rs.next() }
            }
        } finally {
            conn.close()
        }
    }

    fun eventsFor(runId: String): Sequence<DomainEvent> {
        // This CANNOT report a refusal — `Sequence<DomainEvent>` has no case for one —
        // so it FAILS CLOSED at the unreadable row instead of skipping it. See the KDoc on
        // EventStore.eventsFor for why a skip here is worse than a crash: a skip is invisible, and
        // the caller reads a shorter stream as complete history.
        // Lazy row-by-row read. Materialising the full
        // list held every decoded event (including GiB-scale transcripts) in
        // memory before the caller could stream them out. The sequence owns its
        // connection and closes it at exhaustion; a partial iteration leaks the
        // connection to GC finalisation (same as the previous eager form's
        // error path), which no production caller does — all iterate fully.
        // The ORDER BY is `sequence`, and it used to be `rowid`. That is the
        // difference between the row's physical position and the number this
        // store assigned, and it is not a stylistic one: `appendAssigned` stamps
        // the sequence on the PRODUCT thread and enqueues a few instructions
        // later, so two concurrent writers can enqueue in the opposite order to
        // the one they were assigned and the two orders part company. A cursor
        // cuts on `sequence > lastSequence`, so a read that walks the physical
        // order can step over an event whose sequence is below the cursor and
        // lose it for good — the next cursor starts even higher.
        // `sequence` is also the column the `ux_events_run_sequence` index covers,
        // so this ordering is the one the database can serve from the index
        // instead of sorting a temporary b-tree over every matching row.
        sequence {
            // A file with no `events` table has no history to stream. The reader knows it
            // already, so it says so here instead of letting SQLite's "no such table" escape.
            if (!holdsEventHistory) return@sequence
            val conn = connections()
            try {
                conn.prepareStatement(
                    "SELECT event_id, sequence, kind, payload FROM events " +
                        "WHERE run_id = ? ORDER BY sequence ASC"
                ).use { ps ->
                    ps.setString(1, runId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val record = readRecord(rs)
                            // The row's event, or a NAMED refusal. Never a silent skip.
                            // Unwrap rather than cast: `Decoded` is a wrapper carrying the event, not
                            // a DomainEvent itself. Writing `record as? DomainEvent` here compiled
                            // and always yielded null, so every well-formed row reported itself
                            // unreadable — a refusal that was true of nothing. It surfaced as eight
                            // unrelated test failures, which is exactly how a cast error looks when
                            // the exception message is honest about what it believes.
                            val event = when (record) {
                                is dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded -> record.event
                                is dev.rubentxu.pipeline.v2.events.EventRecordRead.Undecodable ->
                                    throw UndecodableEventRecordException(
                                        runId = runId,
                                        sequence = record.sequence,
                                        kind = record.kind,
                                        reason = record.reason,
                                        message = "durable event row ${record.sequence} exists and could " +
                                            "not be decoded; eventsFor cannot report a refusal, so the read " +
                                            "stops here rather than returning history with a hole in it",
                                    )
                            }
                            yield(event)
                        }
                    }
                }
            } finally {
                conn.close()
            }
        }.constrainOnce().let { return it }
    }

    /**
     * Cuts the page in SQL, which is the only place where that is possible: the cut and the limit
     * are the index's job, and asking SQLite for a run's whole history to keep `limit` of it meant
     * decoding every event of a long run to hand back a page of three.
     * `LIMIT limit + 1` is how [dev.rubentxu.pipeline.v2.events.EventSlice.hasMore] is proved
     * rather than guessed: the extra row is looked at and dropped, which is the same single peek
     * the inherited default performs, only here it costs one row instead of the rest of the run.
     * The `ORDER BY sequence` is the same one `eventsFor` uses, and for the same reason — the
     * cursor cuts on sequence, so the read has to be in sequence. `ux_events_run_sequence` covers
     * it, so the database serves the ordering from the index.
     * ## The page bound counts ROWS, not decodable events (E4c)
     * This used to `page.add(decode(payload))` under a `page.size == limit` check, which meant the
     * bound counted rows that had DECODED. One unreadable row then let its successor into the
     * page, so the returned page was neither the requested size nor a prefix of the history, and
     * `nextCursor` advanced by an amount unrelated to how far the reader had got. It also inverted
     * `hasMore`: a row that failed to decode consumed its slot without filling it, so a page could
     * report "no more" with rows still unread. A consumer resuming from that cursor would skip the
     * unreadable row entirely — the silent hole, arrived at from the pagination side as well as
     * the decode side.
     * The fix is not a guard but an order change: count rows, decode inside the page, and let the
     * refusal travel on the same cursor. `EventRecordSlice.nextCursor` is the last ROW's sequence
     * whether or not that row decoded, which is what lets a resuming reader page past a refusal on
     * purpose instead of re-reading it forever or stepping over it blind.
     * This page law is argued here, not witnessed by a test: [SqliteConnectionFactory.open] opens a
     * FRESH connection per call, so an in-memory database would be empty on every read and the
     * store is file-backed by design. Exercising this path needs real durable storage, which is
     * outside what a unit test may own; the claim above is therefore the authority, and the reader
     * of it is the auditor.
*/
    fun readRecords(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventRecordSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val page = ArrayList<dev.rubentxu.pipeline.v2.events.EventRecordRead>(minOf(limit, 64))
        var hasMore = false
        if (!holdsEventHistory) {
            // A file with no `events` table has no page to read. The cursor does not move:
            // there was nothing past it to begin with.
            return dev.rubentxu.pipeline.v2.events.EventRecordSlice(
                records = page,
                nextCursor = dev.rubentxu.pipeline.v2.events.identity.EventCursor(runId, afterSequence),
                hasMore = false,
            )
        }
        connections().use { conn ->
            conn.prepareStatement(
                "SELECT event_id, sequence, kind, payload FROM events WHERE run_id = ? AND sequence > ? " +
                    "ORDER BY sequence ASC LIMIT ?"
            ).use { ps ->
                ps.setString(1, runId)
                ps.setLong(2, afterSequence)
                ps.setInt(3, limit + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (page.size == limit) {
                            // One ROW beyond the page exists. Decoded or not, it is a row, and it
                            // is the only honest way to know the run continues.
                            hasMore = true
                            break
                        }
                        page.add(readRecord(rs))
                    }
                }
            }
        }
        return dev.rubentxu.pipeline.v2.events.EventRecordSlice(
            records = page,
            nextCursor = dev.rubentxu.pipeline.v2.events.identity.EventCursor(
                runId,
                page.lastOrNull()?.sequence ?: afterSequence,
            ),
            hasMore = hasMore,
        )
    }

    /**
     * Authoritatively answer "has this run ever been written to".
     * The [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort]
     * adapter needs to distinguish a run that does not exist (the
     * consumer mistyped the runId, or the run was on a different host)
     * from a run that exists but has not yet produced any event. The
     * adapter's only authority for that distinction is this method, and
     * SQLite's `EXISTS` over the events table is the cheapest correct
     * answer.
     * The check is row-presence on the events table, NOT "non-empty
     * page from readRecords": a run that has just been declared but
     * not yet appended to must answer `true` so the adapter can serve
     * an empty [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Page]
     * to the consumer instead of refusing with
     * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.UnknownRun].
     * Connection lifecycle: opens a fresh short-lived connection via
     * [connections] so the call does not share state with the
     * persistent writer connection (a write under load would otherwise
     * see the connection busy).
*/
    fun hasRun(runId: String): Boolean {
        // A file with no `events` table holds no run. Saying so here keeps the refusal in
        // the vocabulary of the product instead of letting SQLite's "no such table" escape
        // as a raw stack trace from a verb that only wanted to read.
        if (!holdsEventHistory) return false
        return connections().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM events WHERE run_id = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /**
     * Durable `MAX(sequence)` for [runId], or `null` if the run
     * has no rows.
     * The [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort]
     * adapter uses this to refuse a cursor that is past the tail.
     * SQLite's `MAX` is `null` over an empty set, so the function
     * returns `null` for an empty run without a separate existence
     * check; the adapter already gated the unknown-run case on
     * [hasRun] before calling this.
     * Connection lifecycle: opens a fresh short-lived connection via
     * [connections] (same rationale as [hasRun]: avoid sharing
     * state with the persistent writer connection).
*/
    fun tailSequence(runId: String): Long? {
        // A file with no `events` table has no tail either.
        if (!holdsEventHistory) return null
        return connections().use { conn ->
            conn.prepareStatement(
                "SELECT MAX(sequence) FROM events WHERE run_id = ?"
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val v = rs.getLong(1)
                        if (rs.wasNull()) null else v
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Turns ONE durable row into either its typed event or a refusal that names it.
     * The identity comes from the row's own COLUMNS, not from the payload, and that is what makes
     * the refusal honest: `event_id`, `sequence` and `kind` are `NOT NULL` in the schema and are
     * written by the writer's insert binding from the event, so a refusal can repeat what storage actually holds
     * even when the payload is unparseable text. Reading identity out of the payload instead would
     * make the refusal disappear in exactly the case it exists to describe.
     * ## Read by COLUMN NAME, never by index (paid for on the day this was written)
     * The first version of this read `rs.getString(1)`, `rs.getLong(2)`, … against
     * `SELECT event_id, sequence, kind, payload`. That is wrong the moment the SELECT list and the
     * schema disagree: the schema is `(event_id, run_id, sequence, kind, occurred_at, payload)`, so
     * position 2 in the SELECT is `sequence` while position 2 in the TABLE is `run_id`, and
     * `getLong(2)` returned a run id silently coerced to 0. Every row came back as
     * `Undecodable(sequence = 0)`.
     * What makes it worth recording is how it presented. The old code was
     * `firstOrNull()?.let { yield(it) }`, so a mis-indexed read looked exactly like an empty table:
     * `eventsFor` returned an empty list and the row-tuple test failed on `assertEquals(4, …)` with a
     * message about order and payload. Nothing said "you read the wrong column". Under E4c the same
     * bug surfaces as a refusal naming sequence 0, which is strictly more diagnosable — but only
     * because the refusal exists. Positional reads and silent skips were the same accident twice.
     * Returning a sealed result rather than throwing is deliberate: the caller here is a PAGE, and
     * a page has to be able to carry the refusal. Throwing at this level would lose the rows after
     * it. The fail-closed decision belongs to whoever asked for typed events —
     * [eventsFor] and the inherited [dev.rubentxu.pipeline.v2.events.EventStore.readSlice] make it, and\n     * [readRecords] hands the caller the choice.
*/
    private fun readRecord(rs: java.sql.ResultSet): dev.rubentxu.pipeline.v2.events.EventRecordRead {
        val eventId = rs.getString("event_id")
        val sequence = rs.getLong("sequence")
        val kind = rs.getString("kind")
        return when (val outcome = JsonEventLog.decodeStoredRow(rs.getString("payload"), kind)) {
            is StoredRowDecode.Accepted ->
                dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded(outcome.event)
            is StoredRowDecode.Refused ->
                dev.rubentxu.pipeline.v2.events.EventRecordRead.Undecodable(
                    sequence = sequence,
                    kind = kind,
                    eventId = eventId,
                    reason = outcome.reason,
                )
        }
    }
}
