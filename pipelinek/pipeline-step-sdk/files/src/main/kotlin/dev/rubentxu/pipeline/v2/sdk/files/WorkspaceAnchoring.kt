package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.nio.file.Path

/**
 * The single containment anchor for every file Step in this module.
 *
 * ## Why a lexical check is not a containment check
 * `Path.normalize()` collapses `.` and `..` but never dereferences a symlink. So
 * ```text
 * workspace/link -> /etc
 * user path     = "link/passwd"
 * ```
 * normalises to `<workspace>/link/passwd`, which trivially `startsWith` the
 * workspace, while every effect — `Files.readAllBytes`, `Files.write`,
 * `Files.createDirectories`, `Files.exists` — lands on `/etc/passwd`. Measured
 * against this module's own code path, before the fix:
 * ```text
 * lexical startsWith(workspace) = true
 * content read                 = bytes from outside the workspace
 * ```
 * AGENTS.md section 1 makes "no workspace operation may escape the authorized
 * root" a non-negotiable, so a check that passes an escaping path is not a
 * preference between two valid readings.
 *
 * ## The rule this enforces
 * Decide containment on the REAL path, apply the effect to the REAL path, and
 * never walk through a symbolic link that the decision did not resolve.
 * Both sides are resolved the same way, so a symlinked workspace root and a
 * symlinked target are compared like with like.
 *
 * ## The threat, and who the two actors are
 *
 * The product offers two kinds of workspace. One is the workspace of trust: the
 * operator's own directory, reached through `--workspace` or through the
 * directory the run was invoked from. The other is the isolated scratch the
 * product allocates for the run. They differ in WHO can replace a symbolic link
 * inside them:
 *
 *  - in the workspace of trust, the only actor that can replace a link is the
 *    operator. A link of theirs is their own business, the resolution honours
 *    it, and nothing about their checkout is broken by that;
 *  - in the isolated scratch, the run's OWN processes can replace a link. A
 *    step that writes a link inside the workspace, and a later step that
 *    follows it, is the shape of an escape: the profile that promises a
 *    boundary says the workspace is the only place of the operator's that the
 *    run can write, and a link turns that promise into a lie.
 *
 * So the actor that can replace a link is not the operator in the second case,
 * and the containment has to hold against it. The rule above is what holds.
 *
 * ## Why the verdict is split in two
 *
 * The authorization resolves a path at one moment and the effect happens at a
 * later one, and a link replaced in between makes the two disagree. The
 * decision therefore has two parts, and the second one is taken immediately
 * before the effect:
 *
 *  - [authorize] decides whether the target is inside the root, on the real
 *    path;
 *  - [prepareDirectoryChain] and [chainIsFreeOfLinks] look at the chain of
 *    directories between the root and the effect, immediately before the
 *    effect, and refuse to walk through a link.
 *
 * The second part is not redundant. A component that does not exist at the
 * moment of the authorization is a name, not a fact, and the effect would
 * resolve it at a moment when it can be something else.
 *
 * ## The names the product chooses for itself
 *
 * The chain of directories is not the only path an effect travels through. A
 * write stages its bytes at a name of its own choosing next to the target, and
 * the idempotency markers live at a name of their own. Those names are inside
 * the workspace, so the actor that can replace a link can occupy them too — and
 * a write FOLLOWS a link, so the effect would land on the target of it, outside
 * the root. The staging name is judged by the same rule as any other path
 * ([chainIsFreeOfLinks]); a marker name is emptied before it is consulted, so
 * neither the consultation nor the write can go through it.
 *
 * ## The limit of this guarantee
 *
 * The JDK offers no way to hold a directory and act relative to it without
 * following a symbolic link in the last component, and no way to open a path
 * whose every component is verified in the same instant as the effect. What
 * this module offers is therefore a refusal it can OBSERVE: a link that is
 * there when the chain is looked at stops the operation. A link that appears
 * between that look and the effect is not observable by any portable API, and
 * the JDK is not a jail. The promise this module makes is the one it can keep:
 * an operation never knowingly acts through a link, and it never acts outside
 * the root on a path the decision did not resolve.
 *
 * ## Why `WorkspacePathResolver.rejectSymlinkEscape` is the verdict
 * That function already existed, documented as the one place a real path is
 * judged against a real root — and had zero callers. Re-deciding containment
 * here would have created a second authority for a security boundary, which is
 * exactly what its own KDoc forbids. One verdict, one owner.
 */
internal class WorkspaceAnchoring(private val fileSystem: WorkspacePathSystem) {

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
        while (ancestor != null && !fileSystem.exists(ancestor)) {
            ancestor = ancestor.parent
        }
        val existing: Path = ancestor ?: return path
        // `relativize` is the java.nio member. The kotlin.io `relativeTo`
        // extension also exists and resolves to a File here, which does not
        // compile; the member is the intended call anyway.
        val suffix: Path = existing.relativize(path)
        val resolved: Path = fileSystem.realPath(existing)
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

    /**
     * Prepares the chain of directories the effect needs, without crossing a link.
     *
     * The chain is the run of components between [realRoot] and [directory]. A
     * component that is a symbolic link stops the preparation: the effect would
     * land on the target of the link, and the target is not inside the root
     * unless the decision already resolved it. A component that is missing is
     * created here, one at a time, and the creation is what makes it a
     * directory rather than a name.
     *
     * @param realRoot the real path of the authorized root.
     * @param directory the directory the effect needs.
     * @return false when the chain crosses a symbolic link, and the caller has
     *   to refuse. True when the chain is a run of real directories.
     */
    fun prepareDirectoryChain(realRoot: Path, directory: Path): Boolean {
        if (!directory.startsWith(realRoot)) return false
        var current = realRoot
        for (name in realRoot.relativize(directory)) {
            current = current.resolve(name)
            if (fileSystem.isSymbolicLink(current)) return false
            if (!fileSystem.exists(current) && !fileSystem.createDirectory(current)) {
                // The entry was there between the question and the answer. A
                // directory is acceptable; anything else is not, and a link is
                // exactly what this rule is about.
                if (fileSystem.isSymbolicLink(current) || !fileSystem.isDirectory(current)) return false
            }
        }
        return true
    }

    /**
     * Whether the chain between [realRoot] and [path] is free of symbolic links.
     *
     * A component that is missing is free of links: the target is simply not
     * there, and the operation that asks reports it as absent. A component that
     * is a link is not free, and neither is the leaf: an effect applied to a
     * leaf that is a link follows it.
     *
     * @param realRoot the real path of the authorized root.
     * @param path the path the effect is about to touch.
     * @return false when the chain crosses a symbolic link, and the caller has
     *   to refuse.
     */
    fun chainIsFreeOfLinks(realRoot: Path, path: Path): Boolean {
        if (!path.startsWith(realRoot)) return false
        var current = realRoot
        for (name in realRoot.relativize(path)) {
            current = current.resolve(name)
            if (fileSystem.isSymbolicLink(current)) return false
            if (!fileSystem.exists(current)) return true
        }
        return true
    }
}
