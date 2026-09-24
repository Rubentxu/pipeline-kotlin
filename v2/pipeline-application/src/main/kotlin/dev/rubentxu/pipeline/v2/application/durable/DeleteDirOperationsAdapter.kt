package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.DeleteDirInput
import dev.rubentxu.pipeline.v2.application.DeleteDirOperations
import dev.rubentxu.pipeline.v2.application.DeleteDirResult
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor
import java.time.Instant
import java.util.UUID

/**
 * The canonical implementation of [dev.rubentxu.pipeline.v2.application.DeleteDirOperations] —
 * the typed port consumed by `CoreDeleteDirStep.handler` (S2-A7 / G3-fix).
 *
 * ## Why an adapter rather than inlining the IO into the handler
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (effectful handlers adapt
 * to existing certified infrastructure, never embed process/IO logic). The
 * certified `core.sh` Step reaches `ShExecution.invokeShell` through a
 * `ShellOperations` capability. By symmetry, the registry `core.deleteDir` reaches
 * `DeleteDirExecutor` through THIS adapter — reached through
 * `DELETE_DIR_OPERATIONS_CAPABILITY`.
 *
 * ## Capability discipline
 *
 * This adapter is the ONLY thing in `core.deleteDir`'s path that:
 * - holds an [EventSink] reference,
 * - constructs and uses a [WorkspaceResolver],
 * - constructs and executes a [DeleteDirExecutor],
 * - emits a `DirDeleted` event.
 *
 * The handler reaches the typed seam and gets back a `DeleteDirResult(path,
 * deletedCount, sha256)`. It does NOT see filesystem, event sink, runId, stage
 * identity, or workspace root. Same isolation principle as
 * `core.sh → ShellOperations → ShOperationsAdapter` and
 * `core.pwd.tmp → TemporaryWorkspaceOperations → TemporaryWorkspaceOperationsAdapter`.
 *
 * ## No privileged executor
 *
 * The adapter does NOT itself perform process execution. It only performs a
 * workspace deletion on the canonical path and emits one `DirDeleted` event.
 */
class DeleteDirOperationsAdapter(
    private val runIdString: String,
    private val stageIdentity: StageIdentity,
    private val stepIndex: Int,
    private val controlDirRoot: java.nio.file.Path,
    private val eventSink: EventSink,
    /** WU-LPR-062: optional project-workspace override (--workspace). */
    private val workspaceBase: java.nio.file.Path? = null,
    /**
     * WU-RP-053 cut 4: explicit per-invocation cwd derived by `dir(...)`.
     * When absent, deletion preserves the historical stage/project workspace
     * resolution. When present, Jenkins-compatible `deleteDir()` removes the
     * contents of this directory, never those of the enclosing workspace.
     */
    private val effectiveWorkingDirectory: java.nio.file.Path? = null,
) : DeleteDirOperations {

    override fun delete(input: DeleteDirInput): DeleteDirResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspace = effectiveWorkingDirectory
            ?: resolver.resolve(stageIdentity.name, stageIdentity.index)
        resolver.ensureCreated(workspace)

        val executor = DeleteDirExecutor(
            // DeleteDirExecutor asks the resolver again. Return the immutable
            // invocation cwd, not the enclosing stage workspace, so its own
            // path-escape guard is rooted at the same Jenkins-compatible
            // directory selected above.
            workspaceResolver = { _, _ -> workspace },
        )

        val spec = StepSpec.DeleteDir(path = input.path)
        val execResult = executor.execute(
            stageName = stageIdentity.name,
            stageIndex = stageIdentity.index,
            stepIndex = stepIndex,
            spec = spec,
        )

        eventSink.append(
            DirDeleted(
                eventId = UUID.randomUUID().toString(),
                runId = runIdString,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = execResult.path.toString(),
                deletedCount = execResult.deletedCount,
                sha256 = execResult.sha256,
            ),
        )

        return DeleteDirResult(
            path = execResult.path.toString(),
            deletedCount = execResult.deletedCount,
            sha256 = execResult.sha256,
        )
    }
}
