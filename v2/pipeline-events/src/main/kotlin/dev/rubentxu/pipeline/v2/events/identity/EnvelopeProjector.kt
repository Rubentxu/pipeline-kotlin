package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted

/**
 * The SINGLE authority that projects [DomainEvent]s into [PipelineEventEnvelope]s
 * (EVT-1 blast-radius rule: producers are untouched; this is the only place where
 * ResourceRefs are derived from domain facts).
 *
 * Derivation is deterministic: refs depend only on identity fields present in the
 * event (runId, stageIndex, stepIndex). Branch names, completion ordering,
 * occurrence timestamps and sequence numbers never influence identity.
 */
object EnvelopeProjector {

    /**
     * Projects a domain event into its envelope.
     *
     * `source` is the emitting run context (RUN ref) for run-scoped events.
     * `subject` is the most specific affected resource:
     * - STEP ref for step-scoped events (StepStarted/Finished/Failed),
     * - STAGE ref for stage-scoped events (StageStarted/Finished),
     * - the RUN ref itself for run lifecycle events (RunStarted/RunFinished).
     *
     * Fail-closed: an event whose kind carries no derivation rule raises
     * [UnprojectableEventException] rather than degrading identity.
     */
    fun project(event: DomainEvent): PipelineEventEnvelope {
        val runRef = ResourceRefs.run(event.runId)
        val subject: ResourceRef = when (event) {
            is StepStarted -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
            is StepFinished -> ResourceRefs.step(event.runId, event.stageIndex, event.stepIndex)
            is StepFailed -> runRef // step-scoped failure carries no stageIndex; degrade NOT allowed -> run-level subject
            is StageStarted -> ResourceRefs.stage(event.runId, event.stageIndex)
            is StageFinished -> ResourceRefs.stage(event.runId, event.stageIndex)
            is RunStarted, is RunFinished -> runRef
            is ParallelBranchStarted -> ResourceRefs.stage(event.runId, event.parentStageIndex)
            is ParallelBranchFinished -> ResourceRefs.stage(event.runId, event.parentStageIndex)
            else -> throw UnprojectableEventException(
                "No identity derivation rule for event kind '${event.kind}'. " +
                    "Extend EnvelopeProjector fail-closed; never degrade identity.",
            )
        }
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
}

/**
 * Typed failure when an event kind has no identity derivation rule.
 * Fail-closed by design (spec R3).
 */
class UnprojectableEventException(message: String) : IllegalStateException(message)
