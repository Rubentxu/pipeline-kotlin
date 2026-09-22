package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import dev.rubentxu.pipeline.v2.application.StructuralOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlayProjection
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata
import dev.rubentxu.pipeline.v2.application.CoreLegacyStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicyShape
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.AcquiredCredentialScope
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
// WU-G5R.5: durable waitUntil reconciliation driver and identity factory.
import dev.rubentxu.pipeline.v2.application.durable.WaitUntilIdentityFactory

// RETRY-D: control journal + reconciliation driver (ADR-0075 §11).
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.application.durable.retry.RetryReconciliationDriver
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.BranchTerminal
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateId
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelBranchChildSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciler
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciliationInput
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.ContextTransition
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

internal val canonicalStepIds: Set<String> =
    CanonicalCoreStepMetadata.pluginIds +
        CoreStepRegistryFactory.registry().keys().map { it.value }

/**
 * Block Step families whose body is executed by THIS engine (B10 / W1c).
 *
 * Derived from the declared [StepDescriptor] metadata, never from a list of StepKeys: a
 * Step is canonical-body-eligible when it declares a body row
 * (`StepBody.Declared`) whose `BodyExecution.owner` is this engine. Since W1d that row has
 * no implicit owner, so a new body Step cannot become eligible by omission. `core.catchError` / `core.warnError` declare
 * `LEGACY_LINEAR` because their semantics live in the legacy workflow-control rewrite,
 * so they stay refused here rather than being executed as empty shells.
 *
 * Adding a body Step therefore takes ONE descriptor row and no engine change, and this
 * engine cannot silently gain a body family it does not implement.
 */
internal val canonicalBodyStepIds: Set<PluginStepId> =
    StepDescriptorRegistry.standard().bodyStepIds(BodyExecutionOwner.CANONICAL_ENGINE)

/**
 * Describes a non-canonical step detected during pipeline analysis.
 *
 * @property stageIndex Index of the stage containing the non-canonical step
 * @property stepIndex Index of the step within the stage
 * @property stepId Human-readable step ID (from the pipeline definition)
 * @property pluginStepId The plugin step ID (e.g. "custom.step")
 * @property reason Why the step is non-canonical (e.g. "not in canonical core step IDs")
 */
data class NonCanonicalStep(
    val stageIndex: Int,
    val stepIndex: Int,
    val stepId: String,
    val pluginStepId: String,
    val reason: String,
)

/**
 * Analyzes the compiled pipeline and returns the list of non-canonical steps.
 * An empty list means the pipeline is fully canonical and eligible for canonical durable execution.
 */
fun CompiledPipeline.analyzeCanonicalDurableExecution(effectiveRegistry: StepRegistry? = null): List<NonCanonicalStep> {
    // LB-02 / EP-6: eligibility is registry-derived. When the caller has already
    // composed the production registry (core + external plugin contributions),
    // its keys participate in the gate; when null, the default derivation (core
    // metadata + the production core factory) applies — behaviour unchanged for
    // callers that do not supply a registry.
    val eligibleStepIds: Set<String> = if (effectiveRegistry != null) {
        CanonicalCoreStepMetadata.pluginIds + effectiveRegistry.keys().map { it.value }
    } else {
        canonicalStepIds
    }
    val nonCanonical = mutableListOf<NonCanonicalStep>()
    for ((stageIndex, stage) in stages.withIndex()) {
        val steps = (stage.body as? StageBody.Steps)?.steps ?: continue
        for ((stepIndex, step) in steps.withIndex()) {
            val issue = step.checkCanonicalExecution(eligibleStepIds)
            if (issue != null) {
                nonCanonical.add(NonCanonicalStep(
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepId = step.id.value,
                    pluginStepId = step.pluginStepId.value,
                    reason = issue,
                ))
            }
        }
    }
    return nonCanonical
}

