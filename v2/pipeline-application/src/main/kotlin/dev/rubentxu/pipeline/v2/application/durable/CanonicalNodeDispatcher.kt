package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/** Runtime dependencies required by the canonical core step dispatcher. */
data class CanonicalRuntimeContext(
    val opId: OpId,
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val shOptions: ShOptions,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
)

/** Dispatches the supported canonical core nodes through their durable runtime paths. */
class CanonicalNodeDispatcher {
    private val shellDispatcher = CanonicalShellNodeDispatcher()
    private val echoDispatcher = CanonicalEchoNodeDispatcher()
    private val errorDispatcher = CanonicalErrorNodeDispatcher()
    private val sleepDispatcher = CanonicalSleepNodeDispatcher()
    private val writeFileDispatcher = CanonicalWriteFileNodeDispatcher()
    private val emitEventDispatcher = CanonicalEmitEventNodeDispatcher()

    suspend fun dispatch(command: CanonicalCoreStepCommand, context: CanonicalRuntimeContext): StepOutcome =
        when (command) {
            is CanonicalCoreStepCommand.Shell -> shellDispatcher.dispatch(command, context.shellContext())
            is CanonicalCoreStepCommand.Echo -> echoDispatcher.dispatch(command, context.echoContext())
            is CanonicalCoreStepCommand.Error -> errorDispatcher.dispatch(command, context.errorContext())
            is CanonicalCoreStepCommand.Sleep -> sleepDispatcher.dispatch(command, context.sleepContext())
            is CanonicalCoreStepCommand.WriteFile -> writeFileDispatcher.dispatch(command, context.writeFileContext())
            is CanonicalCoreStepCommand.EmitEvent -> emitEventDispatcher.dispatch(command, context.emitEventContext())
        }

    private fun CanonicalRuntimeContext.shellContext() = CanonicalShellDispatchContext(
        opId = opId,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.echoContext() = CanonicalEchoDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.errorContext() = CanonicalErrorDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.sleepContext() = CanonicalSleepDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.writeFileContext() = CanonicalWriteFileDispatchContext(
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.emitEventContext() = CanonicalEmitEventDispatchContext(
        runId = runId,
        stageName = stageName,
        eventSink = eventSink,
    )
}
