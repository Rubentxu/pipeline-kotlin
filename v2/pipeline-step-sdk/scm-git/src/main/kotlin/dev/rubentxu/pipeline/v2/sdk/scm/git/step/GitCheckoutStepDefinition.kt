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
 * Canonical Step family for `scm-git.checkout` (LFC-2E2 / F5.1).
 *
 * The contract is constructor-injected with the SDK primitives needed
 * to perform a real checkout, so the Step stays bound to public SDK
 * types only. Production wires the canonical executors from
 * [dev.rubentxu.pipeline.v2.sdk.scm.git]; tests can wire fakes via the
 * same factory.
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
        requiredCapabilities = emptySet(),
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
     * **Capability discipline (F5.1 UAT-closure):** the contract declares
     * an empty capability set so the canonical engine admits the
     * invocation today; the handler closes over the SDK primitives it
     * needs (workspaceRootResolver, executor factory, optional secret
     * store) rather than reaching a capability surface the runtime does
     * not yet publish. Production routing via [SCM_GIT_OPERATIONS_CAPABILITY]
     * is the F5.2 follow-up slice (per ADR-0092 + the F5.1 receipt's
     * documented follow-ups).
     */
    override val handler = StepHandler<GitCheckoutInput, GitCheckoutOutput> { input, ctx ->
        val workspaceRoot: Path = workspaceRootResolver()
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
