package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path

/**
 * Result of a converged deleteDir operation.
 *
 * Only a deletion that left nothing behind produces this value: a run where an
 * entry survived arrives as [DeleteDirIncompleteException] instead, so a
 * present result IS the verification that the target is empty of content.
 * @property path Resolved absolute path that was deleted
 * @property deletedCount Entries the filesystem confirmed gone this run — a
 *   measurement of the deletion, never a pre-walk promise (0 when the target
 *   held nothing to remove)
 * @property sha256 SHA-256 hex of the `.deleted` marker content. Evidence for
 *   the journal only: no later invocation reads the marker to skip the work.
*/
data class DeleteDirResult(
    val path: Path,
    val deletedCount: Int,
    val sha256: String,
)

/**
 * Executor for [StepSpec.DeleteDir] — convergent workspace directory deletion.
 * ## Behavior
 * 1. Resolves `path` against workspace (default ".")
 * 2. Deletes all contents recursively using walk+delete (post-order); the
 *    target directory itself stays so the marker has a home
 * 3. Verifies against the filesystem: whatever the walk left behind is a
 *    survivor, and a survivor is a failure
 * 4. Writes the `.deleted` marker — with a sha256 of its content — only when
 *    the verification found nothing left. The marker is evidence for the
 *    journal, never proof of cleanliness.
 * ## Convergence
 * - Every invocation consults the filesystem. A marker that exists — valid,
 *   forged or stale — is never read as a no-op: content recreated after a
 *   historical receipt is deleted by the next run, not skipped.
 * - If any entry survives, the result is [DeleteDirIncompleteException] with
 *   the survivors and no marker is written; once the cause is resolved, a new
 *   attempt deletes the rest. A deletion that reports itself done while
 *   content survives is the defect this executor refuses to repeat.
 * - `deletedCount` measures the entries the filesystem confirmed gone.
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
     * Executes a convergent [StepSpec.DeleteDir] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The deleteDir step specification
     * @return [DeleteDirResult] only when the filesystem verification found the
     *   target empty of content; `deletedCount` is the measured deletion
     * @throws IllegalArgumentException if path escapes workspace root
     * @throws DeleteDirIncompleteException if an entry survived the deletion;
     *   no marker is written, and re-running after the cause is resolved
     *   deletes the rest
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

        // The marker is evidence, never authority. The previous reading consulted
        // `<target>/.deleted` here and returned a no-op when a sha-shaped value
        // was present — which made the marker a claim the filesystem was never
        // asked to confirm: content that survived a swallowed deletion, or that
        // an author created after the receipt, stayed invisible to every later
        // run. Each invocation now walks the real filesystem; the marker written
        // at the end records what happened, it does not decide what happens.
        //
        // Delete all contents recursively (but NOT the root targetPath itself).
        // Same reason: the walk is anchored to the real path, so a link named
        // `escape` is removed as a link and never followed into its target.
        val observed = if (fileSystem.exists(realTarget)) {
            fileSystem.walk(realTarget).filter { it != realTarget }
        } else {
            emptyList()
        }
        observed
            .sortedWith(Comparator.reverseOrder<Path>())
            .forEach { p ->
                try {
                    fileSystem.deleteIfExists(p)
                } catch (_: Exception) {
                    // An entry whose removal raised is not forgiven here: it is
                    // still under the target, and the verification below reads
                    // it back from the filesystem and fails the run with it. The
                    // loop continues because a partial deletion is the progress
                    // the next attempt converges from.
                }
            }

        // The verification IS the verdict: the filesystem, read after the walk,
        // says whether the deletion converged. Anything there — a refused entry,
        // one resurrected under a delete that reported success, one created
        // during the window — is a survivor, and survivors are a typed failure.
        val survivors = if (fileSystem.exists(realTarget)) {
            fileSystem.walk(realTarget).filter { it != realTarget }
        } else {
            emptyList()
        }
        // Measured, not promised: the entries the filesystem now confirms gone.
        val deletedCount = observed.count { entry -> !fileSystem.exists(entry) }

        if (survivors.isNotEmpty()) {
            // No marker: a receipt of cleanliness the verification just refuted
            // is exactly the false success this executor no longer writes.
            throw DeleteDirIncompleteException(
                target = declaredTarget,
                survivors = survivors,
                deletedCount = deletedCount,
            )
        }

        // Write the marker inside targetPath — only over a verified-empty
        // target, as journal evidence. The name was emptied above, so the write
        // lands on the name the product emptied and not on the target of a link.
        val markerContent = "deleted:${System.currentTimeMillis()}"
        val sha256 = sha256(markerContent.toByteArray())
        fileSystem.writeBytes(markerFile, sha256.toByteArray(Charsets.UTF_8))

        return DeleteDirResult(
            path = declaredTarget,
            deletedCount = deletedCount,
            sha256 = sha256,
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
