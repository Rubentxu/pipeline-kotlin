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
        val counter = sequenceCounters.computeIfAbsent(event.runId) { AtomicLong() }
        val assignedSequence = if (event.sequence == 0L) {
            counter.incrementAndGet()
        } else {
            // Enforce monotonic: if caller passes a non-zero sequence, use it but also
            // advance the counter so the next auto-assigned sequence is still monotonic.
            val current = counter.get()
            if (event.sequence > current) {
                counter.set(event.sequence)
            }
            event.sequence
        }
        val eventWithSequence = when (event) {
            is RunStarted -> event.copy(sequence = assignedSequence)
            is CompilationStarted -> event.copy(sequence = assignedSequence)
            is CompilationFinished -> event.copy(sequence = assignedSequence)
            is RunFinished -> event.copy(sequence = assignedSequence)
            is StageStarted -> event.copy(sequence = assignedSequence)
            is StageFinished -> event.copy(sequence = assignedSequence)
            is StepStarted -> event.copy(sequence = assignedSequence)
            is StepFinished -> event.copy(sequence = assignedSequence)
        }
        store.computeIfAbsent(event.runId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(eventWithSequence)
            }
        }
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        return store[runId]?.asSequence() ?: emptySequence()
    }
}
