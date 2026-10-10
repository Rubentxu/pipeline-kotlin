package dev.rubentxu.pipeline.v2.application.durable

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves per-stage workspace directories with deterministic naming.
 *
 * Each stage gets a workspace under `<controlRoot>/workspace/<stageName>-<stageIndex>/`.
 * The deterministic naming ensures parallel stages with the same name get different
 * directories (collision-safe per WS-S-002).
 *
 * ## Workspace Lifecycle
 *
 * | Event | Action |
 * |-------|--------|
 * | Stage starts | `ensureCreated()` — lazy creation only when stage has shell steps |
 * | Stage completes (SUCCESS) | `cleanupAfterComplete()` — remove workspace |
 * | Stage fails/LOST/FAILED_TIMEOUT | `retainOnFailure()` — no-op (already retained) |
 *
 * @param controlDirRoot The root directory for all control directories.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
class WorkspaceResolver(
    private val controlDirRoot: Path,
    /**
     * WU-LPR-062: optional project-workspace override (--workspace <dir>).
     * When set, [resolve] returns paths under [workspaceBase]; the journal,
     * artefacts and locks stay under [controlDirRoot].
     *
     * C8: the override is normalised to an absolute, lexically-normal path on
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
     *
     * @param stageName The name of the stage.
     * @param stageIndex The index of the stage (for disambiguation in parallel).
     * @return The deterministic workspace path: `<controlDirRoot>/workspace/<stageName>-<stageIndex>/`
     */
    fun resolve(stageName: String, stageIndex: Int): Path {
        // WU-LPR-062: with an explicit project workspace (--workspace), stages SHARE
        // the given directory (Jenkins-familiar single-workspace semantics): a real
        // project build must run with CWD == the project root where gradlew lives.
        if (workspaceBase != null) return workspaceBase
        val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return controlDirRoot.resolve("workspace").resolve("${safeName}-${stageIndex}")
    }

    /**
     * Resolves the artefacts directory for a given run and stage.
     *
     * @param runId The run identifier.
     * @param stageName The name of the stage.
     * @return The artefacts directory: `<controlDirRoot>/artefacts/<runId>/<stageName>/`
     */
    fun resolveArchiveDir(runId: String, stageName: String): Path {
        // PATH-01 fix: validate runId as a path-safe segment (same character set as RunIdDirectory)
        // AND verify the resolved path stays under `<controlDirRoot>/artefacts/` after lexical
        // normalisation. Defends against traversal (`../`) and symlink-resolved-name against symlinks
        // for the segment itself; symlink defence for the resolved artefacts dir is the caller's
        // responsibility at write time, since a symlink-to-component cannot be detected lexically.
        require(runId.matches(RUN_ID_SEGMENT)) {
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
     *
     * Uses Files.createDirectories for lazy, idempotent creation.
     * This is called only when a stage has shell steps (lazy creation).
     *
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
     *
     * Removes the workspace directory recursively.
     * Idempotent — safe to call even if directory doesn't exist.
     *
     * @param path The workspace path to clean up.
     */
    fun cleanupAfterComplete(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { p ->
                    try {
                        Files.deleteIfExists(p)
                    } catch (_: Exception) {
                        // Ignore deletion errors
                    }
                }
        }
    }

    /**
     * Retains the workspace directory after failure.
     *
     * This is a no-op because workspace directories are already retained on failure
     * by default (no deletion occurs on non-success outcomes).
     * This method exists for API symmetry and explicit documentation.
     *
     * @param path The workspace path to retain.
     */
    fun retainOnFailure(path: Path) {
        // No-op: workspaces are retained on failure by default
        // Retention happens automatically because cleanupAfterComplete
        // is only called on SUCCESS outcomes
    }

    private companion object {
        // Same character set as RunIdDirectory.fileNameFor — kept consistent so the two cannot
        // disagree on what a "safe" run id looks like.
        val RUN_ID_SEGMENT = Regex("""[A-Za-z0-9._-]+""")
    }
}
