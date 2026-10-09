package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.PatternSyntaxException

/**
 * Result of a cleanWs operation.
 *
 * @property deletedFiles Number of files deleted
 * @property deletedDirs Number of directories deleted (recursive, includes now-empty parents)
 * @param patterns The glob patterns applied
 * @param sha256 SHA-256 hex of the .cleaned marker content
 */
data class CleanWsResult(
    val deletedFiles: Int,
    val deletedDirs: Int,
    val patterns: List<String>,
    val sha256: String,
)

/**
 * Executor for [StepSpec.CleanWs] — selective workspace cleanup with glob patterns.
 *
 * ## Behavior
 *
 * 1. Resolves workspace root
 * 2. Determines files to delete:
 *    - `patterns != null`: delete only files matching any pattern (Ant-style globs)
 *    - `patterns == null`: delete all non-.v2 files
 * 3. Deletes matching files (or all non-.v2 files)
 * 4. If `deleteDirs=true`: removes now-empty parent directories recursively
 * 5. Preserves `.v2/artifacts/` tree (F-ARCH-L6-003)
 * 6. Writes `.cleaned` marker with sha256 for MEMOIZED replay
 *
 * ## Ant-Style Glob Semantics
 *
 * - `**` matches any path segment (including zero)
 * - `*` matches within a single path segment
 * - `?` matches a single character
 * - Patterns are relative to workspace root
 *
 * ## Path Safety
 *
 * - Operates only within workspace root
 * - Never touches `.v2/artifacts/` per F-ARCH-L6-003 invariant
 * - C9: refuses the pattern-less form when [rootDestruction] does not permit it.
 *   `cleanWs(patterns = null)` means "delete every non-.v2 file", so against a
 *   `--workspace` root that is the user's whole project. See [CleanWsExecutor].
 *
 * ## Why C8 and C9 share one type
 *
 * Both Steps answer the same question — may this root be destroyed wholesale —
 * and C9 is strictly more dangerous: `deleteDir` has a sub-path form a caller
 * could narrow by accident, `cleanWs` has no partial form at all. Two booleans
 * let one be wired to scratch while the other stayed user-owned, with nothing
 * observing the divergence. One [RootDestruction] makes that unrepresentable.
 *
 * @param workspaceResolver Resolves stage workspace root: `(stageName, stageIndex) -> workspacePath`
 * @param rootDestruction Whether a wholesale root sweep may run.
 */
