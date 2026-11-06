package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.io.IOException
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
 * Executor for [StepSpec.ReadFile] — a read bounded by a limit.
 *
 * ## The guarantee
 *
 * A read holds at most [maxBytes] of the file, and it holds all of it or
 * none of it. A file that does not fit is refused with a
 * [FileReadTooLargeException], and the refusal is a USER failure: the
 * request was well formed, the file was readable, and what stopped it was
 * the size of the file.
 *
 * ## The limit
 *
 * [DEFAULT_MAX_READ_BYTES] is the limit a read runs under, and it is the
 * limit of the SUBSTRATE rather than of the DSL: the Step that calls this
 * executor holds no filesystem of its own, and the published surface of the
 * DSL is not changed to carry a knob that nobody asked for. The value is the
 * one the product already reads a whole file under elsewhere — the JUnit
 * report's own limit, ten mebibytes — for a file the product reads end to
 * end.
 *
 * ## What the compatibility impact is
 *
 * A pipeline that read a file larger than the limit used to receive its
 * content, and now fails with a USER failure that names the limit and the
 * size of the file. There is no knob in the DSL to raise it: the limit is
 * raised by a caller inside the process, through [maxBytes], and a pipeline
 * has no way to reach that. A pipeline that needs a file of that size has to
 * read it with another step.
 *
 * ## What the read does NOT do
 *
 * It does not truncate. A read that cannot hold the whole file holds NONE of
 * it: a prefix that looks like a whole file is a wrong answer that nobody can
 * see, and the refusal above is the honest one.
 *
 * ## Path Safety
 * - Returns exists=false for paths outside workspace or in the .v2 directory
 * - Refuses to read through a symbolic link that the decision did not resolve:
 *   the bytes would come from the target of the link, which is not the file the
 *   pipeline named
 *
 * ## Reading
 * - The bytes are read through the port, under the limit: the read stops at
 *   the limit, so the memory a read may use is bounded by the limit and not
 *   by the size of the file
 * - `encoding = "UTF-8"` (default): decodes bytes via `String(bytes, UTF_8)`
 * - `encoding = "Base64"`: encodes bytes via `Base64.getEncoder().encodeToString(bytes)`
 * - The digest and the size are the ones of the BYTES, and not of the
 *   representation: a Base64 read and a UTF-8 read of the same file publish
 *   the same digest
 *
 * ## Events
 * - This executor emits nothing. The [FileReadResult] carries the evidence
 *   the event needs, and the caller that owns the event channel is the one
 *   that emits it.
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param fileSystem The filesystem the read is applied to.
 * @param maxBytes The limit the read runs under.
*/
class FileReadExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
    private val maxBytes: Long = DEFAULT_MAX_READ_BYTES,
) {

    init {
        // The read is asked for with one byte more than the limit, so the
        // limit has to leave room for that byte.
        require(maxBytes in 0..<Int.MAX_VALUE.toLong()) {
            "the read limit is out of range: $maxBytes"
        }
    }

    /**
     * Executes a [StepSpec.ReadFile] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The readFile step specification
     * @return [FileReadResult] with path, content, sha256, size
     * @throws FileReadTooLargeException if the file holds more than the limit
     * @throws PipelineStepException with an INFRASTRUCTURE failure if the file
     *   could not be read at all
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

        // The read is BOUNDED and the decision is taken HERE, where the limit
        // is: the port is asked for one byte more than the limit, so that a
        // file which grew while it was being read is told apart from a file
        // that fits, and a file that does not fit never becomes whole.
        val read = try {
            fileSystem.readUpTo(realPath, maxBytes + 1)
        } catch (e: IOException) {
            // The file could not be read at all. That is not the user's
            // input: the storage layer refused us, and the failure says so.
            throw PipelineStepException(
                PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "readFile: the file at $declaredPath could not be read: " +
                        (e.message ?: "unknown"),
                    cause = e,
                ),
            )
        }
        // The size the file had when it was measured. A file over the limit
        // is refused with that size, and nothing of it was read.
        if (read.sizeBytes > maxBytes) {
            throw FileReadTooLargeException(
                path = declaredPath,
                atLeastBytes = read.sizeBytes,
                limitBytes = maxBytes,
            )
        }
        // The file grew between the measurement and the read: the bytes that
        // came back are one more than the limit allows, and publishing them
        // would publish a prefix as if it were the whole file.
        if (read.bytes.size > maxBytes) {
            throw FileReadTooLargeException(
                path = declaredPath,
                atLeastBytes = read.bytes.size.toLong(),
                limitBytes = maxBytes,
            )
        }
        val bytes = read.bytes

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
         * The limit a read runs under when the caller does not name one.
         *
         * Ten mebibytes, the size the product already reads a whole file
         * under elsewhere. It bounds the memory a read may use and the size
         * of the journal entry the content lands in; a file above it is
         * refused rather than truncated.
         */
        const val DEFAULT_MAX_READ_BYTES: Long = 10L * 1024L * 1024L

        /**
         * SHA-256 hex of [bytes], routed through the shared utility.
         * Byte-identical to the local implementation this replaces: same algorithm, same
         * lowercase hex, same input. The value is emitted in the Step's observability event,
         * so an external observer comparing digests keeps matching.
    */
        fun sha256(bytes: ByteArray): String = Sha256.ofBytes(bytes)
    }
}
