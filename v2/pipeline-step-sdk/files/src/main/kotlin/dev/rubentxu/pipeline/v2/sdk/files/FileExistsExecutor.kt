package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Result of a file existence check.
 *
 * @property path Resolved absolute path checked
 * @property exists True if the file exists
 */
data class FileExistsResult(
    val path: Path,
    val exists: Boolean,
)

/**
 * Executor for [StepSpec.FileExists] — pure predicate, returns Boolean.
 *
 * ## Semantics
 *
 * - Returns `Boolean` to caller (no event emission per spec)
 * - `Files.exists(path)` is the oracle
 * - Path outside workspace → false
 * - .v2 reserved directory → false
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 */
class FileExistsExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
) {

    /**
     * Executes a [StepSpec.FileExists] step.
     */
    fun execute(stageIndex: Int, stepIndex: Int, spec: StepSpec.FileExists): FileExistsResult {
        val workspace = workspaceResolver(spec.file, stageIndex)
        val targetPath = workspace.resolve(spec.file).normalize()

        // Path safety: outside workspace → false
        if (!targetPath.startsWith(workspace)) {
            return FileExistsResult(targetPath, false)
        }

        // Reserved .v2 directory → false
        if (targetPath.startsWith(workspace.resolve(".v2"))) {
            return FileExistsResult(targetPath, false)
        }

        val exists = Files.exists(targetPath)
        return FileExistsResult(targetPath, exists)
    }
}
