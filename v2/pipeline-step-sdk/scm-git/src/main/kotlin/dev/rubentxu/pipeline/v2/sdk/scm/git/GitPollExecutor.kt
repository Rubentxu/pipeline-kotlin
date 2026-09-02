package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.runCaptured
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

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
    private val taskRuntime: DurableTaskRuntime? = null,
    private val controlRoot: Path = Files.createTempDirectory("git-poll-tasks"),
) {
    private val runtimeInstance: DurableTaskRuntime by lazy {
        taskRuntime ?: ProcessDurableTaskRuntime(
            controlRoot,
            object : Clock { override fun now(): Instant = Instant.now() },
        )
    }

    /**
     * Execute git ls-remote and return the remote SHA.
     *
     * @param url Repository URL (https or file://)
     * @param branch Branch name
     * @param credentialsApplier GitCredentialsApplier for env injection (may be null — no-op)
     * @return Result containing SHA string, or failure classification
     */
    fun execute(url: String, branch: String, credentialsApplier: GitCredentialsApplier? = null): Result<String> {
        return try {
            val args = listOf("git", "ls-remote", url, branch)
            // ls-remote historically used redirectErrorStream(true); emulate
            // by passing both streams into the captured run and joining for
            // failure classification.
            val env = credentialsApplier?.buildEnv() ?: emptyMap()
            val secretEnv = env.mapValues { (_, v) -> SecretHandle.plain(v) }
            val request = TaskExecutionRequest(
                task = TaskSpec.ExecTask(argv = args),
                runId = RunId("git-poll-${UUID.randomUUID()}"),
                opId = "git-poll-${UUID.randomUUID()}",
                timeoutMs = timeoutSeconds * 1000,
                env = secretEnv,
            )
            val captured = runBlocking { runtimeInstance.runCaptured(request) }

            if (captured.timedOut) {
                return Result.failure(IllegalStateException("git ls-remote timed out after ${timeoutSeconds}s"))
            }
            if (captured.exitCode != 0) {
                val reason = classifyFailure(captured.combinedOutput, captured.exitCode)
                return Result.failure(reason)
            }

            // Parse output: "<sha>\t<ref>"
            val firstLine = captured.combinedOutput.lines().firstOrNull() ?: ""
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
