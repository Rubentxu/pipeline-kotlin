package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path

/**
 * Result of a deleteDir operation.
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
 * ## Behavior
 * 1. Resolves `path` against workspace (default ".")
 * 2. Checks for MEMOIZED marker at `<path>/.deleted` — if sha matches, returns deletedCount=0 (no-op)
 * 3. Deletes all contents recursively using walk+delete (post-order)
 * 4. Writes `.deleted` marker with sha256 for MEMOIZED replay
 * ## Path Safety
 * - Enforces workspace-root guard: resolved path MUST start with workspace root
 * - Refuses to act through a symbolic link that the decision did not resolve:
 *   the marker of the deletion would be written on the target of the link,
 *   outside the workspace
 * - Empties the marker name (`.deleted`) when a link occupies it: a marker
 *   read through that link belongs to whatever is on the other side, and a
 *   marker written through it would land outside the workspace
 * - Throws [IllegalArgumentException] if path escapes workspace
 * - Throws [IllegalArgumentException] if the target IS the workspace root while
 *   [rootDestruction] does not permit it. With `--workspace <dir>` the
 *   workspace root is the user's own project, and the default `deleteDir()` (path ".")
 *   resolves to exactly that root, so the Step's own default would erase the
 *   checkout. The default per-stage workspace is disposable scratch space, so
 *   wiping it stays allowed.
 * ## Idempotency
 * - Re-execution with same marker sha = no-op (deletedCount=0)
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param fileSystem The filesystem the deletion is applied to.
 * @param rootDestruction Whether the root may be wiped. A closed type, not a
 *   boolean: [RootDestruction.DecidedElsewhere] is the case the previous
 *   `protectWorkspaceRoot: Boolean` could not express, and it fails closed.
*/
class DeleteDirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
    val rootDestruction: RootDestruction = RootDestruction.DecidedElsewhere,
) {

    /**
     * Executes a [StepSpec.DeleteDir] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The deleteDir step specification
     * @return [DeleteDirResult]
     * @throws IllegalArgumentException if path escapes workspace root
    */
    fun execute(stageName: String, stageIndex: Int, spec: StepSpec.DeleteDir): DeleteDirResult {
        val workspace = workspaceResolver(stageName, stageIndex)

        // Resolve the REAL path before any decision about it. `normalize()`
        // collapses `.` and `..` but does not dereference symlinks, so a
        // `targetPath` that IS a link keeps its in-workspace name while every
        // operation on it — including writing the MEMOIZED marker — lands on the
        // link's target, outside the workspace.
        // Both the containment check and the marker must be anchored to the
        // real path, or a symlink becomes an exit from a workspace that never
        // appears to leave. `toRealPath` fails when the leaf does not exist, so
        // the parent is resolved instead and the leaf is re-appended.
        val anchor = WorkspaceAnchoring(fileSystem)
        val rawTarget = workspace.resolve(spec.path).normalize()
        // Two distinct values, deliberately not one. `realTarget` is where
        // every effect happens. `declaredTarget` is what the caller wrote and
        // is what DeleteDirResult.path reports, because that field is an
        // observable, serialized output and must not silently change shape.
        val declaredTarget = rawTarget
        // The containment verdict is the one the anchor gives every other
        // operation: one verdict, one owner. The refusal it answers with is
        // this Step's own, because the message a pipeline author reads is
        // this Step's contract.
        val realTarget = when (val resolution = anchor.authorize(workspace, rawTarget)) {
            is PathResolution.Resolved -> resolution.path
            is PathResolution.Rejected -> throw IllegalArgumentException(
                "deleteDir path '${spec.path}' escapes workspace root",
            )
        }
        val realWorkspace = anchor.realWorkspace(workspace)

        // The chain, looked at immediately before the deletion. A part of the
        // path that was not there when the decision was taken is a name, and a
        // name that is a link by now would take the marker of this deletion —
        // and the walk that follows it — outside the workspace.
        require(anchor.chainIsFreeOfLinks(realWorkspace, realTarget)) {
            "deleteDir path '${spec.path}' escapes workspace root"
        }

        // A shared user workspace (--workspace) is the user's own
        // project, so the root's contents are never deletable. `deleteDir()`
        // with no argument resolves here, which would otherwise erase the
        // checkout. Scratch workspaces are unaffected and stay wipeable, and an
        // unresolved ownership question fails closed alongside UserOwned.
        // Matched on the cases rather than on a `permitsRootWipe` bit: a bit
        // would make UserOwned and DecidedElsewhere indistinguishable here, and
        // a fourth state would silently inherit whichever value the bit gave it.
        val wipesRoot = when (rootDestruction) {
            RootDestruction.ScratchOwned -> true
            RootDestruction.UserOwned,
            RootDestruction.DecidedElsewhere,
            -> false
        }
        require(wipesRoot || realTarget != realWorkspace) {
            "deleteDir refuses to delete the workspace root itself ('$workspace'); " +
                "pass a sub-path such as deleteDir(\"build\") to remove generated content"
        }

        val markerFile = realTarget.resolve(".deleted")

        // The marker name is the product's own, and the workspace is writable,
        // so the name can be occupied by a link. A digest read THROUGH that
        // link belongs to whatever is on the other side, and honouring it would
        // turn this deletion into a no-op that reports itself as done; a marker
        // WRITTEN through it would land outside the authorized root. The link
        // is removed instead: the marker is then simply not there, and the
        // write at the end of this deletion cannot follow it either.

        if (fileSystem.isSymbolicLink(markerFile)) {
            fileSystem.deleteIfExists(markerFile)
        }

        // MEMOIZED idempotency: check for existing marker
        if (fileSystem.exists(markerFile)) {
            // A marker this executor wrote is a SHA-256 of the marker content; anything else in
            // the file was not written here (a hand edit, a truncated write, a different producer).
            // Such a file is treated as NOT a marker, and the cleanup proceeds normally, rather
            // than refusing. The refusal was the obvious form and it is wrong: it wedges the
            // workspace permanently, because once a bad marker exists no future run could clean
            // it either. Re-running a cleanup is safe by definition — deleting already-deleted
            // content is a no-op — so the convergent answer is also the operable one. What must
            // not happen is the marker value flowing on as a digest, which is what the old
            // `isNotEmpty()` gate did: any non-empty text reached DeleteDirOutput.sha256 and
            // then the journal, where it could never match a real digest.
            val existingSha = try {
                String(fileSystem.readAllBytes(markerFile), Charsets.UTF_8)
                    .trim().takeIf(Sha256::isSha256Hex).orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (existingSha.isNotEmpty()) {
                // Idempotent re-run: marker exists, treat as no-op
                return DeleteDirResult(
                    path = declaredTarget,
                    deletedCount = 0,
                    sha256 = existingSha,
                )
            }
        }

        // Count items before deletion (children only, not the root directory itself)
        val deletedCount = countItems(realTarget)

        // Delete all contents recursively (but NOT the root targetPath itself)
        // This preserves the workspace root and allows the .deleted marker to be written inside it
        // Same reason: the walk is anchored to the real path, so a link named
        // `escape` is removed as a link and never followed into its target.
        val walked = realTarget
        if (fileSystem.exists(walked)) {
            fileSystem.walk(walked)
                .filter { it != walked } // do NOT delete the root directory itself
                .sortedWith(Comparator.reverseOrder<Path>())
                .forEach { p ->
                    try {
                        fileSystem.deleteIfExists(p)
                    } catch (_: Exception) {
                        // Ignore deletion errors for individual files
                    }
                }
        }

        // Write MEMOIZED marker inside targetPath. The name was emptied above,
        // so the write lands on the name the product emptied and not on the
        // target of a link.
        val markerContent = "deleted:${System.currentTimeMillis()}"
        val sha256 = sha256(markerContent.toByteArray())
        fileSystem.writeBytes(markerFile, sha256.toByteArray(Charsets.UTF_8))

        return DeleteDirResult(
            path = declaredTarget,
            deletedCount = deletedCount,
            sha256 = sha256,
        )
    }

    private fun countItems(path: Path): Int {
        if (!fileSystem.exists(path)) return 0
        // The walk is a list here, so it holds no open directory handle per level
        // until it is closed. This runs once per deleteDir, and the leak is
        // invisible until the process hits its descriptor limit.
        return fileSystem.walk(path)
            .count { it != path } // don't count the root itself
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