/** True when the compiled pipeline fits the promoted canonical execution subset. */
fun CompiledPipeline.supportsCanonicalDurableExecution(effectiveRegistry: StepRegistry? = null): Boolean =
    analyzeCanonicalDurableExecution(effectiveRegistry).isEmpty()

internal fun StepNode.checkCanonicalExecution(eligibleStepIds: Set<String> = canonicalStepIds): String? {
    return when (this) {
        is BlockStepNode -> {
            if (pluginStepId !in canonicalBodyStepIds) {
                "block step '${pluginStepId.value}' is not owned by the canonical body engine"
            } else {
                body.forEach { child ->
                    val childIssue = child.checkCanonicalExecution(eligibleStepIds)
                    if (childIssue != null) return childIssue
                }
                null
            }
        }
        is OpaqueStepNode -> {
            if (pluginStepId.value !in eligibleStepIds) {
                "opaque step plugin not in canonical core step IDs"
            } else {
                null
            }
        }
    }
}

internal sealed interface StageTimeoutProjection {
    data object Absent : StageTimeoutProjection
    data class Present(val milliseconds: Long) : StageTimeoutProjection
}

internal fun StageNode.projectShellOptions(base: ShOptions): ShOptions {
    // WS-S-005: merge stage environment (EnvironmentSpec.values: Map<String, String>)
    // into ShOptions.env (Map<String, SecretHandle>)
    val stageEnv: Map<String, dev.rubentxu.pipeline.v2.domain.SecretHandle> =
        environment.values
            .mapValues { dev.rubentxu.pipeline.v2.domain.SecretHandle.plain(it.value) }
    val mergedEnv = base.env + stageEnv

    return when (val timeout = timeoutProjection()) {
        StageTimeoutProjection.Absent -> base.copy(env = mergedEnv)
        is StageTimeoutProjection.Present -> base.copy(
            timeoutMs = base.timeoutMs ?: timeout.milliseconds,
            env = mergedEnv,
        )
    }
}

internal fun StageNode.timeoutProjection(): StageTimeoutProjection {
    val timeoutOptions = options.filter { it.name == "timeout" }
    if (timeoutOptions.isEmpty()) return StageTimeoutProjection.Absent
    require(timeoutOptions.size == 1) { "Stage '$name' has multiple timeout options" }

    val seconds = timeoutOptions.single().value?.toLongOrNull()
        ?: throw IllegalArgumentException("Stage '$name' has an invalid timeout option")
    require(seconds > 0) { "Stage '$name' timeout must be positive" }
    return StageTimeoutProjection.Present(Math.multiplyExact(seconds, 1_000L))
}

internal fun StepOutcome.toOperationStatus(): OperationStatus = when (this) {
    StepOutcome.Success -> OperationStatus.SUCCEEDED
    StepOutcome.Unstable -> OperationStatus.FAILED
    is StepOutcome.Failure -> if (failure.kind == FailureKind.TIMEOUT) {
        OperationStatus.FAILED_TIMEOUT
    } else {
        OperationStatus.FAILED
    }
}

internal sealed interface CanonicalContinuation {
    data object Continue : CanonicalContinuation
    data object ContinueUnstable : CanonicalContinuation
    data class Abort(val failure: PipelineFailure) : CanonicalContinuation
}

internal sealed interface RunningCanonicalShellRecovery {
    data object NotRunningShell : RunningCanonicalShellRecovery
    data class Recovered(val outcome: StepOutcome, val status: OperationStatus) : RunningCanonicalShellRecovery
}

/**
 * Resolution of the durable replay/reconcile decision (B1.2c2-a2.2), derived from the real branches
 * in `dispatch`: divergence detection, running-shell recovery and the effect-aware replay policy.
 *
 * These are genuinely distinct protocol states with distinct terminal semantics, so each carries only
 * the data its own handling needs; incoherent combinations (e.g. a reuse that also re-executes) are not
 * representable.
 *
 * @property operationId Reproduced for error messages only; no journal/cursor is touched by the resolver.
 */
