package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CleanWsInput
import dev.rubentxu.pipeline.v2.application.CleanWsOperations
import dev.rubentxu.pipeline.v2.application.CleanWsResult
import dev.rubentxu.pipeline.v2.application.StageIdentity
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WsCleaned
import dev.rubentxu.pipeline.v2.sdk.files.CleanWsExecutor
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
) : CleanWsOperations {

    /**
     * C12: true when [base] looks like a source-control checkout rather than a
     * disposable scratch directory. Mirrors the identical helper in
     * [DeleteDirOperationsAdapter] so both cleanup Steps agree on what
     * "the user's project" means.
     */
    private fun isProjectCheckout(base: java.nio.file.Path): Boolean =
        listOf(base, base.parent).any { candidate ->
            candidate != null &&
                listOf(".git", ".hg", ".svn").any { marker ->
                    java.nio.file.Files.exists(candidate.resolve(marker))
                }
        }

    override fun clean(input: CleanWsInput): CleanWsResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspace = resolver.resolve(stageIdentity.name, stageIdentity.index)
        resolver.ensureCreated(workspace)

        val executor = CleanWsExecutor(
            workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
            // C9: when --workspace points at the user's own project checkout,
            // the pattern-less form would delete every file in it.
            //
            // C12: same correction as DeleteDirOperationsAdapter. The trigger
            // is the target being a VCS checkout, not the mere presence of
            // --workspace, so disposable scratch workspaces keep their wipe
            // contract. The corpus invokes the CLI with --workspace over a
            // @TempDir, and the over-broad condition broke it there too.
            protectWorkspaceRoot = workspaceBase != null && isProjectCheckout(workspaceBase),
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
