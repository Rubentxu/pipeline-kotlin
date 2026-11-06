package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.MismatchedSecretException
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitRemotePolicy
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
 * 2. git ls-remote <url> <ref> → remote SHA (with credentials), ONLY when the run asked for it
 * 3. git status --porcelain --untracked-files=no → the state of the tracked content
 * 4. The decision (GitCheckoutDecision): clone / leave alone / move / refuse
 * 5. SHA different? → git fetch <url> <ref> + git checkout -B <branch> <sha>
 * 6. No.git? → git clone --branch <branch> <url> <rel>
 * 7. Append changelog via GitChangelogWriter.append(prevSha..HEAD)
 * 8. Emit lifecycle events
 * 9. Classify failures per
 * The published SHA is always the commit the WORKING TREE holds, read back after the
 * materialisation: the commit the remote announced is what the remote had, and the state
 * of the checkout is what the tree has.
 * `poll=false` does not probe the remote at all, and over an existing checkout it does
 * not download from it either: it publishes the local state and says that it is local.
 * Idempotency <2s on SHA-equal no-op.
 * No JGit imports.
 * Argv guard fail-closed on extraHeader/Authorization.
 * Events route through injected EventSink.
 *
 * **THE POLICY: the checkout never discards local changes.**
 *
 * A checkout over a tree that carries changes the run did not make does two things,
 * and both of them are part of the contract this class offers:
 *
 * 1. It does NOT move that tree. It refuses, with a typed reason
 *    ([GitCheckoutRefused]), before the fetch and before the checkout: nothing is
 *    downloaded on its behalf and nothing is overwritten. A checkout that does not
 *    force carries those changes over to the new commit instead — git moves the
 *    branch and leaves the modified file as it was (measured with git 2.56.0) — and
 *    the tree it leaves behind is then neither the old commit nor the new one: a
 *    commit published over a tree that does not hold it.
 * 2. It PUBLISHES the state of the tracked content ([GitLocalState]) next to the
 *    outcome. The outcome says what the checkout did; the state says what it found.
 *    A tree that already holds the declared commit and still carries changes is not
 *    a clean checkout, and the record says so instead of letting the reader assume
 *    it. A tree whose state could not be read is published as UNKNOWN and is not
 *    moved: the checkout does not guess, because the guess it would have to make is
 *    "there is nothing to lose".
 *
 * The command that moves a tree cannot overwrite a local change either: it is
 * `git checkout -B <branch> <sha>` and not `git checkout --force -B …`, so git
 * itself refuses when the destination would overwrite local work. The policy is
 * therefore in the COMMAND as well as in the decision, and neither of the two can
 * be the only place that knows about it.
 *
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
        // The destination is AUTHORISED, not resolved here. The caller resolved it once,
        // against the workspace, before any credential, directory or process existed.
        // Resolving it again here would be a second rule about the same path — and the
        // lax one: `Path.resolve` normalises nothing and confines nothing.
        val workspace = req.authorizedTarget.path
        val gitDir = workspace.resolve(".git")

        // The remote is refused BEFORE the run is told a checkout started, before any
        // credential is applied, and before any process is spawned. git reads an argument
        // that starts with `-` as an OPTION, so a remote shaped like `--upload-pack=…` is
        // not a repository but an instruction to run a program of the author's choosing —
        // and with a `file://` remote that program runs on THIS machine. The refusal has
        // to arrive before the program could.
        //
        // It is a typed refusal, not an exception: the caller classifies the failure by
        // its type, and a remote the author wrote is the author's mistake (USER), not a
        // broken infrastructure.
        GitRemotePolicy.refusalFor(spec.url, spec.branch)?.let { refusal ->
            return Result.failure(GitRemotePolicy.GitRemoteRefused(refusal))
        }

        // The remote and the branch the run declared, in the ONE form every command
        // of this checkout will ask about them.
        val selection = GitRemoteSelection(spec.url, spec.branch)

        // Guard argv: the credential rule, over the arguments the poll is GOING TO RUN.
        //
        // The list is the selection's own (`selection.lsRemoteArgv()`), not a copy of
        // it: a guard over a hand-built argv checks a command that was never examined,
        // and the two lists are free to drift apart the day somebody edits one of them.
        //
        // A refusal here is a FAILURE of the checkout, not a crash of the run: the
        // remote carries a credential in a place git would publish it to the whole
        // machine, and the checkout must say so and stop. It travels as a typed
        // failure so the caller classifies it by type, like every other refusal.
        try {
            guardProcessBuilderArgs(selection.lsRemoteArgv())
        } catch (refused: IllegalArgumentException) {
            return Result.failure(refused)
        }

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
        var credentialHelperPath: String? = null
        var gitConfigFilePath: String? = null
        var sshKeyPath: String? = null
        val credential: ResolvedCredential? = try {
            resolveCredential(req, spec)
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
        if (credential != null) {
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
            when (credential) {
                is ResolvedCredential.Token -> {
                    applier.apply(credential.bytes, spec.url)
                    credentialHelperPath = applier.credentialHelperPath()
                }
                is ResolvedCredential.Basic -> {
                    applier.apply(credential.username, credential.password, spec.url)
                    gitConfigFilePath = applier.gitConfigFilePath()
                }
                is ResolvedCredential.Ssh -> {
                    applier.applySsh(credential.privateKey, credential.passphrase, spec.url)
                    sshKeyPath = applier.sshKeyPath()
                }
            }
            // buildEnv must be called AFTER apply/applySsh, which write the temp files.
            env = applier.buildEnv()
        } else {
            env = emptyMap()
        }

        return try {
            executeInternal(req, spec, workspace, gitDir, startMs, req.previousRemoteSha, env)
                .map {
                    it.copy(
                        credentialHelperPath = credentialHelperPath,
                        gitConfigFilePath = gitConfigFilePath,
                        sshKeyPath = sshKeyPath,
                    )
                }
        } catch (e: Exception) {
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
        val selection = GitRemoteSelection(spec.url, spec.branch)

        // THE PROBE, and only when the run asked for it.
        val probed = probeRemote(req, spec, selection, previousRemoteSha)
        val remoteSha = probed.getOrElse { return Result.failure(it) }

        // Two facts about the tree that is already there: the commit it holds, and
        // the state of its tracked content. The decision needs both: a tree that
        // holds the declared commit is left alone, and a tree that carries work
        // this run did not make is NOT moved.
        val treeExists = Files.exists(gitDir)
        val currentSha = if (treeExists) revParse(workspace, "HEAD", env) else null
        val localState = if (treeExists) readLocalState(workspace, env) else GitLocalState.CLEAN

        val decision = GitCheckoutDecision.decide(
            treeExists = treeExists,
            headSha = currentSha,
            declaredSha = remoteSha,
            localState = localState,
        )
        return actOnDecision(req, spec, workspace, currentSha, startMs, env, decision)
    }

    /**
     * Carries out the decision the checkout made about the tree it found.
     *
     * The DECISION is the only place that decides; this function only carries it
     * out. Every branch publishes what it did AND the state it found the tree in,
     * because those are two facts and a reader needs both to tell an idempotency
     * of the commit from an idempotency of the tree.
     */
    private fun actOnDecision(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        workspace: Path,
        currentSha: String?,
        startMs: Long,
        env: Map<String, String>,
        decision: GitCheckoutDecision,
    ): Result<GitCheckoutResult> = when (decision) {
        is GitCheckoutDecision.Proceed -> when (decision.outcome) {
            GitCheckoutOutcome.CLONED -> cloneTree(req, spec, workspace, startMs, env)
            GitCheckoutOutcome.UPDATED ->
                updateTree(req, spec, workspace, startMs, env, decision, currentSha)
            GitCheckoutOutcome.ALREADY_CURRENT, GitCheckoutOutcome.LOCAL_ONLY -> {
                val sha = currentSha ?: return Result.failure(
                    IllegalStateException(
                        "scm-git.checkout: the checkout decided to publish the tree it found, " +
                            "but the tree holds no commit to publish"
                    )
                )
                publishExisting(req, spec, sha, startMs, decision)
            }
        }
        is GitCheckoutDecision.Refuse -> refuseTree(req, spec, decision)
    }

    /**
     * Publishes the tree the checkout found, without moving it.
     *
     * The commit it publishes is the one the tree holds, and the state it publishes
     * is the state the tree is in: a tree that already holds the declared commit
     * and still carries changes is NOT a clean checkout, and the record says so
     * instead of letting the reader assume it.
     */
    private fun publishExisting(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        sha: String,
        startMs: Long,
        decision: GitCheckoutDecision.Proceed,
    ): Result<GitCheckoutResult> {
        val durationMs = System.currentTimeMillis() - startMs
        emitEvent(req, GitCheckoutCompleted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = 0L,
            occurredAt = Instant.now(clock),
            url = spec.url,
            branch = spec.branch,
            sha = sha,
            changelogPath = if (spec.changelog) req.workspaceRoot.resolve("changelog.txt").toString() else "",
            durationMs = durationMs
        ))
        return Result.success(
            GitCheckoutResult(
                sha = sha,
                durationMs = durationMs,
                outcome = decision.outcome,
                remotePolled = spec.poll,
                localState = decision.localState,
            )
        )
    }

    /**
     * Moves the tree to the commit the run declared.
     *
     * The tree reaches this function only when its tracked content IS its commit:
     * a tree that carries work this run did not make is refused by the decision
     * before it gets here. The command that moves it cannot overwrite a local
     * change either, so a state read wrongly cannot turn into a destroyed tree.
     */
    private fun updateTree(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        workspace: Path,
        startMs: Long,
        env: Map<String, String>,
        decision: GitCheckoutDecision.Proceed,
        currentSha: String?,
    ): Result<GitCheckoutResult> {
        val declaredSha = decision.declaredSha ?: return Result.failure(
            IllegalStateException(
                "scm-git.checkout: the checkout decided to move the working tree, but the run " +
                    "declared no commit to move it to"
            )
        )
        val selection = GitRemoteSelection(spec.url, spec.branch)

        // The remote declared a commit the tree does not hold: download it FROM
        // THE DECLARED REMOTE and materialise it ON THE DECLARED BRANCH.
        val fetchResult = gitFetch(workspace, selection, env)
        if (fetchResult.isFailure) {
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason("Fetch failed: ${fetchResult.exceptionOrNull()?.message}".take(256)),
                exitCode = 128
            ))
            return Result.failure(fetchResult.exceptionOrNull() ?: IllegalStateException("Fetch failed"))
        }

        val materialiseResult = gitMaterialise(workspace, selection, declaredSha, env)
        if (materialiseResult.isFailure) {
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason(
                    "Materialisation failed: ${materialiseResult.exceptionOrNull()?.message}".take(256)
                ),
                exitCode = 128
            ))
            return Result.failure(
                materialiseResult.exceptionOrNull() ?: IllegalStateException("Materialisation failed")
            )
        }

        // The published SHA is the commit the TREE holds, read back after the
        // materialisation. The probed SHA is what the remote HAD when it was
        // asked; the state of the checkout is what the tree has now. A checkout
        // that published the former without reading the latter would announce a
        // commit it did not materialise.
        val materialisedSha = revParse(workspace, "HEAD", env)
        if (materialisedSha == null) {
            val message = "scm-git.checkout: the commit the checkout materialised could not " +
                "be read back from the working tree, so the checkout cannot say what state " +
                "it left"
            val failure = IllegalStateException(message)
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason(message.take(256)),
                exitCode = 128
            ))
            return Result.failure(failure)
        }
        if (materialisedSha != declaredSha) {
            val message = "scm-git.checkout: the working tree holds $materialisedSha after the " +
                "checkout materialised the commit the remote declared ($declaredSha): the " +
                "checkout did not obtain the objects it announced"
            val failure = IllegalStateException(message)
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason(message.take(256)),
                exitCode = 128
            ))
            return Result.failure(failure)
        }

        // Append changelog
        if (spec.changelog) {
            // The changelog reads the SAME authorised destination the checkout
            // wrote to, and it no longer resolves a path of its own: a second
            // resolver is a second rule about the same directory.
            changelog.append(workspace, req.workspaceRoot.resolve("changelog.txt"), currentSha, "HEAD", transcript)
        }

        val durationMs = System.currentTimeMillis() - startMs
        emitEvent(req, GitCheckoutCompleted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = 0L,
            occurredAt = Instant.now(clock),
            url = spec.url,
            branch = spec.branch,
            sha = materialisedSha,
            changelogPath = if (spec.changelog) req.workspaceRoot.resolve("changelog.txt").toString() else "",
            durationMs = durationMs
        ))
        return Result.success(
            GitCheckoutResult(
                sha = materialisedSha,
                durationMs = durationMs,
                outcome = GitCheckoutOutcome.UPDATED,
                remotePolled = spec.poll,
                localState = decision.localState,
            )
        )
    }

    /**
     * Refuses to act on the tree the checkout found, and says why.
     *
     * The refusal travels as a TYPED failure: the caller reads the reason, not a
     * sentence. Nothing was destroyed and nothing was moved — the refusal arrives
     * before the fetch and before the checkout, so the tree the run found is still
     * the tree the run left.
     */
    private fun refuseTree(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        decision: GitCheckoutDecision.Refuse,
    ): Result<GitCheckoutResult> {
        val failure = GitCheckoutRefused(
            reason = decision.reason,
            declaredSha = decision.declaredSha,
            localState = decision.localState,
        )
        val message = failure.message
            ?: "scm-git.checkout: the checkout refused to move the working tree"
        emitEvent(req, GitCheckoutFailed(
            eventId = newEventId(),
            runId = req.runId,
            sequence = 0L,
            occurredAt = Instant.now(clock),
            url = spec.url,
            branch = spec.branch,
            reason = scrubReason(message.take(256)),
            exitCode = 128
        ))
        return Result.failure(failure)
    }

    /**
     * Materialises a checkout that is not there yet.
     *
     * The tree a clone leaves behind is clean by construction: nothing was there to
     * carry over.
     */
    private fun cloneTree(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        workspace: Path,
        startMs: Long,
        env: Map<String, String>,
    ): Result<GitCheckoutResult> {
        val selection = GitRemoteSelection(spec.url, spec.branch)

        // No.git - clone
        Files.createDirectories(workspace)
        val cloneResult = gitClone(selection, workspace, env)
        if (cloneResult.isFailure) {
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason("Clone failed: ${cloneResult.exceptionOrNull()?.message}".take(256)),
                exitCode = 128
            ))
            return Result.failure(cloneResult.exceptionOrNull() ?: IllegalStateException("Clone failed"))
        }

        // The SHA of the clone is the one the TREE holds, read back. The probed
        // SHA is NOT a fallback: it is the commit the remote had when it was asked,
        // and publishing it when the tree cannot be read would publish a commit
        // nobody materialised.
        val sha = revParse(workspace, "HEAD", env)
        if (sha == null) {
            val message = "scm-git.checkout: the commit the clone materialised could not be read " +
                "back from the working tree, so the checkout cannot say what state it left"
            val failure = IllegalStateException(message)
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason(message.take(256)),
                exitCode = 128
            ))
            return Result.failure(failure)
        }

        // Append changelog
        if (spec.changelog) {
            changelog.append(workspace, req.workspaceRoot.resolve("changelog.txt"), null, "HEAD", transcript)
        }

        val durationMs = System.currentTimeMillis() - startMs
        emitEvent(req, GitCheckoutCompleted(
            eventId = newEventId(),
            runId = req.runId,
            sequence = 0L,
            occurredAt = Instant.now(clock),
            url = spec.url,
            branch = spec.branch,
            sha = sha,
            changelogPath = if (spec.changelog) req.workspaceRoot.resolve("changelog.txt").toString() else "",
            durationMs = durationMs
        ))
        return Result.success(
            GitCheckoutResult(
                sha = sha,
                durationMs = durationMs,
                outcome = GitCheckoutOutcome.CLONED,
                remotePolled = spec.poll,
                localState = GitLocalState.CLEAN,
            )
        )
    }

    /**
     * The state of the tracked content of the tree the checkout found.
     *
     * The tree is asked, and the answer is a fact the decision needs: a tree that
     * carries changes this run did not make is not moved. A status the checkout
     * could NOT read is [GitLocalState.UNKNOWN] and not a clean tree: the checkout
     * does not read an unreadable tree as a clean one, and a tree it could not
     * inspect is not moved either.
     */
    private fun readLocalState(workspace: Path, env: Map<String, String>): GitLocalState {
        return try {
            val args = GitLocalState.statusArgv(workspace)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (!captured.succeeded) {
                GitLocalState.UNKNOWN
            } else {
                GitLocalState.stateOf(captured.stdout)
            }
        } catch (refused: GitTranscriptRefused) {
            // A refusal is not "the tree is clean": the run lost the record of what
            // git printed, and a clean tree here would be a fact nobody established.
            // The refusal travels instead.
            throw refused
        } catch (e: Exception) {
            GitLocalState.UNKNOWN
        }
    }

    /**
     * The commit the remote declares for the declared branch, or null when the
     * run said not to ask.
     *
     * **Why the flag is read HERE.** `poll=false` means "do not ask the remote
     * what the head of the declared branch is". The flag travelled from the step
     * input all the way here and was never read: the checkout probed the remote
     * anyway, and published the remote's answer as if it were the state of the
     * checkout. It is read before any process is spawned, so a checkout that was
     * told not to ask does not ask.
     *
     * A NULL here means "the remote was not consulted", and it is a different
     * fact from "the remote was consulted and declared nothing": the first is a
     * checkout that honoured the flag, the second is a checkout that failed.
     * The two never share a value.
     */
    private fun probeRemote(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
        selection: GitRemoteSelection,
        previousRemoteSha: String?,
    ): Result<String?> {
        if (!spec.poll) {
            return Result.success(null)
        }
        val probe = poll.execute(selection, credentialsApplier, transcript)
        val remoteSha = probe.getOrNull()
        if (remoteSha == null) {
            // The remote did not answer with a commit. The checkout says so and
            // stops: a checkout that carried on would publish a state it never
            // established.
            val error = probe.exceptionOrNull()
                ?: IllegalStateException("git ls-remote did not answer the question it was asked")
            val reason = error.message ?: "git ls-remote did not answer the question it was asked"
            emitEvent(req, GitCheckoutFailed(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                reason = scrubReason(reason.take(256)),
                exitCode = 128
            ))
            return Result.failure(error)
        }

        // Check for poll changed
        if (previousRemoteSha != null && previousRemoteSha != remoteSha) {
            emitEvent(req, GitPollChanged(
                eventId = newEventId(),
                runId = req.runId,
                sequence = 0L,
                occurredAt = Instant.now(clock),
                url = spec.url,
                branch = spec.branch,
                previousSha = previousRemoteSha,
                newSha = remoteSha
            ))
        }
        return Result.success(remoteSha)
    }

    /**
     * The credential the checkout declared, already resolved into what its
     * transport needs.
     *
     * The KIND of the credential is the transport: a secret text is a token for
     * a remote over HTTPS, a user name and a password are a Basic header, and
     * a private key is an SSH identity. The kind is DECLARED by the credential
     * itself and never inferred from the bytes.
     *
     * The resolution happens HERE, and not in the applier, because a
     * credential that is more than one secret cannot be projected into a
     * single secret handle by any store: a user name and a password live in
     * ONE credential, and a handle carries ONE secret. A seam that took a
     * handle would therefore be a seam that no store can honour, and the
     * checkout would fail before it wrote a single byte.
     *
     * A declared credential that cannot be resolved is a USER-visible
     * misconfiguration and surfaces as a typed failure BEFORE any subprocess
     * is launched: silently dropping the declared credential would let a user
     * think they authenticated a private repo when in fact the checkout fell
     * back to anonymous.
     */
    private fun resolveCredential(
        req: GitCheckoutRequest,
        spec: dev.rubentxu.pipeline.v2.domain.scm.GitScm,
    ): ResolvedCredential? {
        val credentialsId = spec.credentialsId
        // The effective store is computed ONCE, so the fail-closed branch and
        // the resolution branch share the same view of the seam.
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
        val store = effectiveStore!!
        val credential = try {
            store.get(credentialsId)
        } catch (e: dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException) {
            throw IllegalArgumentException("Could not find credentials entry with ID '${credentialsId.value}'", e)
        }
        return when (credential) {
            // String channel: API token via the credential helper.
            is SecretText ->
                ResolvedCredential.Token(credential.bytes.copyOf())
            // UsernamePassword channel: Basic auth over the per-host configuration.
            is UsernamePassword ->
                ResolvedCredential.Basic(credential.username, credential.password.copyOf())
            // SSH channel: the private key, and the passphrase it names.
            is SshPrivateKey ->
                ResolvedCredential.Ssh(
                    credential.privateKey.copyOf(),
                    resolvePassphrase(store, credential.passphraseRef),
                )
            else -> {
                // Unsupported credential kind for git checkout.
                // MismatchedSecretException verbatim wording.
                throw dev.rubentxu.pipeline.v2.domain.MismatchedSecretException(
                    credentialId = credentialsId,
                    expectedKind = "SecretText|UsernamePassword|SshPrivateKey",
                    actualKind = credential::class.simpleName ?: "Unknown"
                )
            }
        }
    }

    /**
     * The passphrase a private key names, or null when the key has none.
     *
     * The passphrase is a LINK to another credential, and that credential is a
     * single secret: a link that named anything else is a credential whose
     * shape contradicts the link, and it is refused rather than read as
     * whatever it happens to be.
     */
    private fun resolvePassphrase(
        store: dev.rubentxu.pipeline.v2.credentials.api.SecretStore,
        passphraseRef: dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef?,
    ): ByteArray? {
        if (passphraseRef == null) return null
        val referenced = try {
            store.get(passphraseRef.credentialsId)
        } catch (e: dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException) {
            throw IllegalArgumentException(
                "Could not find the passphrase credential '${passphraseRef.credentialsId.value}'",
                e,
            )
        }
        if (referenced !is dev.rubentxu.pipeline.v2.domain.credentials.SecretText) {
            throw dev.rubentxu.pipeline.v2.domain.MismatchedSecretException(
                credentialId = passphraseRef.credentialsId,
                expectedKind = "SecretText",
                actualKind = referenced::class.simpleName ?: "Unknown",
            )
        }
        return referenced.bytes.copyOf()
    }

    private fun revParse(workspace: Path, ref: String, env: Map<String, String>): String? {
        return try {
            val args = listOf("git", "-C", workspace.toString(), "rev-parse", ref)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            // An EMPTY answer is not a commit id: it is the absence of one. Publishing
            // it as the state of the checkout would announce a commit that does not
            // exist, and a reader of that state would take it for a fact about a tree.
            if (captured.succeeded) captured.stdout.trim().ifEmpty { null } else null
        } catch (refused: GitTranscriptRefused) {
            // A refusal is not "the command said nothing": the run lost the record of what git
            // printed, and a null here would read like an absent SHA — a fact the checkout
            // would then act on. The refusal travels instead.
            throw refused
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the declared branch FROM THE DECLARED REMOTE.
     *
     * The remote is named in the argv: a `git fetch` without a remote asks the
     * repository for its own `origin`, and the checkout would then download from
     * a remote the run never declared — and reset to a commit that remote may
     * not have.
     */
    private fun gitFetch(
        workspace: Path,
        selection: GitRemoteSelection,
        env: Map<String, String>,
    ): Result<Unit> {
        return try {
            val args = selection.fetchArgv(workspace)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (captured.succeeded) Result.success(Unit)
            else Result.failure(IllegalStateException("git fetch failed${captured.stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Puts the DECLARED branch at [sha].
     *
     * The branch is named in the argv because `git reset --hard <sha>` moves the
     * branch the checkout HAPPENS to be on: a workspace on `feature` would have
     * `feature` rewritten to the remote's commit while the declared branch never
     * materialised (measured with git 2.56.0).
     */
    private fun gitMaterialise(
        workspace: Path,
        selection: GitRemoteSelection,
        sha: String,
        env: Map<String, String>,
    ): Result<Unit> {
        return try {
            val args = selection.materialiseArgv(workspace, sha)
            val captured = runGit(args, env, GIT_TIMEOUT_SECONDS * 1000)
            if (captured.succeeded) Result.success(Unit)
            else Result.failure(IllegalStateException("git checkout failed${captured.stderr.prependIndent()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun gitClone(
        selection: GitRemoteSelection,
        workspace: Path,
        env: Map<String, String>,
    ): Result<Unit> {
        return try {
            // `--` before the positionals: git stops reading options there, so a remote
            // or a branch that looks like an option is a VALUE and never an instruction.
            // The remote is refused earlier (see `execute`), so this is the second line of
            // the same defence, not the only one.
            val args = selection.cloneArgv(workspace)
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
     * The event plane carries SEMANTIC facts, and process bytes are not one of them. Pushing
     * `git`'s stdout and stderr into `EchoOutputCaptured` would be a second authority over
     * process bytes: the event log would carry process output, and the Output Plane — where
     * process output belongs — would be unreachable from this module. So `scm.git` would keep
     * the transcript alive in the event log as a side effect of having an `EventSink`.
     * A caller loses nothing it could act on:
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
     * fact away. Printing a warning on stderr and letting the checkout carry on would let the
     * checkout go on to do work, report success, and leave the run's record saying nothing about
     * any of it. The refusal travels as [CheckoutRecordRefused] and the checkout ends in failure,
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
        // The credentials files are wiped AFTER execute completes, so they persist through
        // the checkout and are available for verification by callers after execute returns.
        // A composition with no applier has no file to wipe.
        //
        // Every temporary root this checkout opened goes away with it: the applier's
        // directory (which holds the SSH key and the answer file), the executor's own
        // control root, and the control roots of the poll executor and of the changelog
        // writer. Leaving them behind put a private key and a token in the system
        // temporary area for as long as the machine kept them, and nobody was looking
        // after them.
        credentialsApplier?.close()
        poll.close()
        changelog.close()
        GitTempRoot.deleteRecursively(gitControlRoot)
    }
}

/**
 * Request for git checkout.
*/
data class GitCheckoutRequest(
    val spec: CheckoutSpec,
    val runId: String,
    val workspaceRoot: Path,
    /**
     * The authorised destination of the checkout: the only directory this request
     * may write to. It is resolved by the caller against the workspace, before
     * anything is created, so a path that escapes is refused before it can act.
     */
    val authorizedTarget: AuthorizedCheckoutTarget,
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
    /**
     * What the checkout did with the workspace.
     */
    val outcome: GitCheckoutOutcome,
    /**
     * Whether the checkout asked the remote for the head of the declared branch.
     *
     * FALSE means the remote was not consulted, and the published [sha] is the
     * commit the LOCAL working tree held: a consumer that reads this field knows
     * whether the state it was given is a remote fact or a local one.
     */
    val remotePolled: Boolean,
    /**
     * The state of the tracked content of the tree the checkout leaves behind.
     *
     * It travels next to the [outcome] because the two are different facts: the
     * outcome says WHAT the checkout did and this says WHAT it found. An
     * [GitCheckoutOutcome.ALREADY_CURRENT] over a [GitLocalState.MODIFIED] tree is
     * a checkout that found the declared commit AND changes that are not the run's
     * — not a clean checkout, and a consumer that only reads the outcome would say
     * it was.
     */
    val localState: GitLocalState,
    /**
     * The credential helper the checkout wrote (string channel).
     *
     * Non-null when a token credential was applied to a remote that can
     * authenticate with one, and null otherwise: a remote that cannot is given
     * no helper and no token, and the record says that no credential was applied
     * rather than pointing at a file that was never written.
*/
    val credentialHelperPath: String? = null,
    /**
     * The configuration that carries the Basic header (usernamePassword channel).
     *
     * Non-null when a Basic header was written for the remote the checkout was
     * given, and null otherwise: a remote that does not authenticate with a
     * password gets a configuration that says nothing, and the record says that
     * no credential was applied rather than pointing at a file that holds no
     * header.
*/
    val gitConfigFilePath: String? = null,
    /**
     * The SSH key the checkout wrote (ssh channel).
     *
     * Non-null when an SSH key was applied, and null otherwise. The key is what
     * the SSH channel applies, so this is what says the channel ran.
*/
    val sshKeyPath: String? = null,
)

/**
 * The credential a checkout declared, resolved into what its transport needs.
 *
 * The three cases are the three transports, and the kind of the credential in
 * the store is what says which one applies. The bytes are COPIES: the store
 * keeps its own, and a checkout that wiped its copy must not have wiped the
 * store's.
 */
private sealed interface ResolvedCredential {
    /** A token for a remote over HTTPS. */
    data class Token(val bytes: ByteArray) : ResolvedCredential

    /** A user name and a password for a remote over HTTPS. */
    data class Basic(val username: String, val password: ByteArray) : ResolvedCredential

    /** A private key, and the passphrase of that key when it has one. */
    data class Ssh(val privateKey: ByteArray, val passphrase: ByteArray?) : ResolvedCredential
}
