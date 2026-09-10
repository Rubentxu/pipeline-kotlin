package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageStarted
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

/**
 * The SINGLE authority that projects [DomainEvent]s into [PipelineEventEnvelope]s
 * (EVT-1/EVT-2 blast-radius rule: producers are untouched; this is the only place
 * where ResourceRefs are derived from domain facts).
 *
 * Derivation is deterministic and TOTAL over the current closed event family:
 * subject resolution uses only identity fields present in the event
 * (stageIndex+stepIndex → STEP; stageIndex → STAGE; stepIndex without stageIndex →
 * RUN ref per frozen EVT-1 law for StepFailed; no identity fields → RUN ref).
 * Branch names, completion ordering, occurrence timestamps and sequence numbers
 * never influence identity. Sequence is transported as a projection of the
 * store-assigned value; the store remains the sequence authority.
 */
object EnvelopeProjector {

    /**
     * Projects a domain event into its envelope. `source` is the RUN ref of the
     * emitting run; `subject` is the most specific affected resource derivable
     * from identity fields (see class doc).
     */
    fun project(event: DomainEvent): PipelineEventEnvelope {
        val runRef = ResourceRefs.run(event.runId)
        val subject: ResourceRef = subjectOf(event, runRef)
        return PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(source = runRef, id = EventId(event.eventId)),
            kind = event.kind,
            occurredAt = event.occurredAt,
            sequence = event.sequence,
            subject = subject,
            causation = null,
            correlation = null,
        )
    }

    /** Exhaustive subject resolution over the closed DomainEvent family. */
    private fun subjectOf(event: DomainEvent, runRef: ResourceRef): ResourceRef = when (event) {
        // STEP subject: stage + step identity
        is StepStarted -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
        is StepFinished -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
        is RetryAttemptStarted -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
        is RetryAttemptFinished -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
        is TimeoutScheduled ->
            if (event.stageIndex != null && event.stepIndex != null) {
                ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
            } else {
                runRef
            }
        is StepAdmissionObserved -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
        // STAGE subject
        is StageStarted -> ResourceRefs.stage(event.runId, event.stageIndex)
        is StageFinished -> ResourceRefs.stage(event.runId, event.stageIndex)
        is ParallelBranchStarted -> ResourceRefs.stage(event.runId, event.parentStageIndex)
        is ParallelBranchFinished -> ResourceRefs.stage(event.runId, event.parentStageIndex)
        // RUN subject: stepIndex exists but no stage identity (frozen EVT-1 law)
        is StepFailed -> runRef
        is EchoOutputCaptured -> runRef
        is CredentialUsed -> runRef
        // RUN subject: run lifecycle / no finer stable identity in the event
        is RunStarted,
        is CompilationStarted,
        is CompilationFinished,
        is RunFinished,
        is AgentResolved,
        is CredentialBound,
        is CredentialUnbound,
        is GitCheckoutStarted,
        is GitCheckoutCompleted,
        is GitCheckoutFailed,
        is GitPollChanged,
        is FileWritten,
        is FileRead,
        is ArtifactArchived,
        is ArtifactArchiveFailed,
        is DirEntered,
        is DirExited,
        is DirDeleted,
        is WsCleaned,
        is CatchErrorTriggered,
        is StageMarkedUnstable,
        is WorkflowLoaded,
        is WaitUntilPolled,
        is WaitUntilCompleted,
        is PwdResolved,
        is UnixDetected,
        is MilestoneReached,
        is MilestoneAborted,
        is TimeoutTriggered,
        is TimestampsEntered,
        is TimestampsExited,
        -> runRef
    }.let { it }
}

/**
 * Typed failure when an event kind has no identity derivation rule. Retained for
 * forward compatibility: adding a new DomainEvent kind without updating
 * [EnvelopeProjector] must surface as a compile error (exhaustive `when`), and any
 * dynamic miss must fail closed rather than degrade identity.
 */
class UnprojectableEventException(message: String) : IllegalStateException(message)
