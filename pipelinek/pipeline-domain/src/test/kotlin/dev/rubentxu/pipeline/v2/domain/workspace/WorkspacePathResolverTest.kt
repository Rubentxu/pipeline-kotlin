package dev.rubentxu.pipeline.v2.domain.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The confinement every workspace-aware step leans on.
 *
 * [WorkspacePathResolver] is the single authority that decides whether a path a
 * pipeline named may be touched, and it has no substitute: the operations adapter,
 * the workspace operations, the cleanup adapter, the file steps, the `dir(...)`
 * scope and the plugin path anchor all resolve through it. A step that resolved
 * its own base would answer a different question from the one the pipeline author
 * asked, and the difference is exactly the boundary.
 *
 * The resolution is arithmetic on [Path] — no filesystem, no clock, no subprocess —
 * so every case below is decidable without creating a directory. That is what
 * makes the guard observable at all: the only other observer of these decisions
 * is an end-to-end pipeline that would have to arrange a real workspace to reach
 * them, and the one case it does arrange — a symlink leaving the workspace — is
 * decided elsewhere, by the adapter that can read real paths.
 */
class WorkspacePathResolverTest {

    private val root: Path = Paths.get("/home/rub/proyecto")
    private val managed = ExecutionLocation(workspace = WorkspaceLease.Managed(root), cwd = root)

    // --- inside the workspace -------------------------------------------------------

    @Test
    fun `a relative path resolves against the current directory`() {
        val resolved = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "sub/inner")

        assertEquals(Paths.get("/home/rub/proyecto/sub/inner"), (resolved as PathResolution.Resolved).path)
    }

    @Test
    fun `the workspace root anchor ignores the current directory`() {
        // The two anchors answer different questions. `dir("sub")` moves where the next
        // operation happens; it does not move the boundary. A step that declared the
        // root anchor must resolve against the root even from inside a subdirectory.
        val inside = managed.copy(cwd = root.resolve("sub"))

        val resolved = WorkspacePathResolver.resolve(inside, PathAnchor.WORKSPACE_ROOT, "file.txt")

        assertEquals(root.resolve("file.txt"), (resolved as PathResolution.Resolved).path)
    }

    @Test
    fun `a traversal that comes back is still inside`() {
        // `sub/../other` never leaves, so refusing it would be the opposite defect: a
        // guard too tight closes a workspace the operator opened.
        val resolved = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "sub/../other")

        assertEquals(root.resolve("other"), (resolved as PathResolution.Resolved).path)
    }

    @Test
    fun `an absolute path inside the root is accepted`() {
        val resolved = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "/home/rub/proyecto/inside")

        assertEquals(Paths.get("/home/rub/proyecto/inside"), (resolved as PathResolution.Resolved).path)
    }

    // --- outside the workspace ------------------------------------------------------

    @Test
    fun `a traversal out of the root is refused`() {
        val rejected = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "../outside")

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.EscapesWorkspace, "was $reason")
    }

    @Test
    fun `an absolute path outside the root is refused`() {
        // Absolute input is honoured as absolute — it is not silently re-rooted — and
        // then still has to pass containment. Trusting an absolute path because it was
        // spelled out would make one written path the way to reach the whole host.
        val rejected = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "/etc/passwd")

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.AbsolutePathNotAllowed, "was $reason")
    }

    @Test
    fun `a sibling that shares the root's text is refused`() {
        // A character prefix says "/home/rub/proyecto2" begins with "/home/rub/proyecto",
        // which is true of the text and false of the filesystem: they are siblings. A
        // guard that compared strings would let this one through.
        val rejected = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "/home/rub/proyecto2")

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.AbsolutePathNotAllowed, "was $reason")
    }

    @Test
    fun `a relative path to a sibling that shares the root's text is refused`() {
        // The same sibling, reached the way a step actually asks for it: relative to
        // the workspace. An absolute path and a relative one take different branches of
        // the decision, and a guard that only disciplined the absolute one would leave
        // this door open.
        val rejected = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "../proyecto2")

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.EscapesWorkspace, "was $reason")
    }

    @Test
    fun `an empty path is refused`() {
        val rejected = WorkspacePathResolver.resolve(managed, PathAnchor.CURRENT_DIRECTORY, "")

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.InvalidPath, "was $reason")
    }

    @Test
    fun `the boundary is the root, not the current directory`() {
        // Two neighbours of the same decision, and the difference between them is the
        // whole point of keeping the root and the current directory apart. From inside
        // `sub`, a path that leaves the current directory but stays in the workspace is
        // allowed; one that leaves the workspace is not. A guard that measured the
        // current directory would refuse the first, and one that measured nothing
        // would allow the second.
        val inside = managed.copy(cwd = root.resolve("sub"))

        val staying = WorkspacePathResolver.resolve(inside, PathAnchor.CURRENT_DIRECTORY, "../sibling")
        assertEquals(root.resolve("sibling"), (staying as PathResolution.Resolved).path)

        val leaving = WorkspacePathResolver.resolve(inside, PathAnchor.CURRENT_DIRECTORY, "../../elsewhere")
        val reason = (leaving as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.EscapesWorkspace, "was $reason")
    }

    // --- the link that leaves the root ----------------------------------------------

    @Test
    fun `a real path that leaves the root is refused`() {
        // Decided by the adapter that can read real paths, so it is a separate decision
        // from the resolution above and it is refused on its own terms.
        val rejected = WorkspacePathResolver.rejectSymlinkEscape(Paths.get("/home/rub/proyecto/../etc"), root)

        val reason = (rejected as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.SymlinkEscape, "was $reason")
    }

    @Test
    fun `a real path inside the root is accepted`() {
        val resolved = WorkspacePathResolver.rejectSymlinkEscape(root.resolve("sub/file"), root)

        assertEquals(root.resolve("sub/file"), (resolved as PathResolution.Resolved).path)
    }

    // --- the root that may not be destroyed -----------------------------------------

    @Test
    fun `a user-owned root refuses a root-wide destruction`() {
        // The lease is the whole mechanism: the root a user named is theirs, and a
        // root-wide cleanup must fail closed on it whatever it contains.
        val attached = WorkspaceLease.Attached(root)

        val refusal = WorkspacePathResolver.authorizeRootDestruction(attached, "deleteDir")

        val reason = (refusal as DestructiveAuthorization.Refused).reason
        assertTrue(reason is WorkspacePathError.ProtectedWorkspaceRoot, "was $reason")
    }

    @Test
    fun `a managed root permits a root-wide destruction`() {
        // Pinned because the refusal above is also satisfied by refusing everything.
        val refusal = WorkspacePathResolver.authorizeRootDestruction(WorkspaceLease.Managed(root), "deleteDir")

        assertEquals(DestructiveAuthorization.Permitted, refusal)
    }

    // --- the scope that moves -------------------------------------------------------

    @Test
    fun `a derived scope moves the current directory and keeps the root`() {
        val derived = WorkspacePathResolver.deriveDirectory(managed, "sub")

        val location = (derived as DirDerivation.Derived).location
        assertEquals(root.resolve("sub"), location.cwd)
        assertEquals(root, location.workspace.root)
    }

    @Test
    fun `a derived scope that leaves the root is refused`() {
        val derived = WorkspacePathResolver.deriveDirectory(managed, "../outside")

        val reason = (derived as DirDerivation.Rejected).reason
        assertTrue(reason is WorkspacePathError.EscapesWorkspace, "was $reason")
    }
}
