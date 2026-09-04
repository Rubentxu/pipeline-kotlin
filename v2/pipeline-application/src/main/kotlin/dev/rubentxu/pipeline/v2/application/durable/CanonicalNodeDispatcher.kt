package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/** Runtime dependencies required by the canonical core step dispatcher. */
data class CanonicalRuntimeContext(
    val opId: OpId,
    val runId: String,
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

    suspend fun dispatch(node: StepNode, context: CanonicalRuntimeContext): StepOutcome =
        when (node.pluginStepId.value) {
            "core.sh" -> shellDispatcher.dispatch(node, context.shellContext())
            "core.echo" -> echoDispatcher.dispatch(node, context.echoContext())
            "core.error" -> errorDispatcher.dispatch(node, context.errorContext())
            "core.sleep" -> sleepDispatcher.dispatch(node, context.sleepContext())
            else -> throw IllegalArgumentException("Unsupported canonical core step '${node.pluginStepId.value}'")
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
}
