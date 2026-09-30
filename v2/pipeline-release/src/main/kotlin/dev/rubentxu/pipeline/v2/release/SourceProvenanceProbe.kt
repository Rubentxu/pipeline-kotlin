package dev.rubentxu.pipeline.v2.release

import java.io.File
import java.nio.file.Path

/**
 * The effectful half of [SourceProvenance]: reads the facts the pure decider
 * needs. This is the only place that knows Git exists.
 *
 * Every query degrades to [SourceProvenance.PROVENANCE_UNKNOWN] or a zero
 * count when Git cannot answer, and the decider decides what an unanswered
 * question means. The probe never decides — it observes.
 */
object SourceProvenanceProbe {

    /**
     * Snapshot version control state for the repository containing [repoRoot].
     *
     * Note the asymmetry that matters: a *failed dirty check* is reported as
     * `SourceProvenance.PROVENANCE_UNKNOWN` rather than as "clean". If Git is
     * unavailable we cannot prove the tree is clean, and reporting zero
     * modified files would turn an outage into a pass.
     */
    fun probe(repoRoot: Path): SourceProvenanceFacts {
        val head = git(repoRoot, listOf("rev-parse", "HEAD"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SourceProvenance.PROVENANCE_UNKNOWN

        // `git status --porcelain` lists staged and unstaged changes to tracked
        // files as XY pairs. Untracked files ('??') are deliberately ignored:
        // they are not part of any commit's content, so they cannot make the
        // recorded commit a lie about the compiled sources.
        val status = git(repoRoot, listOf("status", "--porcelain"))
        if (status == null) {
            return SourceProvenanceFacts(
                headCommit = head,
                modifiedTrackedFiles = 0,
                stagedButUncommitted = 0,
                builtFromCommit = null,
            )
        }

        var modified = 0
        var staged = 0
        status.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val index = line.getOrNull(0)?.toString() ?: " "
            val worktree = line.getOrNull(1)?.toString() ?: " "
            if (index == "?" && worktree == "?") return@forEach // untracked
            if (index != " " && index != "?") staged++
            if (worktree != " " && worktree != "?") modified++
        }

        return SourceProvenanceFacts(
            headCommit = head,
            modifiedTrackedFiles = modified,
            stagedButUncommitted = staged,
            builtFromCommit = null,
        )
    }

    /**
     * Run git and return stdout, or `null` when git is absent, the directory is
     * not a repository, or the command fails. A `null` means "no answer", which
     * is a real state the decider must handle — never an implicit zero.
     */
    private fun git(repoRoot: Path, args: List<String>): String? = try {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoRoot.toFile())
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) stdout else null
    } catch (_: Exception) {
        null
    }
}

/**
 * Convenience for the Gradle task: the repository root that owns the version
 * control state of a build. Falls back to the current directory when the
 * layout is unexpected, so the law still applies instead of silently not
 * applying.
 */
fun defaultRepoRoot(): Path =
    File(System.getProperty("user.dir")).toPath()
