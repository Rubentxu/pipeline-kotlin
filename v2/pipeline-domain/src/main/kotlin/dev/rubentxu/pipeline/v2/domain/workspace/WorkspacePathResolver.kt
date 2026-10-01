package dev.rubentxu.pipeline.v2.domain.workspace

import java.nio.file.Path

/**
 * Pure resolution of a user-supplied path against an [ExecutionLocation].
 *
 * This is the single authority every workspace-aware Step must use. A Step that
 * rebuilds its own base — e.g. by re-deriving `controlDirRoot/stageName/index` —
 * reintroduces exactly the divergence ADR-0100 removes (INV-WS-012).
 *
 * The functions here are total and effect-free: they perform no filesystem
 * access, so confinement decisions are testable without a filesystem. Callers
 * apply effects only after a resolution has succeeded.
 */
object WorkspacePathResolver {

    /**
     * The base a [PathAnchor] denotes in this location.
     *
     * Pure read: CURRENT_DIRECTORY yields `cwd`, WORKSPACE_ROOT yields the stable
     * lease root. Note these are frequently equal, and are still kept apart.
     */
    fun baseFor(location: ExecutionLocation, anchor: PathAnchor): Path =
        when (anchor) {
            PathAnchor.CURRENT_DIRECTORY -> location.cwd
            PathAnchor.WORKSPACE_ROOT -> location.workspace.root
        }

    /**
     * Resolve a user path against its declared anchor and authorise it.
     *
     * Policy (ADR-0100 §8, §9):
     * - a relative path is resolved against [anchor] and must stay inside the
     *   workspace root;
     * - `..` traversal out of the root is rejected;
     * - an absolute path inside the root is accepted, because some Step
     *   contracts historically allowed it;
     * - an absolute path outside the root is rejected — host-wide access needs a
     *   separate, explicit capability.
     *
     * Symlink escapes are NOT decided here: that requires filesystem reads and
     * belongs to the authorising adapter, which calls [rejectSymlinkEscape] once
     * it has resolved real paths. Keeping the check out of this function is what
     * preserves the functional-core / effectful-shell split.
     */
    fun resolve(
        location: ExecutionLocation,
        anchor: PathAnchor,
        userPath: String,
    ): PathResolution {
        if (userPath.isEmpty()) {
            return PathResolution.Rejected(WorkspacePathError.InvalidPath(userPath))
        }

        val root = location.workspace.root.normalize()
        val candidate = Path.of(userPath)

        // Absolute input: only admissible when it is already inside the root.
        if (candidate.isAbsolute) {
            val normalised = candidate.normalize()
            return if (normalised.startsWith(root)) {
                PathResolution.Resolved(normalised)
            } else {
                PathResolution.Rejected(WorkspacePathError.AbsolutePathNotAllowed(userPath))
            }
        }

        val base = baseFor(location, anchor).normalize()
        val resolved = base.resolve(candidate).normalize()

        // A normalised path that no longer starts with the root escaped via `..`.
        if (!resolved.startsWith(root)) {
            return PathResolution.Rejected(WorkspacePathError.EscapesWorkspace(userPath, root))
        }

        return PathResolution.Resolved(resolved)
    }

    /**
     * Reject a target whose real path leaves the workspace.
     *
     * Called by the authorising adapter after it has canonicalised the path, so
     * that a symlink pointing outside the root cannot be used to reach it. Kept
     * separate from [resolve] because it needs filesystem state.
     */
    fun rejectSymlinkEscape(
        realTarget: Path,
        realRoot: Path,
    ): PathResolution {
        val real = realTarget.normalize()
        return if (real.startsWith(realRoot.normalize())) {
            PathResolution.Resolved(real)
        } else {
            PathResolution.Rejected(WorkspacePathError.SymlinkEscape(realTarget, realRoot))
        }
    }

    /**
     * Decide whether a root-wide destructive operation is permitted.
     *
     * This is the whole point of the typed lease. A [WorkspaceLease.Attached]
     * root is user-owned and refuses destruction regardless of whether it
     * contains a VCS marker; a [WorkspaceLease.Managed] root is PipelineK's own
     * scratch and keeps its lifecycle cleanup.
     *
     * Pure and total — no VCS inspection, which is the inference ADR-0102 removes.
     */
    fun authorizeRootDestruction(
        lease: WorkspaceLease,
        operation: String,
    ): DestructiveAuthorization =
        when (lease) {
            is WorkspaceLease.Attached ->
                DestructiveAuthorization.Refused(
                    WorkspacePathError.ProtectedWorkspaceRoot(operation, lease.root),
                )

            is WorkspaceLease.Managed -> DestructiveAuthorization.Permitted
        }

    /**
     * Derive the child location for a `dir(...)` scope.
     *
     * `dir` changes only the current directory; the workspace root and its
     * ownership are carried through unchanged (INV-WS-001). Because a value is
     * returned rather than mutated, leaving the scope restores the parent simply
     * by discarding the child — there is no global `chdir` to undo.
     */
    fun deriveDirectory(
        location: ExecutionLocation,
        relativePath: String,
    ): DirDerivation =
        when (val resolved = resolve(location, PathAnchor.CURRENT_DIRECTORY, relativePath)) {
            is PathResolution.Resolved -> DirDerivation.Derived(location.copy(cwd = resolved.path))
            is PathResolution.Rejected -> DirDerivation.Rejected(resolved.reason)
        }
}

/**
 * Outcome of deriving a `dir` child context.
 *
 * Separate from [PathResolution] because a caller that only wants to scope a
 * body does not need to distinguish "the path was bad" from "the path was
 * refused by the sandbox" at the type level — the reason is still carried, so no
 * information is lost.
 */
sealed interface DirDerivation {
    data class Derived(val location: ExecutionLocation) : DirDerivation
    data class Rejected(val reason: WorkspacePathError) : DirDerivation
}
