package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.events.identity.EventCursor

/**
 * A bounded, ordered window of TYPED history, as decided by the sequence authority.
 *
 * This is to [PipelineEventEnvelope] what [DomainEvent] is to its envelope: the thing itself, and a
 * projection of it. The envelope is identity — an occurrence, its kind, its position, its time —
 * and a consumer that needs the outcome of a stage or the name of a step cannot read it from there.
 * [EventSlice] is the page of the events themselves, so "what happened" survives the trip.
 *
 * The three fields are the whole of a resumable read, and they are the store's answer, not the
 * caller's arithmetic:
 *
 *  - [events] is at most `limit` long and strictly ascending by sequence;
 *  - [nextCursor] is the cursor to resume from — the last sequence **returned**, or the position
 *    the read started from when the page came back empty. A caller that computed it would have to
 *    know the rule and could get it wrong on the last page. It is never null: a `null` cursor
 *    already means "start at the beginning", so a nullable continuation could not distinguish the
 *    end of a run from its start. [hasMore] is what says whether the run is over;
 *  - [hasMore] is a real observation, not a guess. It is proved by looking one element past the
 *    page, which is why it is a field and not derivable from `events.size < limit`: a short page
 *    whose next element exists is possible, and a page that exactly fills the limit can still be
 *    the end.
 *
 * It never claims completeness in the other direction either: a page at the end of a run is not
 * evidence that the run finished, exactly as [EventPage] does not read the absence of a
 * `RunFinished` as "complete" (EVT-2 law).
 */
data class EventSlice(
    val events: List<DomainEvent>,
    val nextCursor: EventCursor,
    val hasMore: Boolean,
)

/**
 * Append-only event store for pipeline run events.
 */
interface EventStore {
    /**
     * Appends an event to the store. The store assigns the monotonic sequence number.
     */
    fun append(event: DomainEvent)

    /**
     * WU-LPR-105: append with EXPLICIT write-side acknowledgement.
     *
     * Returns the store-ASSIGNED event (the sequence authority's own decision),
     * so callers never discover write metadata by racing the read model
     * (`eventsFor` before COMMIT was the sequence=0 publication race).
     *
     * Semantics: the return value is ASSIGNED, not DURABLY_COMMITTED —
     * in-process projection must not gate on COMMIT (no sync-commit-per-event).
     * Durability observers use `flush()` / cursor reads (read-after-commit).
     * Default: legacy `append` + echo (stores that do not transform the event);
     * stores that assign sequences MUST override.
     */
    fun appendAssigned(event: DomainEvent): DomainEvent {
        append(event)
        return event
    }

    /**
     * Returns all events for the given run, in sequence order.
     */
    fun eventsFor(runId: String): Sequence<DomainEvent>

    /**
     * One resumable page of TYPED events, cut by this store's own sequence.
     *
     * **The store is the sequence authority, so the store is where a page is decided.** Cursor,
     * the `sequence > after` cut, limit, ordering, the continuation cursor and `hasMore` are all
     * answered here, once, by whoever owns the ordering.
     *
     * That is the point of the method existing at all. Before it, the only paged read in the Event
     * Plane was `EventTail.readAfter`, and it lived in a READER — a component that reads
     * `eventsFor` and filters it, which is not a component that can be asked "what is the order".
     * A consumer that wanted the semantics could not page, and a consumer that could page could
     * not see them; the only route was to re-cut the full read by hand and re-implement four rules
     * that this class already knows. A rule that lives only in a KDoc is a rule every consumer
     * writes again, and writes again slightly wrong.
     *
     * The default implementation is the semantics, written once, over [eventsFor]. A store that
     * can cut inside its own storage — an indexed table, a bounded scan — SHOULD override it with
     * an equivalent query, and `EventSliceParityLawsTest` is what makes "equivalent" mean
     * something: same run, same cursor and same limit must produce the same sequences, the same
     * continuation and the same `hasMore`, whichever implementation answered. Overriding is an
     * optimisation, never a change of meaning.
     *
     * @param after resume strictly after this cursor's sequence; `null` starts at the beginning.
     * @param limit must be positive. It is a page bound, not a promise: a page may come back
     *   shorter than `limit` and still report [EventSlice.hasMore].
     */
    fun readSlice(runId: String, after: EventCursor?, limit: Int): EventSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val remaining = eventsFor(runId).filter { it.sequence > afterSequence }.iterator()
        val page = ArrayList<DomainEvent>(minOf(limit, DEFAULT_PAGE_HINT))
        var hasMore = false
        while (remaining.hasNext()) {
            if (page.size == limit) {
                // One element beyond the page exists, which is the only honest way to know it.
                hasMore = true
                break
            }
            page.add(remaining.next())
        }
        return EventSlice(
            events = page,
            nextCursor = EventCursor(runId, page.lastOrNull()?.sequence ?: afterSequence),
            hasMore = hasMore,
        )
    }
}

/**
 * Only sizes the first allocation; the page is bounded by `limit`, not by this.
 *
 * A file-level constant rather than a member of [EventStore], because a `private companion object`
 * on an INTERFACE is emitted as a `public static final field` on the interface's `DefaultImpls`
 * — it reaches the published ABI of a contract module and, with it, every compatibility promise
 * that goes with being published.
 */
private const val DEFAULT_PAGE_HINT = 64

/**
 * Event sink that can also be read from.
 */
interface EventSink : EventStore

/**
 * A sink that discards all events (no-op).
 */
object NullEventSink : EventSink {
    override fun append(event: DomainEvent) = Unit
    override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
}
