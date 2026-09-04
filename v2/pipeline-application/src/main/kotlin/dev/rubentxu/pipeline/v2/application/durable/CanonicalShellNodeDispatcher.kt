package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/** Runtime dependencies required to dispatch one canonical shell node. */
data class CanonicalShellDispatchContext(
    val opId: OpId,
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val shOptions: ShOptions,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
)

/** Dispatches canonical `core.sh` nodes through the existing durable shell path. */
class CanonicalShellNodeDispatcher {
    suspend fun dispatch(command: CanonicalCoreStepCommand.Shell, context: CanonicalShellDispatchContext): StepOutcome {
        // When returnStdout=true, enable captureStdout so the durable shell executor
        // tees stdout to output.txt (the typed result channel for returnStdout mode).
        val effectiveOptions = if (command.returnStdout) {
            context.shOptions.copy(captureStdout = true)
        } else {
            context.shOptions
        }

        return ShExecution.runShellCommandTyped(
            command = DurableShellCommand(command.command),
            opId = context.opId,
            runId = context.runId,
            stageIndex = context.stageIndex,
            stepIndex = context.stepIndex,
            shOptions = effectiveOptions,
            controlDirRoot = context.controlDirRoot,
            eventSink = context.eventSink,
        )
    }
}
