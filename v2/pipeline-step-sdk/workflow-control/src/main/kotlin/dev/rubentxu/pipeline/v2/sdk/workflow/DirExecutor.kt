package dev.rubentxu.pipeline.v2.sdk.workflow

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Result of a dir block execution.
 *
 * @property outcome The outcome of the nested steps ("success", "unstable", "failure")
 * @property previousPath The previous working directory before entering
 * @property currentPath The directory that was entered
 * @property restoredPath The directory after exit (should equal previousPath)
 */
data class DirResult(
    val outcome: String,
    val previousPath: String,
    val currentPath: String,
    val restoredPath: String,
)

/**
 * Executor for [StepSpec.Dir] — changes working directory for nested steps.
 *
 * ## Behavior
 *
 * 1. Resolves the target path using workspace resolver lambda
 * 2. Changes working directory
 * 3. Executes nested steps (caller handles nested execution)
 * 4. Restores previous working directory in finally block
 *
 * ## Design
 *
 * This executor follows the SDK pattern (no application dependency):
 * - Uses `(stageName, stageIndex) -> Path` lambda for workspace resolution
 * - Does NOT emit events - caller handles event emission
 * - Does NOT execute nested steps - caller handles iteration
 *
 * Design: D8 (ADR-0052 §D8)
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 */
class DirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
) {

    /**
     * Executes a dir block.
     *
     * Changes to the target directory, executes the action, and restores the previous
     * directory in all cases (normal return or exception).
     *
     * @param stageName The current stage name
     * @param stageIndex The current stage index
     * @param stepIndex The step index for this dir step
     * @param step The dir step spec
     * @param action Action to execute while in the directory
     * @return [DirResult] with path tracking information
     * @throws PathTraversalException if path escapes workspace
     */
    fun execute(
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        step: StepSpec.Dir,
        action: () -> String,
    ): DirResult {
        val previousPath = System.getProperty("user.dir") ?: "."
        val workspace = workspaceResolver(stageName, stageIndex)
        val targetPath = resolveTargetPath(step.path, workspace)

        // Change to target directory
        changeDirectory(targetPath)

        return try {
            val outcome = action()
            DirResult(
                outcome = outcome,
                previousPath = previousPath,
                currentPath = targetPath.toString(),
                restoredPath = previousPath,
            )
        } finally {
            // Always restore previous directory
            changeDirectory(Paths.get(previousPath))
        }
    }

    private fun resolveTargetPath(path: String, workspace: Path): Path {
        val target = if (path.startsWith("/")) {
            // Absolute path — use as-is
            Paths.get(path)
        } else {
            // Relative path — resolve against workspace root (Jenkins semantics)
            workspace.resolve(path)
        }.normalize()

        // Guard against path traversal outside workspace
        val workspaceStr = workspace.toString()
        if (!target.toString().startsWith(workspaceStr)) {
            throw PathTraversalException(path, "Path traversal attempt outside workspace: $target")
        }

        return target
    }

    private fun changeDirectory(path: Path) {
        val dir = path.toFile()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        System.setProperty("user.dir", path.toUri().path)
    }
}

/**
 * Thrown when a path traversal attempt is detected (e.g., `../` escape outside workspace).
 */
class PathTraversalException(
    val requestedPath: String,
    override val message: String,
) : RuntimeException(message)
