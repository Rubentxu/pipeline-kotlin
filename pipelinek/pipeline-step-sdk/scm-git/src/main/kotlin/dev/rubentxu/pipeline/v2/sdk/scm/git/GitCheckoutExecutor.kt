package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.MismatchedSecretException
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Git checkout executor with idempotent SHA-equality.
 *  sequence:
 * 1. If `<workspace>/<rel>/.git` exists: git rev-parse HEAD → current SHA
 * 2. git ls-remote <url> <branch> → remote SHA (with credentials)
 * 3. SHA equal? → emit GitCheckoutCompleted (no-op)
 * 4. SHA different? → git fetch + git reset --hard <remoteSha>
 * 5. No.git? → git clone --branch <branch> <url> <rel>
 * 6. Append changelog via GitChangelogWriter.append(prevSha..HEAD)
 * 7. Emit lifecycle events
 * 8. Classify failures per
 * Idempotency <2s on SHA-equal no-op.
 * No JGit imports.
 * Argv guard fail-closed on extraHeader/Authorization.
 * Events route through injected EventSink.
 * @param poll GitPollExecutor for ls-remote
 * @param changelog GitChangelogWriter for changelog.txt
 * @param credentialsApplier GitCredentialsApplier for temp file auth. Null means "this checkout
 *   authenticates with nothing": a checkout whose spec declares credentials and finds no applier
 *   refuses instead of running unauthenticated.
 * @param clock Clock for event timestamps
 * @param secretStore SecretStore for credential resolution (may be null in test paths)
 * @param secretPatternRegistry SecretPatternRegistry for scrubbing GitCheckoutFailed.reason
 * @param transcript the host's transcript destination. Null means "this composition has no
 *   destination": the checkout still runs, and its diagnostics still say what went wrong.
