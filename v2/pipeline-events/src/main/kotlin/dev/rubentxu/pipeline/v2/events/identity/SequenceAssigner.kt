package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
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
 * Sequence re-stamping over the closed DomainEvent family, mirroring the
 * exhaustive copy pattern used by the stores at assignment time. Keeps the
 * projector honest: the envelope always carries the STORE-assigned sequence.
 */
internal object SequenceAssigner {
    fun withSequence(event: DomainEvent, sequence: Long): DomainEvent = when (event) {
        is AgentResolved -> event.copy(sequence = sequence)
        is ArtifactArchived -> event.copy(sequence = sequence)
        is ArtifactArchiveFailed -> event.copy(sequence = sequence)
        is CatchErrorTriggered -> event.copy(sequence = sequence)
        is CompilationFinished -> event.copy(sequence = sequence)
        is CompilationStarted -> event.copy(sequence = sequence)
        is CredentialBound -> event.copy(sequence = sequence)
        is CredentialUnbound -> event.copy(sequence = sequence)
        is CredentialUsed -> event.copy(sequence = sequence)
        is DirDeleted -> event.copy(sequence = sequence)
        is DirEntered -> event.copy(sequence = sequence)
        is DirExited -> event.copy(sequence = sequence)
        is EchoOutputCaptured -> event.copy(sequence = sequence)
        is FileRead -> event.copy(sequence = sequence)
        is FileWritten -> event.copy(sequence = sequence)
        is GitCheckoutCompleted -> event.copy(sequence = sequence)
        is GitCheckoutFailed -> event.copy(sequence = sequence)
        is GitCheckoutStarted -> event.copy(sequence = sequence)
        is GitPollChanged -> event.copy(sequence = sequence)
        is MilestoneAborted -> event.copy(sequence = sequence)
        is MilestoneReached -> event.copy(sequence = sequence)
        is ParallelBranchFinished -> event.copy(sequence = sequence)
        is ParallelBranchStarted -> event.copy(sequence = sequence)
        is PwdResolved -> event.copy(sequence = sequence)
        is RetryAttemptFinished -> event.copy(sequence = sequence)
        is RetryAttemptStarted -> event.copy(sequence = sequence)
        is RunFinished -> event.copy(sequence = sequence)
        is RunStarted -> event.copy(sequence = sequence)
        is StageFinished -> event.copy(sequence = sequence)
        is StageMarkedUnstable -> event.copy(sequence = sequence)
        is StageStarted -> event.copy(sequence = sequence)
        is StepAdmissionObserved -> event.copy(sequence = sequence)
        is StepFailed -> event.copy(sequence = sequence)
        is StepFinished -> event.copy(sequence = sequence)
        is StepStarted -> event.copy(sequence = sequence)
        is TimeoutScheduled -> event.copy(sequence = sequence)
        is TimeoutTriggered -> event.copy(sequence = sequence)
        is TimestampsEntered -> event.copy(sequence = sequence)
        is TimestampsExited -> event.copy(sequence = sequence)
        is UnixDetected -> event.copy(sequence = sequence)
        is WaitUntilCompleted -> event.copy(sequence = sequence)
        is WaitUntilPolled -> event.copy(sequence = sequence)
        is WorkflowLoaded -> event.copy(sequence = sequence)
        is WsCleaned -> event.copy(sequence = sequence)
    }
}
