package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory event store.
*/
class InMemoryEventStore : EventSink, EventRunSnapshotReader {

    private val store = ConcurrentHashMap<String, MutableList<DomainEvent>>()
    private val sequenceCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun append(event: DomainEvent) {
        appendAssigned(event)
    }

    /** Returns the store-assigned event (explicit write-side acknowledgement). */
    override fun appendAssigned(event: DomainEvent): DomainEvent {
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
        val eventWithSequence = event.withSequence(assignedSequence)
        store.computeIfAbsent(event.runId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(eventWithSequence)
            }
        }
        return eventWithSequence
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        return store[runId]?.asSequence() ?: emptySequence()
    }

    /**
     * The run's tail and the page after the cursor, read at ONE instant.
     *
     * ## The same law the durable store obeys
     *
     * The tail is the maximum sequence of the run's rows, and the page is cut from the same rows
     * under the same monitor the write takes, so the two cannot disagree: an append that lands
     * between them would have to break the monitor, and the monitor is the same one the writer
     * holds while it appends.
     *
     * The tail is read under the monitor too, which it did not used to be. A tail read OUTSIDE the
     * monitor walked a list that another thread was appending to, and a walk over a list being
     * mutated is not a fact about the list: it can fail with a concurrent modification, or return
     * a maximum that was true for half of the walk.
     *
     * A run with a key but no rows cannot happen here — [appendAssigned] always inserts, and the
     * key is created on the first append — so the empty-run case reduces to a missing key.
     */
    override fun snapshotOf(runId: String, after: EventCursor?, limit: Int): EventRunSnapshot {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val list = store[runId] ?: return EventRunSnapshot(tail = null, page = emptyPage(runId, afterSequence))
        return synchronized(list) {
            EventRunSnapshot(
                tail = list.maxOfOrNull { it.sequence },
                page = cutPage(list, runId, afterSequence, limit),
            )
        }
    }

    /**
     * Cuts the page inside the list instead of filtering a full [eventsFor] scan.
     * The read holds the same monitor the write does. `eventsFor` hands out `asSequence()` over
     * the live list without taking it, so a caller iterating while the producer appends is reading
     * a `MutableList` that is being mutated — the sequence is lazy, so the hazard is at iteration
     * time, not at the call. The inherited default is safe to override precisely because it reads
     * through the same `eventsFor`; this one does not, so it takes the lock itself.
     * ## Every row here decodes, and that is a property of the TYPE, not an assumption (E4c)
     * The store holds `DomainEvent` objects, so there is no wire payload to misread and no `kind`
     * this binary might not know: an undecodable row is not a case that can occur here, and every
     * record is therefore [EventRecordRead.Decoded]. That is why this override does not need a
     * refusal branch — not because it forgot one.
     * It is also why this store cannot disagree with the SQLite one about a well-formed run: there
     * is no refusal path here that could answer differently. The divergence only becomes observable
     * on a malformed row, which is a property of SQLite's storage and not of this type.
     * ## The page is cut in ONE place
     * [readRecords] and [snapshotOf] are two doors onto the same cut, and they cut it with the
     * same code ([cutPage]), so the two cannot drift apart.
     * ## What holds this to the inherited meaning, and what does not (no test does)
     * The cross-store equivalence is a per-subclass obligation with no central witness; see
     * [dev.rubentxu.pipeline.v2.events.EventStore.readRecords]. What holds the meaning HERE is
     * structural rather than witnessed: there is no undecodable case to disagree about, and the
     * page/cursor law lives in the inherited default rather than in a second copy of it.
    */
    override fun readRecords(
        runId: String,
        after: EventCursor?,
        limit: Int,
    ): EventRecordSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val list = store[runId] ?: return emptyPage(runId, afterSequence)
        return synchronized(list) { cutPage(list, runId, afterSequence, limit) }
    }

    /**
     * The cut, over a list whose monitor the caller already holds.
     */
    private fun cutPage(
        list: List<DomainEvent>,
        runId: String,
        afterSequence: Long,
        limit: Int,
    ): EventRecordSlice {
        val page = ArrayList<EventRecordRead>(minOf(limit, 64))
        var hasMore = false
        for (event in list) {
            if (event.sequence <= afterSequence) continue
            if (page.size == limit) {
                hasMore = true
                break
            }
            page.add(EventRecordRead.Decoded(event))
        }
        return EventRecordSlice(
            records = page,
            nextCursor = EventCursor(runId, page.lastOrNull()?.sequence ?: afterSequence),
            hasMore = hasMore,
        )
    }

    /**
     * The page of a run this store holds no rows for: no records, and a cursor that does not move,
     * because there was nothing past it to begin with.
     */
    private fun emptyPage(runId: String, afterSequence: Long): EventRecordSlice =
        EventRecordSlice(
            records = emptyList(),
            nextCursor = EventCursor(runId, afterSequence),
            hasMore = false,
        )
}
