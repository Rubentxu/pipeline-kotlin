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
    // S2-A4 / G5: emitEventDispatcher removed (LEGACY_REMOVED) — core.emit.event executes
    // exclusively through CoreEmitEventStep via the registry.
    private val milestoneDispatcher = CanonicalMilestoneNodeDispatcher()
    private val deleteDirDispatcher = CanonicalDeleteDirNodeDispatcher()
    private val cleanWsDispatcher = CanonicalCleanWsNodeDispatcher()
    private val loadDispatcher = CanonicalLoadNodeDispatcher()
    private val pwdDispatcher = CanonicalPwdNodeDispatcher()
    private val isUnixDispatcher = CanonicalIsUnixNodeDispatcher()
    private val waitUntilDispatcher = CanonicalWaitUntilNodeDispatcher()
    private val archiveArtifactsDispatcher = CanonicalArchiveArtifactsNodeDispatcher()

    suspend fun dispatch(command: CanonicalCoreStepCommand, context: CanonicalRuntimeContext): StepOutcome =
        when (command) {
            // S2-A4 / G5: EmitEvent when-branch removed (LEGACY_REMOVED).
            is CanonicalCoreStepCommand.Milestone -> milestoneDispatcher.dispatch(command, context.milestoneContext())
            is CanonicalCoreStepCommand.DeleteDir -> deleteDirDispatcher.dispatch(command, context.deleteDirContext())
            is CanonicalCoreStepCommand.CleanWs -> cleanWsDispatcher.dispatch(command, context.cleanWsContext())
            is CanonicalCoreStepCommand.Load -> loadDispatcher.dispatch(command, context.loadContext())
            is CanonicalCoreStepCommand.Pwd -> pwdDispatcher.dispatch(command, context.pwdContext())
            is CanonicalCoreStepCommand.IsUnix -> isUnixDispatcher.dispatch(command, context.isUnixContext())
            // waitUntil: condition is not serializable; emit stub events and return success
            // Full condition evaluation requires the in-memory path where lambdas are preserved
            is CanonicalCoreStepCommand.WaitUntil -> waitUntilDispatcher.dispatchStub(command, context.waitUntilContext())
            is CanonicalCoreStepCommand.ArchiveArtifacts -> archiveArtifactsDispatcher.dispatch(command, context.archiveArtifactsContext())
        }

    // S2-A4 / G5: emitEventContext() removed with the legacy dispatcher (LEGACY_REMOVED).

    private fun CanonicalRuntimeContext.milestoneContext() = CanonicalMilestoneDispatchContext(
        runId = runId,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.deleteDirContext() = CanonicalDeleteDirDispatchContext(
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.cleanWsContext() = CanonicalCleanWsDispatchContext(
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.loadContext() = CanonicalLoadDispatchContext(
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
        loadedFingerprints = mutableSetOf(), // Per-run fingerprint cache
    )

    private fun CanonicalRuntimeContext.pwdContext() = CanonicalPwdDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
        workspaceRoot = shOptions.workspaceRoot,
    )

    private fun CanonicalRuntimeContext.isUnixContext() = CanonicalIsUnixDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
    )

    private fun CanonicalRuntimeContext.waitUntilContext() = CanonicalWaitUntilDispatchContext(
        runId = runId,
        stepIndex = stepIndex,
        eventSink = eventSink,
        condition = { true }, // Stub: condition not serializable in canonical path
    )

    private fun CanonicalRuntimeContext.archiveArtifactsContext() = CanonicalArchiveArtifactsDispatchContext(
        runId = runId,
        stageName = stageName,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
        workspaceRoot = shOptions.workspaceRoot,
    )

}
