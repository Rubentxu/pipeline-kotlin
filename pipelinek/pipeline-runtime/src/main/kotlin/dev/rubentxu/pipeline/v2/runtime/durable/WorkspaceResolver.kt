package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the workspace directory of a stage.
 *
 * ## The two cases, and which one is the default
 *
 * With NO base, every stage gets a workspace of its own, under
 * `<controlRoot>/workspace/<stageName>-<stageIndex>/`. The deterministic
 * naming ensures parallel stages with the same name get different
 * directories (collision-safe per).
 *
 * With a base —the directory the run was invoked in, or the one the operator
 * named with `--workspace`— EVERY STAGE GETS THAT SAME DIRECTORY: the stages
 * of the run SHARE the workspace, the way a Jenkins run shares the workspace
 * of its node. The base is what the CLI attaches by default; the run that
 * asks for isolation is the one that attaches none, and only that run gives
 * each stage a workspace of its own.
 *
 * ## Workspace Lifecycle
 * | Event | Action |
 * |-------|--------|
 * | Stage starts | `ensureCreated()` — lazy creation only when stage has shell steps |
 * | Stage completes (SUCCESS) | `cleanupAfterComplete()` — remove workspace |
 * | Stage fails/LOST/FAILED_TIMEOUT | `retainOnFailure()` — no-op (already retained) |
 * @param controlDirRoot The root directory for all control directories.
 * Durable sh Pattern</a>
*/
class WorkspaceResolver(
    private val controlDirRoot: Path,
    /**
     * Optional project-workspace override (--workspace <dir>).
     * When set, [resolve] returns paths under [workspaceBase]; the journal,
     * artefacts and locks stay under [controlDirRoot].
     * The override is normalised to an absolute, lexically-normal path on
     * construction. The Step containment guards compare with
     * [Path.startsWith], which is component-wise and only meaningful when both
     * sides have the same form. A bare `--workspace .` yields
     * `Paths.get(".")`, whose [Path.normalize] is the EMPTY path, so
     * `out.txt` does not start with `""` and every file Step fails closed with
     * "escapes workspace" even though the path is trivially inside. That also
     * silently disabled the deleteDir root guard, since the same comparison
     * rejected the root. Both sides being absolute keeps the guards honest.
*/
    workspaceBaseArg: Path? = null,
    private val workspaceBase: Path? = workspaceBaseArg?.toAbsolutePath()?.normalize(),
) {

    /**
     * The root directory for all control directories.
*/
    val root: Path = controlDirRoot

    /**
     * Resolves the workspace path for a given stage.
     * @param stageName The name of the stage.
     * @param stageIndex The index of the stage (for disambiguation in parallel).
     * @return The workspace of the stage: the shared base when the run has one,
     *   and `<controlDirRoot>/workspace/<stageName>-<stageIndex>/` when it has none.
*/
    fun resolve(stageName: String, stageIndex: Int): Path {
        // With an explicit project workspace (--workspace), stages SHARE
        // the given directory (Jenkins-familiar single-workspace semantics): a real
        // project build must run with CWD == the project root where gradlew lives.
        if (workspaceBase != null) return workspaceBase
        val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return controlDirRoot.resolve("workspace").resolve("${safeName}-${stageIndex}")
    }

    /**
     * Resolves the artefacts directory for a given run and stage.
     * @param runId The run identifier.
     * @param stageName The name of the stage.
     * @return The artefacts directory: `<controlDirRoot>/artefacts/<runId>/<stageName>/`
*/
    fun resolveArchiveDir(runId: String, stageName: String): Path {
        // The run decides whether it can be a name: `RunId` owns the alphabet of
        // an identity that can name a durable location, and asking it is what
        // keeps this resolver and the lease store from disagreeing about what a
        // safe run id looks like. The resolved path is ALSO verified to stay
        // under `<controlDirRoot>/artefacts/` after lexical normalisation, which
        // defends against traversal and against a symlink-resolved name;
        // symlink defence for the resolved artefacts dir is the caller's
        // responsibility at write time, since a symlink-to-component cannot be
        // detected lexically.
        require(RunId.isNameable(runId)) {
            "resolveArchiveDir: runId '$runId' is not a path-safe segment"
        }
        val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val artefactsRoot = controlDirRoot.resolve("artefacts").toAbsolutePath().normalize()
        val resolved = artefactsRoot.resolve(runId).resolve(safeName).normalize()
        require(resolved.startsWith(artefactsRoot)) {
            "resolveArchiveDir: resolved path '$resolved' is not contained under '$artefactsRoot'"
        }
        return resolved
    }

    /**
     * Ensures the workspace directory exists, creating it if necessary.
     * Uses Files.createDirectories for lazy, idempotent creation.
     * This is called only when a stage has shell steps (lazy creation).
     * @param path The workspace path to ensure exists.
     * @return The same path (for chaining).
*/
    fun ensureCreated(path: Path): Path {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
        }
        return path
    }

    /**
     * Cleans up the workspace directory after successful completion.
     * Removes the workspace directory recursively.
     * Idempotent — safe to call even if directory doesn't exist.
     * @param path The workspace path to clean up.
*/
    fun cleanupAfterComplete(path: Path) {
        if (Files.exists(path)) {
            // `use`, not a bare chain: `Files.walk` returns a Stream that holds an open
            // directory handle for the duration of the walk, and this runs once per completed
            // run. Without the close, each call leaks a descriptor until the process ends --
            // which is a hard `Too many open files` failure on the usual 1024-fd limit once a
            // long pipeline, a retry loop or a durable restart has run enough of them.
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder())
                    .forEach { p ->
                        try {
                            Files.deleteIfExists(p)
                        } catch (_: Exception) {
                            // Ignore deletion errors
                        }
                    }
            }
        }
    }

    /**
     * Retains the workspace directory after failure.
     * This is a no-op because workspace directories are already retained on failure
     * by default (no deletion occurs on non-success outcomes).
     * This method exists for API symmetry and explicit documentation.
*/
    fun retainOnFailure() {
        // No-op: workspaces are retained on failure by default
        // Retention happens automatically because cleanupAfterComplete
        // is only called on SUCCESS outcomes
    }
}
