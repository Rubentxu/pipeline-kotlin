package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.echo

/** Runtime dependencies required to dispatch one canonical echo node. */
data class CanonicalEchoDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
)

/** Dispatches canonical `core.echo` nodes through the existing event path. */
class CanonicalEchoNodeDispatcher {
    fun dispatch(node: StepNode, context: CanonicalEchoDispatchContext): StepOutcome {
        val command = CanonicalCoreStepDecoder.decode(node) as? CanonicalCoreStepCommand.Echo
            ?: throw IllegalArgumentException("CanonicalEchoNodeDispatcher only accepts core.echo nodes")

        echo(StepContext(runId = context.runId), command.text, context.eventSink, context.stepIndex)
        return StepOutcome.Success
    }
}
