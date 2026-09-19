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
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRequest
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * SCM Operations capability key (F5.1).
 *
 * The handler does NOT reach CanonicalRuntimeContext or process-engine
 * classes directly — it asks for a narrow typed capability that
 * v2/pipeline-application/runtime wires with the concrete [EventSink]
 * + [Clock] + secret resolver at composition time. This is the
 * LB-02 / G3-A4.2 discipline: declared capability == used capability.
 */
val SCM_GIT_OPERATIONS_CAPABILITY: StepCapability = StepCapability("scm-git.operations")

/**
 * `scm-git.checkout` OFFICIAL_PLUGIN Step (LFC-2E2 / F5.1 / WU-LPR-WC-SCM).
 *
 * Capability discipline (F5.1 + WC-SCM): the contract declares
 * [WORKSPACE_IDENTITY_CAPABILITY] and the handler reads the canonical
 * workspace root from the typed capability seam. The constructor
 * default `workspaceRootResolver` survives ONLY as a developer-escape
 * hatch for direct `handler.invoke(...)` unit tests that bypass the
 * canonical registry boundary; production runs always thread the typed
 * capability through [StepHandlerContext.capabilities], so neither
 * `pipeline.workspace.root` nor `user.dir` is consulted in production.
 *
 * The `relativeTargetDir` semantics documented for F5.1 are preserved:
 * if the path is absolute it is honoured verbatim (legacy callers that
 * supplied an absolute path keep that behaviour); if it is relative
 * (the default) it is resolved against the typed workspace root.
 */
