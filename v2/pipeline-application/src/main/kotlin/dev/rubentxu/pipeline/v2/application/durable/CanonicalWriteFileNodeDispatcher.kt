package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path

/**
 * Runtime dependencies for canonical writeFile dispatch.
 *
 * @param runId The run identifier
 * @param stageName Extracted from the step node's compound ID (e.g. "build" from "build/writefile-0")
 * @param stageIndex The index of the stage containing this writeFile step
 * @param stepIndex The index of this step within the stage
 * @param controlDirRoot Root directory for durable control files (provides the workspace/ subdirectory)
 * @param eventSink Event sink for appending domain events
 */
data class CanonicalWriteFileDispatchContext(
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
)

/**
 * Runtime dependencies for canonical emit-event dispatch.
 *
 * @param runId The run identifier
 * @param eventSink Event sink for appending domain events
 */
data class CanonicalEmitEventDispatchContext(
    val runId: String,
    val eventSink: EventSink,
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.WriteFile] nodes through [dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor].
 *
 * LFC1-007: Reuses the existing [dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor] from
 * `:pipeline-step-sdk:files` module to preserve atomic-write semantics and path-traversal guards.
 */
class CanonicalWriteFileNodeDispatcher {

    suspend fun dispatch(command: CanonicalCoreStepCommand.WriteFile, ctx: CanonicalWriteFileDispatchContext): StepOutcome {
        val workspace = ctx.controlDirRoot?.resolve("workspace")
            ?: throw IllegalStateException("controlDirRoot is required for writeFile")

        val workspaceResolver = { name: String, idx: Int ->
            workspace.resolve("${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}-${idx}")
        }

        val executor = dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor(
            workspaceResolver = workspaceResolver,
            eventSink = ctx.eventSink,
        )

        val result = executor.execute(
            ctx.stageName,
            ctx.stageIndex,
            ctx.stepIndex,
            dev.rubentxu.pipeline.v2.dsl.StepSpec.WriteFile(
                file = command.file,
                text = command.text,
                encoding = command.encoding,
            ),
        )

        ctx.eventSink.append(
            dev.rubentxu.pipeline.v2.events.FileWritten(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                path = result.path,
                sha256 = result.sha256,
                size = result.size,
                atomicallyMoved = result.atomicallyMoved,
            ),
        )

        return StepOutcome.Success
    }
}
