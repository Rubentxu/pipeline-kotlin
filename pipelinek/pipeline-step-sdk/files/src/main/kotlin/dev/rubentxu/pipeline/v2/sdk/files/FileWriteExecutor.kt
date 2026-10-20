package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * - Enforces reserved.v2 directory guard
 * - Auto-creates parent directories
 * ## Encoding
 * `encoding = ""` (default): `text.toByteArray(UTF_8)`
 * - `encoding = "Base64"`: decodes text via `Base64.getDecoder().decode(text)`
 * ## Events
 * Event emission is handled by the dispatcher which has access to DomainEvent types
 * The [FileWriteResult] carries all data needed for event creation.
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param eventSink Event sink (passed for future event emission by dispatcher)
*/
class FileWriteExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    @Suppress("UNUSED_PARAMETER") private val eventSink: EventSink = NullEventSink,
) {

    /**
     * Executes a [StepSpec.WriteFile] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param stepIndex The step index (unused — event emission deferred to dispatcher)
     * @param spec The writeFile step specification
     * @return [FileWriteResult] on success
     * @throws IllegalArgumentException if path escapes workspace or targets.v2 directory
*/
    fun execute(stageName: String, stageIndex: Int, stepIndex: Int, spec: StepSpec.WriteFile): FileWriteResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        // Two paths, deliberately not one. `declaredPath` is what the caller wrote and is what
        // FileWriteResult.path reports, because that field is an observable, serialized output.
        // `realPath` is where every effect happens — the mkdir, the temp file, the rename — and it
        // is what both guards are anchored to. A lexical `startsWith` passes
        // `<workspace>/link/anything` for any link, so a `link` pointing outside the workspace used
        // to authorise writing through it: the mkdir landed in-workspace and the bytes landed out.
        val declaredPath = workspace.resolve(spec.file).normalize()
        val realWorkspace = WorkspaceAnchoring.realWorkspace(workspace)
        val realPath = when (val resolution = WorkspaceAnchoring.authorize(workspace, declaredPath)) {
            is PathResolution.Resolved -> resolution.path
            is PathResolution.Rejected -> throw IllegalArgumentException(
                "writeFile path '${spec.file}' escapes workspace",
            )
        }

        // Reserved directory guard, on real paths, so a link pointing at `.v2` cannot write into the
        // reserved area under another name.
        require(!realPath.startsWith(realWorkspace.resolve(".v2"))) {
            "writeFile path '${spec.file}' targets reserved .v2 directory"
        }

        // Auto-create parent directories
        Files.createDirectories(realPath.parent)

        // Decode content if Base64
        val bytes = if (spec.encoding == "Base64") {
            java.util.Base64.getDecoder().decode(spec.text)
        } else {
            spec.text.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        }

        // Atomic write: temp file in same directory, then atomic rename
        val tmpPath = realPath.resolveSibling("${realPath.fileName}.tmp")
        var atomicallyMoved = true

        try {
            Files.write(tmpPath, bytes)
            Files.move(tmpPath, realPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Cross-filesystem mount point: fall back to non-atomic move
            Files.move(tmpPath, realPath, StandardCopyOption.REPLACE_EXISTING)
            atomicallyMoved = false
        }

        val sha256 = sha256(Files.readAllBytes(realPath))
        val size = Files.size(realPath)

        return FileWriteResult(
            path = declaredPath,
            sha256 = sha256,
            size = size,
            atomicallyMoved = atomicallyMoved,
        )
    }

    companion object {
        /**
         * SHA-256 hex of [bytes], routed through the shared utility
         * Byte-identical to the local implementation this replaces: same algorithm, same
         * lowercase hex, same input. The value is emitted in the Step's observability event,
         * so an external observer comparing digests keeps matching.
*/
        fun sha256(bytes: ByteArray): String = Sha256.ofBytes(bytes)
    }
}