internal sealed interface InvocationReconciliation {
    /** Fingerprint divergence was detected; the invocation must fail closed without executing. */
    data class Diverged(val operationId: String) : InvocationReconciliation

    /** A RUNNING shell was recovered to a concrete outcome+terminal status without re-invoking it. */
    data class RecoverRunning(val outcome: StepOutcome, val status: OperationStatus) : InvocationReconciliation

    /** The journaled result is reusable; return the cached success without executing. */
    data object ReuseCompleted : InvocationReconciliation

    /** The replay policy rejected re-execution; the pipeline must abort via the lifecycle boundary. */
    data class RejectedAbort(val operationId: String) : InvocationReconciliation

    /** Fresh/re-run: the invocation is cleared to execute through the effective executor. */
    data object Execute : InvocationReconciliation
}

internal sealed interface BlockShellScope {
    data object None : BlockShellScope
    data class Directory(val target: Path, val previous: Path) : BlockShellScope
    data class TimestampsScope(val runId: String) : BlockShellScope
    data class EnvScope(val overrides: List<String>, val parentEnv: Map<String, dev.rubentxu.pipeline.v2.domain.SecretHandle>) : BlockShellScope

    /**
     * B13/E-EM-11: body-deadline contract projected from the `core.timeout` payload.
     * The deadline flows to child ShOptions as the certified per-invocation Sh
     * watchdog budget (min of own and inherited remaining), so cancellation
     * propagates to the child subprocess through the kill seam.
     */
    data class Timeout(val budgetMs: Long) : BlockShellScope

    /**
     * B13/E-EM-11: body-attempt contract projected from the `core.retry` payload.
     * The body is re-dispatched up to [maxAttempts] times; each attempt carries a
     * deterministic journal identity (attempt BlockSegment appended to bodyPath),
     * so restart/replay reconstructs attempt state from the journal, not memory.
     */
    data class Retry(val maxAttempts: Int) : BlockShellScope

    /**
     * WU-G5R.3: body-polling contract projected from the `core.waitUntil` payload.
     * The body (a condition script) is re-dispatched until it succeeds (exit 0) or
     * backoff exceeds MAX_BACKOFF_MS.  Each poll carries a deterministic journal identity
     * so restart/replay reconstructs poll state from the journal, not memory.
     *
     * initialRecurrencePeriod: initial delay in ms before first poll
     * quiet: suppress logging if true
     * maxBackoffMs: ceiling for exponential backoff (default 60 s)
     */
    data class WaitUntilScope(
        val initialRecurrencePeriod: Long,
        val quiet: Boolean,
        val maxBackoffMs: Long = 60_000L,
    ) : BlockShellScope
}

/**
 * How one body is executed, decided from its DECLARED policy (B10 / W1c).
 *
 * Total over [BodyExecutionPolicy]: every declared shape either has an interpretation
 * here or is named as rejected. No case carries a StepKey, and no case falls back to
 * "run the children in sequence" — a shape without an interpretation is refused, so a
 * body family can never be executed by an engine that does not implement it.
 */
internal sealed interface BodyExecutionProjection {

    /** The generic body machinery runs this body with [scope] projected around it. */
    data class Scope(val scope: BlockShellScope) : BodyExecutionProjection

    /**
     * The body runs under a bound credential lease (acquire -> environment overlay ->
     * always close). Not a `BlockShellScope`: a lease is not a context dimension children
     * inherit from [ShOptions], because its value only exists AFTER the acquisition effect.
     *
     * Carries the decoded bindings, so the payload is decoded exactly once, in the pure
     * projector, and a malformed payload is a typed [InvalidInput] before any effect. Before
     * W1d this projection was a bare marker and the payload was decoded a second time inside
     * the credential-specific execution path.
     */
    data class CredentialLease(
        val bindings: List<CredentialBindingSpec>,
    ) : BodyExecutionProjection

