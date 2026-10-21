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
 * Dispatchers return a [StepOutcome]; they do not publish lifecycle failures.
 * Unexpected exceptions deliberately propagate so engine and scope invariants
 * cannot be reclassified as ordinary step failures.
*/
class StepExecutionBoundary(
    private val eventSink: EventSink,
) {
    suspend fun execute(
        context: StepLifecycleContext,
        body: suspend () -> CommonExecutionResult,
    ): CommonExecutionResult {
        eventSink.append(context.stepStarted())
        // The verdict, once the boundary has one. It is the SAME `StepOutcome` the body returned and
        // the same one the `StepFailed` event is written from, so the terminal event and the failure
        // evidence cannot disagree about what happened.
        var verdict: StepOutcome? = null
        try {
            return body().also { result ->
                val outcome = result.outcome
                verdict = outcome
                if (outcome is StepOutcome.Failure) {
                    eventSink.append(context.stepFailed(outcome))
                }
            }
        } catch (exception: PipelineStepException) {
            val outcome = StepOutcome.Failure(exception.failure)
            verdict = outcome
            eventSink.append(context.stepFailed(outcome))
            return CommonExecutionResult(outcome = outcome, encodedOutput = null)
        } finally {
            // The terminal event is emitted for a failed step too, so it must carry the verdict: a
            // consumer that reads "the step finished" cannot tell a success from a failure without
            // it. When the body blew up with something that is not a step failure, there is no
            // verdict, and `null` says exactly that — unknown, not success.
            eventSink.append(context.stepFinished(verdict))
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
        // The stage the failure belongs to. The boundary knows it, and a consumer that has to
        // attribute the failure to a stage must not have to join this event with another one to
        // find out: a step index means a different step in each stage.
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        stepName = stepName,
        stepType = stepType,
        failureKind = outcome.failure.kind,
        message = outcome.failure.message,
    )

    /**
     * The terminal event of the step, carrying the verdict when there is one.
     *
     * [verdict] is `null` only when the step's execution ended without a decided outcome, and the
     * event then says nothing about the result: that is the honest reading of "unknown".
     */
    private fun StepLifecycleContext.stepFinished(verdict: StepOutcome?) = StepFinished(
        eventId = UUID.randomUUID().toString(),
        runId = runId,
        sequence = 0L,
        occurredAt = Instant.now(),
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        stepName = stepName,
        stepType = stepType,
        outcome = verdict?.wireToken,
    )
}
