package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteResult
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path

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
}
