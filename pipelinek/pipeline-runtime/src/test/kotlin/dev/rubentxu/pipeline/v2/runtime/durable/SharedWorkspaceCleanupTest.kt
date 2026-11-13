package dev.rubentxu.pipeline.v2.runtime.durable

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The cleanup of a workspace, and the one it may not touch.
 *
 * ## The guarantee under trial
 *
 * That the workspace the stages SHARE —the directory the run was invoked in,
 * or the one the operator named with `--workspace`— is never removed by the
 * cleanup of a stage, and that the workspace of a stage that belongs to the
 * run IS removed.
 *
 * ## Why the shared one is a different thing
 *
 * Because the shared workspace is the OPERATOR's directory: their project,
 * their code, the place a build expects to run in. The workspace of a stage
 * is the run's own scratch, under the control root, and removing it is what
 * the cleanup is for. A cleanup that cannot tell the two apart removes the
 * project instead of the scratch, and nothing in the tree would notice until
 * the project is gone.
 *
 * ## Why the refusal is loud
 *
 * Because a cleanup that does nothing in silence is indistinguishable from a
 * cleanup that found nothing to remove. The caller that asked has to learn
 * that the directory it named is not the run's to remove —and why.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SharedWorkspaceCleanupTest {

    /**
     * The cleanup of a stage does not remove the shared workspace.
     *
     * The shared workspace is the operator's directory: the cleanup refuses
     * it, says why, and the directory is still there afterwards.
     */
    @Test
    @DisplayName("the cleanup of a stage does not remove the shared workspace of the operator")
    fun theCleanupOfAStageDoesNotRemoveTheSharedWorkspaceOfTheOperator() {
        val shared = project.resolve("project")
        Files.createDirectories(shared)
        Files.writeString(shared.resolve("keep.txt"), "the operator's project")
        val resolver = WorkspaceResolver(controlRoot, shared)

        val refusal = assertThrows(
            IllegalArgumentException::class.java,
            { resolver.cleanupAfterComplete(resolver.resolve("build", 0)) },
            "the cleanup of the shared workspace has to be refused, not done in silence",
        )
        assertTrue(
            refusal.message?.contains("shared", ignoreCase = true) == true,
            "the refusal says that the workspace is shared: ${refusal.message}",
        )
        assertTrue(
            Files.exists(shared.resolve("keep.txt")),
            "the shared workspace is the directory of the operator, and it survives",
        )
    }

    /**
     * The cleanup of a stage does not remove a directory INSIDE the shared
     * workspace either.
     *
     * The directory of the operator is his in its whole: a subdirectory of it
     * is as much his as the root, and a cleanup that removes one of them
     * removes something the run was never given.
     */
    @Test
    @DisplayName("the cleanup of a stage does not remove a directory inside the shared workspace")
    fun theCleanupOfAStageDoesNotRemoveADirectoryInsideTheSharedWorkspace() {
        val shared = project.resolve("project")
        val inside = shared.resolve("module")
        Files.createDirectories(inside)
        Files.writeString(inside.resolve("keep.txt"), "the operator's module")
        val resolver = WorkspaceResolver(controlRoot, shared)

        assertThrows(
            IllegalArgumentException::class.java,
            { resolver.cleanupAfterComplete(inside) },
            "the cleanup of a directory inside the shared workspace has to be refused",
        )
        assertTrue(
            Files.exists(inside.resolve("keep.txt")),
            "a directory inside the shared workspace belongs to the operator, and it survives",
        )
    }

    /**
     * The cleanup of a stage removes the workspace of that stage.
     *
     * The other side of the same rule: what belongs to the run is removed,
     * and the method keeps the purpose it was written for.
     */
    @Test
    @DisplayName("the cleanup of a stage removes the workspace of that stage")
    fun theCleanupOfAStageRemovesTheWorkspaceOfThatStage() {
        val resolver = WorkspaceResolver(controlRoot, null)
        val workspace = resolver.resolve("build", 0)
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("out.txt"), "the output of the stage")

        resolver.cleanupAfterComplete(workspace)

        assertFalse(
            Files.exists(workspace),
            "the workspace of the stage is the run's own scratch, and the cleanup removes it",
        )
    }

    @TempDir
    lateinit var root: Path

    /** The directory the run was invoked in, shared by every stage. */
    private val project: Path get() = root

    /** The control root of the run. */
    private val controlRoot: Path get() = root.resolve("control")
}
