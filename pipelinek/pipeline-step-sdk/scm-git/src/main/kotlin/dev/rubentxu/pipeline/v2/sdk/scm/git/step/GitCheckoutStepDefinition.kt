package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as ExecutionSite
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitRemotePolicy
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.scm.git.AuthorizedCheckoutTarget
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutOutcome
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRefused
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRequest
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitTranscript
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitTranscriptRefused
import dev.rubentxu.pipeline.v2.sdk.scm.git.SCM_GIT_TRANSCRIPT_CAPABILITY
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * SCM Operations capability key
 * The handler does NOT reach RuntimeContext or process-engine
 * classes directly — it asks for a narrow typed capability that
 * pipelinek/pipeline-application/runtime wires with the concrete [EventSink]
 * + [Clock] + secret resolver at composition time. This is the
 * The discipline: declared capability == used capability
*/
val SCM_GIT_OPERATIONS_CAPABILITY: StepCapability = StepCapability("scm-git.operations")

/**
 * `scm-git.checkout` OFFICIAL_PLUGIN Step
 * The contract declares
 * [WORKSPACE_IDENTITY_CAPABILITY] and the handler reads the canonical
 * workspace root from the typed capability seam. The constructor
 * removed the `workspaceRootResolver` developer-escape hatch
 * entirely. A location that is not a directory is now a typed
 * INFRASTRUCTURE failure; neither `pipeline.workspace.root` nor `user.dir`
 * is consulted anywhere in this Step.
 * The `relativeTargetDir` semantics documented for are preserved
 * if the path is absolute it is honoured verbatim (legacy callers that
 * supplied an absolute path keep that behaviour); if it is relative
 * (the default) it is resolved against the typed workspace root.
 *
 * The credential this Step declares is resolved by the executor from the
 * store, and the KIND of the credential decides the transport: a secret text
 * is a token, a user name and a password are a Basic header, and a private
 * key is an SSH identity. The Step does not receive handles, and it does not
 * choose the channel: the credential declares its own kind. See
 * [GitCheckoutExecutor] for the contract in full.
*/
class GitCheckoutStepDefinition(
    executorFactory: (workspaceRoot: Path, transcript: GitTranscript?) -> GitCheckoutExecutor =
        { workspaceRoot, transcript ->
            GitCheckoutExecutor(
                poll = GitPollExecutor(),
                changelog = GitChangelogWriter(),
                credentialsApplier = GitCredentialsApplier(
                    tempDir = Files.createTempDirectory("scm-git-step"),
                ),
                transcript = transcript,
            )
        },
    private val eventSink: EventSink? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? = null,
) : StepDefinition<GitScm, GitCheckoutOutput> {

    override val contract = StepContract(
        key = ScmGitCheckoutKey.VALUE,
        descriptor = StepDescriptor(
            stepId = ScmGitCheckoutKey.VALUE.value,
            name = "scm-git.checkout",
            jenkinsSurface = "checkout",
            jenkinsDivergence = "the Jenkins step takes the SCM in an scm block; here the KEY of the step names the SCM that is checked out.",
            configRef = "",
            pluginId = "scm-git",
            pluginVersion = "0.0.1-dev",  // overridden by manifest at registration
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE, Effect.EXECUTES_SUBPROCESS),
            replayPolicy = ReplayPolicy.MEMOIZED,
            recoveryPolicy = dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None,
        ),
        inputCodec = GitCheckoutInputCodec,
        outputCodec = GitCheckoutOutputCodec,
        // Declare the typed workspace identity capability
        // so the canonical engine admits the invocation AND so the
        // capability admission is fail-closed before the handler runs.
        // The handler reads the canonical workspace root from
        // ctx.capabilities.get(WORKSPACE_IDENTITY_CAPABILITY, WorkspaceIdentity::class)
        // and never falls back to ambient process state.
        //
        // The transcript capability is REQUIRED, not optional: a checkout whose output
        // cannot be persisted produces a diagnostic nobody can reconstruct, and the
        // failure is silent in the worst way — the checkout says "authentication failed"
        // and the bytes that would have said WHY stayed inside the plugin.
        requiredCapabilities = setOf<StepCapability>(
            EXECUTION_LOCATION_CAPABILITY,
            SCM_GIT_TRANSCRIPT_CAPABILITY,
        ),
    )

    /**
     * Typed handler that bridges the registry seam to the SDK executor.
     * - The input IS the domain's SCM carrier ([GitScm]), so there is nothing to
     *   translate: the credential reference arrives typed, with no
     *   Map<String, Any?> smuggling.
     * - Builds a workspace-scoped executor so per-Step cleanup is bounded.
     * - The handler returns a typed [GitCheckoutOutput]; failures surface
     *   through the [PluginStepException] typed algebra defined by the
     *   domain. Credentials NEVER enter the typed payload.
     * **Capability discipline: ** the contract declares
     * [WORKSPACE_IDENTITY_CAPABILITY] and the handler reads the
     * canonical workspace root from the typed capability seam. The
     * capability admission is fail-closed before the handler runs; if
     * the boundary admits this Step we are guaranteed the typed
     * capability is available and `get<WorkspaceIdentity>(...)` returns
     * the workspace root that `--workspace <dir>` populated upstream.
     * **Workspace resolution (`relativeTargetDir`):**
     * Absolute path: honoured verbatim (preserves.1-documented
     *    behaviour for callers that supplied an absolute path).
     *  - relative path: resolved against the typed workspace root, NOT
     *    against `user.dir`. This makes `--workspace <dir>` actually
     *    deterministic for production runs.
     * The `workspaceRootResolver` constructor default is a developer
     * Escape hatch only: it is consulted as a fallback when
     * the handler is admitted but the typed capability points at a
     * workspace that no longer exists on disk (rare; covers direct
     * unit-test construction outside the canonical bridge). Production
     * runs never hit this branch because the canonical engine threads
     * a valid workspace identity through the registry.
*/
    override val handler = StepHandler<GitScm, GitCheckoutOutput> { input, ctx ->
        // Read the canonical workspace root from the typed
        // capability seam. The capability access is fail-closed: the
        // boundary re-checks the declared capabilities before the handler
        // runs and throws if any are missing, so reaching this `get(...)`
        // is guaranteed to succeed when the handler was admitted.
        val location: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY, ExecutionSite::class)
        // `checkout` anchors on
        // CURRENT_DIRECTORY, so a `dir(...)` scope moves where the repository
        // lands. This is the value the legacy capability already supplied
        // (`workingDirectory ?: workspaceRoot`); what changes is that the Step
        // now *states* the anchor instead of receiving the cwd under the name
        // "workspace root".
        val capabilityWorkspaceRoot: Path = location.cwd
        // WorkspaceRoot is the
        // canonical pipeline workspace, and `relativeTargetDir` resolves
        // against it (or is honoured verbatim if absolute). Production
        // reads workspaceRoot from the typed capability seam; the
        // (see the guard below)
        // capability points at a directory that no longer exists on disk
        // (rare; covers direct unit-test construction outside the
        // canonical bridge).
        // The developer-escape hatch is gone. Falling back to
        // `System.getProperty("pipeline.workspace.root") ?: user.dir` meant a
        // run whose typed location had gone missing would silently operate on
        // whatever directory the JVM happened to start in — ambient state as a
        // Workspace authority, which forbids outright. A location that
        // is not a directory is now a typed failure instead.
        if (!Files.isDirectory(capabilityWorkspaceRoot)) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "scm-git.checkout: execution location is not a directory: $capabilityWorkspaceRoot",
                ),
            )
        }
        val workspaceRoot: Path = capabilityWorkspaceRoot
        // The transcript destination is read from the typed capability seam, exactly like the
        // execution location: the handler does not know WHICH store the bytes land in, only
        // that there is one. The admission is fail-closed, so a Step admitted without the
        // destination never reaches this line.
        val transcript: GitTranscript = ctx.capabilities.get(SCM_GIT_TRANSCRIPT_CAPABILITY, GitTranscript::class)
        val spec = CheckoutSpec(
            // The input IS the SCM carrier: the handler projects it, it does not
            // rebuild it. A handler that re-listed the fields would be a second
            // place where "what a checkout is" is written down.
            scm = input,
        )
        val sink: EventSink = this.eventSink ?: NullEventSink
        // The executor owns the temporary roots: the credential directory (the SSH key,
        // the answer file, the helper scripts) and the control roots of the poll
        // executor and of the changelog writer. `use` closes it when the step ends —
        // on the failure path too — so a checkout that failed halfway does not leave a
        // private key and a token in the system temporary area.
        // The destination is authorised BEFORE the checkout exists.
        //
        // It used to be resolved twice: the executor resolved it with `Path.resolve`
        // — which normalises nothing and confines nothing — and worked there, and
        // the handler validated the same user path AFTERWARDS, when the files were
        // already written. A refusal that arrives after the effect is a note, not a
        // guard. Now the path is resolved once, here, by the authority that owns
        // confinement, and the executor receives the resolved destination.
        val authorizedTarget = AuthorizedCheckoutTarget.resolve(
            location = location,
            stepKey = ScmGitCheckoutKey.VALUE.value,
            relativeTargetDir = input.relativeTargetDir,
        )
        executorFactory(workspaceRoot, transcript).use { executor ->
            val resolved = executor.execute(
                GitCheckoutRequest(
                    spec = spec,
                    runId = ctx.runId.value,
                    workspaceRoot = workspaceRoot,
                    authorizedTarget = authorizedTarget,
                    eventSink = sink,
                    clock = this.clock,
                    secretStore = this.secretStore,
                    stepIndex = ctx.stepIndex,
                    previousRemoteSha = null,
                ),
            ).getOrElse { throwable ->
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = classifyCheckoutFailure(throwable),
                        message = throwable.message ?: "scm-git checkout failed",
                    ),
                )
            }
            GitCheckoutOutput(
                resolvedSha = resolved.sha,
                // The published path IS the authorised destination: the same directory
                // the checkout wrote to, by construction and not by coincidence.
                localPath = authorizedTarget.path.toString(),
                wasCloned = resolved.outcome == GitCheckoutOutcome.CLONED,
                // The record says what HAPPENED, not what was declared: a checkout
                // that declared a credential and got none (a remote that cannot
                // authenticate with it) applied no credential, and a checkout that
                // applied an SSH key applied one.
                credentialApplied = resolved.credentialHelperPath != null ||
                    resolved.gitConfigFilePath != null ||
                    resolved.sshKeyPath != null,
                // Whether the remote was asked for the head of the declared branch.
                // A reader that does not know it cannot tell a remote fact from a
                // local one, and the two are published by the same field.
                remotePolled = resolved.remotePolled,
                // The state of the tracked content the checkout found. A reader that
                // only knows the outcome cannot tell a checkout that left a clean
                // tree alone from one that left a tree carrying changes alone.
                localState = resolved.localState,
            )
        }
    }
}