*/
open class GitCheckoutExecutor(
    private val poll: GitPollExecutor,
    private val changelog: GitChangelogWriter,
    private val credentialsApplier: GitCredentialsApplier?,
    private val clock: Clock = Clock.systemUTC(),
    private val secretStore: SecretStore? = null,
    private val secretPatternRegistry: SecretPatternRegistry = SecretPatternRegistry(),
    private val taskRuntime: DurableTaskRuntime? = null,
    private val gitControlRoot: Path = Files.createTempDirectory("git-tasks"),
    private val transcript: GitTranscript? = null,
) : AutoCloseable {

    /**
     * Scrubs [text] with the registry that decides what is a secret in THIS run.
     *
     * The host's transcript carries the run's registry, and that registry is the same one the
     * run's own diagnostics are scrubbed with. Preferring it over the constructor argument means
     * the transcript and the failure message agree on what a secret is; the argument remains for
     * the compositions that have a registry and no transcript.
     *
     * A FUNCTION and not a captured property: the registry is chosen where the scrub happens,
     * so the choice is part of the behaviour instead of a value frozen at construction time.
     */
    private fun scrubSecrets(text: String): String =
        (transcript?.secretPatterns ?: secretPatternRegistry).scrub(text)

    /**
     * The runtime used to execute git subcommands. Lazily built per instance
     * (avoids forcing callers/tests to wire it; deterministic default root).
*/
    private val runtimeInstance: DurableTaskRuntime by lazy {
        taskRuntime ?: ProcessDurableTaskRuntime(
            gitControlRoot,
            object : dev.rubentxu.pipeline.v2.domain.durable.Clock {
                override fun now(): Instant = Instant.now()
            },
        )
    }

    /**
     * Runs one git command and returns what it printed.
     *
     * The command's output goes to TWO places: the capture the caller reads for its own
     * diagnostics, and the host's transcript, which is where a reader finds out WHY a checkout
     * failed. The two are the same bytes seen twice — the capture is the whole text, the
     * transcript is the stream — and neither is derived from the other, so publishing cannot
     * shorten what the capture holds.
     *
     * The writer is created per command and flushed before this function returns, because the
     * redactor's lookahead only becomes decidable at end-of-stream.
     */
    private fun runGit(args: List<String>, env: Map<String, String>, timeoutMs: Long): dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.CapturedRun {
        guardProcessBuilderArgs(args)
        val secretEnv = env.mapValues { (_, v) -> SecretHandle.plain(v) }
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = args),
            runId = RunId("git-${UUID.randomUUID()}"),
            opId = "git-${UUID.randomUUID()}",
            timeoutMs = timeoutMs,
            env = secretEnv,
        )
        return runBlocking { runtimeInstance.runCapturedTo(request, transcript) }
    }

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
    open fun execute(req: GitCheckoutRequest): Result<GitCheckoutResult> {
        val spec = req.spec.scm as dev.rubentxu.pipeline.v2.domain.scm.GitScm
        val workspace = req.workspaceRoot.resolve(spec.relativeTargetDir)
        val gitDir = workspace.resolve(".git")

        // Guard argv
        guardProcessBuilderArgs(listOf("git", "ls-remote", spec.url, spec.branch))

        // Emit GitCheckoutStarted
        try {
            emitEvent(req, GitCheckoutStarted(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                credentialsRef = spec.credentialsId?.let { dev.rubentxu.pipeline.v2.domain.CredentialsRef(it) }
            ))
        } catch (refused: CheckoutRecordRefused) {
            // The run's record cannot hold what the checkout is about to do, so the
            // checkout does not do it. A checkout that cannot say what it did is a
            // checkout the run can neither replay nor explain.
            return Result.failure(refused)
        }

        val startMs = System.currentTimeMillis()

        // Apply credentials if needed; capture the credentials file path
        // so tests can verify it was written with real secrets.
        // NOTE: close is NOT called here — it is deferred to GitCheckoutExecutor.close
        // so the file persists for the duration of execute and is available
        // for verification by the caller AFTER execute returns.
        var credentialsFilePath: String? = null
        var gitConfigFilePath: String? = null
        val gitCreds: GitCredentials? = try {
            resolveGitCredentials(req, spec)
        } catch (e: IllegalStateException) {
            // The resolver
            // raises IllegalStateException when a declared credentialsRef
            // cannot be resolved against any reachable SecretStore. We
            // surface that as Result.failure so the step contract (typed
            // PluginStepException / FailureKind) propagates the diagnostic
            // instead of an uncaught throw that would crash the JVM path.
            return Result.failure(e)
        }
        // The environment git runs with. It is empty unless credentials were applied, and it is
        // built AFTER they were applied because applying them is what writes the files the
        // environment points at.
        val env: Map<String, String>
        if (gitCreds != null) {
            // The applier is the only thing that can put credentials in front of git. A checkout
            // that DECLARED credentials and has no applier refuses here: running git
            // unauthenticated would turn a declared credential into an anonymous attempt, and
            // the failure would then read as "the server said no" instead of "the run never
            // used the credential it declared".
            val applier = credentialsApplier ?: return Result.failure(
                IllegalStateException(
                    "scm-git.checkout: credentials were declared but no GitCredentialsApplier is " +
                        "available to apply them. Refusing to run git unauthenticated."
                )
            )
            val stringCred = gitCreds.string
            val userCred = gitCreds.user
            val passCred = gitCreds.pass
            val sshKeyCred = gitCreds.sshKey
            val sshPassphraseCred = gitCreds.sshPassphrase
            when {
                stringCred != null -> {
                    applier.apply(stringCred, spec.url)
                    credentialsFilePath = applier.credentialsFilePath()
                }
                userCred != null && passCred != null -> {
                    applier.apply(userCred, passCred, spec.url)
                    gitConfigFilePath = applier.gitConfigFilePath()
                }
                sshKeyCred != null -> {
                    applier.applySsh(sshKeyCred, sshPassphraseCred, spec.url)
                }
            }
            // buildEnv must be called AFTER apply/applySsh, which write the temp files.
            env = applier.buildEnv()
        } else {
            env = emptyMap()
        }

        return try {
            executeInternal(req, spec, workspace, gitDir, startMs, req.previousRemoteSha, env)
                .map { it.copy(credentialsFilePath = credentialsFilePath, gitConfigFilePath = gitConfigFilePath) }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            // GitCheckoutFailed.reason must be scrubbed before emission.
            // Scrub removes registered secret patterns (canaries + real credentials).
            val rawReason = e.message?.take(256) ?: "Unknown error"
            val scrubbedReason = scrubSecrets(rawReason)
            val failure = try {
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(clock),
                    url = spec.url,
                    branch = spec.branch,
                    reason = scrubbedReason,
                    exitCode = -1
                ))
                e
            } catch (refused: CheckoutRecordRefused) {
                // The failure could not be recorded either. The refused record is the fact
                // the run cannot prove, so it is the one that travels; what actually broke
                // rides along inside it.
                refused.addSuppressed(e)
                refused
            }
            Result.failure(failure)
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
        val remoteShaResult = poll.execute(url, branch, credentialsApplier, transcript)
        if (remoteShaResult.isFailure) {
            val durationMs = System.currentTimeMillis() - startMs
            val errorMsg = remoteShaResult.exceptionOrNull()?.message ?: "Unknown"
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                reason = scrubReason(errorMsg.take(256)),
                exitCode = 128
            ))
            return Result.failure(remoteShaResult.exceptionOrNull() ?: IllegalStateException(errorMsg))
        }
        val remoteSha = remoteShaResult.getOrNull()!!

        // Check for poll changed
        if (previousRemoteSha != null && previousRemoteSha != remoteSha) {
            emitEvent(req, GitPollChanged(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                previousSha = previousRemoteSha,
                newSha = remoteSha
            ))
        }

        // Step 1: Check if.git exists
        if (Files.exists(gitDir)) {
            // Existing checkout - check SHA equality
            val currentSha = revParse(workspace, "HEAD", env)
            if (currentSha != null && currentSha == remoteSha) {
                // SHA equal - no-op
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutCompleted(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = 0L,
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
            val fetchResult = gitFetch(workspace, env)
            if (fetchResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(clock),
                    url = url,
                    branch = branch,
                    reason = scrubReason("Fetch failed: ${fetchResult.exceptionOrNull()?.message}".take(256)),
                    exitCode = 128
                ))
                return Result.failure(fetchResult.exceptionOrNull() ?: IllegalStateException("Fetch failed"))
            }

            val resetResult = gitResetHard(workspace, remoteSha, env)
            if (resetResult.isFailure) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(req, GitCheckoutFailed(
                    eventId = newEventId(),
                    runId = req.runId,
                    sequence = 0L,
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
                changelog.append(workspaceRoot, spec.relativeTargetDir, currentSha, "HEAD", transcript)
            }

            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(req, GitCheckoutCompleted(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                sha = remoteSha,
                changelogPath = if (spec.changelog) workspaceRoot.resolve("changelog.txt").toString() else "",
                durationMs = durationMs
            ))
            return Result.success(GitCheckoutResult(remoteSha, durationMs, "fetch+reset"))
        }

        // No.git - clone
        Files.createDirectories(workspace)
        val cloneResult = gitClone(url, branch, workspace, env)
        if (cloneResult.isFailure) {
            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = url,
                branch = branch,
                reason = scrubReason("Clone failed: ${cloneResult.exceptionOrNull()?.message}".take(256)),
                exitCode = 128
            ))
            return Result.failure(cloneResult.exceptionOrNull() ?: IllegalStateException("Clone failed"))
        }

        // Get cloned SHA
        val sha = revParse(workspace, "HEAD", env) ?: remoteSha

        // Append changelog
        if (spec.changelog) {
            changelog.append(workspaceRoot, spec.relativeTargetDir, null, "HEAD", transcript)
        }

        val durationMs = System.currentTimeMillis() - startMs
        emitEvent(req, GitCheckoutCompleted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = 0L,
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
        // However, apply needs secretStore to resolve the actual secret bytes.
        val applierCreds = credentialsApplier?.credentials
        if (applierCreds != null &&
            (applierCreds.string != null || applierCreds.user != null || applierCreds.pass != null ||
                applierCreds.sshKey != null)
        ) {
            // Ensure secretStore is available — apply will need it to resolve secret bytes
            if (secretStore == null && req.secretStore == null) {
                throw IllegalStateException(
                    "SecretStore is required when credentials are configured on GitCredentialsApplier, " +
                    "but none was provided. Pass SecretStore to GitCheckoutExecutor or include it in GitCheckoutRequest."
                )
            }
            return applierCreds
        }
        // Production path: resolve from SecretStore using credentialsId
        // Kind is DECLARED, never inferred from byte content.
        // Typed Credential hierarchy is the kind system.
        // If the user declared a
        // credentialsId in the script, the executor MUST NOT silently proceed
        // with anonymous git when no SecretStore is reachable. A declared
        // credential that cannot be resolved is a USER-visible misconfiguration
        // and must surface as a typed failure BEFORE any subprocess is
        // launched — silently dropping the declared credentialsRef would let a
        // user think they authenticated a private repo when in fact the
        // checkout fell back to anonymous (which then either succeeds with a
        // public-mirror artifact or fails with a confusing remote auth error).
        val credentialsId = spec.credentialsId
        // Compute the effective store ONCE so the fail-closed branch and the
        // resolution branch share the same view of the seam.
        val effectiveStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? =
            secretStore ?: req.secretStore
        if (credentialsId != null && effectiveStore == null) {
            throw IllegalStateException(
                "scm-git.checkout: credentialsRef declared as '${credentialsId.value}' but no " +
                    "SecretStore was provided to resolve it. Either supply a SecretStore " +
                    "(LocalSecretStore via PIPELINE_CREDENTIALS_STORE, or wire one into " +
                    "GitCheckoutExecutor / GitCheckoutRequest) or remove credentialsRef from the " +
                    "pipeline script. The credentials intent MUST NOT be silently dropped.",
            )
        }
        if (credentialsId == null) return null
        return try {
            val credential = effectiveStore!!.get(credentialsId)
            // Pattern-match on DECLARED kind
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
                    // MismatchedSecretException verbatim wording
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

    private fun revParse(workspace: Path, ref: String, env: Map<String, String>): String? {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "rev-parse", ref)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (captured.succeeded) captured.stdout.trim() else null
        } catch (refused: GitTranscriptRefused) {
            // A refusal is not "the command said nothing": the run lost the record of what git
            // printed, and a null here would read like an absent SHA — a fact the checkout
            // would then act on. The refusal travels instead.
            throw refused
        } catch (e: Exception) {
            null
        }
    }

    private fun gitFetch(workspace: Path, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "fetch")
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (captured.succeeded) Result.success(Unit)
            else Result.failure(IllegalStateException("git fetch failed${captured.stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitResetHard(workspace: Path, sha: String, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "reset", "--hard", sha)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (captured.succeeded) Result.success(Unit)
            else Result.failure(IllegalStateException("git reset --hard failed${captured.stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitClone(url: String, branch: String, workspace: Path, env: Map<String, String>): Result<Unit> {
        return try {
            val args = listOf("git", "clone", "--branch", branch, url, workspace.toString())
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 2 * 1000)
            if (captured.succeeded) Result.success(Unit)
            else Result.failure(IllegalStateException("git clone failed${captured.stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Emits a SEMANTIC event about what the checkout did.
     * ## Why git's own stdout does not come through here
     * This used to have a sibling, `emitEcho`, which pushed `git`'s stdout and stderr into
     * `EchoOutputCaptured`. That was a second authority over process bytes: the event log
     * carried process output, and the Output Plane — where process output belongs — was
     * unreachable from this module. So `scm.git` kept the transcript alive in the event log
     * as a side effect of having an `EventSink`.
     * Removing it loses nothing a caller could act on:
     *  - **Failures** already carry their stderr, in the typed failure itself
     *    (`"git clone failed${captured.stderr.prependIndent()}"`).
     *  - **Progress** lines like `Cloning into '…'` are process logging, not a semantic fact, and
     *    their rightful home is the transcript — not the event plane.
     * What this DOES leave open, stated plainly rather than papered over: the git transcript is
     * published to the Output Plane through the injected [GitTranscript] port, so a reader CAN
     * retrieve it with a cursor — but what lives there is the PROCESS OUTPUT, not the semantic
     * fact. The events remain the only place a reader finds what the checkout DID. Two
     * authorities over two different questions, with no overlap between them.
     * ## A record that cannot be written is not a warning
     * The sink is where the run's memory of the checkout lives, so a write it refuses takes the
     * fact away. This used to print a warning on stderr and let the checkout carry on: the
     * checkout went on to do work, reported success, and the run's record said nothing about any
     * of it. The refusal now travels as [CheckoutRecordRefused] and the checkout ends in failure,
     * which is the only honest outcome when the fact is gone.
*/
    private fun emitEvent(req: GitCheckoutRequest, event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
        try {
            req.eventSink.append(event)
        } catch (e: Exception) {
            throw CheckoutRecordRefused(event.kind, e)
        }
    }

    /**
     * Scrubs a reason string using the secret pattern registry.
     * GitCheckoutFailed.reason must be scrubbed before emission.
*/
    private fun scrubReason(reason: String): String {
        return scrubSecrets(reason)
    }

    private fun newEventId(): String = java.util.UUID.randomUUID().toString()

    override fun close() {
        // Wipe credentials files AFTER execute completes.
        // This ensures the credentials file persists through execute and
        // is available for verification by callers after execute returns.
        // A composition with no applier has no file to wipe.
        credentialsApplier?.close()
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
    // `stepIndex` is a STEP ORDINAL, not an event sequence. It was
    // being passed as `DomainEvent.sequence` for all 11 checkout events, which
    // bypassed the store's sequence authority: every checkout event claimed the
    // same number (the step ordinal), so repeated checkouts of a step, or a
    // checkout alongside any other event of that run, collided on
    // UNIQUE(run_id, sequence). All 11 sites now pass `0L`, which
    // `appendAssigned` treats as "assign me the durable sequence". The ordinal
    // still belongs in the step-scoped payload, not the run-scoped sequence.
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
     * Path.git-credentials file (string channel).
     * Non-null when string credentials were applied.
*/
    val credentialsFilePath: String? = null,
    /**
     * Path.gitconfig file (usernamePassword channel).
     * Non-null when usernamePassword credentials were applied.
*/
    val gitConfigFilePath: String? = null,
)