class CleanWsExecutor(
    private val workspaceResolver: (stageName: String, stageIndex: Int) -> Path,
    val rootDestruction: RootDestruction = RootDestruction.DecidedElsewhere,
) {

    /**
     * Executes a [StepSpec.CleanWs] step.
     *
     * @param stageName The stage name
     * @param stageIndex The stage index
     * @param stepIndex The step index (unused — event emission deferred to dispatcher)
     * @param spec The cleanWs step specification
     * @return [CleanWsResult]
     */
    fun execute(stageName: String, stageIndex: Int, stepIndex: Int, spec: StepSpec.CleanWs): CleanWsResult {
        val workspace = workspaceResolver(stageName, stageIndex)
        val v2ArtifactsRoot = workspace.resolve(".v2").resolve("artifacts")

        // C9 interlock: `cleanWs()` with no patterns means "delete every
        // non-.v2 file in the workspace". In a scratch workspace that is fine,
        // but with --workspace the root is the user's own project, and this
        // form would delete every file in it. Unlike deleteDir, this is not
        // recoverable at all: there is no pattern to narrow it by accident. An
        // unresolved ownership question fails closed here too.
        //
        // Matched on the cases, for the same reason as deleteDir: a bit would
        // let a new ownership state inherit a verdict nobody chose for it.
        val wipesRoot = when (rootDestruction) {
            RootDestruction.ScratchOwned -> true
            RootDestruction.UserOwned,
            RootDestruction.DecidedElsewhere,
            -> false
        }
        require(wipesRoot || !spec.patterns.isNullOrEmpty()) {
            "cleanWs refuses to run without patterns on workspace '$workspace'; " +
                "pass patterns such as cleanWs(patterns = listOf(\"build/**\")) " +
                "so only generated content is removed"
        }

        val markerFile = workspace.resolve(".cleaned")
        val effectivePatterns = spec.patterns ?: emptyList()

        // Check MEMOIZED marker for idempotent re-run
        if (Files.exists(markerFile)) {
            val existingSha = try {
                Files.readString(markerFile).trim()
            } catch (_: Exception) {
                ""
            }
            if (existingSha.isNotEmpty() && effectivePatterns.isEmpty()) {
                // Idempotent re-run of delete-all with matching marker
                return CleanWsResult(
                    deletedFiles = 0,
                    deletedDirs = 0,
                    patterns = effectivePatterns,
                    sha256 = existingSha,
                )
            }
        }

        var deletedFiles = 0
        var deletedDirs = 0

        if (!Files.exists(workspace)) {
            return CleanWsResult(0, 0, effectivePatterns, sha256("".toByteArray()))
        }

        // Collect files to delete
        val filesToDelete = mutableListOf<Path>()

        if (effectivePatterns.isNotEmpty()) {
            // Selective deletion with patterns
            for (pattern in effectivePatterns) {
                val matched = resolveAntPattern(workspace, pattern, v2ArtifactsRoot)
                filesToDelete.addAll(matched)
            }
        } else {
            // Delete all non-.v2 files
            Files.walk(workspace)
                .filter { it != workspace }
                .filter { !it.startsWith(v2ArtifactsRoot) }
                .filter { Files.isRegularFile(it) }
                .forEach { filesToDelete.add(it) }
        }

        // Delete files
        for (file in filesToDelete) {
            try {
                Files.deleteIfExists(file)
                deletedFiles++
            } catch (_: Exception) {
                // Ignore individual file deletion errors
            }
        }

        // Delete empty parent directories if deleteDirs=true
        if (spec.deleteDirs) {
            deletedDirs = deleteEmptyParents(workspace, v2ArtifactsRoot)
        }

        // Write MEMOIZED marker
        val markerContent = "cleaned:${System.currentTimeMillis()}:${effectivePatterns.joinToString(",")}"
        val sha256 = sha256(markerContent.toByteArray())
        Files.writeString(markerFile, sha256)

        return CleanWsResult(
            deletedFiles = deletedFiles,
            deletedDirs = deletedDirs,
            patterns = effectivePatterns,
            sha256 = sha256,
        )
    }

    /**
     * Resolves Ant-style glob patterns to actual file paths.
     *
     * Handles:
     * - all .class files recursively
     * - everything under build/
     * - all .tmp files in root
     */
    private fun resolveAntPattern(workspace: Path, pattern: String, v2ArtifactsRoot: Path): List<Path> {
        val results = mutableListOf<Path>()

        // Convert Ant pattern to regex
        val regex = antPatternToRegex(pattern)
        val regexPattern = try {
            java.util.regex.Pattern.compile(regex)
        } catch (e: PatternSyntaxException) {
            return emptyList()
        }

        Files.walk(workspace)
            .filter { it != workspace }
            .filter { !it.startsWith(v2ArtifactsRoot) }
            .filter { Files.isRegularFile(it) }
            .forEach { file ->
                val relPath = workspace.relativize(file).toString()
                if (regexPattern.matcher(relPath).matches()) {
                    results.add(file)
                }
            }

        return results
    }

    private fun antPatternToRegex(pattern: String): String {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            when {
                ch == '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        // ** — zero or more complete path segments (each ending in /)
                        if (i + 2 < pattern.length && pattern[i + 2] == '*') {
                            // *** or **** — treat as **
                            sb.append("(.*/)*")
                            i += 2
                        } else if (i + 2 < pattern.length) {
                            // **** (three stars total: ** followed by *) → **/* shorthand
                            sb.append("(.*/)*")
                            i += 3  // skip ** and the following *
                        } else {
                            // ** at end (no following *) — matches everything including /
                            sb.append(".*")
                            i += 2
                        }
                    } else {
                        // * matches everything except /
                        sb.append("[^/]*")
                        i++
                    }
                }
                ch == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                ch == '.' -> {
                    sb.append("\\.")
                    i++
                }
                ch == '/' -> {
                    sb.append("/")
                    i++
                }
                else -> {
                    sb.append(java.util.regex.Pattern.quote(ch.toString()))
                    i++
                }
            }
        }
        sb.append("$")
        return sb.toString()
    }

    /**
     * Deletes empty parent directories bottom-up, excluding workspace root and .v2 tree.
     */
    private fun deleteEmptyParents(workspace: Path, v2ArtifactsRoot: Path): Int {
        var deleted = 0
        Files.walk(workspace)
            .filter { it != workspace }
            .filter { Files.isDirectory(it) }
            .filter { !it.startsWith(v2ArtifactsRoot) }
            .sorted(Comparator.reverseOrder()) // depth-first (deepest first)
            .forEach { dir ->
                if (Files.exists(dir)) {
                    try {
                        val entries = dir.toFile().listFiles()
                        if (entries != null && entries.isEmpty()) {
                            Files.deleteIfExists(dir)
                            deleted++
                        }
                    } catch (_: Exception) {
                        // Ignore
                    }
                }
            }
        return deleted
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
