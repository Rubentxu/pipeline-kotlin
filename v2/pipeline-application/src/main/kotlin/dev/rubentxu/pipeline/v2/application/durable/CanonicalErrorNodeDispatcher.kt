package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.error as executeError

/** Runtime dependencies required to dispatch one canonical error node. */
data class CanonicalErrorDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
)

/** Dispatches canonical `core.error` nodes through the existing failure event path. */
class CanonicalErrorNodeDispatcher {
    fun dispatch(node: StepNode, context: CanonicalErrorDispatchContext): StepOutcome {
        val command = CanonicalCoreStepDecoder.decode(node) as? CanonicalCoreStepCommand.Error
            ?: throw IllegalArgumentException("CanonicalErrorNodeDispatcher only accepts core.error nodes")

        return try {
            executeError(
                StepContext(runId = context.runId),
                command.message,
                command.failureKind,
                context.eventSink,
                context.stepIndex,
            )
        } catch (_: IllegalStateException) {
            StepOutcome.Failure(PipelineFailure(command.failureKind, command.message))
        }
    }
}
