package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Runtime dependencies for canonical load dispatch.
 *
 * @param runId The run identifier
 * @param stageName Stage name for workspace resolution
 * @param stageIndex The index of the stage containing this load step
 * @param stepIndex The index of this step within the stage
 * @param controlDirRoot Root directory for durable control files (provides the workspace/ subdirectory)
 * @param eventSink Event sink for appending domain events
 */
data class CanonicalLoadDispatchContext(
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
    /** Re-entrant fingerprint cache: set of "$path:$sha256" already loaded in this run */
    val loadedFingerprints: MutableSet<String>,
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.Load] nodes.
 *
 * T-05: load step — reads and evaluates a pipeline script file in the workspace.
 * Re-entrant: subsequent calls with same (path, sha256) are skipped.
 *
 * NOTE: This dispatcher handles file reading, SHA-256 computation, and re-entrancy checking.
 * The actual compilation and step execution is deferred to the coordinator level since
 * load produces multiple child steps that need to be executed in the same run context.
 */
class CanonicalLoadNodeDispatcher {

    fun dispatch(command: CanonicalCoreStepCommand.Load, ctx: CanonicalLoadDispatchContext): StepOutcome {
        val controlDirRoot = ctx.controlDirRoot
            ?: return StepOutcome.Failure(
                PipelineFailure(FailureKind.INFRASTRUCTURE, "controlDirRoot is required for load")
            )

        val workspaceResolver = WorkspaceResolver(controlDirRoot)
        val stageWorkspace = workspaceResolver.resolve(ctx.stageName, ctx.stageIndex)

        // Resolve the script path relative to workspace
        val scriptPath = stageWorkspace.resolve(command.path).normalize()

        // Security: ensure path is within workspace
        if (!scriptPath.startsWith(stageWorkspace)) {
            return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "load path '${command.path}' escapes workspace root"
                )
            )
        }

        // Read file and compute SHA-256
        val fileContent = try {
            java.io.File(scriptPath.toUri()).readBytes()
        } catch (e: java.io.FileNotFoundException) {
            return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "load: file not found: ${command.path}"
                )
            )
        }

        val sha256 = sha256(fileContent)

        // Re-entrancy check
        val fingerprint = "${command.path}:$sha256"
        if (ctx.loadedFingerprints.contains(fingerprint)) {
            // Re-entrant load: same path + sha already loaded in this run
            ctx.eventSink.append(
                dev.rubentxu.pipeline.v2.events.WorkflowLoaded(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = ctx.runId,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                    path = command.path,
                    stepCount = 0,
                    sha256 = sha256,
                ),
            )
            return StepOutcome.Success
        }

        // Mark as loaded for re-entrancy
        ctx.loadedFingerprints.add(fingerprint)

        // Emit WorkflowLoaded with stepCount = 0
        // NOTE: Full step extraction and execution is handled at the coordinator level
        // to properly integrate loaded steps into the durable execution context.
        ctx.eventSink.append(
            dev.rubentxu.pipeline.v2.events.WorkflowLoaded(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                path = command.path,
                stepCount = 0,
                sha256 = sha256,
            ),
        )

        return StepOutcome.Success
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
