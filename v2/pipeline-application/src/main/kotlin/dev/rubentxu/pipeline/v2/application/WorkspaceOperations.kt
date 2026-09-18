package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsResult
import dev.rubentxu.pipeline.v2.sdk.files.FileReadExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileReadResult
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteResult
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.FileRead
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
) : WorkspaceOperations {

    override fun writeFile(file: String, text: String, encoding: String): FileWriteResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        val resolver = WorkspaceResolver(root)
        resolver.ensureCreated(resolver.resolve(stageName, stageIndex))
        val executor = FileWriteExecutor(
            workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
            eventSink = eventSink,
        )
        return executor.execute(
            stageName,
            stageIndex,
            0, // stepIndex is unused by the executor's event path (dispatcher emits)
            StepSpec.WriteFile(file = file, text = text, encoding = encoding),
        )
    }

    override fun readFile(file: String, encoding: String): FileReadResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        val resolver = WorkspaceResolver(root)
        resolver.ensureCreated(resolver.resolve(stageName, stageIndex))
        val result = FileReadExecutor(
            workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
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
        val resolver = WorkspaceResolver(root)
        resolver.ensureCreated(resolver.resolve(stageName, stageIndex))
        val result = FileExistsExecutor(
            workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
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
