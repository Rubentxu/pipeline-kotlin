package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path

/**
 * Runtime dependencies for canonical cleanWs dispatch.
 *
 * @param runId The run identifier
 * @param stageName Extracted from the step node's compound ID
 * @param stageIndex The index of the stage containing this cleanWs step
 * @param stepIndex The index of this step within the stage
 * @param controlDirRoot Root directory for durable control files (provides the workspace/ subdirectory)
 * @param eventSink Event sink for appending domain events
 */
data class CanonicalCleanWsDispatchContext(
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.CleanWs] nodes through [CleanWsExecutor].
 *
 * T-05: cleanWs step — cleans workspace with optional Ant-style glob filtering.
 * @param deleteDirs If true, delete all subdirectories too
 * @param patterns Additional glob patterns to delete
 */
class CanonicalCleanWsNodeDispatcher {

    suspend fun dispatch(command: CanonicalCoreStepCommand.CleanWs, ctx: CanonicalCleanWsDispatchContext): StepOutcome {
        val controlDirRoot = ctx.controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for cleanWs")

        val workspaceResolver = WorkspaceResolver(controlDirRoot)

        val executor = dev.rubentxu.pipeline.v2.sdk.files.CleanWsExecutor(
            workspaceResolver = { name, idx -> workspaceResolver.resolve(name, idx) },
        )

        // Ensure the stage workspace directory exists
        val stageWorkspace = workspaceResolver.resolve(ctx.stageName, ctx.stageIndex)
        workspaceResolver.ensureCreated(stageWorkspace)

        val result = executor.execute(
            ctx.stageName,
            ctx.stageIndex,
            ctx.stepIndex,
            dev.rubentxu.pipeline.v2.dsl.StepSpec.CleanWs(
                deleteDirs = command.deleteDirs,
                patterns = command.patterns,
            ),
        )

        ctx.eventSink.append(
            dev.rubentxu.pipeline.v2.events.WsCleaned(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                deletedFiles = result.deletedFiles,
                deletedDirs = result.deletedDirs,
                patterns = result.patterns,
                sha256 = result.sha256,
            ),
        )

        return StepOutcome.Success
    }
}
