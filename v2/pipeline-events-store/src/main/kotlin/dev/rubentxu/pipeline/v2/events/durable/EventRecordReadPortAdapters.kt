package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult

/**
 * M1-A — production adapter for [EventRecordReadPort] backed by any [EventStore].
 *
 * ## Why this adapter exists
 *
 * [EventRecordReadPort] is the public read-only port. The M1 contract says
 * Fabric consumes through it, not through [EventStore] directly, because
 * [EventStore] exposes `append` and `appendAssigned` (the write side) and
 * is therefore not a safe surface for a remote consumer.
 *
 * [EventRecordReadPortStoreAdapter] is the one and only implementation
 * that backs the port. It holds a single authority for sequence,
 * cursor, and the typed-vs-undecodable cut, by delegating to
 * [EventStore.readRecords] — which the existing KDoc pins as the page
 * authority ("The store is the sequence authority, so the store is
 * where a page is decided"). The adapter adds:
 *
 *  - the **unknown-run vs empty-history distinction** ([EventRecordReadRefusal.UnknownRun]
 *    vs an empty [EventRecordReadResult.Page]). The adapter asks the
 *    store via the [runExists] callback before calling readRecords.
 *    A Sqlite-backed adapter supplies `EXISTS(SELECT 1 FROM events
 *    WHERE run_id = ?) LIMIT 1`; an in-memory adapter supplies
 *    `runId in knownRuns`. Without this distinction, a consumer
 *    cannot tell whether `readRecords` returned zero rows because the
 *    run does not exist or because the run exists but has not yet
 *    produced any event. The operator's correction #6 in the M1
 *    review (2026-10-10) made this distinction mandatory.
 *
 *  - the **cursor-past-tail distinction** ([EventRecordReadRefusal.CursorBeyondTail]
 *    vs an empty [EventRecordReadResult.Page]). The underlying
 *    [EventStore.readRecords] returns an empty page when the cursor
 *    is past the tail; the M1 contract says that must be refused
 *    closed, not silently served. The adapter asks the store via the
 *    [tailSequence] callback for the durable `MAX(sequence)` of the
 *    run and refuses when `after.lastSequence > tailSequence`. A
 *    Sqlite-backed adapter supplies
 *    `SELECT MAX(sequence) FROM events WHERE run_id = ?`; an
 *    in-memory adapter supplies the max of the in-memory list.
 *    The callback returns `null` for an empty run, in which case
 *    ANY non-null `after` is also past the tail.
 *
 *  - the **storage-error translation**. Any exception from
 *    [EventStore.readRecords] is caught and translated to
 *    [EventRecordReadRefusal.StorageError] with a short diagnostic.
 *    The port is total; a thrown exception would defeat the closed
 *    refusal hierarchy.
 *
 *  - the **[EventQuery] argument** is currently a no-op (the underlying
 *    [EventStore.readRecords] does not filter; it is the sequence
 *    authority and the cut point, not a query engine). The contract
 *    is that query filtering happens at the store layer when the store
 *    supports it. A future store-side filter implementation will plug
 *    in here without a port change.
 *
 *  - the **[after] cursor** is passed through to the store, which is
 *    the page authority. The store is responsible for the
 *    "strictly greater than" cut and the [EventRecordSlice.nextCursor]
 *    continuation.
 *
 * ## Cursors and the M1 contract
 *
 * The adapter does NOT introduce a fourth cursor vocabulary. The cursor
 * is [EventCursor] (the existing event-plane cursor at
 * `dev.rubentxu.pipeline.v2.events.identity.EventCursor`). A future
 * typed `RunId` would replace the `String` here without touching the
 * port.
 */
class EventRecordReadPortStoreAdapter(
    private val store: EventStore,
    /**
     * The store-side authority that distinguishes a known run with no
     * history yet from a run that does not exist. The adapter calls
     * this BEFORE [EventStore.readRecords] so an empty page is
     * unambiguous.
     */
    private val runExists: (String) -> Boolean,
    /**
     * The store-side authority for the durable tail sequence of a
     * known run. Returns `null` for an empty run, otherwise the
     * `MAX(sequence)` over the run. The adapter calls this when
     * [after] is non-null so a cursor past the tail can be refused
     * closed instead of silently served as an empty page.
     */
    private val tailSequence: (String) -> Long?,
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
        if (!runExists(runId)) {
            return EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun(runId))
        }
        if (after != null) {
            val tail = tailSequence(runId)
            // tail == null means the run exists but is empty. Any
            // non-null after cursor is then strictly past the tail
            // and must be refused. tail != null with after.lastSequence
            // greater than tail is the standard past-tail case.
            val requested = after.lastSequence
            if (tail == null || requested > tail) {
                return EventRecordReadResult.Refused(
                    EventRecordReadRefusal.CursorBeyondTail(runId, requested, tail ?: 0L),
                )
            }
        }
        return try {
            val slice = store.readRecords(runId, after, limit)
            // The store is the page authority; the adapter does not
            // re-cut. The slice's records carry typed events AND
            // undecodable rows in store sequence order; the limit
            // bound counts ROWS, not decodable events. The M1-A
            // design rule (the page bound counts rows) is satisfied
            // by delegating to readRecords, whose KDoc says exactly
            // that.
            EventRecordReadResult.Page(slice)
        } catch (e: Exception) {
            EventRecordReadResult.Refused(
                EventRecordReadRefusal.StorageError(shortCause(e)),
            )
        }
    }

    private fun shortCause(e: Throwable): String {
        val cls = e::class.simpleName ?: e.javaClass.name
        val msg = e.message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
