package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path

/**
 * Runtime dependencies for canonical deleteDir dispatch.
 *
 * @param runId The run identifier
 * @param stageName Extracted from the step node's compound ID
 * @param stageIndex The index of the stage containing this deleteDir step
 * @param stepIndex The index of this step within the stage
 * @param controlDirRoot Root directory for durable control files (provides the workspace/ subdirectory)
 * @param eventSink Event sink for appending domain events
 */
data class CanonicalDeleteDirDispatchContext(
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.DeleteDir] nodes through [DeleteDirExecutor].
 *
 * T-05: deleteDir step — recursively deletes workspace contents, leaves workspace intact.
 * Idempotent: re-execution on already-deleted path emits DirDeleted with deletedCount=0.
 */
class CanonicalDeleteDirNodeDispatcher {

    suspend fun dispatch(command: CanonicalCoreStepCommand.DeleteDir, ctx: CanonicalDeleteDirDispatchContext): StepOutcome {
        val controlDirRoot = ctx.controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for deleteDir")

        val workspaceResolver = WorkspaceResolver(controlDirRoot)

        val executor = dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor(
            workspaceResolver = { name, idx -> workspaceResolver.resolve(name, idx) },
        )

        // Ensure the stage workspace directory exists
        val stageWorkspace = workspaceResolver.resolve(ctx.stageName, ctx.stageIndex)
        workspaceResolver.ensureCreated(stageWorkspace)

        val result = executor.execute(
            ctx.stageName,
            ctx.stageIndex,
            ctx.stepIndex,
            dev.rubentxu.pipeline.v2.dsl.StepSpec.DeleteDir(
                path = command.path,
            ),
        )

        ctx.eventSink.append(
            dev.rubentxu.pipeline.v2.events.DirDeleted(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                path = result.path.toString(),
                deletedCount = result.deletedCount,
                sha256 = result.sha256,
            ),
        )

        return StepOutcome.Success
    }
}
