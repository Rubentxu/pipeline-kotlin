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
 *   [rootDestruction] does not permit it (C8). With `--workspace <dir>` the
 *   workspace root is the user's own project, and the default `deleteDir()` (path ".")
 *   resolves to exactly that root, so the Step's own default would erase the
 *   checkout. The default per-stage workspace is disposable scratch space, so
 *   wiping it stays allowed and WCL-S-001/S-002 keep their contract.
 *
 * ## Idempotency
 *
 * - Re-execution with same marker sha = no-op (deletedCount=0)
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param rootDestruction Whether the root may be wiped. A closed type, not a
 *   boolean: [RootDestruction.DecidedElsewhere] is the case the previous
 *   `protectWorkspaceRoot: Boolean` could not express, and it fails closed.
 */
class DeleteDirExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    val rootDestruction: RootDestruction = RootDestruction.DecidedElsewhere,
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

        // Resolve the REAL path before any decision about it. `normalize()`
        // collapses `.` and `..` but does not dereference symlinks, so a
        // `targetPath` that IS a link keeps its in-workspace name while every
        // operation on it — including writing the MEMOIZED marker — lands on
        // the link's target, outside the workspace.
        //
        // Both the containment check and the marker must be anchored to the
        // real path, or a symlink becomes an exit from a workspace that never
        // appears to leave. `toRealPath` fails when the leaf does not exist, so
        // the parent is resolved instead and the leaf is re-appended.
        val realWorkspace = toRealPathAllowingMissing(workspace)
        val rawTarget = workspace.resolve(spec.path).normalize()
        // Two distinct values, deliberately not one. `realTarget` is where
        // every effect happens. `declaredTarget` is what the caller wrote and
        // is what DeleteDirResult.path reports, because that field is an
        // observable, serialized output and must not silently change shape.
        val realTarget = toRealPathAllowingMissing(rawTarget)
        val declaredTarget = rawTarget

        // Workspace-root safety guard, now on real paths: it is the only check
        // that survives a symlinked root or a symlinked target.
        require(realTarget.startsWith(realWorkspace)) {
            "deleteDir path '${spec.path}' escapes workspace root"
        }

        // C8 interlock: a shared user workspace (--workspace) is the user's own
        // project, so the root's contents are never deletable. `deleteDir()`
        // with no argument resolves here, which would otherwise erase the
        // checkout. Scratch workspaces are unaffected and stay wipeable, and an
        // unresolved ownership question fails closed alongside UserOwned.
        //
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
        if (Files.exists(walked)) {
            Files.walk(walked)
                .filter { it != walked } // do NOT delete the root directory itself
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
            path = declaredTarget,
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

    /**
     * Dereference [path] as far as it exists, tolerating a missing leaf.
     *
     * `Path.toRealPath` throws when the leaf does not exist, and `deleteDir`
     * legitimately targets directories that have not been created yet. So the
     * deepest existing ancestor is resolved and the remaining segments are
     * re-appended. That yields the real path when the target exists and a
     * correctly anchored guess when it does not.
     */
    private fun toRealPathAllowingMissing(path: Path): Path {
        var ancestor: Path? = path
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.parent
        }
        val existing: Path = ancestor ?: return path
        // `relativize` is the java.nio member. The kotlin.io `relativeTo`
        // extension also exists and resolves to a File here, which does not
        // compile; the member is the intended call anyway.
        val suffix: Path = existing.relativize(path)
        val resolved: Path = existing.toRealPath()
        return resolved.resolve(suffix).normalize()
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
