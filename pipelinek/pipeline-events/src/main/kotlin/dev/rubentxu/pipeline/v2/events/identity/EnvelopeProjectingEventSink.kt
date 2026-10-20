package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * EVT-2 canonical-path decorator over the legacy [EventSink] facade.
 *
 * Write path: `append(DomainEvent)` → inner store (assigns sequence — the store
 * remains the sole sequence authority) → EnvelopeProjector on the event WITH its
 * assigned sequence → [EventPublisher]. Producers are untouched: they keep
 * appending raw DomainEvents and never see ResourceRef or envelopes.
 *
 * Read path lives in [InMemoryEventHistory]/[SqliteEventHistory] adapters (see
 * adapters package). This class is write-side only.
 */
class EnvelopeProjectingEventSink(
    private val inner: EventSink,
    private val publisher: EventPublisher,
) : EventSink by inner {

    override fun append(event: DomainEvent) {
        appendAssigned(event)
    }

    /**
     * WU-LPR-105: the projection consumes the store's explicit write-side
     * acknowledgement (`appendAssigned` returns the ASSIGNED event) and never
     * re-reads the read model to discover write metadata. The previous
     * `eventsFor(...) ?: event.sequence` fallback published sequence=0
     * whenever the async batched writer had not yet COMMITted the row
     * (observed as [0,0,0,0,5,6,0]) — a read-side race, now structurally
     * impossible. Store remains the sole sequence authority: no second
     * counter, no invention, no flush-per-event, no polling.
     *
     * Acknowledgement semantics: ASSIGNED (sequence decided), not
     * DURABLY_COMMITTED; durability observers keep using flush()/cursor.
     */
    override fun appendAssigned(event: DomainEvent): DomainEvent {
        val assigned = inner.appendAssigned(event)
        publisher.publish(EnvelopeProjector.project(assigned))
        return assigned
    }
}
