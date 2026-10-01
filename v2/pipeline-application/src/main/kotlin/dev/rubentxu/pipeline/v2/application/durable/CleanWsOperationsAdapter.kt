package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CleanWsInput
import dev.rubentxu.pipeline.v2.application.CleanWsOperations
import dev.rubentxu.pipeline.v2.application.CleanWsResult
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WsCleaned
import dev.rubentxu.pipeline.v2.sdk.files.CleanWsExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The canonical implementation of [dev.rubentxu.pipeline.v2.application.CleanWsOperations] —
 * the typed port consumed by `CoreCleanWsStep.handler` (LFC-2E1-S2-A10 / G1).
 *
 * ## Why an adapter rather than inlining the IO into the handler
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (effectful handlers adapt
 * to existing certified infrastructure, never embed process/IO logic). By symmetry
 * with the certified `core.deleteDir → DeleteDirOperations → DeleteDirOperationsAdapter`
 * pattern, the registry `core.cleanWs` reaches the existing `CleanWsExecutor` SDK
 * authority through THIS adapter — reached through `CLEAN_WS_OPERATIONS_CAPABILITY`.
 *
 * ## Capability discipline
 *
 * This adapter is the ONLY thing in `core.cleanWs`'s path that:
 * - holds an [EventSink] reference,
 * - constructs and uses a [WorkspaceResolver],
 * - constructs and executes a [CleanWsExecutor],
 * - emits a `WsCleaned` event.
 *
 * The handler reaches the typed seam and gets back a `CleanWsResult`. It does NOT
 * see filesystem, event sink, runId, stage identity, or workspace root. Same
 * isolation principle as `core.sh → ShellOperations → ShOperationsAdapter` and
 * `core.deleteDir → DeleteDirOperations → DeleteDirOperationsAdapter`.
 *
 * ## No privileged executor
 *
 * The adapter reuses the SAME `CleanWsExecutor` substrate the legacy
 * `CanonicalCleanWsNodeDispatcher` uses (identical construction: WorkspaceResolver
 * over controlDirRoot, ensureCreated before execution) — differential equivalence
 * by construction, not by reimplementation.
 */
class CleanWsOperationsAdapter(
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
     * this value rather than inferred from a VCS marker; RP034-I retires the
     * fallback.
     */
    private val executionLocation: dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation,
) : CleanWsOperations {
    /**
     * Idempotent creation of the workspace directory this Step operates on.
     *
     * The base itself comes from the execution location (RP034-I); this only
     * ensures the directory exists, which is what the SDK executor and its
     * `.deleted` / `.cleaned` marker require.
     */
    private fun ensureWorkspace(workspace: Path) {
        if (!Files.isDirectory(workspace)) {
            Files.createDirectories(workspace)
        }
    }


    override fun clean(input: CleanWsInput): CleanWsResult {
        // RP034-I: `cleanWs` anchors on WORKSPACE_ROOT (anchor matrix) — it
        // wipes the workspace, so a `dir(...)` scope must not move it. The base
        // is the lease root the location reports, not one re-derived from the
        // control root, for the same reason as deleteDir.
        val workspace: Path = executionLocation.workspace.root
        ensureWorkspace(workspace)

        val executor = CleanWsExecutor(
            workspaceResolver = { _, _ -> workspace },
            // RP034-G (ADR-0102): derived from the typed lease, not from a VCS
            // marker. The previous condition engaged only when `--workspace` was
            // passed explicitly, leaving the no-flag path unprotected, and it
            // treated a bare non-VCS project tree as disposable scratch. A
            // user-owned root is now refused regardless of what is on disk,
            // while PipelineK-managed scratch keeps its wipe contract.
            protectWorkspaceRoot = WorkspacePathResolver.authorizeRootDestruction(
                executionLocation.workspace,
                "cleanWs",
            ) !is DestructiveAuthorization.Permitted,
        )

        val execResult = executor.execute(
            stageName = stageIdentity.name,
            stageIndex = stageIdentity.index,
            stepIndex = stepIndex,
            spec = StepSpec.CleanWs(
                deleteDirs = input.deleteDirs,
                patterns = input.patterns,
            ),
        )

        eventSink.append(
            WsCleaned(
                eventId = UUID.randomUUID().toString(),
                runId = runIdString,
                sequence = 0L,
                occurredAt = Instant.now(),
                deletedFiles = execResult.deletedFiles,
                deletedDirs = execResult.deletedDirs,
                patterns = execResult.patterns,
                sha256 = execResult.sha256,
            ),
        )

        return CleanWsResult(
            deletedFiles = execResult.deletedFiles,
            deletedDirs = execResult.deletedDirs,
            patterns = execResult.patterns,
            sha256 = execResult.sha256,
        )
    }
}