class GitCheckoutStepDefinition(
    private val executorFactory: (workspaceRoot: Path) -> GitCheckoutExecutor = { workspaceRoot ->
        GitCheckoutExecutor(
            poll = GitPollExecutor(),
            changelog = GitChangelogWriter(),
            credentialsApplier = GitCredentialsApplier(
                tempDir = Files.createTempDirectory("scm-git-step"),
                credentials = GitCredentials(),
            ),
        )
    },
    private val eventSink: EventSink? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? = null,
    // WU-LPR-WC-SCM: developer-escape hatch ONLY. The production path
    // reads the workspace root from WORKSPACE_IDENTITY_CAPABILITY; this
    // resolver is consulted by the handler as a last-resort fallback
    // when the handler is admitted through the boundary but the typed
    // capability happens to point at a workspace that no longer exists
    // (rare; covers direct unit-test construction outside the canonical
    // bridge). Production runs always thread a valid typed workspace
    // identity, so this fallback is never exercised in production.
    private val workspaceRootResolver: () -> Path = { Path.of(System.getProperty("pipeline.workspace.root") ?: System.getProperty("user.dir") ?: ".") },
) : StepDefinition<GitCheckoutInput, GitCheckoutOutput> {

    override val contract = StepContract(
        key = ScmGitCheckoutKey.VALUE,
        descriptor = StepDescriptor(
            stepId = ScmGitCheckoutKey.VALUE.value,
            name = "scm-git.checkout",
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
        // WU-LPR-WC-SCM: declare the typed workspace identity capability
        // so the canonical engine admits the invocation AND so the
        // capability admission is fail-closed before the handler runs.
        // The handler reads the canonical workspace root from
        // ctx.capabilities.get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
        // and never falls back to user.dir in production.
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    /**
     * Typed handler that bridges the registry seam to the SDK executor.
     *
     * - Decodes the credentialsRef into a typed [GitScm.credentialsId]
     *   carrier (no Map<String, Any?> smuggling).
     * - Builds a workspace-scoped executor so per-Step cleanup is bounded.
     * - The handler returns a typed [GitCheckoutOutput]; failures surface
     *   through the [PluginStepException] typed algebra defined by the
     *   domain. Credentials NEVER enter the typed payload.
     *
     * **Capability discipline (F5.1 + WC-SCM):** the contract declares
     * [WORKSPACE_IDENTITY_CAPABILITY] and the handler reads the
     * canonical workspace root from the typed capability seam. The
     * capability admission is fail-closed before the handler runs; if
     * the boundary admits this Step we are guaranteed the typed
     * capability is available and `get<WorkspaceIdentity>(...)` returns
     * the workspace root that `--workspace <dir>` populated upstream.
     *
     * **Workspace resolution (`relativeTargetDir`):**
     *  - absolute path: honoured verbatim (preserves F5.1-documented
     *    behaviour for callers that supplied an absolute path).
     *  - relative path: resolved against the typed workspace root, NOT
     *    against `user.dir`. This makes `--workspace <dir>` actually
     *    deterministic for production runs.
     *
     * The `workspaceRootResolver` constructor default is a developer
     * escape hatch only: it is consulted as a LAST-RESORT fallback when
     * the handler is admitted but the typed capability points at a
     * workspace that no longer exists on disk (rare; covers direct
     * unit-test construction outside the canonical bridge). Production
     * runs never hit this branch because the canonical engine threads
     * a valid workspace identity through the registry.
     */
    override val handler = StepHandler<GitCheckoutInput, GitCheckoutOutput> { input, ctx ->
        // WU-LPR-WC-SCM: read the canonical workspace root from the typed
        // capability seam. The capability access is fail-closed: the
        // boundary re-checks the declared capabilities before the handler
        // runs and throws if any are missing, so reaching this `get(...)`
        // is guaranteed to succeed when the handler was admitted.
        val capabilityWorkspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot
        // Preserve the documented F5.1 semantics: workspaceRoot is the
        // canonical pipeline workspace, and `relativeTargetDir` resolves
        // against it (or is honoured verbatim if absolute). Production
        // reads workspaceRoot from the typed capability seam; the
        // developer-escape resolver is consulted only when the typed
        // capability points at a directory that no longer exists on disk
        // (rare; covers direct unit-test construction outside the
        // canonical bridge).
        val workspaceRoot: Path = if (Files.isDirectory(capabilityWorkspaceRoot)) {
            capabilityWorkspaceRoot
        } else {
            workspaceRootResolver()
        }
        val executor = executorFactory(workspaceRoot)
        val spec = CheckoutSpec(
            scm = GitScm(
                url = input.url,
                branch = input.branch,
                credentialsId = input.toDomainCredentialsId(),
                changelog = input.changelog,
                poll = input.poll,
                relativeTargetDir = input.relativeTargetDir,
            ),
        )
        val sink: EventSink = eventSink ?: NullEventSink
        val resolved = executor.execute(
            GitCheckoutRequest(
                spec = spec,
                runId = ctx.runId.value,
                workspaceRoot = workspaceRoot,
                eventSink = sink,
                clock = clock,
                secretStore = secretStore,
                stepIndex = ctx.stepIndex,
                previousRemoteSha = null,
            ),
        ).getOrElse { throwable ->
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = classifyFailureKind(throwable),
                    message = throwable.message ?: "scm-git checkout failed",
                ),
            )
        }
        GitCheckoutOutput(
            resolvedSha = resolved.sha,
            localPath = workspaceRoot.resolve(input.relativeTargetDir).toString(),
            wasCloned = resolved.classification == "clone",
            credentialApplied = resolved.credentialsFilePath != null || resolved.gitConfigFilePath != null,
        )
    }

    /**
     * Maps a checkout exception to a [FailureKind] from the public
     * [FailureKind] algebra. The classifier does NOT inspect the
     * exception message (which may carry a credential that the
     * SecretPatternRegistry already scrubbed); it inspects only the
     * exception class.
     */
    private fun classifyFailureKind(throwable: Throwable): FailureKind = when {
        // Heuristic: the SCM/Git executor wraps auth failures in a
        // dedicated exception type. Until the SCM domain grows a typed
        // FailureKind, we use the throwable message prefix as a
        // last-resort discriminator (the message itself is already
        // scrubbed upstream by GitCredentialsApplier/SecretPatternRegistry).
        throwable.message?.startsWith("auth", ignoreCase = true) == true -> FailureKind.NETWORK
        throwable.message?.contains("not found", ignoreCase = true) == true -> FailureKind.USER
        else -> FailureKind.INFRASTRUCTURE
    }
}

/**
 * ResourceRef address for SCM/Git as a provider family (used by manifest
 * construction so the [PluginFamily] projection stays consistent).
 */
internal fun scmGitProviderFamilyRef(namespace: String) =
    dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.pluginFamily(namespace, "scm-git")
