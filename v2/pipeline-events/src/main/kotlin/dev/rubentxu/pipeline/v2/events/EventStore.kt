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
     *
     * ## This CANNOT report a row it could not read (P3-E E4c)
     *
     * `Sequence<DomainEvent>` has no case for "a durable record existed and did not decode". A
     * store whose rows can fail to decode — SQLite, where the payload is text and the `kind` may
     * come from a newer runtime — MUST NOT answer this by skipping those rows, because a skip here
     * is invisible: the caller sees a shorter stream with no hole marked, and cannot distinguish
     * absence from unreadability.
     *
     * So a store that has an [EventRecordRead.Undecodable] to report answers this **fail-closed**:
     * it throws [UndecodableEventRecordException] naming the sequence, rather than returning a
     * stream that pretends the row never existed. A caller that must tolerate a gap uses
     * [readRecords] and states its own policy; a caller that cannot is stopped at the row.
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
     * ## It REFUSES rather than shorten (P3-E E4c)
     *
     * The default is [readRecords] projected through [EventRecordSlice.requireFullyDecoded], which
     * throws [UndecodableEventRecordException] on a refusal. It used to be a cut over [eventsFor]
     * written out longhand, and that is the shape the defect took: the page bound counted DECODED
     * events, so a row that refused to decode let the next row into the page, moved
     * `nextCursor` by an amount unrelated to progress, and could report `hasMore = false` with rows
     * still unread. The short page was the tell — and it looked like the end of history.
     *
     * A store that can cut inside its own storage — an indexed table, a bounded scan — SHOULD
     * override [readRecords] with an equivalent query, and `EventSliceParityLawsTest` is what makes
     * "equivalent" mean something: same run, same cursor and same limit must produce the same
     * sequences, the same continuation and the same `hasMore`, whichever implementation answered.
     * Overriding is an optimisation, never a change of meaning.
     *
     * @param after resume strictly after this cursor's sequence; `null` starts at the beginning.
     * @param limit must be positive. It is a page bound, not a promise: a page may come back
     *   shorter than `limit` and still report [EventSlice.hasMore].
     * @throws UndecodableEventRecordException if any row in the page could not be decoded.
     */
    fun readSlice(runId: String, after: EventCursor?, limit: Int): EventSlice =
        readRecords(runId, after, limit).requireFullyDecoded()

    /**
     * The HONEST paged read: one page of durable rows, each decoded or explicitly refused.
     * P3-E E4c — Durable Read Truth.
     *
     * [readSlice] answers a narrower question — "give me the events that decoded" — and to answer
     * it honestly its page bound has to count ROWS. A store that decoded first and then counted
     * would let an unreadable row shrink the page below `limit`, corrupt `hasMore`, and move the
     * continuation cursor by an amount that has nothing to do with how far the reader got. That is
     * not a hypothetical: it is exactly what `SqliteEventStore.readSlice` did before E4c, where a
     * single malformed row in the middle of a page made the next page start early and made
     * `hasMore` lie.
     *
     * So this method is the authority for pagination, and [readSlice] is defined in terms of it
     * rather than beside it. One cut, one cursor, one `hasMore` — the enriched result travels on
     * the SAME authority, which is what keeps a refusal from becoming a second, parallel history.
     *
     * The default reads [eventsFor], which cannot produce a refusal (it yields only decoded
     * events), so for a store that has no undecodable representation this is exactly
     * [readSlice]'s rule. A store whose rows can fail to decode — SQLite, where the payload is
     * text and the kind can come from a newer runtime — MUST override this to report the refusal.
     * Not overriding it is then a lie about the store's own storage, and
     * `DurableReadTruthFitnessTest` is what makes that visible.
     *
     * @param after resume strictly after this cursor's sequence; `null` starts at the beginning.
     * @param limit bounds ROWS, not decodable events.
     */
    fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val remaining = eventsFor(runId).filter { it.sequence > afterSequence }.iterator()
        val page = ArrayList<EventRecordRead>(minOf(limit, DEFAULT_PAGE_HINT))
        var hasMore = false
        while (remaining.hasNext()) {
            if (page.size == limit) {
                hasMore = true
                break
            }
            page.add(EventRecordRead.Decoded(remaining.next()))
        }
        return EventRecordSlice(
            records = page,
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
