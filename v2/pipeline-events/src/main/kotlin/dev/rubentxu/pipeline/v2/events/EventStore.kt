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
