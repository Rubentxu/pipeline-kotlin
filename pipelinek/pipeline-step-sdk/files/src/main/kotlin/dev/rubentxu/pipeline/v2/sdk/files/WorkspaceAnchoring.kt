package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * The single containment anchor for every file Step in this module.
 * ## Why a lexical check is not a containment check
 * `Path.normalize()` collapses `.` and `..` but never dereferences a symlink. So
 * ```text
 * workspace/link -> /etc
 * user path     = "link/passwd"
 * ```
 * normalises to `<workspace>/link/passwd`, which trivially `startsWith` the
 * workspace, while every effect — `Files.readAllBytes`, `Files.write`,
 * `Files.createDirectories`, `Files.exists` — lands on `/etc/passwd`. Measured
 *  against this module's own code path, before the fix:
 * ```text
 * lexical startsWith(workspace) = true
 * content read                 = bytes from outside the workspace
 * ```
 * AGENTS.md section 1 makes "no workspace operation may escape the authorized
 * root" a non-negotiable, so a check that passes an escaping path is not a
 * preference between two valid readings.
 * ## The rule this enforces
 * Decide containment on the REAL path, and apply effects to the REAL path.
 * Both sides are resolved the same way, so a symlinked workspace root and a
 * symlinked target are compared like with like.
 * ## Why `WorkspacePathResolver.rejectSymlinkEscape` is the verdict
 * That function already existed, documented as the one place a real path is
 * judged against a real root — and had zero callers. Re-deciding containment
 * here would have created a second authority for a security boundary, which is
 * exactly what its own KDoc forbids. One verdict, one owner.
*/
internal object WorkspaceAnchoring {

    /**
     * Dereference [path] as far as it exists, tolerating a missing leaf.
     * `Path.toRealPath` throws when the leaf does not exist, and a write target
     * legitimately does not exist yet. So the deepest existing ancestor is
     * resolved and the remaining segments are re-appended. That yields the real
     * path when the target exists and a correctly anchored guess when it does
     * not — the guess still refuses an escape through a symlinked ancestor,
     * which is the case that matters.
*/
    fun realPathAllowingMissing(path: Path): Path {
        var ancestor: Path? = path
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.parent
        }
        val existing: Path = ancestor ?: return path
        // `relativize` is the java.nio member. The kotlin.io `relativeTo`
        // extension also exists and resolves to a File here, which does not
        // compile; the member is the intended call anyway.
        val suffix: Path = existing.relativize(path)
        val resolved: Path = existing.toRealPath()
        return resolved.resolve(suffix).normalize()
    }

    /**
     * The one containment verdict for a user-supplied path inside [workspace].
     * [rawTarget] is the DECLARED path — what the caller wrote — because that is
     * what the observable result types report. Callers must apply effects to the
     * [PathResolution.Resolved.path] instead, which is the real path.
*/
    fun authorize(workspace: Path, rawTarget: Path): PathResolution =
        WorkspacePathResolver.rejectSymlinkEscape(
            realTarget = realPathAllowingMissing(rawTarget),
            realRoot = realPathAllowingMissing(workspace),
        )

    /**
     * The real path of [workspace], for the reserved-directory comparison.
     * Comparing a real target against a declared root would reject every path
     * whenever the workspace root is itself reached through a symlink, which is
     * a legal `--workspace` layout.
*/
    fun realWorkspace(workspace: Path): Path = realPathAllowingMissing(workspace)
}
