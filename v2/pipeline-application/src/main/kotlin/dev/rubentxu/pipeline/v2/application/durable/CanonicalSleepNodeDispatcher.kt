package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.sleep

/** Runtime dependencies required to dispatch one canonical sleep node. */
data class CanonicalSleepDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
)

/** Dispatches canonical `core.sleep` nodes through the existing timing path. */
class CanonicalSleepNodeDispatcher {
    fun dispatch(command: CanonicalCoreStepCommand.Sleep, context: CanonicalSleepDispatchContext): StepOutcome {
        sleep(StepContext(runId = context.runId), command.seconds, context.eventSink, context.stepIndex)
        return StepOutcome.Success
    }
}
