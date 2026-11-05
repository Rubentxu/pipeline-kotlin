package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Path

/**
 * Result of a file existence check.
 * @property path Resolved absolute path checked
 * @property exists True if the file exists
*/
data class FileExistsResult(
    val path: Path,
    val exists: Boolean,
)

/**
 * Executor for [StepSpec.FileExists] — pure predicate, returns Boolean.
 * ## Semantics
 * - Returns `Boolean` to caller (no event emission per spec)
 * - The existence of the path is the oracle
 * - Path outside workspace → false, INCLUDING a path that only leaves the
 *   workspace through a symlink: `normalize()` does not dereference links, so
 *   `<workspace>/link/passwd` starts with the workspace while `link` may point
 *   anywhere. The check is anchored on the real path.
 * - .v2 reserved directory → false, anchored on the real path too, so a link
 *   pointing at `.v2` cannot probe the reserved area under another name
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param fileSystem The filesystem the check is applied to.
*/
class FileExistsExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    private val fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
) {

    /**
     * Executes a [StepSpec.FileExists] step.
     * @param stageName The stage name (used as workspace identifier)
     * @param stageIndex The stage index (passed to workspace resolver)
     * @param spec The fileExists step specification
     * @return [FileExistsResult] with path and exists flag
    */
    fun execute(stageName: String, stageIndex: Int, spec: StepSpec.FileExists): FileExistsResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        // Two paths, deliberately not one. `declaredPath` is what the caller wrote and is what
        // FileExistsResult.path reports, because that field is an observable, serialized output.
        // `realPath` is where the filesystem is actually consulted, and it is what both guards are
        // anchored to — a lexical `startsWith` passes `<workspace>/link/anything` for any link.
        val declaredPath = workspace.resolve(spec.file).normalize()
        val anchor = WorkspaceAnchoring(fileSystem)
        val realPath = when (val resolution = anchor.authorize(workspace, declaredPath)) {
            is PathResolution.Resolved -> resolution.path
            is PathResolution.Rejected -> return FileExistsResult(declaredPath, false)
        }

        val realWorkspace = anchor.realWorkspace(workspace)
        // Reserved .v2 directory → false, on real paths.
        if (realPath.startsWith(realWorkspace.resolve(".v2"))) {
            return FileExistsResult(declaredPath, false)
        }

        // The chain, looked at immediately before the check. A part of the path that was
        // not there when the decision was taken is a name, and a name that is a link by
        // now would make the answer describe the target of the link rather than the
        // file the pipeline named.
        if (!anchor.chainIsFreeOfLinks(realWorkspace, realPath)) {
            return FileExistsResult(declaredPath, false)
        }

        val exists = fileSystem.exists(realPath)
        return FileExistsResult(declaredPath, exists)
    }
}
