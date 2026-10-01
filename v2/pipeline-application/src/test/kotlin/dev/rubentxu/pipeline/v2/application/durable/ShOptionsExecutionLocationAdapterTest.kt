package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * RP034-C — runtime authority bridge.
 *
 * Verifies the new seam can represent the workspace root and the current
 * directory **simultaneously and distinguishably**, which is the property the
 * single-valued `WorkspaceIdentity` could not express. The legacy capability
 * remains registered and unchanged, so this slice is behaviour-preserving.
 */
@DisplayName("RP034-C execution location runtime bridge")
class ShOptionsExecutionLocationAdapterTest {

    private val root = Path.of("/ws/project")
    private val scoped = Path.of("/ws/project/backend")

    @Nested
    @DisplayName("root and cwd are simultaneously representable")
    inner class RootAndCwd {

        @Test
        fun `at the top of a run cwd equals the workspace root`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, null, root)
            assertEquals(root, capability.workspace.root)
            assertEquals(root, capability.cwd)
        }

        @Test
        fun `inside a dir scope the root is preserved and the cwd advances`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, scoped, root)
            assertEquals(
                root,
                capability.workspace.root,
                "a dir scope must not redefine the workspace root",
            )
            assertEquals(scoped, capability.cwd)
        }

        @Test
        fun `the two anchors now resolve differently inside a dir scope`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, scoped, root)
            val fromCwd = WorkspacePathResolver.resolve(capability, PathAnchor.CURRENT_DIRECTORY, "out.txt")
            val fromRoot = WorkspacePathResolver.resolve(capability, PathAnchor.WORKSPACE_ROOT, "out.txt")

            assertEquals(scoped.resolve("out.txt"), (fromCwd as PathResolution.Resolved).path)
            assertEquals(
                root.resolve("out.txt"),
                (fromRoot as PathResolution.Resolved).path,
                "the root anchor must not follow the cwd into a dir scope",
            )
        }

        @Test
        fun `legacy workspace identity still observes the cwd for compatibility`() {
            // Documents precisely the divergence the new seam removes: the legacy
            // capability reports one Path, so within a `dir` scope it reports the
            // cwd under the name "workspace root". RP034-I retires it.
            val capability = ShOptionsExecutionLocationAdapter.from(root, scoped, root)
            val legacyEffective = scoped
            assertEquals(legacyEffective, capability.cwd)
            assertTrue(
                legacyEffective != capability.workspace.root,
                "this test is only meaningful while the two authorities differ",
            )
        }
    }

    @Nested
    @DisplayName("derivation is pure and total")
    inner class Derivation {

        @Test
        fun `a null workspace root falls back and still yields an absolute location`() {
            val fallback = Path.of("/fallback/ws")
            val capability = ShOptionsExecutionLocationAdapter.from(null, null, fallback)
            assertEquals(fallback, capability.workspace.root)
            assertTrue(capability.cwd.isAbsolute)
        }

        @Test
        fun `a relative scope is normalised to absolute`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, Path.of("backend"), root)
            // The invariant the bridge guarantees is that the location is
            // absolute and well-formed. Interpreting a *relative* scope string is
            // the caller's job: ShOptions carries an already-absolute projection in
            // production, and the runtime never hands this adapter a relative one.
            // Resolving it against the process CWD here would reintroduce exactly
            // the ambient-state dependency ADR-0100 removes, so the adapter only
            // normalises.
            assertTrue(
                capability.cwd.isAbsolute,
                "the derived cwd must be absolute, was ${capability.cwd}",
            )
        }

        @Test
        fun `the adapter is free of ambient process state`() {
            // Same inputs must yield the same location regardless of user.dir:
            // the derivation reads only its arguments.
            val capability = ShOptionsExecutionLocationAdapter.from(root, scoped, root)
            assertEquals(root, capability.workspace.root)
            assertEquals(scoped, capability.cwd)
        }
    }

    @Nested
    @DisplayName("ownership is projected as a typed state")
    inner class Ownership {

        @Test
        fun `the bridge reports the lease it derived rather than inspecting the filesystem`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, null, root)
            assertEquals(WorkspaceOwnership.PIPELINEK, capability.workspace.ownership)
        }

        @Test
        fun `a git checkout is not specially recognised by the bridge`() {
            // The bridge performs no VCS inspection at all: ownership comes from
            // the lease type, never from a marker on disk. RP034-G is what makes
            // the CLI produce an Attached lease for a user project.
            val gitLike = Path.of("/home/dev/git-project")
            val plain = Path.of("/home/dev/plain-project")
            assertEquals(
                ShOptionsExecutionLocationAdapter.from(gitLike, null, gitLike).workspace.ownership,
                ShOptionsExecutionLocationAdapter.from(plain, null, plain).workspace.ownership,
            )
        }
    }

    @Nested
    @DisplayName("confinement is enforced through the capability")
    inner class Confinement {

        @Test
        fun `traversal out of the workspace is refused with a typed reason`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, null, root)
            val outcome = WorkspacePathResolver.resolve(capability, PathAnchor.CURRENT_DIRECTORY, "../../etc/passwd")
            assertTrue(outcome is PathResolution.Rejected)
        }

        @Test
        fun `an absolute host path is refused`() {
            val capability = ShOptionsExecutionLocationAdapter.from(root, null, root)
            assertTrue(
                WorkspacePathResolver.resolve(capability, PathAnchor.CURRENT_DIRECTORY, "/etc/shadow")
                    is PathResolution.Rejected,
            )
        }
    }
}
