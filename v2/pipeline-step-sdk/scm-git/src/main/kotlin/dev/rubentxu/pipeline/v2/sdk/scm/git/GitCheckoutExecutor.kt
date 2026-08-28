package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.MismatchedSecretException
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
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
 * @param secretStore SecretStore for credential resolution (may be null in test paths)
 * @param secretPatternRegistry SecretPatternRegistry for scrubbing GitCheckoutFailed.reason (INV-L6-CR-013)
 */
class GitCheckoutExecutor(
    private val poll: GitPollExecutor,
    private val changelog: GitChangelogWriter,
    private val credentialsApplier: GitCredentialsApplier,
    private val clock: Clock = Clock.systemUTC(),
    private val secretStore: SecretStore? = null,
    private val secretPatternRegistry: SecretPatternRegistry = SecretPatternRegistry(),
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

        // Apply credentials if needed; capture the credentials file path
        // so tests can verify it was written with real secrets.
        // NOTE: close() is NOT called here — it is deferred to GitCheckoutExecutor.close()
        // so the file persists for the duration of execute() and is available
        // for verification by the caller AFTER execute() returns.
        var credentialsFilePath: String? = null
        var gitConfigFilePath: String? = null
        val gitCreds = resolveGitCredentials(req, spec)
        if (gitCreds != null) {
            val stringCred = gitCreds.string
            val userCred = gitCreds.user
            val passCred = gitCreds.pass
            val sshKeyCred = gitCreds.sshKey
            val sshPassphraseCred = gitCreds.sshPassphrase
            when {
                stringCred != null -> {
                    credentialsApplier.apply(stringCred, spec.url)
                    credentialsFilePath = credentialsApplier.credentialsFilePath()
                }
                userCred != null && passCred != null -> {
                    credentialsApplier.apply(userCred, passCred, spec.url)
                    gitConfigFilePath = credentialsApplier.gitConfigFilePath()
                }
                sshKeyCred != null -> {
                    credentialsApplier.applySsh(sshKeyCred, sshPassphraseCred, spec.url)
                }
            }
        }

        return try {
            // Apply credentials BEFORE executeInternal so buildEnv() is available for all git spawns.
            // buildEnv() must be called AFTER apply()/applySsh() which write the temp files.
            val env = if (gitCreds != null) {
                credentialsApplier.buildEnv()
            } else {
                emptyMap()
            }

            executeInternal(req, spec, workspace, gitDir, startMs, req.previousRemoteSha, env)
                .map { it.copy(credentialsFilePath = credentialsFilePath, gitConfigFilePath = gitConfigFilePath) }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            // INV-L6-CR-013: GitCheckoutFailed.reason must be scrubbed before emission.
            // Scrub removes registered secret patterns (canaries + real credentials).
            val rawReason = e.message?.take(256) ?: "Unknown error"
            val scrubbedReason = secretPatternRegistry.scrub(rawReason)
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubbedReason,
                exitCode = -1
            ))
            Result.failure(e)
        }
    }

    private fun executeInternal(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        workspace: Path,
        gitDir: Path,
        startMs: Long,
        previousRemoteSha: String?,
        env: Map<String, String> = emptyMap(),
    ): Result<GitCheckoutResult> {
        val url = spec.url
        val branch = spec.branch
        val workspaceRoot = req.workspaceRoot

        // Step 2: ls-remote to get remote SHA (with buildEnv wired into ProcessBuilder)
        val remoteShaResult = poll.execute(url, branch, credentialsApplier)
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
                reason = scrubReason(errorMsg.take(256)),
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
            val currentSha = revParse(req, workspace, "HEAD", env)
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
            val fetchResult = gitFetch(req, workspace, env)
            if (fetchResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    reason = scrubReason("Fetch failed: ${fetchResult.exceptionOrNull()?.message}".take(256)),
                    exitCode = 128
                ))
                return Result.failure(fetchResult.exceptionOrNull() ?: IllegalStateException("Fetch failed"))
            }

            val resetResult = gitResetHard(req, workspace, remoteSha, env)
            if (resetResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = req.stepIndex.toLong(),
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    reason = scrubReason("Reset failed: ${resetResult.exceptionOrNull()?.message}".take(256)),
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
        val cloneResult = gitClone(req, url, branch, workspace, env)
        if (cloneResult.isFailure) {
            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                reason = scrubReason("Clone failed: ${cloneResult.exceptionOrNull()?.message}".take(256)),
                exitCode = 128
            ))
            return Result.failure(cloneResult.exceptionOrNull() ?: IllegalStateException("Clone failed"))
        }

        // Get cloned SHA
        val sha = revParse(req, workspace, "HEAD", env) ?: remoteSha

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
        // If the applier has built-in credentials (test path), return them directly.
        // This preserves backward compatibility with tests that construct GitCredentials directly.
        // However, apply() needs secretStore to resolve the actual secret bytes.
        val applierCreds = credentialsApplier.credentials
        if (applierCreds.string != null || applierCreds.user != null || applierCreds.pass != null ||
            applierCreds.sshKey != null) {
            // Ensure secretStore is available — apply() will need it to resolve secret bytes
            if (secretStore == null && req.secretStore == null) {
                throw IllegalStateException(
                    "SecretStore is required when credentials are configured on GitCredentialsApplier, " +
                    "but none was provided. Pass SecretStore to GitCheckoutExecutor or include it in GitCheckoutRequest."
                )
            }
            return applierCreds
        }
        // Production path: resolve from SecretStore using credentialsId
        // INV-L6-CR-001: kind is DECLARED, never inferred from byte content.
        // INV-L6-CR-004: typed Credential hierarchy is the kind system.
        val credentialsId = spec.credentialsId ?: return null
        val store = secretStore ?: req.secretStore ?: return null
        return try {
            val credential = store.get(credentialsId)
            // Pattern-match on DECLARED kind (INV-L6-CR-001)
            when (credential) {
                is dev.rubentxu.pipeline.v2.domain.credentials.SecretText -> {
                    // String channel: API token via credential helper
                    GitCredentials(
                        string = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(credentialsId),
                        user = null,
                        pass = null,
                        sshKey = null,
                        sshPassphrase = null,
                    )
                }
                is dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword -> {
                    // usernamePassword channel: basic auth via per-host config
                    GitCredentials(
                        string = null,
                        user = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(credentialsId),
                        pass = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(credentialsId),
                        sshKey = null,
                        sshPassphrase = null,
                    )
                }
                is dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey -> {
                    // SSH channel: private key via GIT_SSH_COMMAND
                    GitCredentials(
                        string = null,
                        user = null,
                        pass = null,
                        sshKey = dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(credentialsId),
                        sshPassphrase = credential.passphraseRef?.let {
                            dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef(it.credentialsId)
                        },
                    )
                }
                else -> {
                    // Unsupported credential kind for git checkout
                    // INV-L6-CR-010: MismatchedSecretException verbatim wording
                    throw dev.rubentxu.pipeline.v2.domain.MismatchedSecretException(
                        credentialId = credentialsId,
                        expectedKind = "SecretText|UsernamePassword|SshPrivateKey",
                        actualKind = credential::class.simpleName ?: "Unknown"
                    )
                }
            }
        } catch (e: dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException) {
            throw IllegalArgumentException("Could not find credentials entry with ID '${credentialsId.value}'", e)
        }
    }

    private fun revParse(req: GitCheckoutRequest, workspace: Path, ref: String, env: Map<String, String>): String? {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "rev-parse", ref)
            guardProcessBuilderArgs(args)
            val builder = ProcessBuilder(args)
            env.forEach { (key, value) -> builder.environment()[key] = value }
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (stderr.isNotBlank()) {
                emitEcho(req, "git rev-parse stderr: $stderr")
            }
            if (exitCode && process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    private fun gitFetch(req: GitCheckoutRequest, workspace: Path, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "fetch")
            guardProcessBuilderArgs(args)
            val builder = ProcessBuilder(args)
            env.forEach { (key, value) -> builder.environment()[key] = value }
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (stdout.isNotBlank()) {
                emitEcho(req, stdout)
            }
            if (stderr.isNotBlank()) {
                emitEcho(req, "git fetch stderr: $stderr")
            }
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git fetch failed${stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitResetHard(req: GitCheckoutRequest, workspace: Path, sha: String, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "reset", "--hard", sha)
            guardProcessBuilderArgs(args)
            val builder = ProcessBuilder(args)
            env.forEach { (key, value) -> builder.environment()[key] = value }
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (stdout.isNotBlank()) {
                emitEcho(req, stdout)
            }
            if (stderr.isNotBlank()) {
                emitEcho(req, "git reset --hard stderr: $stderr")
            }
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git reset --hard failed${stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitClone(req: GitCheckoutRequest, url: String, branch: String, workspace: Path, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "clone", "--branch", branch, url, workspace.toString())
            guardProcessBuilderArgs(args)
            val builder = ProcessBuilder(args)
            env.forEach { (key, value) -> builder.environment()[key] = value }
            val process = builder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor(GIT_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            if (stdout.isNotBlank()) {
                emitEcho(req, stdout)
            }
            if (stderr.isNotBlank()) {
                emitEcho(req, "git clone stderr: $stderr")
            }
            if (exitCode && process.exitValue() == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("git clone failed${stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Emits git command stdout/stderr through EchoOutputCaptured events.
     * The RedactingEventSink will redact any secret patterns before persisting.
     */
    private fun emitEcho(req: GitCheckoutRequest, content: String) {
        if (content.isBlank()) return
        try {
            req.eventSink.append(EchoOutputCaptured(
                eventId = newEventId(),
                runId = req.runId,
                sequence = req.stepIndex.toLong(),
                occurredAt = Instant.now(clock),
                stepIndex = req.stepIndex,
                content = content,
            ))
        } catch (e: Exception) {
            System.err.println("Warning: failed to emit EchoOutputCaptured: ${e.message}")
        }
    }

    private fun emitEvent(req: GitCheckoutRequest, event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
        try {
            req.eventSink.append(event)
        } catch (e: Exception) {
            System.err.println("Warning: failed to emit event ${event.kind}: ${e.message}")
        }
    }

    /**
     * Scrubs a reason string using the secret pattern registry.
     * INV-L6-CR-013: GitCheckoutFailed.reason must be scrubbed before emission.
     */
    private fun scrubReason(reason: String): String {
        return secretPatternRegistry.scrub(reason)
    }

    private fun newEventId(): String = java.util.UUID.randomUUID().toString()

    override fun close() {
        // Wipe credentials files AFTER execute() completes.
        // This ensures the credentials file persists through execute() and
        // is available for verification by callers after execute() returns.
        credentialsApplier.close()
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
    /**
     * Path to the .git-credentials file (string channel).
     * Non-null when string credentials were applied.
     */
    val credentialsFilePath: String? = null,
    /**
     * Path to the .gitconfig file (usernamePassword channel).
     * Non-null when usernamePassword credentials were applied.
     */
    val gitConfigFilePath: String? = null,
)
