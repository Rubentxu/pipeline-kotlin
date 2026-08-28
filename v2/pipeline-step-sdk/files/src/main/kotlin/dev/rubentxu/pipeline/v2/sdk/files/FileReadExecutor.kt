package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Result of a file read operation.
 *
 * @property path Resolved absolute path of the file
 * @property content The file content as a String (null if file does not exist)
 * @property sha256 SHA-256 hex digest (null if file does not exist)
 * @property size Size in bytes (null if file does not exist)
 * @property exists True if the file existed
 */
data class FileReadResult(
    val path: Path,
    val content: String?,
    val sha256: String?,
    val size: Long?,
    val exists: Boolean,
)

/**
 * Executor for [StepSpec.ReadFile] — pure read, returns content to caller.
 *
 * ## Reading
 *
 * - `Files.readAllBytes(targetPath)` for content
 * - `encoding = "UTF-8"` (default): decodes bytes via `String(bytes, UTF_8)`
 * - `encoding = "Base64"`: encodes bytes via `Base64.getEncoder().encodeToString(bytes)`
 *
 * ## Path Safety
 *
 * - Returns exists=false for paths outside workspace or in .v2 directory
 *
 * ## Events
 *
 * Event emission is handled by the dispatcher (T-09). This executor returns [FileReadResult]
 * with all data needed for event creation.
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param eventSink Event sink (passed for future event emission by dispatcher)
 */
class FileReadExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    @Suppress("UNUSED_PARAMETER") private val eventSink: EventSink = NullEventSink,
) {

    /**
     * Executes a [StepSpec.ReadFile] step.
     *
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param stepIndex The step index (unused)
     * @param spec The readFile step specification
     * @return [FileReadResult] with path, content, sha256, size
     */
    fun execute(stageName: String, stageIndex: Int, stepIndex: Int, spec: StepSpec.ReadFile): FileReadResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        val targetPath = workspace.resolve(spec.file).normalize()

        // Path safety: outside workspace → false
        if (!targetPath.startsWith(workspace)) {
            return FileReadResult(targetPath, null, null, null, exists = false)
        }

        // Reserved .v2 directory → false
        if (targetPath.startsWith(workspace.resolve(".v2"))) {
            return FileReadResult(targetPath, null, null, null, exists = false)
        }

        if (!Files.exists(targetPath)) {
            return FileReadResult(targetPath, null, null, null, exists = false)
        }

        val bytes = Files.readAllBytes(targetPath)
        val sha256 = sha256(bytes)
        val size = Files.size(targetPath)

        val content = if (spec.encoding == "Base64") {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        }

        return FileReadResult(
            path = targetPath,
            content = content,
            sha256 = sha256,
            size = size,
            exists = true,
        )
    }

    companion object {
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