    /** Decoded input is invalid: a typed SCHEMA rejection, never a fallback. */
    data class InvalidInput(val detail: String) : BodyExecutionProjection

    /**
     * The declared shape has no interpretation in this engine build. Unreachable by
     * construction (policy resolution rejects a shape outside
     * [BodyExecutionSupport]), kept total so that widening the support without
     * implementing the shape fails closed instead of silently degrading.
     */
    data class Unimplemented(val shape: BodyExecutionPolicyShape) : BodyExecutionProjection
}

/**
 * Projects body execution from the DECLARED policy and the node's typed payload.
 *
 * `(policy, options) -> BodyExecutionProjection`: pure, no effects, no Step name. The
 * SHAPE is the declaration; the runtime VALUES (`dir`'s path, `timeout`'s seconds,
 * `retry`'s budget) are decoded input. Malformed input is returned as a typed
 * [BodyExecutionProjection.InvalidInput], never thrown as domain control flow.
 */
internal fun BlockStepNode.projectBodyExecution(
    policy: BodyExecutionPolicy,
    options: ShOptions,
): BodyExecutionProjection = when (policy) {
    is BodyExecutionPolicy.Sequential -> BodyExecutionProjection.Scope(BlockShellScope.None)
    is BodyExecutionPolicy.Scoped -> projectScopedBody(policy.projection, options)
    is BodyExecutionPolicy.Retrying -> {
        // WU-LPR-301: dispatch is policy-driven, not StepKey-driven. A Retrying body that
        // declares a WaitUntilShape projects to WaitUntilScope using the declared cadence;
        // any other Retrying body projects to the shared Retry scope with the decoded
        // attempt budget. The body-routing dispatch is a closed ADT match on
        // BodyExecutionPolicy; there is no concrete-PluginStepId comparison here.
        policy.waitUntil?.let { shape ->
            BodyExecutionProjection.Scope(
                BlockShellScope.WaitUntilScope(
                    initialRecurrencePeriod = shape.initialRecurrencePeriodMs,
                    quiet = shape.quiet,
                ),
            )
        } ?: BodyExecutionProjection.Scope(
            BlockShellScope.Retry(maxAttempts = decodeAttemptBudgetMaxAttempts()),
        )
    }
    is BodyExecutionPolicy.Parallel -> BodyExecutionProjection.Unimplemented(policy.shape)
}

/** Which context dimension a scoped body projects, and how its value is decoded. */
internal fun BlockStepNode.projectScopedBody(
    projection: BodyContextProjection,
    options: ShOptions,
): BodyExecutionProjection = when (projection) {
    is BodyContextProjection.WorkingDirectory -> projectWorkingDirectory(options)
    is BodyContextProjection.Environment -> projectEnvironment(options)
    is BodyContextProjection.Timestamps -> BodyExecutionProjection.Scope(BlockShellScope.TimestampsScope(runId = ""))
    is BodyContextProjection.Deadline -> decodeDeadline()
    is BodyContextProjection.CredentialLease -> decodeCredentialBindings()
}

internal fun BlockStepNode.projectWorkingDirectory(options: ShOptions): BodyExecutionProjection {
    val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
    val path = payload["path"]?.jsonPrimitive?.contentOrNull
        ?: return BodyExecutionProjection.InvalidInput("requires a path")
    if (path.isBlank()) return BodyExecutionProjection.InvalidInput("path must not be blank")

    val previous = options.workingDirectory ?: options.workspaceRoot
    val target = Path.of(path).let { candidate ->
        if (candidate.isAbsolute) candidate else previous.resolve(candidate)
    }.normalize()
    if (!Path.of(path).isAbsolute && !target.startsWith(previous)) {
        return BodyExecutionProjection.InvalidInput("path escapes the workspace: $path")
    }
    return BodyExecutionProjection.Scope(BlockShellScope.Directory(target, previous))
}

