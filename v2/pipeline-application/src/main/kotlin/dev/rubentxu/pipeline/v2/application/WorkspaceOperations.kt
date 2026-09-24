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
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import java.util.UUID
import java.nio.file.Files
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
 *
 * ## Security seam (WU-RP-053 fix)
 *
 * The adapter carries TWO distinct directory authorities:
 *
 * - `authorizedWorkspaceRoot` (immutable, computed ONCE at construction time): the
 *   workspace path the handler is allowed to write to. Filesystem operations
 *   authorize `targetPath` against this root in the adapter BEFORE any I/O effect.
 * - `effectiveWorkingDirectory` (per-invocation cwd of a `dir(...)` block): the
 *   directory relative paths are resolved against at the substrate layer. The
 *   substrate stays unchanged — same single-source-of-truth for path composition
 *   used by `core.sh` (D8, ADR-0052) and the file Steps.
 *
 * The composition rule matches `ShExecution.effectiveOptions` so `core.sh`,
 * `core.pwd`, and `core.{writeFile,readFile,fileExists}` agree on the cwd inside
 * a `dir(...)` block without each Step having to inspect overlays.
 *
 * ## Wide-traversal invariant
 *
 * The substrate (`FileWriteExecutor`/Read/Exists) enforces `targetPath.startsWith(workspace)`,
 * where `workspace = effectiveCwd`. That guard is the LOCAL guard: it only
 * protects `effectiveCwd`. The adapter's `authorize(...)` is the WIDE guard:
 * it rejects any target whose canonical (symlink-resolved) form lies OUTSIDE
 * `authorizedWorkspaceRoot` — even if the textual target is inside, even if
 * `effectiveCwd` is itself outside (e.g. `dir("/etc") { writeFile(...) }`),
 * even if a symlink leaf or intermediate directory points outside.
 *
 * The canonical form is derived by parent-resolve: the leaf is appended to the
 * realpath of the leaf's parent directory. This detects intermediate symlinks
 * even when the leaf file does not yet exist (writeFile creates leaves), and
 * rejects absolute paths that physically live outside the workspace.
 *
 * ## What MUST NOT happen
 *
 * - Handler MUST NOT receive `authorizedWorkspaceRoot` or `effectiveWorkingDirectory`
 *   directly; both are encapsulated by the adapter.
 * - Handler MUST NOT bypass the adapter to call `FileWriteExecutor` etc. directly.
 *
 * ## Reserved .v2
 *
 * `.v2` is reserved against the AUTHORIZED workspace root. Resolution into
 * `authorizedWorkspaceRoot/.v2/` is rejected regardless of which cwd is active,
 * so a `dir(".v2") { writeFile(...) }` inside a workspace that itself contains
 * `.v2` cannot bypass the reserved-tree invariant.
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
     * Effective working directory (cwd of the current lexical scope, e.g. a
     * `dir(...)` block). File system operations resolve `file` against THIS
     * directory when set; otherwise they resolve against the [workspaceBase] /
     * per-stage workspace. Honoured by the bridge; ignored here only when null.
     */
    private val effectiveWorkingDirectory: Path? = null,
) : WorkspaceOperations {

    /**
     * Immutable workspace root the handler is authorised to operate in. Computed
     * from [workspaceBase] (when set, the project's own directory) or
     * [WorkspaceResolver] (legacy per-stage) — deterministic, no I/O. This is
     * the SOLE security guard the adapter enforces; `effectiveCwd` only decides
     * where relative paths are resolved, never what the handler is allowed to
     * touch.
     */
    private val authorizedWorkspaceRoot: Path by lazy {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        WorkspaceResolver(root, workspaceBase).resolve(stageName, stageIndex)
            .toAbsolutePath().normalize()
    }

    /**
     * Single authority for the directory the file Steps resolve relative paths
     * against at the SUBSTRATE layer. The substrate does NOT know about the
     * authorizedWorkspaceRoot — that is the adapter's job.
     *
     * Composition rule (deterministic, no I/O):
     *
     *   1. effectiveWorkingDirectory wins when set (it IS the cwd of the block).
     *   2. workspaceBase (Jenkins-familiar single-workspace, --workspace).
     *   3. per-stage directory computed by [WorkspaceResolver].
     *
     * Mirrors `ShExecution.effectiveOptions = shOptions.copy(workspaceRoot = ...)`
     * so `core.sh` and `core.{writeFile,readFile,fileExists}` agree on the cwd.
     */
    internal fun effectiveRoot(): Path {
        if (effectiveWorkingDirectory != null) return effectiveWorkingDirectory
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        return WorkspaceResolver(root, workspaceBase).resolve(stageName, stageIndex)
    }

    /**
     * Authorize [targetPath] against the immutable workspace authority BEFORE
     * any I/O effect. The method is fail-closed: ANY of three checks failing
     * aborts the operation. For read/exists we translate the throw to
     * `exists=false`; for write we let it propagate.
     *
     * Checks, in order:
     *
     * 1. **Textual containment**: the normalized absolute target must start with
     *    [authorizedWorkspaceRoot]. Catches `..` traversal AND absolute paths that
     *    physically live outside before any FS read.
     * 2. **Canonical containment**: the canonical absolute target (parent
     *    realpath + leaf) must start with [authorizedWorkspaceRoot]. Catches
     *    symlink escapes: intermediate directory symlinks that point outside,
     *    even when the leaf file does not yet exist.
     * 3. **Reserved `.v2`**: the canonical target must not start with
     *    `authorizedWorkspaceRoot.resolve(".v2")`. Catches `dir(".v2")` and
     *    textual `.v2/...` paths.
     */
    private fun authorize(targetPath: Path): Path {
        val textualTarget = targetPath.normalize().toAbsolutePath()
        require(textualTarget.startsWith(authorizedWorkspaceRoot)) {
            "writeFile/readFile/fileExists path '${textualTarget}' escapes authorized workspace root '${authorizedWorkspaceRoot}'"
        }
        val canonical = canonicalizeParentResolve(textualTarget)
        require(canonical.startsWith(authorizedWorkspaceRoot)) {
            "writeFile/readFile/fileExists path '${textualTarget}' resolves via symlink " +
                "to '${canonical}' which escapes authorized workspace root " +
                "'${authorizedWorkspaceRoot}'"
        }
        require(!canonical.startsWith(authorizedWorkspaceRoot.resolve(".v2"))) {
            "writeFile/readFile/fileExists path '${textualTarget}' resolves to " +
                "'${canonical}' which targets reserved .v2 directory under " +
                "authorized workspace root"
        }
        return canonical
    }

    /**
     * Canonicalize [targetPath] by walking the path segment-by-segment through
     * a symlink-aware process. For each segment:
     *
     *   - if the segment is a symlink (regardless of whether its target exists),
     *     read the link target and resolve it against the current canonical
     *     prefix (recursive if the target is itself relative);
     *   - else append the segment literally.
     *
     * Brand-new files whose leaf does not yet exist pass through unchanged
     * (their textual form already survives the textual containment check).
     *
     * Robustness rules:
     *
     * - If the parent does not exist (deep new tree), return the textual
     *   target — the substrate's own `startsWith(workspace)` guard still
     *   rejects any escape and will create the parent.
     * - If symlink resolution throws IOException (broken link chain),
     *   return the textual target. The substrate's guard catches any escape.
     */
    private fun canonicalizeParentResolve(targetPath: Path): Path {
        val parent = targetPath.parent ?: return targetPath
        if (!Files.exists(parent)) return targetPath
        return try {
            val parentReal = parent.toRealPath()
            val leaf = targetPath.fileName ?: return parentReal
            val composed = parentReal.resolve(leaf.toString())
            // If the composed leaf is a symlink (the common case for an
            // attacker-placed escape), follow it explicitly so the WIDE guard
            // sees the real target. Plain files / directories / missing leaves
            // pass through as-is (the textual containment check + the parent
            // canonical prefix already protect them).
            if (Files.isSymbolicLink(composed)) {
                try {
                    val linkTarget = Files.readSymbolicLink(composed)
                    // Resolve the link target against parentReal (relative) or
                    // absolutely when absolute.
                    val resolved = if (linkTarget.isAbsolute) linkTarget
                    else parentReal.resolve(linkTarget)
                    if (Files.exists(resolved)) resolved.toRealPath() else resolved
                } catch (_: java.io.IOException) {
                    composed
                }
            } else {
                composed
            }
        } catch (_: java.io.IOException) {
            targetPath
        }
    }

    override fun writeFile(file: String, text: String, encoding: String): FileWriteResult {
        val root = controlDirRoot
            ?: throw IllegalStateException("controlDirRoot is required for workspace file operations")
        val cwd = effectiveRoot()
        // Wide authorization gate BEFORE any I/O. The substrate below still gets
        // its single-root check via its own workspaceResolver lambda; this
        // adapter-level guard is the WIDE check that catches inputs that escape
        // `cwd` (e.g. abs paths, symlink targets outside authorizedWorkspaceRoot).
        authorize(cwd.resolve(file))
        WorkspaceResolver(root, workspaceBase).ensureCreated(cwd)
        val executor = FileWriteExecutor(
            workspaceResolver = { _, _ -> cwd },
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
        val cwd = effectiveRoot()
        // Read paths that escape return exists=false (Jenkins semantics for
        // out-of-workspace reads). Authorize throws when the path escapes the
        // authorised workspace; we translate to exists=false for read so the
        // handler sees a uniform typed failure.
        try {
            authorize(cwd.resolve(file))
        } catch (_: IllegalArgumentException) {
            return FileReadResult(
                path = cwd.resolve(file).normalize().toAbsolutePath(),
                content = null,
                sha256 = null,
                size = null,
                exists = false,
            )
        }
        WorkspaceResolver(root, workspaceBase).ensureCreated(cwd)
        val result = FileReadExecutor(
            workspaceResolver = { _, _ -> cwd },
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
        val cwd = effectiveRoot()
        try {
            authorize(cwd.resolve(file))
        } catch (_: IllegalArgumentException) {
            return FileExistsResult(
                path = cwd.resolve(file).normalize().toAbsolutePath(),
                exists = false,
            )
        }
        WorkspaceResolver(root, workspaceBase).ensureCreated(cwd)
        val result = FileExistsExecutor(
            workspaceResolver = { _, _ -> cwd },
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
