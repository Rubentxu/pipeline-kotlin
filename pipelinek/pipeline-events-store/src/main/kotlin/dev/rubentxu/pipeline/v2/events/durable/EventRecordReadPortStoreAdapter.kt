package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult

/**
 * Production adapter for [EventRecordReadPort] backed by an [EventRunSnapshotReader].
 *
 * ## Why this adapter exists
 *
 * [EventRecordReadPort] is the public read-only port. The contract says Fabric consumes through
 * it, not through the store directly, because the store exposes `append` and `appendAssigned` (the
 * write side) and is therefore not a safe surface for a remote consumer. What an external consumer
 * may read, and what it may not do, is the contract the root README states under "Reading a run
 * from outside".
 *
 * [EventRecordReadPortStoreAdapter] is the one and only implementation that backs the port. It
 * holds a single authority for sequence, cursor, and the typed-vs-undecodable cut, by delegating
 * to the store's snapshot — which is the page authority ("the store is the sequence authority, so
 * the store is where a page is decided"). The adapter adds:
 *
 *  - the **unknown-run vs empty-history distinction**
 *    ([EventRecordReadRefusal.UnknownRun] vs an empty [EventRecordReadResult.Page]). A consumer
 *    that cannot tell the two apart cannot decide whether to read the run or to declare it
 *    unknown, and the distinction is mandatory. Two authorities answer it, and each answers its
 *    own question:
 *
 *      - the EVENT PLANE answers "does the event plane hold a row for this run?", and the
 *        snapshot's tail is that answer: a run with a row has a tail, and a run without a row has
 *        none (`tail != null`).
 *      - the DECLARED-RUN authority ([runDeclared]) answers "was this run declared at all?", and
 *        the lease is that authority: a run that was deliberately declared is known even before it
 *        writes its first event.
 *
 *    The declared-run authority is consulted ONLY when the event plane holds nothing. That is not
 *    an optimisation: it is the order that makes the composed answer exact, because the two
 *    authorities disagree only in the case where the event plane is empty, and in that case the
 *    declared-run authority is the one that can still say the run exists.
 *
 *  - the **cursor-past-tail distinction** ([EventRecordReadRefusal.CursorBeyondTail] vs an empty
 *    [EventRecordReadResult.Page]). The store's page comes back empty when the cursor is past the
 *    tail; the contract says that must be refused closed, not silently served. The tail the
 *    snapshot carries is the durable `MAX(sequence)` of the run, and the adapter refuses when
 *    `after.lastSequence > tail`. A run with no rows has no tail, and any non-null cursor is then
 *    strictly past it.
 *
 *  - the **cursor runId mismatch rejection** ([EventRecordReadRefusal.StorageError]). An `after`
 *    whose `runId` differs from the requested `runId` is a contract violation — a consumer mixed
 *    two cursors from different runs — and is refused closed BEFORE the state checks. The check is
 *    BEFORE them because it is a contract-level error, not a state error: the cursor's runId is a
 *    parameter of the API, not a fact about the store. Surfacing it as
 *    [EventRecordReadRefusal.StorageError] (rather than a separate closed hierarchy case) keeps the
 *    refusal ADT unchanged.
 *
 *  - the **[EventQuery] argument refusal** ([EventRecordReadRefusal.StorageError]). A
 *    non-[EventQuery.All] query is refused closed with a `StorageError("query not supported:
 *    <kind>")` diagnostic. The store does not filter (it is the sequence authority and the cut
 *    point, not a query engine); the previous "pass-through" semantic was honest but left a query
 *    filterless, which silently returned the full page. Filtering belongs to whoever knows what it
 *    wants, not to the store.
 *
 *  - the **storage-error translation**. Any exception raised while reading is caught and translated
 *    to [EventRecordReadRefusal.StorageError] with a short diagnostic. The port is total; a thrown
 *    exception would defeat the closed refusal hierarchy. The declared-run authority is inside the
 *    same net: a lease that cannot be read is a storage failure, not a fact about the run.
 *
 *  - the **[after] cursor** is passed through to the store, which is the page authority. The store
 *    is responsible for the "strictly greater than" cut and the
 *    [dev.rubentxu.pipeline.v2.events.EventRecordSlice.nextCursor] continuation.
 *
 * ## ONE read, and why that matters
 *
 * The adapter asks the snapshot reader ONCE. The tail and the page used to be two questions asked
 * in two connections at two moments of the same run, and a write landing between them produced
 * refusals of a run that was there: a run whose first event arrived after the tail was read was
 * refused as a cursor past a tail that did not exist, and a run whose page was newer than its tail
 * was described by two facts that cannot both be true.
 *
 * The snapshot answers both at one instant, and the adapter answers the consumer from that one
 * answer. A consumer is told a tail and a page that agree, or it is told a refusal.
 *
 * ## Cursors and the contract
 *
 * The adapter does NOT introduce a fourth cursor vocabulary. The cursor is [EventCursor] (the
 * existing event-plane cursor at `dev.rubentxu.pipeline.v2.events.identity.EventCursor`). A future
 * typed `RunId` would replace the `String` here without touching the port.
 */
