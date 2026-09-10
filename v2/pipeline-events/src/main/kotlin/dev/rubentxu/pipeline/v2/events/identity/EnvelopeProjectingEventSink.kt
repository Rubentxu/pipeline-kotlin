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
        inner.append(event)
        // Re-read the stored fact so the projected envelope carries the
        // store-assigned sequence (projection, never invention). The stored
        // events include the one just appended.
        val assigned = inner.eventsFor(event.runId)
            .lastOrNull { it.eventId == event.eventId }?.sequence ?: event.sequence
        publisher.publish(EnvelopeProjector.project(copyWithSequence(event, assigned)))
    }

    /**
     * Returns an event equal to [event] but with the store-assigned sequence.
     * Uses the family's `copy(sequence=...)` per kind via the same exhaustive
     * pattern the stores already use for assignment (see SqliteEventStore).
     */
    private fun copyWithSequence(event: DomainEvent, sequence: Long): DomainEvent =
        if (event.sequence == sequence) event else SequenceAssigner.withSequence(event, sequence)
}
