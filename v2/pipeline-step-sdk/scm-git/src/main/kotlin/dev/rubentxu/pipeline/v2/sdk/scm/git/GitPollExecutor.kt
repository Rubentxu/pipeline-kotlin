package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Executes `git ls-remote <url> <branch>` to get the remote SHA.
 *
 * Structured argv — no secrets in args. Honors inherited env from
 * GitCredentialsApplier (GIT_CONFIG_GLOBAL + HOME for credential helper).
 *
 * INV-L5-CR-006: synchronous poll, no daemon.
 *
 * @param timeoutSeconds max wait for git command (default 30)
 */
class GitPollExecutor(
    private val timeoutSeconds: Long = 30,
) {
    /**
     * Execute git ls-remote and return the remote SHA.
     *
     * @param url Repository URL (https or file://)
     * @param branch Branch name
     * @return Result containing SHA string, or failure classification
     */
    fun execute(url: String, branch: String): Result<String> {
        return try {
            val args = listOf("git", "ls-remote", url, branch)
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!exited) {
                // Timeout
                return Result.failure(IllegalStateException("git ls-remote timed out after ${timeoutSeconds}s"))
            }

            val exitValue = process.exitValue()
            if (exitValue != 0) {
                // Non-zero exit
                val reason = classifyFailure(output, exitValue)
                return Result.failure(reason)
            }

            // Parse output: "<sha>\t<ref>"
            val firstLine = output.lines().firstOrNull() ?: ""
            val sha = firstLine.substringBefore("\t").trim()

            if (sha.isBlank()) {
                Result.failure(IllegalStateException("git ls-remote returned empty SHA for $branch"))
            } else {
                Result.success(sha)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Classifies git failure based on stderr/stdout content.
     */
    private fun classifyFailure(output: String, exitCode: Int): Exception {
        val lower = output.lowercase()
        return when {
            lower.contains("authentication failed") ||
            lower.contains("auth") ||
            lower.contains("could not resolve host") -> {
                IllegalStateException("Authentication failed or host unreachable: ${output.take(256)}")
            }
            lower.contains("remote branch") && lower.contains("not found") -> {
                IllegalStateException("Remote branch not found: ${output.take(256)}")
            }
            else -> IllegalStateException("git ls-remote failed (exit $exitCode): ${output.take(256)}")
        }
    }
}
