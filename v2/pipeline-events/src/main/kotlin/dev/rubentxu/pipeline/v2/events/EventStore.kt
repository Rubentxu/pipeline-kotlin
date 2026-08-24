package dev.rubentxu.pipeline.v2.events

/**
 * Append-only event store for pipeline run events.
 */
interface EventStore {
    /**
     * Appends an event to the store. The store assigns the monotonic sequence number.
     */
    fun append(event: DomainEvent)

    /**
     * Returns all events for the given run, in sequence order.
     */
    fun eventsFor(runId: String): Sequence<DomainEvent>
}

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
