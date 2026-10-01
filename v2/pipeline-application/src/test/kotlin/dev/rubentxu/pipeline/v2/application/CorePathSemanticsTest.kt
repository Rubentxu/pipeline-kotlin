package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * RP034-D — core path semantics.
 *
 * The law RP034-D establishes is that the filesystem Steps and the shell Steps
 * observe the **same** current directory inside a `dir` scope, and that the
 * workspace root stays fixed while they do.
 *
 * The behaviour these cases assert is what ADR-0100 §2 and §6 require of the
 * migrated verticals. They run against the domain authority, which is the
 * single implementation the adapters are being moved onto, so the suite states
 * the contract the migration must satisfy rather than re-observing the legacy
 * `WorkspaceResolver` path.
 */
@DisplayName("RP034-D core path semantics")
class CorePathSemanticsTest {

    private val root: Path
    private val location: ExecutionLocation

    init {
        root = Path.of("/ws/project").toAbsolutePath().normalize()
        location = ExecutionLocation(WorkspaceLease.Attached(root), root)
    }

    @Nested
    @DisplayName("shell and filesystem share one cwd")
    inner class ShellAndFilesystemAgree {

        @Test
        fun `a relative file path inside dir resolves under the dir, not the root`() {
            val child = derive("backend")
            val fileTarget = resolve(child, PathAnchor.CURRENT_DIRECTORY, "build/out.txt")

            assertEquals(
                root.resolve("backend/build/out.txt"),
                fileTarget,
                "a file Step must observe the same cwd the shell does",
            )
        }

        @Test
        fun `pwd-style observation and file resolution agree on the directory`() {
            val child = derive("backend")
            val shellCwd = child.cwd
            val fileTarget = resolve(child, PathAnchor.CURRENT_DIRECTORY, "marker.txt")

            assertEquals(
                shellCwd.resolve("marker.txt"),
                fileTarget,
                "shell cwd and filesystem base must not diverge",
            )
        }

        @Test
        fun `workspace root is invariant across the scope`() {
            val child = derive("backend")
            assertEquals(
                root,
                child.workspace.root,
                "the workspace root must not follow the cwd (INV-WS-001)",
            )
            assertNotEquals(child.workspace.root, child.cwd)
        }
    }

    @Nested
    @DisplayName("nested dir composes")
    inner class NestedDir {

        @Test
        fun `two levels resolve from the outermost root`() {
            val inner = derive(derive("backend"), "api")
            assertEquals(root.resolve("backend/api"), inner.cwd)
            assertEquals(
                root.resolve("backend/api/deep/file.txt"),
                resolve(inner, PathAnchor.CURRENT_DIRECTORY, "deep/file.txt"),
            )
        }

        @Test
        fun `leaving a scope returns the parent without any global state`() {
            val parent = derive("backend")
            val child = derive(parent, "api")
            // The parent value is untouched; there is nothing to restore.
            assertEquals(root.resolve("backend"), parent.cwd)
            assertEquals(root.resolve("backend/api"), child.cwd)
            assertEquals(root, location.cwd, "the outermost context is still the workspace root")
        }
    }

    @Nested
    @DisplayName("confines every workspace-scoped step")
    inner class Confinement {

        @Test
        fun `traversal and absolute escapes are refused for file steps too`() {
            val child = derive("backend")
            assertTrue(outcome(child, PathAnchor.CURRENT_DIRECTORY, "../../../etc/passwd") is PathResolution.Rejected)
            assertTrue(outcome(child, PathAnchor.CURRENT_DIRECTORY, "/etc/shadow") is PathResolution.Rejected)
        }

        @Test
        fun `a path escaping from inside a dir scope is still refused`() {
            // The scope is already nested; `..` must not climb past the root.
            val inner = derive(derive("backend"), "api")
            val rejected = outcome(inner, PathAnchor.CURRENT_DIRECTORY, "../../../outside.txt")
            assertTrue(
                rejected is PathResolution.Rejected,
                "a nested scope must not become an escape hatch",
            )
        }
    }

    @Nested
    @DisplayName("root-anchored steps keep the stable boundary")
    inner class RootAnchored {

        @Test
        fun `a root-anchored step ignores the dir scope`() {
            val child = derive("backend")
            assertEquals(
                root.resolve("artifacts/out.zip"),
                resolve(child, PathAnchor.WORKSPACE_ROOT, "artifacts/out.zip"),
                "a WORKSPACE_ROOT anchor must not follow the cwd",
            )
        }
    }

    private fun derive(loc: ExecutionLocation, path: String): ExecutionLocation =
        (dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
            .deriveDirectory(loc, path) as dev.rubentxu.pipeline.v2.domain.workspace.DirDerivation.Derived)
            .location

    /** Convenience for the common case: derive from the top-level location. */
    private fun derive(path: String): ExecutionLocation = derive(location, path)

    private fun resolve(loc: ExecutionLocation, anchor: PathAnchor, path: String): Path =
        (dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
            .resolve(loc, anchor, path) as PathResolution.Resolved).path

    /**
     * The raw outcome, for cases that assert a rejection.
     *
     * Kept separate from [resolve] so a refusal is observed as a refusal rather
     * than surfacing as a cast failure: a test that means to check confinement
     * must not fail with a ClassCastException that hides the reason.
     */
    private fun outcome(loc: ExecutionLocation, anchor: PathAnchor, path: String): PathResolution =
        dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver.resolve(loc, anchor, path)

    @Test
    @DisplayName("a real attached workspace rejects root destruction regardless of its contents")
    fun `destructive safety holds for a real directory`(@TempDir tempDir: Path) {
        val project = Files.createDirectory(tempDir.resolve("plain-project"))
        val lease = WorkspaceLease.Attached(project.toRealPath())
        val outcome = dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
            .authorizeRootDestruction(lease, "deleteDir")
        assertTrue(
            outcome is dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization.Refused,
            "a real non-VCS project directory must still be protected",
        )
    }
}