internal fun BlockStepNode.projectEnvironment(options: ShOptions): BodyExecutionProjection {
    val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
    val overridesArray = payload["overrides"]?.jsonArray
    val overrides = overridesArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    return BodyExecutionProjection.Scope(BlockShellScope.EnvScope(overrides = overrides, parentEnv = options.env))
}

// B13/E-EM-11: fail-closed contract decode — malformed deadline/attempt payloads are
// typed schema rejections, never a silent plain-sequence fallback.
internal fun BlockStepNode.decodeDeadline(): BodyExecutionProjection {
    val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
    val seconds = payload["seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: return BodyExecutionProjection.InvalidInput("requires integer seconds")
    if (seconds <= 0) return BodyExecutionProjection.InvalidInput("seconds must be > 0, got $seconds")
    return BodyExecutionProjection.Scope(BlockShellScope.Timeout(budgetMs = seconds * 1000L))
}

// EM-7/LFC-5.3 (INC-022): the credential bindings are decoded HERE, in the pure projection,
// so an invalid payload is a typed schema rejection before the acquisition effect runs and
// before any child is dispatched. There is no second decode on the execution path (W1d).
internal fun BlockStepNode.decodeCredentialBindings(): BodyExecutionProjection =
    try {
        BodyExecutionProjection.CredentialLease(CredentialBindingsPayload.decode(payload.encoded))
    } catch (error: IllegalArgumentException) {
        BodyExecutionProjection.InvalidInput("withCredentials bindings invalid: ${error.message}")
    }

internal fun BlockStepNode.decodeAttemptBudgetMaxAttempts(): Int {
    val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
    val maxAttempts = payload["maxAttempts"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?: throw IllegalArgumentException("requires integer maxAttempts")
    require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    return maxAttempts
}

/**
 * Project a [dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch] from
 * [BodyInvocationContext] onto the parent [ExecutionContext] (Phase 1b, WU-LPR-302).
 *
 * Each variant of the closed [BodyContextProjection] family maps to its existing
 * pure derivation. The projection's rejection cases
 * ([dev.rubentxu.pipeline.v2.domain.step.BodyContextRejection.HandledOutsideOverlay],
 * e.g. `Deadline` / `CredentialLease`) are intentionally **silently dropped**:
 * the seam is honest about what it does not do — deadlines are projected onto
 * `ShOptions.timeoutMs` and credential leases are projected by the
 * `executeCredentialLeasedBody` preamble, not by the reentry seam. A dropped
 * patch falls through to the parent unchanged; rejection stays inside the typed
 * algebra, never as an exception.
 *
 * The parent is preserved (CTX-P: derivation never mutates `parent.overlays`).
 * `None` returns the parent verbatim.
 */
internal fun applyPatchToContext(
    parent: dev.rubentxu.pipeline.v2.domain.ExecutionContext,
    patch: dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch,
): dev.rubentxu.pipeline.v2.domain.ExecutionContext = when (patch) {
    dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch.None -> parent
    is dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch.Directory ->
        when (
            val d = dev.rubentxu.pipeline.v2.domain.step.deriveChildExecutionContext(
                parent = parent,
                projection = dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.WorkingDirectory,
                runtime = dev.rubentxu.pipeline.v2.domain.step.BodyRuntimeValue.DirectoryValue(patch.path),
            )
        ) {
            is dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation.Derived -> d.context
            is dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation.Rejected -> parent
        }
    is dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch.Environment ->
        when (
            val d = dev.rubentxu.pipeline.v2.domain.step.deriveChildExecutionContext(
                parent = parent,
                projection = dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.Environment,
                runtime = dev.rubentxu.pipeline.v2.domain.step.BodyRuntimeValue.EnvironmentValue(patch.values),
            )
        ) {
            is dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation.Derived -> d.context
            is dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation.Rejected -> parent
        }
    is dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch.CredentialLease ->
        // Handled by `executeCredentialLeasedBody` preamble; the seam is honest.
        parent
}

