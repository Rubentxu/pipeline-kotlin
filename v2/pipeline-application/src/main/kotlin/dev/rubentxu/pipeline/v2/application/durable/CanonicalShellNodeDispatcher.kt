package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
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
        // C2: Use typed shell command with structured failure mapping
        // Evidence-driven dispatcher: removed require on returnStdout for additive compatibility
        return ShExecution.runShellCommandTyped(
            command = DurableShellCommand(command.command),
            opId = context.opId,
            runId = context.runId,
            stageIndex = context.stageIndex,
            stepIndex = context.stepIndex,
            shOptions = context.shOptions,
            controlDirRoot = context.controlDirRoot,
            eventSink = context.eventSink,
        )
    }
}
