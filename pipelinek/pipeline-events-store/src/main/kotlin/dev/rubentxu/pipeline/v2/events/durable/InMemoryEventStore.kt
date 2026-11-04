package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory event store.
*/
class InMemoryEventStore : EventSink {

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
     * Authoritatively answer "has this run ever been written to".
     * The [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort]
     * adapter needs to distinguish a run that does not exist from a
     * run that exists but has not yet produced any event. The in-memory
     * authority is the map key: a runId with no key has never been
     * `append`ed. (Note: a runId with a key but an empty list cannot
     * happen in the in-memory store — `append` always inserts, and the
     * map is created on first `append`.)
*/
    fun hasRun(runId: String): Boolean = store.containsKey(runId)

    /**
     * The durable `MAX(sequence)` of the in-memory list, or
     * `null` if the run has no rows.
     * Mirrors [dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore.tailSequence]
     * so the [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort]
     * adapter can refuse a cursor past the tail uniformly across the
     * two stores. A run with a key but an empty list cannot happen
     * in this store — `append` always inserts and the map is created
     * on first `append` — so the empty-run case reduces to a missing
     * key here.
*/
    fun tailSequence(runId: String): Long? =
        store[runId]?.maxOfOrNull { it.sequence }

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
     * ## What holds this to the inherited meaning, and what does not (no test does)
     * The cross-store equivalence is a per-subclass obligation with no central witness; see
     * [dev.rubentxu.pipeline.v2.events.EventStore.readRecords]. What holds the meaning HERE is
     * structural rather than witnessed: there is no undecodable case to disagree about, and the
     * page/cursor law lives in the inherited default rather than in a second copy of it.
*/
    override fun readRecords(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventRecordSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val page = ArrayList<dev.rubentxu.pipeline.v2.events.EventRecordRead>(minOf(limit, 64))
        var hasMore = false
        store[runId]?.let { list ->
            synchronized(list) {
                for (event in list) {
                    if (event.sequence <= afterSequence) continue
                    if (page.size == limit) {
                        hasMore = true
                        break
                    }
                    page.add(dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded(event))
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
}
