package dev.rubentxu.pipeline.v2.events

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
            is AgentResolved -> event.copy(sequence = assignedSequence)
            is ParallelBranchStarted -> event.copy(sequence = assignedSequence)
            is ParallelBranchFinished -> event.copy(sequence = assignedSequence)
            is RetryAttemptStarted -> event.copy(sequence = assignedSequence)
            is RetryAttemptFinished -> event.copy(sequence = assignedSequence)
            is TimeoutScheduled -> event.copy(sequence = assignedSequence)
            is StepFailed -> event.copy(sequence = assignedSequence)
            is EchoOutputCaptured -> event.copy(sequence = assignedSequence)
            is CredentialBound -> event.copy(sequence = assignedSequence)
            is CredentialUsed -> event.copy(sequence = assignedSequence)
            is CredentialUnbound -> event.copy(sequence = assignedSequence)
            // L5 SCM Events
            is GitCheckoutStarted -> event.copy(sequence = assignedSequence)
            is GitCheckoutCompleted -> event.copy(sequence = assignedSequence)
            is GitCheckoutFailed -> event.copy(sequence = assignedSequence)
            is GitPollChanged -> event.copy(sequence = assignedSequence)
            // L7 Jenkins File + Artefact Events (ML-R7)
            is FileWritten -> event.copy(sequence = assignedSequence)
            is FileRead -> event.copy(sequence = assignedSequence)
            is ArtifactArchived -> event.copy(sequence = assignedSequence)
            is ArtifactArchiveFailed -> event.copy(sequence = assignedSequence)
            // ML-R9 workflow-control events
            is DirEntered -> event.copy(sequence = assignedSequence)
            is DirExited -> event.copy(sequence = assignedSequence)
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
