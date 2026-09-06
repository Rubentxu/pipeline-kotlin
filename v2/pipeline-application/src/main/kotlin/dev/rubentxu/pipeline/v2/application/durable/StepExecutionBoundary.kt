package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import java.util.UUID

/**
 * Identity and presentation data required for one emitted step lifecycle.
 *
 * This is deliberately distinct from domain's `StepExecutionContext`, which
 * is the typed `StepDispatcher` contract. This application value supplies
 * event coordinates only; it must not become a second dispatcher context.
 */
data class StepLifecycleContext(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val stepName: String,
    val stepType: String,
)

/**
 * The single lifecycle owner for a canonical step execution.
 *
 * Dispatchers return a [StepOutcome]; they do not publish lifecycle failures.
 * Unexpected exceptions deliberately propagate so engine and scope invariants
 * cannot be reclassified as ordinary step failures.
 */
class StepExecutionBoundary(
    private val eventSink: EventSink,
) {
    suspend fun execute(
        context: StepLifecycleContext,
        body: suspend () -> StepOutcome,
    ): StepOutcome {
        eventSink.append(context.stepStarted())
        try {
            return body().also { outcome ->
                if (outcome is StepOutcome.Failure) {
                    eventSink.append(context.stepFailed(outcome))
                }
            }
        } catch (exception: PipelineStepException) {
            val outcome = StepOutcome.Failure(exception.failure)
            eventSink.append(context.stepFailed(outcome))
            return outcome
        } finally {
            eventSink.append(context.stepFinished())
        }
    }

    private fun StepLifecycleContext.stepStarted() = StepStarted(
        eventId = UUID.randomUUID().toString(),
        runId = runId,
        sequence = 0L,
        occurredAt = Instant.now(),
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        stepName = stepName,
        stepType = stepType,
    )

    private fun StepLifecycleContext.stepFailed(outcome: StepOutcome.Failure) = StepFailed(
        eventId = UUID.randomUUID().toString(),
        runId = runId,
        sequence = 0L,
        occurredAt = Instant.now(),
        stepIndex = stepIndex,
        stepName = stepName,
        stepType = stepType,
        failureKind = outcome.failure.kind,
        message = outcome.failure.message,
    )

    private fun StepLifecycleContext.stepFinished() = StepFinished(
        eventId = UUID.randomUUID().toString(),
        runId = runId,
        sequence = 0L,
        occurredAt = Instant.now(),
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        stepName = stepName,
        stepType = stepType,
    )
}
