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
 * Executor for [StepSpec.WriteFile] — an atomic write through a staging file.
 *
 * ## How the write is published
 *
 * 1. The bytes go into a staging file the FILESYSTEM creates, in the target's
 *    own directory, with a name that no entry held. The filesystem picks the
 *    name and creates the file exclusively, so two writes that overlap never
 *    share one.
 * 2. The digest and the size are read from that staging file, BEFORE the
 *    rename: they describe the content of THIS write, and not what another
 *    write left at the target in the meantime.
 * 3. The staging file is renamed onto the target. The rename replaces the
 *    ENTRY at the target, so a target that is a link is replaced rather than
 *    followed. The staging file is a sibling of the target, so the rename
 *    stays inside one filesystem; [FileWriteResult.atomicallyMoved] says
 *    whether the filesystem refused the atomic rename anyway.
 * 4. If the rename fails, the staging file of THIS operation is removed and
 *    the file the target had is left as it was: the rename did not happen.
 *
 * ## What the staging file is not
 *
 * It is not a name of the product's own, and it is not a name that can be
 * occupied in advance: the filesystem creates it exclusively and the bytes go
 * into the file it made. A name that was taken is not reused, so nothing that
 * was already there receives what this write meant for the new file.
 *
 * ## What is NOT guaranteed
 *
 * The JDK cannot create a file relative to an open directory descriptor, so
 * the chain of directories is judged immediately before the effect and not
 * atomically with it: a component of that chain replaced between the judgement
 * and the creation can still divert the effect. The THREAT is an actor who
 * can write INSIDE the workspace — the same actor who can edit the pipeline —
 * and the guarantee is one of DEPTH (the chain is judged at the last moment,
 * and the authorized root is the containment), not of atomicity. Nothing here
 * claims isolation against an actor who can write inside the root.
 *
 * ## Path Safety
 * - Resolves `file` against workspace
 * - Enforces the path traversal guard on REAL paths, so a symlink out of the
 *   workspace is refused rather than written through
 * - Enforces reserved .v2 directory guard
 * - Auto-creates parent directories, and refuses to create them through a
 *   symbolic link: a link that appears between the decision and the effect
 *   would put the bytes outside the workspace
 *
 * ## Encoding
 * - `encoding = "UTF-8"` (default): `text.toByteArray(UTF_8)`
 * - `encoding = "Base64"`: decodes text via `Base64.getDecoder().decode(text)`
 *
 * ## Events
 * - This executor emits nothing. The [FileWriteResult] carries the evidence
 *   the event needs, and the caller that owns the event channel is the one
 *   that emits it.
 *
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

        // Atomic write: a staging file in the same directory, then the rename
        // that publishes it. The rename replaces the ENTRY at the target, so a
        // target that is a link is replaced rather than followed.
        //
        // The staging file is NOT a name of the product's own. The filesystem
        // creates it exclusively, in the target's directory, so two writes
        // that overlap never share one and nothing that was already there can
        // receive what this write meant for the new file. A name that was
        // taken before the write is not reused.
        val stagingPath = fileSystem.stageBytes(realPath, bytes)
        try {
            // The digest and the size are read from the STAGING file, before
            // the rename: one reading, and the two values cannot describe
            // different contents. Reading the TARGET after the rename would
            // describe whatever another write left there in the meantime.
            val written = fileSystem.readAllBytes(stagingPath)
            val sha256 = sha256(written)
            val size = written.size.toLong()
            val atomicallyMoved = fileSystem.move(stagingPath, realPath)

            return FileWriteResult(
                path = declaredPath,
                sha256 = sha256,
                size = size,
                atomicallyMoved = atomicallyMoved,
            )
        } catch (t: Throwable) {
            // The staging file of THIS operation, and nothing else. The rename
            // did not happen, so the file the target had is still there.
            fileSystem.deleteIfExists(stagingPath)
            throw t
        }
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
