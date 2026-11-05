package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path

/**
 * Result of a file read operation.
 * @property path Resolved absolute path of the file
 * @property content The file content as a String (null if file does not exist)
 * @property sha256 SHA-256 hex digest (null if file does not exist)
 * @property size Size in bytes (null if file does not exist)
 * @property exists True if file existed
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
 * ## Reading
 * - `readAllBytes(realPath)` for content, where `realPath` is the
 *   symlink-resolved target the containment guard approved
 * - `encoding = "UTF-8"` (default): decodes bytes via `String(bytes, UTF_8)`
 * - `encoding = "Base64"`: encodes bytes via `Base64.getEncoder().encodeToString(bytes)`
 * ## Path Safety
 * - Returns exists=false for paths outside workspace or in the .v2 directory
 * - Refuses to read through a symbolic link that the decision did not resolve:
 *   the bytes would come from the target of the link, which is not the file the
 *   pipeline named
 * ## Events
 * - Event emission is handled by the dispatcher. This executor returns [FileReadResult]
 *   with all data needed for event creation.
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param fileSystem The filesystem the read is applied to.
*/
class FileReadExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
) {

    /**
     * Executes a [StepSpec.ReadFile] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The readFile step specification
     * @return [FileReadResult] with path, content, sha256, size
    */
    fun execute(stageName: String, stageIndex: Int, spec: StepSpec.ReadFile): FileReadResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        // Two paths, deliberately not one. `declaredPath` is what the caller wrote and is what
        // FileReadResult.path reports, because that field is an observable, serialized output.
        // `realPath` is where the bytes actually come from, and it is what both guards are
        // anchored to — a lexical `startsWith` passes `<workspace>/link/anything` for any link,
        // so a `link` pointing outside the workspace used to authorise reading it.
        val declaredPath = workspace.resolve(spec.file).normalize()
        val anchor = WorkspaceAnchoring(fileSystem)
        val realPath = when (val resolution = anchor.authorize(workspace, declaredPath)) {
            is PathResolution.Resolved -> resolution.path
            is PathResolution.Rejected -> return FileReadResult(declaredPath, null, null, null, exists = false)
        }

        val realWorkspace = anchor.realWorkspace(workspace)
        // Reserved .v2 directory → false, on real paths, so a link pointing at `.v2` cannot read
        // the reserved area under another name.
        if (realPath.startsWith(realWorkspace.resolve(".v2"))) {
            return FileReadResult(declaredPath, null, null, null, exists = false)
        }

        // The chain, looked at immediately before the read. The decision above resolved the
        // parts of the path that were there; a part that was NOT there is a name, and a name
        // that is a link by now would take the read outside the workspace. A part that is
        // simply missing means the file is not there, which is the answer the read gives.
        if (!anchor.chainIsFreeOfLinks(realWorkspace, realPath)) {
            return FileReadResult(declaredPath, null, null, null, exists = false)
        }

        if (!fileSystem.exists(realPath)) {
            return FileReadResult(declaredPath, null, null, null, exists = false)
        }

        val bytes = fileSystem.readAllBytes(realPath)
        val sha256 = sha256(bytes)
        val size = bytes.size.toLong()

        val content = if (spec.encoding == "Base64") {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        }

        return FileReadResult(
            path = declaredPath,
            content = content,
            sha256 = sha256,
            size = size,
            exists = true,
        )
    }

    companion object {
        /**
         * SHA-256 hex of [bytes], routed through the shared utility.
         * Byte-identical to the local implementation this replaces: same algorithm, same
         * lowercase hex, same input. The value is emitted in the Step's observability event,
         * so an external observer comparing digests keeps matching.
    */
        fun sha256(bytes: ByteArray): String = Sha256.ofBytes(bytes)
    }
}
