package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.DeleteDirInput
import dev.rubentxu.pipeline.v2.application.DeleteDirOperations
import dev.rubentxu.pipeline.v2.application.DeleteDirResult
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
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
     * RP034-G (ADR-0102): the typed workspace lease for this step.
     *
     * Additive and optional. When supplied, ownership of the root is read from
     * this value; when null the adapter falls back to the legacy
     * [ProjectCheckoutDetector] heuristic, which RP034-I retires.
     */
    private val executionLocation: dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation? = null,
) : DeleteDirOperations {

    override fun delete(input: DeleteDirInput): DeleteDirResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspace = resolver.resolve(stageIdentity.name, stageIdentity.index)
        resolver.ensureCreated(workspace)

        val executor = DeleteDirExecutor(
            workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
            // RP034-G (ADR-0102): the guard is derived from the typed lease, not
            // from a VCS marker.
            //
            // The previous condition was
            //   workspaceBase != null && ProjectCheckoutDetector.isProjectCheckout(workspaceBase)
            // which had two defects ADR-0102 removes:
            //
            //  1. it only engaged when `--workspace` was passed explicitly, so the
            //     no-flag path reached the user's directory unprotected — exactly
            //     the intermediate state WU-RP-034 forbids;
            //  2. it treated a bare non-VCS project tree as disposable scratch,
            //     because ownership was inferred from the absence of a marker.
            //
            // Ownership now arrives as a WorkspaceLease, so a user-owned root is
            // refused whether or not it carries .git, .hg or .svn, and PipelineK
            // scratch keeps its wipe contract regardless of its contents.
            protectWorkspaceRoot = executionLocation?.let {
                WorkspacePathResolver.authorizeRootDestruction(
                    it.workspace,
                    "deleteDir",
                ) !is DestructiveAuthorization.Permitted
            } ?: (workspaceBase != null && ProjectCheckoutDetector.isProjectCheckout(workspaceBase)),
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
