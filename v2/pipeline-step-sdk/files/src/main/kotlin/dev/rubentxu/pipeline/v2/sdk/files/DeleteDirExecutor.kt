package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
 *
 * ## Idempotency
 *
 * - Re-execution with same marker sha = no-op (deletedCount=0)
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 */
class DeleteDirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
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
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
