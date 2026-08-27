package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Git checkout executor with idempotent SHA-equality.
 *
 * D4 sequence:
 * 1. If `<workspace>/<rel>/.git` exists: git rev-parse HEAD → current SHA
 * 2. git ls-remote <url> <branch> → remote SHA (with credentials)
 * 3. SHA equal? → emit GitCheckoutCompleted (no-op)
 * 4. SHA different? → git fetch + git reset --hard <remoteSha>
 * 5. No .git? → git clone --branch <branch> <url> <rel>
 * 6. Append changelog via GitChangelogWriter.append(prevSha..HEAD)
 * 7. Emit lifecycle events per D7
 * 8. Classify failures per D8
 *
 * INV-L5-CR-001: idempotency <2s on SHA-equal no-op.
 * INV-L5-CR-002: no JGit imports.
 * INV-L5-CR-004: argv guard fail-closed on extraHeader/Authorization.
 * INV-CR-CR4: events route through injected EventSink.
 *
 * @param poll GitPollExecutor for ls-remote
 * @param changelog GitChangelogWriter for changelog.txt
 * @param credentialsApplier GitCredentialsApplier for temp file auth
 * @param clock Clock for event timestamps
 */
class GitCheckoutExecutor(
    private val poll: GitPollExecutor,
    private val changelog: GitChangelogWriter,
    private val credentialsApplier: GitCredentialsApplier,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    companion object {
        private const val GIT_TIMEOUT_SECONDS = 30L

        /**
         * Guards against forbidden argv patterns.
         * Fail-closed: throws IllegalArgumentException.
         */
        fun guardProcessBuilderArgs(args: List<String>) {
            GitCredentialsApplier.guardProcessBuilderArgs(args)
        }
    }

    /**
     * Execute checkout for the given request.
     */
    fun execute(req: GitCheckoutRequest): Result<GitCheckoutResult> {
        val spec = req.spec.scm as dev.rubentxu.pipeline.v2.domain.scm.GitScm
        val workspace = req.workspaceRoot.resolve(spec.relativeTargetDir)
        val gitDir = workspace.resolve(".git")

        // Guard argv
        guardProcessBuilderArgs(listOf("git", "ls-remote", spec.url, spec.branch))

        // Emit GitCheckoutStarted (D7)
        emitEvent(req, GitCheckoutStarted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = req.stepIndex.toLong(),
            occurredAt = Instant.now(clock),
            url = spec.url,
            branch = spec.branch,
            credentialsRef = spec.credentialsId?.let { dev.rubentxu.pipeline.v2.domain.CredentialsRef(it) }
        ))

        val startMs = System.currentTimeMillis()

        return credentialsApplier.use {
            // Apply credentials if needed
            val gitCreds = resolveGitCredentials(req, spec)
            if (gitCreds != null) {
                val stringCred = gitCreds.string
                val userCred = gitCreds.user
                val passCred = gitCreds.pass
                when {
                    stringCred != null -> credentialsApplier.apply(stringCred)
                    userCred != null && passCred != null -> credentialsApplier.apply(userCred, passCred)
                }
            }

            try {
                executeInternal(req, spec, workspace, gitDir, startMs, req.previousRemoteSha)
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = spec.url,
                    branch = spec.branch,
                    reason = e.message?.take(256) ?: "Unknown error",
                    exitCode = -1
                ))
                Result.failure(e)
            }
        }
    }

    private fun executeInternal(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        workspace: Path,
        gitDir: Path,
        startMs: Long,
        previousRemoteSha: String?,
    ): Result<GitCheckoutResult> {
        val url = spec.url
        val branch = spec.branch
        val workspaceRoot = req.workspaceRoot

        // Step 2: ls-remote to get remote SHA
        val remoteShaResult = poll.execute(url, branch)
        if (remoteShaResult.isFailure) {
            val durationMs = System.currentTimeMillis() - startMs
            val errorMsg = remoteShaResult.exceptionOrNull()?.message ?: "Unknown"
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                reason = errorMsg.take(256),
                exitCode = 128
            ))
            return Result.failure(remoteShaResult.exceptionOrNull() ?: IllegalStateException(errorMsg))
        }
        val remoteSha = remoteShaResult.getOrNull()!!

        // Check for poll changed (D6)
        if (previousRemoteSha != null && previousRemoteSha != remoteSha) {
            emitEvent(req, GitPollChanged(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                previousSha = previousRemoteSha,
                newSha = remoteSha
            ))
        }

        // Step 1: Check if .git exists
        if (Files.exists(gitDir)) {
            // Existing checkout - check SHA equality
            val currentSha = revParse(workspace, "HEAD")
            if (currentSha != null && currentSha == remoteSha) {
                // SHA equal - no-op
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutCompleted(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    sha = currentSha,
                    changelogPath = if (spec.changelog) workspaceRoot.resolve("changelog.txt").toString() else "",
                    durationMs = durationMs
                ))
                return Result.success(GitCheckoutResult(currentSha, durationMs, "no-op"))
            }

            // SHA different - fetch and reset
            val fetchResult = gitFetch(workspace)
            if (fetchResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    reason = "Fetch failed: ${fetchResult.exceptionOrNull()?.message}".take(256),
                    exitCode = 128
                ))
                return Result.failure(fetchResult.exceptionOrNull() ?: IllegalStateException("Fetch failed"))
            }

            val resetResult = gitResetHard(workspace, remoteSha)
            if (resetResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    reason = "Reset failed: ${resetResult.exceptionOrNull()?.message}".take(256),
                    exitCode = 128
                ))
                return Result.failure(resetResult.exceptionOrNull() ?: IllegalStateException("Reset failed"))
            }

            // Append changelog
            if (spec.changelog) {
                changelog.append(workspaceRoot, spec.relativeTargetDir, currentSha, "HEAD")
            }

            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(req, GitCheckoutCompleted(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                sha = remoteSha,
                changelogPath = if (spec.changelog) workspaceRoot.resolve("changelog.txt").toString() else "",
                durationMs = durationMs
            ))
            return Result.success(GitCheckoutResult(remoteSha, durationMs, "fetch+reset"))
        }

        // No .git - clone
        Files.createDirectories(workspace)
        val cloneResult = gitClone(url, branch, workspace)
        if (cloneResult.isFailure) {
            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                reason = "Clone failed: ${cloneResult.exceptionOrNull()?.message}".take(256),
                exitCode = 128
            ))
            return Result.failure(cloneResult.exceptionOrNull() ?: IllegalStateException("Clone failed"))
        }

        // Get cloned SHA
        val sha = revParse(workspace, "HEAD") ?: remoteSha

        // Append changelog
        if (spec.changelog) {
            changelog.append(workspaceRoot, spec.relativeTargetDir, null, "HEAD")
        }

        val durationMs = System.currentTimeMillis() - startMs
        emitEvent(req, GitCheckoutCompleted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = req.stepIndex.toLong(),
            occurredAt = Instant.now(clock),
            url = url,
            branch = branch,
            sha = sha,
            changelogPath = if (spec.changelog) workspaceRoot.resolve("changelog.txt").toString() else "",
            durationMs = durationMs
        ))
        return Result.success(GitCheckoutResult(sha, durationMs, "clone"))
    }

    private fun resolveGitCredentials(req: GitCheckoutRequest, spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm): GitCredentials? {
        // In real implementation, would resolve from SecretStore
        // For now, return null if no credentialsId
        if (spec.credentialsId == null) return null
        // Placeholder - actual resolution would happen via SecretStore
        return null
    }

    private fun revParse(workspace: Path, ref: String): String? {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "rev-parse", ref)
            val process = ProcessBuilder(args).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (exitCode && process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    private fun gitFetch(workspace: Path): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "fetch")
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git fetch failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitResetHard(workspace: Path, sha: String): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "reset", "--hard", sha)
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git reset --hard failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitClone(url: String, branch: String, workspace: Path): Result<Unit> {
        return try {
            val args = listOf("git", "clone", "--branch", branch, url, workspace.toString())
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git clone failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun emitEvent(req: GitCheckoutRequest, event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
        try {
            req.eventSink.append(event)
        } catch (e: Exception) {
            System.err.println("Warning: failed to emit event ${event.kind}: ${e.message}")
        }
    }

    private fun newEventId(): String = java.util.UUID.randomUUID().toString()

    override fun close() {
        // GitCredentialsApplier handles its own cleanup
    }
}

/**
 * Request for git checkout.
 */
data class GitCheckoutRequest(
    val spec: CheckoutSpec,
    val runId: String,
    val workspaceRoot: Path,
    val eventSink: EventSink,
    val clock: Clock,
    val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore?,
    val stepIndex: Int,
    val previousRemoteSha: String?,
)

/**
 * Result of git checkout.
 */
data class GitCheckoutResult(
    val sha: String,
    val durationMs: Long,
    val classification: String, // "no-op", "fetch+reset", "clone"
)
