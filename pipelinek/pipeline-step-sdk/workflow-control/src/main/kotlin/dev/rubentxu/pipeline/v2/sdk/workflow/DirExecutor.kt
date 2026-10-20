package dev.rubentxu.pipeline.v2.sdk.workflow

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Result of a dir block execution.
 * @property outcome The outcome of the nested steps ("success", "unstable", "failure")
 * @property previousPath The workspace context before entering
 * @property currentPath The resolved directory context
 * @property restoredPath The workspace context after exit (equals [previousPath])
*/
data class DirResult(
    val outcome: String,
    val previousPath: String,
    val currentPath: String,
    val restoredPath: String,
)

/**
 * Executor for [StepSpec.Dir] — resolves working-directory context for nested steps.
 * ## Behavior
 * 1. Resolves the target path using workspace resolver lambda
 * 2. Creates the target directory when needed
 * 3. Executes nested steps (caller handles nested execution)
 * 4. Restores previous working directory in finally block
 * ## Design
 * This executor follows the SDK pattern (no application dependency):
 * - Uses `(stageName, stageIndex) -> Path` lambda for workspace resolution
 * - Does NOT emit events - caller handles event emission
 * - Does NOT execute nested steps - caller handles iteration
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
*/
class DirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
) {

    /**
     * Executes a dir block.
     * Resolves the target directory, executes the action, and returns the context paths.
     * The controller JVM working directory is never mutated.
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
        val workspace = workspaceResolver(stageName, stageIndex)
        val targetPath = resolveTargetPath(step.path, workspace)
        Files.createDirectories(targetPath)

        val outcome = action()
        return DirResult(
            outcome = outcome,
            previousPath = workspace.toString(),
            currentPath = targetPath.toString(),
            restoredPath = workspace.toString(),
        )
    }

    /**
     * Resolve [path] against [workspace], refusing anything that leaves it.
     * `internal` rather than `private` so the confinement decision is reachable without a
     * filesystem: [execute] calls `Files.createDirectories` before the nested steps run, and
     * AGENTS.md section 2 forbids filesystem persistence in a unit test. A guard whose only
     * reachable path runs through a directory creation is a guard with no coverage.
*/
    internal fun resolveTargetPath(path: String, workspace: Path): Path {
        // The boundary is normalised too, or an unnormalised root silently refuses its own
        // children: `/ws/./sub` does not start with `/ws/./`.
        val root = workspace.normalize()
        val target = if (path.startsWith("/")) {
            // Absolute path — use as-is
            Paths.get(path)
        } else {
            // Relative path — resolve against workspace root (Jenkins semantics)
            root.resolve(path)
        }.normalize()

        // Path.startsWith compares NAME ELEMENTS; String.startsWith compares characters.
        // Those differ exactly on the sibling that shares a prefix, which is the sibling an
        // attacker picks: for workspace `/home/rub/proyecto`, the character comparison admitted
        // `/home/rub/proyecto2`, `../proyecto-evil` and `../proyectoX/inner` — three real
        // directories outside the authorised root, each passing because its name begins with
        // the root's. `WorkspacePathAnchors` already records this class of guard as the reason
        // `core-utils.zip`/`.unzip` moved to `WorkspacePathResolver`; this file was the copy
        // that stayed, and it had the string form on top of the wrong boundary.
        if (!target.startsWith(root)) {
            throw PathTraversalException(path, "Path traversal attempt outside workspace: $target")
        }

        return target
    }
}

/**
 * Thrown when a path traversal attempt is detected (e.g., `../` escape outside workspace).
*/
class PathTraversalException(
    val requestedPath: String,
    override val message: String,
) : RuntimeException(message)
