package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Appends new commits to `<workspace>/changelog.txt`.
 *
 * Format: `<7-char-sha> <first-line-of-subject>` (truncated 256 chars, non-ASCII stripped).
 * Idempotent: parses existing file for already-listed SHAs and skips duplicates.
 *
 * INV-L5-CR-005: plain-text changelog, no JDOM XML.
 *
 * @param timeoutSeconds max wait for git log command (default 10)
 */
class GitChangelogWriter(
    private val timeoutSeconds: Long = 10,
) {
    companion object {
        private const val MAX_SUBJECT_LEN = 256
        private val SHA7_PATTERN = Regex("^[0-9a-f]{7}")
    }

    /**
     * Append new commits from `<prevSha>..<endRef>` to `<workspace>/<rel>/changelog.txt`.
     *
     * @param workspace Workspace root
     * @param relativeTargetDir Relative directory inside workspace
     * @param previousSha SHA to start from (excluded), or null/empty for all history
     * @param endRef Git ref for end of range (e.g., "HEAD")
     */
    fun append(workspace: Path, relativeTargetDir: String, previousSha: String?, endRef: String): Result<Unit> {
        return try {
            val targetDir = workspace.resolve(relativeTargetDir)
            val changelogFile = workspace.resolve("changelog.txt")

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

            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                return Result.failure(IllegalStateException("git log timed out after ${timeoutSeconds}s"))
            }

            if (process.exitValue() != 0) {
                return Result.failure(IllegalStateException("git log failed with exit ${process.exitValue()}"))
            }

            val output = process.inputStream.bufferedReader().readText()
            val newLines = output
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
