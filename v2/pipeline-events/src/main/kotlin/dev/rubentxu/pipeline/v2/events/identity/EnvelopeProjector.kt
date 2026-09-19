package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
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
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
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

object EnvelopeProjector {

    /**
     * Projects a domain event into its envelope using the **legacy** path
     * with no [ProviderProvenance] projection. Kept stable for all
     * existing call sites that do not pass a provider seam.
     *
     * Equivalent to `project(event, null)`.
     */
    fun project(event: DomainEvent): PipelineEventEnvelope = project(event, null)

    /**
     * Projects a domain event into its envelope, optionally attaching a
     * [ProviderProvenance] audit projection when [providerLookup] is
     * supplied and the event is Step-emitted by a Step registered with
     * provider metadata.
     *
     * The lookup is O(1) (the registry's `providerOf(key)`); the
     * projector does NOT scan and does NOT branch on [dev.rubentxu.pipeline.v2.domain.step.Delivery]
     * (delivery is metadata, never a verdict).
     */
    fun project(event: DomainEvent, providerLookup: ((PluginStepId) -> StepProviderMetadata?)?): PipelineEventEnvelope {
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
            provenance = provenanceOf(event, providerLookup),
        )
    }

    /**
     * Resolves the audit [ProviderProvenance] for Step-emitted events when
     * a [providerLookup] seam is available. Returns `null` otherwise
     * (legacy path, run-lifecycle events, file-IO events).
     */
    private fun provenanceOf(
        event: DomainEvent,
        providerLookup: ((PluginStepId) -> StepProviderMetadata?)?,
    ): ProviderProvenance? {
        val lookup = providerLookup ?: return null
        val stepKey: PluginStepId = when (event) {
            is StepStarted -> PluginStepId(event.stepType)
            is StepFinished -> PluginStepId(event.stepType)
            is StepFailed -> PluginStepId(event.stepType)
            is RetryAttemptStarted -> PluginStepId(event.stepType)
            is RetryAttemptFinished -> PluginStepId(event.stepType)
            is StepAdmissionObserved -> PluginStepId(event.stepKey)
            is TimeoutScheduled ->
                if (event.stepType != null) PluginStepId(event.stepType) else return null
            else -> return null
        }
        val provider = lookup(stepKey) ?: return null
        return provider.toProvenance()
    }

    /** Adapter: [StepProviderMetadata] → [ProviderProvenance] (C8 audit projection). */
    private fun StepProviderMetadata.toProvenance(): ProviderProvenance {
        val ns = plugin.segments.firstOrNull() ?: ""
        val identity = if (plugin.segments.size >= 3) {
            // segments = [namespace, "plugin", identity, ...]
            plugin.segments.drop(2).joinToString("/")
        } else {
            ""
        }
        return ProviderProvenance(
            pluginPublisher = publisher,
            pluginNamespace = ns,
            pluginIdentity = identity,
            releaseVersion = release.version.toString(),
            releaseDigest = release.digest.value,
            families = families.map { it.name }.toSortedSet(),
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
        is FileExistsChecked,
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
