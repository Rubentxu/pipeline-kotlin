package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathError
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * RP034-G — ownership is typed, and destruction of a user's checkout is refused.
 *
 * ADR-0102 removes the VCS-marker heuristic as the ownership authority. The
 * cases below cover the full matrix the slice must demonstrate: every kind of
 * directory a user might point a run at, and the rule that none of them may be
 * destroyed by default when the workspace is Attached.
 *
 * The matrix deliberately includes a bare non-VCS project and a `.git` *file*
 * (worktree/submodule). Both are the shapes the old heuristic got wrong: the
 * first was treated as disposable scratch, the second was only correct by
 * accident of checking existence rather than type.
 */
@DisplayName("RP034-G ownership and destructive safety")
class DestructiveSafetyOwnershipTest {

    private fun refusal(operation: String, root: Path) {
        val lease = WorkspaceLease.Attached(root)
        val outcome = WorkspacePathResolver.authorizeRootDestruction(lease, operation)
        assertTrue(
            outcome is DestructiveAuthorization.Refused,
            "$operation must be refused on an attached root, was $outcome",
        )
        val reason = (outcome as DestructiveAuthorization.Refused).reason
        assertTrue(
            reason is WorkspacePathError.ProtectedWorkspaceRoot,
            "expected ProtectedWorkspaceRoot, was $reason",
        )
        assertEquals(root, (reason as WorkspacePathError.ProtectedWorkspaceRoot).root)
    }

    @Nested
    @DisplayName("every shape of user project is protected")
    inner class ProjectShapes {

        @Test
        fun `a plain non-VCS project is protected`(@TempDir tempDir: Path) {
            val project = Files.createDirectory(tempDir.resolve("plain-project"))
            Files.writeString(project.resolve("README.md"), "no vcs here")
            refusal("deleteDir", project)
            refusal("cleanWs", project)
        }

        @Test
        fun `a git repository is protected`(@TempDir tempDir: Path) {
            val project = Files.createDirectory(tempDir.resolve("git-project"))
            Files.createDirectory(project.resolve(".git"))
            refusal("deleteDir", project)
            refusal("cleanWs", project)
        }

        @Test
        fun `a git worktree or submodule, where the git marker is a file, is protected`(@TempDir tempDir: Path) {
            val project = Files.createDirectory(tempDir.resolve("worktree-project"))
            Files.writeString(project.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/x")
            refusal("deleteDir", project)
        }

        @Test
        fun `mercurial and subversion checkouts are protected`(@TempDir tempDir: Path) {
            val hg = Files.createDirectory(tempDir.resolve("hg-project"))
            Files.createDirectory(hg.resolve(".hg"))
            val svn = Files.createDirectory(tempDir.resolve("svn-project"))
            Files.createDirectory(svn.resolve(".svn"))
            refusal("deleteDir", hg)
            refusal("deleteDir", svn)
        }

        @Test
        fun `an empty directory the user named is still protected`(@TempDir tempDir: Path) {
            refusal("deleteDir", Files.createDirectory(tempDir.resolve("empty")))
        }

        @Test
        fun `a nested directory inside a repository is protected too`(@TempDir tempDir: Path) {
            val repo = Files.createDirectory(tempDir.resolve("monorepo"))
            Files.createDirectory(repo.resolve(".git"))
            val nested = Files.createDirectories(repo.resolve("services/api"))
            refusal("cleanWs", nested)
        }
    }

    @Nested
    @DisplayName("ownership does not depend on what is on disk")
    inner class NoInference {

        @Test
        fun `the same directory type yields the same decision regardless of markers`(@TempDir tempDir: Path) {
            val withGit = Files.createDirectory(tempDir.resolve("a"))
            Files.createDirectory(withGit.resolve(".git"))
            val withoutGit = Files.createDirectory(tempDir.resolve("b"))

            val a = WorkspaceLease.Attached(withGit)
            val b = WorkspaceLease.Attached(withoutGit)

            assertEquals(a.ownership, b.ownership)
            // Both must be refused. The refusals name different roots, so the
            // decision *shape* is compared rather than the values.
            assertTrue(
                WorkspacePathResolver.authorizeRootDestruction(a, "deleteDir")
                    is DestructiveAuthorization.Refused,
            )
            assertTrue(
                WorkspacePathResolver.authorizeRootDestruction(b, "deleteDir")
                    is DestructiveAuthorization.Refused,
                "ownership must not be inferred from a VCS marker",
            )
        }

        @Test
        fun `the legacy detector still exists but is no longer consulted for ownership`() {
            // The detector may remain as a diagnostic; what must not happen is a
            // destructive decision depending on it. Both lease cases are decided
            // purely by type.
            assertEquals(WorkspaceOwnership.USER, WorkspaceLease.Attached(Path.of("/x")).ownership)
            assertEquals(WorkspaceOwnership.PIPELINEK, WorkspaceLease.Managed(Path.of("/x")).ownership)
        }
    }

    @Nested
    @DisplayName("managed scratch keeps its lifecycle cleanup")
    inner class ManagedScratch {

        @Test
        fun `PipelineK-owned scratch may be wiped at the root`(@TempDir tempDir: Path) {
            val scratch = Files.createDirectory(tempDir.resolve("scratch"))
            val lease = WorkspaceLease.Managed(scratch)
            assertEquals(
                DestructiveAuthorization.Permitted,
                WorkspacePathResolver.authorizeRootDestruction(lease, "cleanWs"),
            )
            assertEquals(
                DestructiveAuthorization.Permitted,
                WorkspacePathResolver.authorizeRootDestruction(lease, "deleteDir"),
            )
        }

        @Test
        fun `ownership is identical whether or not the scratch looks like a repo`(@TempDir tempDir: Path) {
            val plain = WorkspaceLease.Managed(Files.createDirectory(tempDir.resolve("s1")))
            val looksLikeRepo = Files.createDirectory(tempDir.resolve("s2"))
            Files.createDirectory(looksLikeRepo.resolve(".git"))
            val marked = WorkspaceLease.Managed(looksLikeRepo)

            assertEquals(
                WorkspacePathResolver.authorizeRootDestruction(plain, "deleteDir"),
                WorkspacePathResolver.authorizeRootDestruction(marked, "deleteDir"),
                "a managed workspace must keep its cleanup contract whatever its contents",
            )
        }
    }

    @Nested
    @DisplayName("subdirectory cleanup remains allowed")
    inner class Subdirectories {

        @Test
        fun `a subdirectory of an attached workspace is not the protected root`(@TempDir tempDir: Path) {
            val project = Files.createDirectory(tempDir.resolve("project"))
            val build = Files.createDirectory(project.resolve("build"))
            // Confinement is what governs a subdirectory, and the subdirectory
            // itself is not the lease root, so it is not the protected case.
            assertTrue(build != project)
            refusal("deleteDir", project)
            // Deleting `build` is a different target and is permitted by
            // confinement; this test pins that the protection is root-scoped.
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.workspace.PathResolution.Resolved(build),
                WorkspacePathResolver.resolve(
                    dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation(
                        WorkspaceLease.Attached(project.toRealPath()),
                        project.toRealPath(),
                    ),
                    dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor.CURRENT_DIRECTORY,
                    "build",
                ),
            )
        }
    }
}
