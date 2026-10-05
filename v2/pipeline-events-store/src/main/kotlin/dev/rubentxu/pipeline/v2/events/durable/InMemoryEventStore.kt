package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockAcquired
import dev.rubentxu.pipeline.v2.events.LockReleased
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StepAdmissionObserved
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned
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

    /** WU-LPR-105: returns the store-assigned event (explicit write-side acknowledgement). */
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
        val eventWithSequence = when (event) {
            is RunStarted -> event.copy(sequence = assignedSequence)
            is CompilationStarted -> event.copy(sequence = assignedSequence)
            is CompilationFinished -> event.copy(sequence = assignedSequence)
            is RunFinished -> event.copy(sequence = assignedSequence)
            is StageStarted -> event.copy(sequence = assignedSequence)
            is StageSkipped -> event.copy(sequence = assignedSequence)
            is PostConditionSelected -> event.copy(sequence = assignedSequence)
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
            is FileExistsChecked -> event.copy(sequence = assignedSequence)
            is ArtifactArchived -> event.copy(sequence = assignedSequence)
            is ArtifactArchiveFailed -> event.copy(sequence = assignedSequence)
            // WU-LPR-089 — core.stash/core.unstash durable cross-stage data movement
            is StashCreated -> event.copy(sequence = assignedSequence)
            is StashRestored -> event.copy(sequence = assignedSequence)
            is StashFailed -> event.copy(sequence = assignedSequence)
            // WU-LPR-090 — core.publishHTML durable HTML report publishing
            is HtmlReportPublished -> event.copy(sequence = assignedSequence)
            is HtmlReportSkipped -> event.copy(sequence = assignedSequence)
            is HtmlReportFailed -> event.copy(sequence = assignedSequence)
            // ML-R9 workflow-control events
            is DirEntered -> event.copy(sequence = assignedSequence)
            is DirExited -> event.copy(sequence = assignedSequence)
            // ML-R9 workspace-cleanup events (T-05)
            is DirDeleted -> event.copy(sequence = assignedSequence)
            is WsCleaned -> event.copy(sequence = assignedSequence)
            // ML-R9 error-handling events (T-06)
            is CatchErrorTriggered -> event.copy(sequence = assignedSequence)
            is DirectiveAdmitted -> event.copy(sequence = assignedSequence)
            is ExecutionTargetResolved -> event.copy(sequence = assignedSequence)
            is DirectiveDenied -> event.copy(sequence = assignedSequence)
            is GateEvaluated -> event.copy(sequence = assignedSequence)
            is StageMarkedUnstable -> event.copy(sequence = assignedSequence)
            // ML-R9 workflow-utility events (T-07)
            is WorkflowLoaded -> event.copy(sequence = assignedSequence)
            is WaitUntilPolled -> event.copy(sequence = assignedSequence)
            is WaitUntilCompleted -> event.copy(sequence = assignedSequence)
            is PwdResolved -> event.copy(sequence = assignedSequence)
            is UnixDetected -> event.copy(sequence = assignedSequence)
            // ML-R9 T-09 milestone events
            is MilestoneReached -> event.copy(sequence = assignedSequence)
            is MilestoneAborted -> event.copy(sequence = assignedSequence)
            // RP6-A / WU-091 §6 lock events
            is LockRequested -> event.copy(sequence = assignedSequence)
            is LockAcquired -> event.copy(sequence = assignedSequence)
            is LockReleased -> event.copy(sequence = assignedSequence)
            is LockSkipped -> event.copy(sequence = assignedSequence)
            is LockAcquireFailed -> event.copy(sequence = assignedSequence)
            // RP6-B / WU-092 §6 input events
            is InputRequested -> event.copy(sequence = assignedSequence)
            is HttpRequestStarted -> event.copy(sequence = assignedSequence)
            is HttpResponseReceived -> event.copy(sequence = assignedSequence)
            is HttpStatusRejected -> event.copy(sequence = assignedSequence)
            is HttpRequestFailed -> event.copy(sequence = assignedSequence)
            is InputProceed -> event.copy(sequence = assignedSequence)
            is InputAborted -> event.copy(sequence = assignedSequence)
            is InputDenied -> event.copy(sequence = assignedSequence)
            // ML-R9 T-10 timeout events
            is TimeoutTriggered -> event.copy(sequence = assignedSequence)
            // ML-R9 T-08 timestamps decorator events
            is TimestampsEntered -> event.copy(sequence = assignedSequence)
            is TimestampsExited -> event.copy(sequence = assignedSequence)
            // S2.5.7 / B1.2c3 — LB-01 durable-spine admission observation (WU-1)
            is StepAdmissionObserved -> event.copy(sequence = assignedSequence)
        }
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
     * Cuts the page inside the list instead of filtering a full [eventsFor] scan.
     *
     * The read holds the same monitor the write does. `eventsFor` hands out `asSequence()` over
     * the live list without taking it, so a caller iterating while the producer appends is reading
     * a `MutableList` that is being mutated — the sequence is lazy, so the hazard is at iteration
     * time, not at the call. The inherited default is safe to override precisely because it reads
     * through the same `eventsFor`; this one does not, so it takes the lock itself.
     *
     * `EventSliceParityLawsTest` is what holds this to the inherited meaning.
     */
    override fun readSlice(
        runId: String,
        after: dev.rubentxu.pipeline.v2.events.identity.EventCursor?,
        limit: Int,
    ): dev.rubentxu.pipeline.v2.events.EventSlice {
        require(limit > 0) { "limit must be positive, got $limit" }
        val afterSequence = after?.lastSequence ?: 0L
        val page = ArrayList<DomainEvent>(minOf(limit, 64))
        var hasMore = false
        store[runId]?.let { list ->
            synchronized(list) {
                for (event in list) {
                    if (event.sequence <= afterSequence) continue
                    if (page.size == limit) {
                        hasMore = true
                        break
                    }
                    page.add(event)
                }
            }
        }
        return dev.rubentxu.pipeline.v2.events.EventSlice(
            events = page,
            nextCursor = dev.rubentxu.pipeline.v2.events.identity.EventCursor(
                runId,
                page.lastOrNull()?.sequence ?: afterSequence,
            ),
            hasMore = hasMore,
        )
    }
}
