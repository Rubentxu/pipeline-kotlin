package dev.rubentxu.pipeline.v2.domain.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * RP034-B — invariant and property tests for the workspace domain model.
 *
 * These run with no filesystem, no runtime, no coordinator: the model is a pure
 * value algebra, so its laws are testable in isolation (AGENTS.md review
 * checklist #2, "can this be pure?").
 */
@DisplayName("RP034-B workspace domain model")
class WorkspaceModelTest {

    private val root = Path.of("/ws/project")
    private val attached = WorkspaceLease.Attached(root)
    private val managed = WorkspaceLease.Managed(Path.of("/ctl/workspace/stage-0"))
    private val location = ExecutionLocation(attached, root)

    private fun resolved(anchor: PathAnchor, path: String): Path =
        (WorkspacePathResolver.resolve(location, anchor, path) as PathResolution.Resolved).path

    private fun rejection(anchor: PathAnchor, path: String): WorkspacePathError =
        (WorkspacePathResolver.resolve(location, anchor, path) as PathResolution.Rejected).reason

    @Nested
    @DisplayName("ownership is typed, never inferred")
    inner class Ownership {

        @Test
        fun `attached is always USER and managed is always PIPELINEK`() {
            assertEquals(WorkspaceOwnership.USER, attached.ownership)
            assertEquals(WorkspaceOwnership.PIPELINEK, managed.ownership)
        }

        @Test
        fun `a bare directory with no VCS marker is still user-owned when attached`() {
            // The defect that motivated ADR-0102: a non-VCS project used to read
            // as disposable scratch. The type now carries the answer directly.
            val plainDirectory = Path.of("/home/dev/my-project")
            val lease = WorkspaceLease.Attached(plainDirectory)
            assertEquals(WorkspaceOwnership.USER, lease.ownership)
            assertTrue(
                WorkspacePathResolver.authorizeRootDestruction(lease, "deleteDir")
                    is DestructiveAuthorization.Refused,
                "a plain non-VCS project must still be protected",
            )
        }

        @Test
        fun `a git checkout attached is no different from a non-VCS one`() {
            val gitCheckout = WorkspaceLease.Attached(Path.of("/home/dev/git-project"))
            val plainProject = WorkspaceLease.Attached(Path.of("/home/dev/plain-project"))
            // Both refuse; only the reported root differs, which is correct since
            // the refusal names the directory actually being protected.
            listOf(gitCheckout, plainProject).forEach { lease ->
                val outcome = WorkspacePathResolver.authorizeRootDestruction(lease, "cleanWs")
                assertTrue(
                    outcome is DestructiveAuthorization.Refused,
                    "ownership must not depend on the VCS type of the directory",
                )
            }
            assertEquals(WorkspaceOwnership.USER, gitCheckout.ownership)
            assertEquals(WorkspaceOwnership.USER, plainProject.ownership)
        }
    }

    @Nested
    @DisplayName("destructive safety")
    inner class DestructiveSafety {

        @Test
        fun `attached root refuses deleteDir and cleanWs`() {
            listOf("deleteDir", "cleanWs").forEach { operation ->
                val outcome = WorkspacePathResolver.authorizeRootDestruction(attached, operation)
                assertTrue(outcome is DestructiveAuthorization.Refused, "$operation must fail closed")
                val reason = (outcome as DestructiveAuthorization.Refused).reason
                assertTrue(
                    reason is WorkspacePathError.ProtectedWorkspaceRoot,
                    "expected ProtectedWorkspaceRoot, was $reason",
                )
            }
        }

        @Test
        fun `managed root keeps lifecycle cleanup`() {
            listOf("deleteDir", "cleanWs").forEach { operation ->
                assertEquals(
                    DestructiveAuthorization.Permitted,
                    WorkspacePathResolver.authorizeRootDestruction(managed, operation),
                    "$operation must remain allowed on PipelineK-managed scratch",
                )
            }
        }
    }

    @Nested
    @DisplayName("cwd and root are distinct responsibilities")
    inner class CwdAndRoot {

        @Test
        fun `an ExecutionLocation may hold the same path for both without conflating them`() {
            val same = ExecutionLocation(attached, attached.root)
            assertEquals(same.workspace.root, same.cwd)
            assertEquals(
                Path.of("/ws/project"),
                WorkspacePathResolver.baseFor(same, PathAnchor.WORKSPACE_ROOT),
            )
            assertEquals(
                Path.of("/ws/project"),
                WorkspacePathResolver.baseFor(same, PathAnchor.CURRENT_DIRECTORY),
            )
        }

        @Test
        fun `a nested cwd resolves against the current directory, not the root`() {
            val nested = ExecutionLocation(attached, root.resolve("backend"))
            assertEquals(
                root.resolve("backend"),
                WorkspacePathResolver.baseFor(nested, PathAnchor.CURRENT_DIRECTORY),
            )
            assertEquals(
                root,
                WorkspacePathResolver.baseFor(nested, PathAnchor.WORKSPACE_ROOT),
                "the workspace root must not follow the cwd into a dir scope",
            )
        }
    }

    @Nested
    @DisplayName("dir derives cwd and preserves the lease")
    inner class DirSemantics {

        private fun derive(path: String) =
            WorkspacePathResolver.deriveDirectory(location, path) as DirDerivation.Derived

        private fun deriveRejected(path: String) =
            WorkspacePathResolver.deriveDirectory(location, path) as DirDerivation.Rejected

        @Test
        fun `dir changes only the cwd`() {
            val child = derive("backend").location
            assertEquals(root.resolve("backend"), child.cwd)
            assertEquals(root, child.workspace.root, "dir must not redefine the workspace root")
            assertEquals(WorkspaceOwnership.USER, child.workspace.ownership)
        }

        @Test
        fun `nested dir composes from the parent cwd`() {
            val outer = derive("backend").location
            val inner = WorkspacePathResolver.deriveDirectory(outer, "api") as DirDerivation.Derived
            assertEquals(root.resolve("backend/api"), inner.location.cwd)
            assertEquals(root, inner.location.workspace.root)
        }

        @Test
        fun `leaving a dir scope restores the parent by discarding the child`() {
            val parent = location
            val child = derive("backend").location
            // No global state to undo: the parent value is simply still in hand.
            assertEquals(root, parent.cwd)
            assertNotEquals(parent.cwd, child.cwd)
        }

        @Test
        fun `dir rejects escapes and absolute paths`() {
            assertTrue(
                deriveRejected("../outside").reason is WorkspacePathError.EscapesWorkspace,
                "dir must not escape the workspace",
            )
            assertTrue(
                deriveRejected("/tmp/foo").reason is WorkspacePathError.AbsolutePathNotAllowed,
                "dir must not accept an absolute path as an escape hatch",
            )
        }

        @Test
        fun `dir into dot and into a nested subdirectory are accepted`() {
            assertEquals(root, derive(".").location.cwd, "dir('.') is a semantic no-op")
            assertEquals(root.resolve("backend/api"), derive("backend/api").location.cwd)
        }
    }

    @Nested
    @DisplayName("path resolution is total and confinement-checked")
    inner class Resolution {

        @Test
        fun `relative path resolves against the current directory`() {
            assertEquals(
                root.resolve("out.txt"),
                resolved(PathAnchor.CURRENT_DIRECTORY, "out.txt"),
            )
        }

        @Test
        fun `relative path resolves against the root when anchored there`() {
            assertEquals(root.resolve("out.txt"), resolved(PathAnchor.WORKSPACE_ROOT, "out.txt"))
        }

        @Test
        fun `the two anchors diverge inside a dir scope`() {
            val nested = ExecutionLocation(attached, root.resolve("backend"))
            val fromCwd = WorkspacePathResolver.resolve(
                nested, PathAnchor.CURRENT_DIRECTORY, "out.txt",
            ) as PathResolution.Resolved
            val fromRoot = WorkspacePathResolver.resolve(
                nested, PathAnchor.WORKSPACE_ROOT, "out.txt",
            ) as PathResolution.Resolved
            assertEquals(root.resolve("backend/out.txt"), fromCwd.path)
            assertEquals(root.resolve("out.txt"), fromRoot.path)
        }

        @Test
        fun `traversal outside the root is rejected`() {
            assertTrue(
                rejection(PathAnchor.CURRENT_DIRECTORY, "../../etc/passwd")
                    is WorkspacePathError.EscapesWorkspace,
            )
        }

        @Test
        fun `absolute path inside the root is accepted for legacy contracts`() {
            assertEquals(
                root.resolve("legacy/absolute.txt"),
                resolved(PathAnchor.CURRENT_DIRECTORY, root.resolve("legacy/absolute.txt").toString()),
            )
        }

        @Test
        fun `absolute path outside the root is rejected`() {
            assertTrue(
                rejection(PathAnchor.CURRENT_DIRECTORY, "/etc/passwd")
                    is WorkspacePathError.AbsolutePathNotAllowed,
            )
        }

        @Test
        fun `an empty path is rejected rather than silently meaning the root`() {
            assertTrue(rejection(PathAnchor.CURRENT_DIRECTORY, "") is WorkspacePathError.InvalidPath)
        }

        @Test
        fun `symlink escape is decided by the authorising adapter, not here`() {
            val outcome = WorkspacePathResolver.rejectSymlinkEscape(Path.of("/etc/shadow"), root)
            assertTrue((outcome as PathResolution.Rejected).reason is WorkspacePathError.SymlinkEscape)
        }
    }

    @Nested
    @DisplayName("CLI intent is a closed type, not a nullable path")
    inner class Requests {

        @Test
        fun `the three CLI modes are distinct cases`() {
            val requests: List<WorkspaceRequest> = listOf(
                WorkspaceRequest.AttachInvocationDirectory,
                WorkspaceRequest.AttachExplicit(Path.of("/ws")),
                WorkspaceRequest.ManagedIsolated,
            )
            assertEquals(3, requests.toSet().size)
        }

        @Test
        fun `managed isolated implies PipelineK ownership once allocated`() {
            assertEquals(WorkspaceOwnership.PIPELINEK, WorkspaceLease.Managed(Path.of("/ctl/ws")).ownership)
        }

        @Test
        fun `allocation policy is independent of origin`() {
            val policy: WorkspaceAllocationPolicy = WorkspaceAllocationPolicy.StageIsolated
            assertEquals(WorkspaceAllocationPolicy.StageIsolated, policy)
            // The same policy can apply to either origin; origin lives in the lease.
            assertEquals(WorkspaceOwnership.USER, attached.ownership)
            assertEquals(WorkspaceOwnership.PIPELINEK, managed.ownership)
        }
    }
}
