package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.runCaptured
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The effectful half of [SourceProvenance]: reads the facts the pure decider
 * needs. Git execution goes through the canonical [DurableTaskRuntime]
 * (LF-0309: ProcessDurableTaskRuntime is the single authorised process home);
 * this module constructs processes as little as the runtime lets it — which
 * is to say, never directly.
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
    fun probe(repoRoot: Path, runtime: DurableTaskRuntime = defaultRuntime()): SourceProvenanceFacts {
        val head = git(runtime, repoRoot, listOf("rev-parse", "HEAD"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SourceProvenance.PROVENANCE_UNKNOWN

        // `git status --porcelain` lists staged and unstaged changes to tracked
        // files as XY pairs. Untracked files ('??') are deliberately ignored:
        // they are not part of any commit's content, so they cannot make the
        // recorded commit a lie about the compiled sources.
        val status = git(runtime, repoRoot, listOf("status", "--porcelain"))
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
     * Run `git` through the task runtime and return stdout, or `null` when git
     * is absent, the directory is not a repository, or the command fails. A
     * `null` means "no answer", which is a real state the decider must handle —
     * never an implicit zero.
     */
    private fun git(runtime: DurableTaskRuntime, repoRoot: Path, args: List<String>): String? = try {
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = listOf("git") + args),
            runId = RunId("source-provenance-${UUID.randomUUID()}"),
            opId = "source-provenance-${UUID.randomUUID()}",
            timeoutMs = 30_000L,
            env = emptyMap<String, SecretHandle>(),
            workspaceRoot = repoRoot.toString(),
        )
        val captured = runBlocking { runtime.runCaptured(request) }
        if (captured.exitCode == 0 && !captured.timedOut) captured.stdout else null
    } catch (_: Exception) {
        null
    }

    private fun defaultRuntime(): DurableTaskRuntime =
        ProcessDurableTaskRuntime(
            controlRoot = java.nio.file.Files.createTempDirectory("source-provenance-control"),
            clock = object : dev.rubentxu.pipeline.v2.domain.durable.Clock {
                override fun now(): Instant = Instant.now()
            },
        )
}
