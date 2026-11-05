package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path

/**
 * Result of a file write operation.
 * @property path Resolved absolute path of the written file
 * @property sha256 SHA-256 hex digest of the file content
 * @property size Size in bytes
 * @property atomicallyMoved True if ATOMIC_MOVE succeeded; false if cross-fs fallback was used
*/
data class FileWriteResult(
    val path: Path,
    val sha256: String,
    val size: Long,
    val atomicallyMoved: Boolean,
)

/**
 * Executor for [StepSpec.WriteFile] — atomic temp+rename file write.
 * ## Atomic Write Strategy
 * 1. Write content to `<target>.tmp` in the SAME directory as target (guarantees same filesystem)
 * 2. Atomically rename via `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`
 * 3. On `AtomicMoveNotSupportedException` (cross-filesystem): fall back to `REPLACE_EXISTING`
 * ## Path Safety
 * - Resolves `file` against workspace
 * - Enforces the path traversal guard on REAL paths, so a symlink out of the
 *   workspace is refused rather than written through
 * - Enforces reserved .v2 directory guard
 * - Auto-creates parent directories, and refuses to create them through a
 *   symbolic link: a link that appears between the decision and the effect
 *   would put the bytes outside the workspace
 * - Judges the staging name (`<file>.tmp`) the same way it judges the target:
 *   the workspace is writable, so an actor can occupy that name with a link,
 *   and a write follows a link
 * ## Encoding
 * - `encoding = "UTF-8"` (default): `text.toByteArray(UTF_8)`
 * - `encoding = "Base64"`: decodes text via `Base64.getDecoder().decode(text)`
 * ## Events
 * - Event emission is handled by the dispatcher which has access to DomainEvent types.
 *   The [FileWriteResult] carries all data needed for event creation.
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param fileSystem The filesystem the write is applied to.
*/
class FileWriteExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
) {

    /**
     * Executes a [StepSpec.WriteFile] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The writeFile step specification
     * @return [FileWriteResult] on success
     * @throws IllegalArgumentException if path escapes workspace or targets .v2 directory
    */
    fun execute(stageName: String, stageIndex: Int, spec: StepSpec.WriteFile): FileWriteResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        // Two paths, deliberately not one. `declaredPath` is what the caller wrote and is what
        // FileWriteResult.path reports, because that field is an observable, serialized output.
        // `realPath` is where every effect happens — the mkdir, the temp file, the rename — and it
        // is what both guards are anchored to. A lexical `startsWith` passes
        // `<workspace>/link/anything` for any link, so a `link` pointing outside the workspace used
        // to authorise writing through it: the mkdir landed in-workspace and the bytes landed out.
        val declaredPath = workspace.resolve(spec.file).normalize()
        val anchor = WorkspaceAnchoring(fileSystem)
        val realPath = when (val resolution = anchor.authorize(workspace, declaredPath)) {
            is PathResolution.Resolved -> resolution.path
            is PathResolution.Rejected -> throw IllegalArgumentException(
                "writeFile path '${spec.file}' escapes workspace",
            )
        }

        val realWorkspace = anchor.realWorkspace(workspace)
        // Reserved directory guard, on real paths, so a link pointing at `.v2` cannot write into the
        // reserved area under another name.
        require(!realPath.startsWith(realWorkspace.resolve(".v2"))) {
            "writeFile path '${spec.file}' targets reserved .v2 directory"
        }

        // The directories the write needs, prepared immediately before the effect. The
        // decision above resolved the parts of the path that were there; a part that
        // was NOT there is a name, and the name is created here as a directory or
        // refused. Creating it through a link is the escape this refuses: the bytes
        // would land on the target of the link, outside the workspace.
        require(anchor.prepareDirectoryChain(realWorkspace, realPath.parent)) {
            "writeFile path '${spec.file}' escapes workspace"
        }

        // Decode content if Base64
        val bytes = if (spec.encoding == "Base64") {
            java.util.Base64.getDecoder().decode(spec.text)
        } else {
            spec.text.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        }

        // Atomic write: temp file in same directory, then atomic rename. The rename
        // replaces the ENTRY at the target, so a target that is a link is replaced
        // rather than followed.
        val tmpPath = realPath.resolveSibling("${realPath.fileName}.tmp")
        // The staging name is a path of the product's own choosing, and the
        // workspace is writable: an actor that can write inside it can occupy
        // that name with a link before the write, and a write FOLLOWS a link, so
        // the bytes would land on the target of it, outside the root. The
        // staging path is therefore judged the way the target was — the chain,
        // looked at immediately before the effect — and a link anywhere in it
        // stops the write.
        require(anchor.chainIsFreeOfLinks(realWorkspace, tmpPath)) {
            "writeFile path '${spec.file}' escapes workspace"
        }
        fileSystem.writeBytes(tmpPath, bytes)
        val atomicallyMoved = fileSystem.move(tmpPath, realPath)

        // The bytes that are on disk after the move are the bytes the digest
        // and the size are taken from: one reading, and the two values cannot
        // describe different contents.
        val written = fileSystem.readAllBytes(realPath)
        val sha256 = sha256(written)
        val size = written.size.toLong()

        return FileWriteResult(
            path = declaredPath,
            sha256 = sha256,
            size = size,
            atomicallyMoved = atomicallyMoved,
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
