package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import java.nio.file.Files
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsResult
import dev.rubentxu.pipeline.v2.sdk.files.FileReadExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileReadResult
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteResult
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import java.util.UUID
import java.nio.file.Path
import java.time.Instant

/**
 * Typed seam that a registry-routed Step handler calls to perform stage-workspace
 * file operations (S2-A3 / G1).
 *
 * This interface is the *only* contract a Step handler holds for workspace file
 * writes. The handler MUST NOT carry filesystem logic itself; it adapts to this
 * typed seam, which is implemented by the certified
 * [FileWriteExecutor] substrate (atomic temp+rename write, path-traversal guard,
 * reserved `.v2` guard). The capability system wires the adapter in
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess].
 *
 * ## Why a typed seam and not `FileWriteExecutor` directly?
 *
 * AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE (handler must adapt to existing
 * certified infrastructure). Mirrors [ShellOperations] for `core.sh`: the substrate
 * stays the single authority for `FileWritten` emission; the handler neither writes
 * files nor emits events.
 *
 * ## Scope
 *
 * The interface intentionally hides:
 * - control-dir root — owned by the canonical runtime context;
 * - stage identity (name/index) — owned by the canonical runtime context;
 * - the event sink — owned by the runtime context; the substrate is the single
 *   `FileWritten` emitter.
 */
interface WorkspaceOperations {

    /**
     * Atomically writes [text] to [file] (relative to the current stage workspace)
     * with [encoding], returning the closed typed [FileWriteResult].
     */
    fun writeFile(file: String, text: String, encoding: String): FileWriteResult

    /**
     * Reads [file] (relative to the current stage workspace) with [encoding],
     * returning the typed [FileReadResult] (exists=false for out-of-workspace,
     * reserved-.v2, or missing targets — the substrate owns the path guard).
     * The adapter is the single `FileRead` event emitter; content NEVER enters
     * the event channel (INV-L6-EVT-001).
     */
    fun readFile(file: String, encoding: String): FileReadResult

    /**
     * Checks whether [file] exists in the current stage workspace using the
     * same path guard as [readFile]. Emits a `FileExistsChecked` event via the
     * adapter (single emitter).
     */
    fun fileExists(file: String): FileExistsResult
}

/**
 * Runtime adapter binding [WorkspaceOperations] to the certified [FileWriteExecutor]
 * substrate and the canonical [WorkspaceResolver]. The handler never sees either.
 */
class WorkspaceOperationsAdapter(
    private val stageName: String,
    private val stageIndex: Int,
    private val controlDirRoot: Path?,
    private val eventSink: EventSink,
    private val runId: String = "",
    /** WU-LPR-062: optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
    /**
     * RP034-D (ADR-0100): the shared execution location for this step.
     *
     * When present, file Steps resolve against the same current directory the
     * shell vertical observes, so a `dir` scope applies uniformly instead of
     * only to `sh`. When null (legacy callers and unit tests that build this
     * adapter directly), the adapter falls back to the per-stage
     * [WorkspaceResolver] it always used.
     */
    private val executionLocation: dev.rubentxu.pipeline.v2.application.durable.ExecutionLocationCapability? = null,
) : WorkspaceOperations {

    /**
     * The base directory file paths resolve against for the current scope.
     *
     * RP034-D: this prefers the shared [executionLocation] cwd and falls back to
     * the stage workspace. Keeping one method here is what makes the verticals
     * agree: every file operation resolves through this, so none of them can
     * drift back to a private reconstruction.
     */
    private fun effectiveWorkspaceRoot(controlRoot: Path): Path {
        val fromLocation = executionLocation?.let { capability ->
            dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
                .baseFor(capability.location, dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor.CURRENT_DIRECTORY)
        }
        return fromLocation ?: WorkspaceResolver(controlRoot, workspaceBase).resolve(stageName, stageIndex)
    }

    override fun writeFile(file: String, text: String, encoding: String): FileWriteResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        // RP034-D: resolve against the shared execution location so a `dir`
        // scope applies to file Steps exactly as it does to `sh`.
        val base = effectiveWorkspaceRoot(root)
        Files.createDirectories(base)
        val executor = FileWriteExecutor(
            workspaceResolver = { _, _ -> base },
            eventSink = eventSink,
        )
        val result = executor.execute(
            stageName,
            stageIndex,
            0,
            StepSpec.WriteFile(file = file, text = text, encoding = encoding),
        )
        // Single-emitter: the adapter is the ONLY FileWritten emitter (WU-LPR-071
        // fix, CR-U9 family): the executor substrate carries the write evidence but
        // event emission was left to a dispatcher that does not exist on the registry
        // path, silently dropping the FileWritten contract.
        eventSink.append(
            FileWritten(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = result.path,
                sha256 = result.sha256,
                size = result.size,
                atomicallyMoved = result.atomicallyMoved,
            ),
        )
        return result
    }

    override fun readFile(file: String, encoding: String): FileReadResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        val base = effectiveWorkspaceRoot(root)
        val result = FileReadExecutor(
            workspaceResolver = { _, _ -> base },
        ).execute(stageName, stageIndex, 0, StepSpec.ReadFile(file = file, encoding = encoding))
        // Single-emitter: the adapter is the ONLY FileRead emitter. Payload is
        // restricted to path + sha256 + size — never content (INV-L6-EVT-001).
        eventSink.append(
            FileRead(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = result.path,
                sha256 = result.sha256,
                size = result.size,
            ),
        )
        return result
    }

    override fun fileExists(file: String): FileExistsResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        val base = effectiveWorkspaceRoot(root)
        val result = FileExistsExecutor(
            workspaceResolver = { _, _ -> base },
        ).execute(stageName, stageIndex, 0, StepSpec.FileExists(file = file))
        eventSink.append(
            FileExistsChecked(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = result.path,
                exists = result.exists,
            ),
        )
        return result
    }
}
