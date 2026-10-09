package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Result of a deleteDir operation.
 *
 * @property path Resolved absolute path that was deleted
 * @property deletedCount Number of files/directories deleted (0 if already deleted)
 * @property sha256 SHA-256 hex of the .deleted marker content
 */
data class DeleteDirResult(
    val path: Path,
    val deletedCount: Int,
    val sha256: String,
)

/**
 * Executor for [StepSpec.DeleteDir] — atomic workspace directory deletion.
 *
 * ## Behavior
 *
 * 1. Resolves `path` against workspace (default ".")
 * 2. Checks for MEMOIZED marker at `<path>/.deleted` — if sha matches, returns deletedCount=0 (no-op)
 * 3. Deletes all contents recursively using walk+delete (post-order)
 * 4. Writes `.deleted` marker with sha256 for MEMOIZED replay
 *
 * ## Path Safety
 *
 * - Enforces workspace-root guard: resolved path MUST start with workspace root
 * - Throws [IllegalArgumentException] if path escapes workspace
 * - Throws [IllegalArgumentException] if the target IS the workspace root while
 *   [protectWorkspaceRoot] is set (C8). With `--workspace <dir>` the workspace
 *   root is the user's own project, and the default `deleteDir()` (path ".")
 *   resolves to exactly that root, so the Step's own default would erase the
 *   checkout. The default per-stage workspace is disposable scratch space, so
 *   wiping it stays allowed and WCL-S-001/S-002 keep their contract.
 *
 * ## Idempotency
 *
 * - Re-execution with same marker sha = no-op (deletedCount=0)
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param protectWorkspaceRoot When true, refuse to delete the workspace root itself.
 */
class DeleteDirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val protectWorkspaceRoot: Boolean = false,
) {

    /**
     * Executes a [StepSpec.DeleteDir] step.
     *
     * @param stageName The stage name
     * @param stageIndex The stage index
     * @param stepIndex The step index (unused — event emission deferred to dispatcher)
     * @param spec The deleteDir step specification
     * @return [DeleteDirResult]
     * @throws IllegalArgumentException if path escapes workspace root
     */
    fun execute(stageName: String, stageIndex: Int, stepIndex: Int, spec: StepSpec.DeleteDir): DeleteDirResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        val targetPath = workspace.resolve(spec.path).normalize()

        // Workspace-root safety guard
        require(targetPath.startsWith(workspace)) {
            "deleteDir path '${spec.path}' escapes workspace root"
        }

        // C8 interlock: a shared user workspace (--workspace) is the user's own
        // project, so the root's contents are never deletable. `deleteDir()`
        // with no argument resolves here, which would otherwise erase the
        // checkout. Scratch workspaces are unaffected and stay wipeable.
        require(!protectWorkspaceRoot || targetPath != workspace) {
            "deleteDir refuses to delete the workspace root itself ('$workspace'); " +
                "pass a sub-path such as deleteDir(\"build\") to remove generated content"
        }

        val markerFile = targetPath.resolve(".deleted")

        // MEMOIZED idempotency: check for existing marker
        if (Files.exists(markerFile)) {
            val existingSha = try {
                Files.readString(markerFile).trim()
            } catch (_: Exception) {
                ""
            }
            if (existingSha.isNotEmpty()) {
                // Idempotent re-run: marker exists, treat as no-op
                return DeleteDirResult(
                    path = targetPath,
                    deletedCount = 0,
                    sha256 = existingSha,
                )
            }
        }

        // Count items before deletion (children only, not the root directory itself)
        val deletedCount = countItems(targetPath)

        // Delete all contents recursively (but NOT the root targetPath itself)
        // This preserves the workspace root and allows the .deleted marker to be written inside it
        if (Files.exists(targetPath)) {
            Files.walk(targetPath)
                .filter { it != targetPath } // do NOT delete the root directory itself
                .sorted(Comparator.reverseOrder())
                .forEach { p ->
                    try {
                        Files.deleteIfExists(p)
                    } catch (_: Exception) {
                        // Ignore deletion errors for individual files
                    }
                }
        }

        // Write MEMOIZED marker inside targetPath
        val markerContent = "deleted:${System.currentTimeMillis()}"
        val sha256 = sha256(markerContent.toByteArray())
        Files.writeString(markerFile, sha256)

        return DeleteDirResult(
            path = targetPath,
            deletedCount = deletedCount,
            sha256 = sha256,
        )
    }

    private fun countItems(path: Path): Int {
        if (!Files.exists(path)) return 0
        return Files.walk(path)
            .filter { it != path } // don't count the root itself
            .count().toInt()
    }

    companion object {
        /**
         * SHA-256 hex of [bytes], routed through the shared utility (B0).
         *
         * Byte-identical to the local implementation this replaces: same algorithm, same
         * lowercase hex, same input. The value is emitted in the Step's observability event,
         * so an external observer comparing digests keeps matching.
         */
        fun sha256(bytes: ByteArray): String = Sha256.ofBytes(bytes)
    }
}
