package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Appends new commits to `<workspace>/changelog.txt`.
 * Format: `<7-char-sha> <first-line-of-subject>` (truncated 256 chars, non-ASCII stripped).
 * Idempotent: parses existing file for already-listed SHAs and skips duplicates.
 * Plain-text changelog, no JDOM XML.
 * @param timeoutSeconds max wait for git log command (default 10)
*/
class GitChangelogWriter(
    private val timeoutSeconds: Long = 10,
    private val taskRuntime: DurableTaskRuntime? = null,
    private val controlRoot: Path = Files.createTempDirectory("git-changelog-tasks"),
) : AutoCloseable {
    private val runtimeInstance: DurableTaskRuntime by lazy {
        taskRuntime ?: ProcessDurableTaskRuntime(
            controlRoot,
            object : Clock { override fun now(): Instant = Instant.now() },
        )
    }
    companion object {
        private const val MAX_SUBJECT_LEN = 256
        private val SHA7_PATTERN = Regex("^[0-9a-f]{7}")
    }

    /**
     * Append new commits from `<prevSha>..<endRef>` to `<workspace>/<rel>/changelog.txt`.
     * @param targetDir The authorised checkout directory. It arrives RESOLVED: this
     *   writer does not resolve paths, because a second resolver is a second rule
     *   about the same directory, and the strict one has to be the only one.
     * @param changelogFile The file the changelog is written to
     * @param previousSha SHA to start from (excluded), or null/empty for all history
     * @param endRef Git ref for end of range (e.g., "HEAD")
     * @param transcript the host's transcript destination, or null when this composition has
     *   none. What `git log` printed is published there, so a reader can see the log the
     *   changelog was derived from.
*/
    fun append(
        targetDir: Path,
        changelogFile: Path,
        previousSha: String?,
        endRef: String,
        transcript: GitTranscript? = null,
    ): Result<Unit> {
        return try {
            // Parse existing SHAs to skip duplicates
            val existingShas = if (Files.exists(changelogFile)) {
                Files.readAllLines(changelogFile)
                    .mapNotNull { line ->
                        SHA7_PATTERN.find(line)?.value
                    }
                    .toMutableSet()
            } else {
                mutableSetOf()
            }

            // Build git log command
            val range = if (previousSha.isNullOrBlank()) {
                endRef
            } else {
                "$previousSha..$endRef"
            }

            val args = listOf(
                "git", "-C", targetDir.toString(),
                "log", "--pretty=format:%h %s",
                range
            )

            val request = TaskExecutionRequest(
                task = TaskSpec.ExecTask(argv = args),
                runId = RunId("git-log-${UUID.randomUUID()}"),
                opId = "git-log-${UUID.randomUUID()}",
                timeoutMs = timeoutSeconds * 1000,
                env = emptyMap(),
            )
            val captured = runBlocking { runtimeInstance.runCapturedTo(request, transcript) }
            if (captured.timedOut) {
                return Result.failure(IllegalStateException("git log timed out after ${timeoutSeconds}s"))
            }
            if (captured.exitCode != 0) {
                return Result.failure(IllegalStateException("git log failed (exit ${captured.exitCode}): ${captured.combinedOutput}"))
            }

            val newLines = captured.combinedOutput
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .filter { line ->
                    val sha = SHA7_PATTERN.find(line)?.value ?: ""
                    sha.isNotBlank() && sha !in existingShas
                }
                .map { line ->
                    // Truncate subject to MAX_SUBJECT_LEN, strip non-ASCII
                    val parts = line.split(" ", limit = 2)
                    val sha = parts.getOrElse(0) { "" }
                    val subject = parts.getOrElse(1) { "" }
                        .take(MAX_SUBJECT_LEN)
                        .map { c -> if (c.code in 32..126) c else '?' }
                        .joinToString("")
                    "$sha $subject"
                }

            if (newLines.isNotEmpty()) {
                val content = if (Files.exists(changelogFile)) {
                    val existing = Files.readString(changelogFile).trimEnd()
                    if (existing.isNotBlank()) "$existing\n${newLines.joinToString("\n")}" else newLines.joinToString("\n")
                } else {
                    newLines.joinToString("\n")
                }
                Files.writeString(changelogFile, content)
            }

            Result.success(Unit)
        } catch (refused: GitTranscriptRefused) {
            // A refusal is not "the changelog could not be written": the run lost the record of
            // what git printed, and that fact travels instead of being flattened into a
            // failure nobody reads. The caller that ignores a changelog failure must not be
            // able to ignore this one.
            throw refused
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes the control root this writer opened.
     *
     * The root holds what the durable task runtime writes while it runs `git log`, and
     * nothing in it is worth keeping after the checkout is over. A writer that nobody
     * closes leaves a directory in the system temporary area for as long as the machine
     * keeps it.
     */
    override fun close() {
        GitTempRoot.deleteRecursively(controlRoot)
    }
}
