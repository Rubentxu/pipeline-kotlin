package com.pipeline.v2.events

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory event store.
 */
class InMemoryEventStore : EventSink {

    private val store = ConcurrentHashMap<String, MutableList<DomainEvent>>()
    private val sequenceCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun append(event: DomainEvent) {
        store.computeIfAbsent(event.runId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(event)
            }
        }
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        return store[runId]?.asSequence() ?: emptySequence()
    }
}