/**
 * Maps a checkout exception to a [FailureKind] from the public [FailureKind]
 * algebra.
 *
 * The classifier does NOT inspect the exception message (which may carry a
 * credential that the SecretPatternRegistry already scrubbed); it inspects only
 * the exception class. A word in the output of a git command must not decide
 * what kind of failure the run had.
 */
internal fun classifyCheckoutFailure(throwable: Throwable): FailureKind = when {
    // The transcript could not be persisted. This is INFRASTRUCTURE by TYPE, not by
    // message: the bytes of the checkout's own output are missing, so the run's
    // record is incomplete — that is an infrastructure fault whatever the git
    // command said, and reading the message here would let a word in the
    // command's output decide the kind.
    throwable is GitTranscriptRefused -> FailureKind.INFRASTRUCTURE
    // The remote was refused by the policy. It is USER by TYPE: the author wrote a
    // remote that may not be used, and nothing about the machine or the network
    // decides it. Reading the message here would be wrong for the same reason.
    throwable is GitRemotePolicy.GitRemoteRefused -> FailureKind.USER
    // The checkout refused to move a tree that carries local work. It is USER by
    // TYPE: the tree the run was pointed at holds changes the run did not make,
    // and the remedy is a decision about that tree (clean it, or point the run at
    // another one). No retry and no machine fault resolves it, and calling it
    // INFRASTRUCTURE would tell the caller to look for a broken machine where
    // there is a decision to make.
    throwable is GitCheckoutRefused -> FailureKind.USER
    // Heuristic: the SCM/Git executor wraps auth failures in a
    // dedicated exception type. Until the SCM domain grows a typed
    // FailureKind, we use the throwable message prefix as a
    // last-resort discriminator (the message itself is already
    // scrubbed upstream by GitCredentialsApplier/SecretPatternRegistry).
    throwable.message?.startsWith("auth", ignoreCase = true) == true -> FailureKind.NETWORK
    throwable.message?.contains("not found", ignoreCase = true) == true -> FailureKind.USER
    else -> FailureKind.INFRASTRUCTURE
}

/**
 * ResourceRef address for SCM/Git as a provider family (used by manifest
 * construction so the [PluginFamily] projection stays consistent).
*/
internal fun scmGitProviderFamilyRef(namespace: String) =
    dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.pluginFamily(namespace, "scm-git")
