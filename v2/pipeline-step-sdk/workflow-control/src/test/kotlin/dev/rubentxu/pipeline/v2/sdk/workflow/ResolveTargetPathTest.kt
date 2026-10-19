package dev.rubentxu.pipeline.v2.sdk.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The confinement `dir(...)` applies before a nested step runs anywhere.
 * `dir("x")` runs arbitrary nested steps with `x` as their context, so where `x` resolves is the
 * boundary between "the pipeline wrote this" and "the pipeline reached something it did not
 * name". The KDoc on [DirExecutor.execute] promises a [PathTraversalException] when the path
 * escapes the workspace, which makes this a stated contract rather than an internal detail.
 * Every path below is one a pipeline author can type. Nothing here touches a filesystem: the
 * resolution is arithmetic on [Path], so the guard is decidable without creating a directory —
 * which is the only reason [DirExecutor.resolveTargetPath] is `internal` rather than `private`.
*/
class ResolveTargetPathTest {

    private val workspace: Path = Paths.get("/home/rub/proyecto")
    private val executor = DirExecutor { _, _ -> workspace }

    // --- the workspace is reachable, and only the workspace ------------------------

    @Test
    fun `a relative path resolves inside the workspace`() {
        assertEquals(
            Paths.get("/home/rub/proyecto/sub/inner"),
            executor.resolveTargetPath("sub/inner", workspace),
        )
    }

    @Test
    fun `a bare dot is the workspace root itself`() {
        // `dir(".")` is the Jenkins default and must stay legal; only leaving the root is not.
        assertEquals(workspace.normalize(), executor.resolveTargetPath(".", workspace))
    }

    @Test
    fun `an inner traversal that comes back is still inside`() {
        // `sub/../other` never leaves, so refusing it would be the opposite defect: a guard too
        // tight closes a workspace the operator opened.
        assertEquals(
            Paths.get("/home/rub/proyecto/other"),
            executor.resolveTargetPath("sub/../other", workspace),
        )
    }

    @Test
    fun `an unnormalised root resolves its own children`() {
        // The boundary is normalised alongside the target. Without that, `/ws/./sub` does not
        // start with `/ws/./` and the guard refuses a path inside the workspace.
        val messy = Paths.get("/home/rub/./proyecto")

        assertEquals(
            Paths.get("/home/rub/proyecto/sub"),
            executor.resolveTargetPath("sub", messy),
        )
    }

    // --- the sibling that shares a prefix is the case this file exists for ---------

    @Test
    fun `a sibling directory whose name begins with the root is refused`() {
        // The three escapes below all passed a `target.toString().startsWith(workspaceStr)`
        // guard, and all three name real directories outside the authorised root. A character
        // prefix says "/home/rub/proyecto2" begins with "/home/rub/proyecto", which is true of
        // the text and false of the filesystem: they are siblings, not parent and child.
        val escapes = listOf(
            "../proyecto-evil",
            "/home/rub/proyecto2",
            "../proyectoX/inner",
        )

        for (attempt in escapes) {
            val escaped = workspace.resolve(attempt).normalize()
            assertThrows(PathTraversalException::class.java, { executor.resolveTargetPath(attempt, workspace) }) {
                "\"$attempt\" resolved to $escaped, which is outside $workspace, and was allowed"
            }
        }
    }

    @Test
    fun `an ordinary escape is refused`() {
        // The case the guard was written for, pinned so the fix cannot be "refuse everything".
        assertThrows(PathTraversalException::class.java) {
            executor.resolveTargetPath("../other", workspace)
        }
        assertThrows(PathTraversalException::class.java) {
            executor.resolveTargetPath("../../etc", workspace)
        }
    }

    @Test
    fun `an absolute path outside the workspace is refused`() {
        // Absolute input is honoured as absolute -- it is not silently re-rooted -- and then
        // still has to pass containment. Trusting an absolute path because it was spelled out
        // would make `dir` the one way to leave the workspace without writing `..`.
        assertThrows(PathTraversalException::class.java) {
            executor.resolveTargetPath("/etc/passwd", workspace)
        }
    }

    @Test
    fun `an absolute path inside the workspace is allowed`() {
        // Pinned because the previous test's failure mode is a guard that refuses both sides.
        assertEquals(
            Paths.get("/home/rub/proyecto/inside-absolute"),
            executor.resolveTargetPath("/home/rub/proyecto/inside-absolute", workspace),
        )
    }

    // --- the refusal names what was attempted -------------------------------------

    @Test
    fun `the refusal carries the requested path, not only the resolved one`() {
        // An operator reading "traversal attempt" needs to know which path in their pipeline
        // did it. The resolved path alone is a consequence they cannot act on.
        val refusal = assertThrows(PathTraversalException::class.java) {
            executor.resolveTargetPath("../other", workspace)
        }

        assertEquals("../other", refusal.requestedPath)
        assertEquals(true, refusal.message.contains("/home/rub/other"))
    }
}