class EventRecordReadPortStoreAdapter(
    /**
     * The store's snapshot reader: it answers a run's tail and its page at ONE instant, and it
     * opens ONE connection to do it.
     */
    private val reader: EventRunSnapshotReader,
    /**
     * The authority that DECLARED runs, for the runs the event plane has not written to yet. The
     * lease is that authority in the product's composition. It is consulted only when the event
     * plane holds no row for the run, so a composition with no other authority passes a function
     * that answers `false`.
     */
    private val runDeclared: (String) -> Boolean,
) : EventRecordReadPort {

    override fun readRecords(
        runId: String,
        after: EventCursor?,
        query: EventQuery,
        limit: Int,
    ): EventRecordReadResult {
        if (limit <= 0) {
            return EventRecordReadResult.Refused(
                EventRecordReadRefusal.StorageError("limit must be positive, got $limit"),
            )
        }
        // The cursor's runId must match the requested runId; a mismatch
        // is a contract error (the consumer passed a cursor from a
        // different run), not a state error. Refuse BEFORE reading,
        // because it is about the cursor, not about this run's state.
        if (after != null && after.runId != runId) {
            return EventRecordReadResult.Refused(
                EventRecordReadRefusal.StorageError(
                    "cursor.runId (${after.runId}) does not match requested runId ($runId)",
                ),
            )
        }
        // The EventQuery argument is currently a no-op pass-through at
        // the underlying store. Refusing non-All queries closed here
        // makes the no-op explicit and surfaces a typed rejection the
        // consumer can act on. Query filtering belongs to whoever knows
        // what it wants, not to the store.
        if (query !is EventQuery.All) {
            val kind = when (query) {
                is EventQuery.ByKind -> "ByKind(${query.kind})"
                is EventQuery.BySource -> "BySource(${query.source})"
                is EventQuery.BySubject -> "BySubject(${query.subject})"
                is EventQuery.BySequenceRange -> "BySequenceRange(${query.fromSequence}, ${query.toSequence})"
                is EventQuery.All -> "All" // unreachable, kept for exhaustiveness
            }
            return EventRecordReadResult.Refused(
                EventRecordReadRefusal.StorageError("query not supported: $kind"),
            )
        }
        return try {
            // ONE read: the tail and the page arrive together, from one instant of the store.
            val snapshot = reader.snapshotOf(runId, after, limit)
            val tail = snapshot.tail
            when {
                // The event plane holds no row for this run, and nobody declared it either.
                tail == null && !runDeclared(runId) ->
                    EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun(runId))
                // The cursor is strictly past the tail. A run with no rows has no tail, and any
                // cursor is then strictly past it.
                after != null && (tail == null || after.lastSequence > tail) ->
                    EventRecordReadResult.Refused(
                        EventRecordReadRefusal.CursorBeyondTail(
                            runId,
                            after.lastSequence,
                            tail ?: 0L,
                        ),
                    )
                // The store is the page authority; the adapter does not re-cut. The slice's
                // records carry typed events AND undecodable rows in store sequence order; the
                // limit bound counts ROWS, not decodable events. The design rule (the page bound
                // counts rows) is satisfied by delegating, whose KDoc says exactly that.
                else -> EventRecordReadResult.Page(snapshot.page)
            }
        } catch (e: Exception) {
            EventRecordReadResult.Refused(
                EventRecordReadRefusal.StorageError(shortCause(e)),
            )
        }
    }

    /**
     * The same narrowing as `DiagnosticText.from` in `:pipeline-output`, kept here because the
     * module graph forbids sharing it: `:pipeline-output` depends on nothing but the stdlib, by an
     * explicit decision recorded in its own build file, and `:pipeline-events-store` is not
     * downstream of it. Adding that dependency to delete four duplicated lines would invert the
     * boundary that keeps the output plane independent of the event plane.
     */
    private fun shortCause(e: Throwable): String {
        val cls = e::class.simpleName ?: e.javaClass.name
        val msg = e.message?.take(120)?.replace('\r', ' ')?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
