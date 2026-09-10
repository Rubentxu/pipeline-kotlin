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
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.application.durable.credentials.AcquiredCredentialScope
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeCleanup
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
// RETRY-D: control journal + reconciliation driver (ADR-0075 §11).
import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory
import dev.rubentxu.pipeline.v2.application.durable.retry.RetryReconciliationDriver
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ContextStack
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/**
 * Canonical Step keys eligible for canonical durable execution (EP-F2.6: renamed from
 * `canonicalCoreStepIds` — the authority is NOT core-only). The durable spine accepts a step as
 * canonical when its key is either (a) a legacy executable core id, or (b) present in the
 * production [StepRegistry] — which is open-world: it includes core Steps (`core.echo`,
 * `core.sh`) AND external plugin contributions discovered at composition time. A key is NOT
 * canonical-eligible before its plugin is registered and becomes eligible after registration
 * (proven by EP_F26_GenericProductionPathProofTest). The coordinator derives eligibility from
 * this authority, never from the decoded command world nor from a closed core catalogue.
 */
private val canonicalStepIds: Set<String> =
    CanonicalCoreStepMetadata.pluginIds +
        CoreStepRegistryFactory.registry().keys().map { it.value }
private val canonicalBodyStepIds: Set<String> = setOf(
    "core.dir",
    "core.timeout",
    "core.retry",
    "core.withCredentials",
    "core.timestamps",
    "core.withEnv",
)

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

private fun StepNode.checkCanonicalExecution(eligibleStepIds: Set<String> = canonicalStepIds): String? {
    return when (this) {
        is BlockStepNode -> {
            if (pluginStepId.value !in canonicalBodyStepIds) {
                "block step plugin not in canonical body step IDs"
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

private sealed interface StageTimeoutProjection {
    data object Absent : StageTimeoutProjection
    data class Present(val milliseconds: Long) : StageTimeoutProjection
}

private fun StageNode.projectShellOptions(base: ShOptions): ShOptions {
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

private fun StageNode.timeoutProjection(): StageTimeoutProjection {
    val timeoutOptions = options.filter { it.name == "timeout" }
    if (timeoutOptions.isEmpty()) return StageTimeoutProjection.Absent
    require(timeoutOptions.size == 1) { "Stage '$name' has multiple timeout options" }

    val seconds = timeoutOptions.single().value?.toLongOrNull()
        ?: throw IllegalArgumentException("Stage '$name' has an invalid timeout option")
    require(seconds > 0) { "Stage '$name' timeout must be positive" }
    return StageTimeoutProjection.Present(Math.multiplyExact(seconds, 1_000L))
}

private fun StepOutcome.toOperationStatus(): OperationStatus = when (this) {
    StepOutcome.Success -> OperationStatus.SUCCEEDED
    StepOutcome.Unstable -> OperationStatus.FAILED
    is StepOutcome.Failure -> if (failure.kind == dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT) {
        OperationStatus.FAILED_TIMEOUT
    } else {
        OperationStatus.FAILED
    }
}

private sealed interface CanonicalContinuation {
    data object Continue : CanonicalContinuation
    data object ContinueUnstable : CanonicalContinuation
    data class Abort(val failure: PipelineFailure) : CanonicalContinuation
}

private sealed interface RunningCanonicalShellRecovery {
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
private sealed interface InvocationReconciliation {
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

private sealed interface BlockShellScope {
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
}

private fun BlockStepNode.projectShellScope(options: ShOptions): BlockShellScope = when (pluginStepId.value) {
    "core.dir" -> {
        val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
        val path = payload["path"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("core.dir requires a path")
        require(path.isNotBlank()) { "core.dir path must not be blank" }

        val previous = options.workingDirectory ?: options.workspaceRoot
        val target = Path.of(path).let { candidate ->
            if (candidate.isAbsolute) candidate else previous.resolve(candidate)
        }.normalize()
        require(Path.of(path).isAbsolute || target.startsWith(previous)) {
            "core.dir path escapes workspace: $path"
        }
        BlockShellScope.Directory(target, previous)
    }
    "core.timestamps" -> {
        BlockShellScope.TimestampsScope(runId = "")
    }
    "core.withEnv" -> {
        val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
        val overridesArray = payload["overrides"]?.jsonArray
        val overrides = overridesArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        BlockShellScope.EnvScope(overrides = overrides, parentEnv = options.env)
    }
    // B13/E-EM-11: fail-closed contract decode — malformed retry/timeout payloads
    // are typed schema rejections, never a silent plain-sequence fallback.
    "core.timeout" -> {
        val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
        val seconds = payload["seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("core.timeout requires integer seconds")
        require(seconds > 0) { "core.timeout seconds must be > 0, got $seconds" }
        BlockShellScope.Timeout(budgetMs = seconds * 1000L)
    }
    "core.retry" -> {
        val payload = Json.parseToJsonElement(this.payload.encoded).jsonObject
        val maxAttempts = payload["maxAttempts"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("core.retry requires integer maxAttempts")
        require(maxAttempts >= 1) { "core.retry maxAttempts must be >= 1, got $maxAttempts" }
        BlockShellScope.Retry(maxAttempts = maxAttempts)
    }
    else -> BlockShellScope.None
}

/** Executes the linear canonical core subset with the durable journal and replay cursor. */
class CanonicalDurableRunCoordinator(
    private val dispatcher: CanonicalNodeDispatcher,
    private val journal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val clock: Clock,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val eventSink: EventSink,
    private val credentialScopePort: CredentialScopePort,
    private val controlDirRoot: Path? = null,
    private val shOptions: ShOptions = ShOptions.EMPTY,
    private val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector(),
    // CDE.2-b2: durable metadata resolved by structural step key (pre-decode). Nullable default so a
    // registry-injected constructor can opt into the composite (CDE.3-e4.2); legacy call-sites that do
    // not pass a resolver keep the [CoreLegacyStepMetadataResolver] authority exactly as before.
    private val stepMetadataResolver: StepMetadataResolver? = null,
    // B1.2c2-a1: temporary compatibility seam for the EFFECTIVE step invocation. Optional so the
    // existing ~25 construction sites compile unchanged; production default delegates to the legacy
    // dispatcher. Not the final DI architecture.
    invocationExecutor: CanonicalInvocationExecutor? = null,
    // CDE.3-b3: the new authority seam for effective execution. Optional for dual characterization;
    // when absent it defaults to the legacy adapter over [invocationExecutor] (or the legacy
    // dispatcher), preserving behaviour exactly. Existing callers that inject the old executor keep
    // working unchanged because the default boundary routes to it.
    commonExecutionBoundary: CommonExecutionBoundary? = null,
    // CDE.3-e4.1: optional open StepRegistry (plugin definitions) for registry-driven metadata and,
    // later, execution. Additive at the end of the ctor so the ~25 legacy call-sites (positional or
    // named) compile unchanged. No global registry, no service locator, no singleton.
    private val stepRegistry: StepRegistry? = null,
    // RETRY-D: optional retry control journal. When bound, the retry branch of `dispatchBody`
    // routes through RetryReconciliationDriver (ADR-0075 §11). When null, the pre-ADR-0075
    // inline retry loop is preserved bit-equivalent — existing callers and tests see no change.
    private val retryControlJournal: FileBasedRetryControlJournal? = null,
) {
    /** Active context stack for body scope tracking (EM-4). */
    private var contextStack: ContextStack = ContextStack.EMPTY

    /**
     * Effective pre-decode metadata authority (CDE.3-e4.2). An explicit [stepMetadataResolver] wins;
     * otherwise an injected [stepRegistry] opts into the composite (core keys -> legacy authority,
     * other keys -> registry metadata, unknown -> hard defect); otherwise the legacy core authority.
     * The durable coordinator only ever consumes [StepMetadata]; it does not know where it came from.
     */
    private val metadataResolver: StepMetadataResolver = stepMetadataResolver
        ?: if (stepRegistry != null) RegistryStepMetadataResolver.composite(stepRegistry)
        else CoreLegacyStepMetadataResolver

    /**
     * Effective step executor, routed through the new common seam (CDE.3-b3). The production default
     * adapts the injected legacy [invocationExecutor] (or the legacy dispatcher) behind
     * [CommonExecutionBoundary], preserving behaviour exactly; a caller may inject its own boundary
     * for dual characterization. The durable coordinator only ever hands an opaque [PreparedExecution]
     * to this seam and never names a decoded command type.
     *
     * As of S2.5.7 WU-5 the call is routed through [ExecutionBoundaryFactory.build] (the named
     * producer seam) directly, bypassing the [buildDefaultExecutionBoundary] forwarder. The factory
     * is the structural-switch authority (binary `if (stepRegistry != null)` preserved bit-a-bit); the
     * forwarder is kept as a source-compatibility shim for any external callers.
     */
    private val executionBoundary: CommonExecutionBoundary = commonExecutionBoundary
        // CDE.3-e4.5 / B1.2c3-S2.5.7 WU-5: structural switch lives in ExecutionBoundaryFactory.build
        // (binary legacy-bit-equivalent: registry present -> SeamedRouting; otherwise -> LegacyOnly).
        ?: ExecutionBoundaryFactory.build(dispatcher, invocationExecutor, stepRegistry)

    // C3: RunStarted/RunFinished state
    private var currentOutcome: RunOutcome = RunOutcome.Success
    // Track whether RunStarted was emitted (for RunFinished correlation)
    private var runStartedEmitted = false

    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        // Reset state for this run
        currentOutcome = RunOutcome.Success
        runStartedEmitted = false
        contextStack = ContextStack.EMPTY

        // C3: Emit RunStarted at the beginning of the pipeline run
        eventSink.append(
            RunStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                scriptPath = pipeline.source.path,
            ),
        )
        runStartedEmitted = true

        try {
            stagesLoop@ for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]
                // Stage boundary: context stack must be empty when entering a stage
                check(contextStack.isEmpty) {
                    "Scope stack leaked into stage '${stage.name}' at index $stageIndex: ${contextStack.size} frame(s) remaining"
                }
                val steps = (stage.body as? StageBody.Steps)?.steps
                var stageWorkspace: Path? = null
                if (controlDirRoot != null) {
                    val resolver = WorkspaceResolver(controlDirRoot)
                    val workspacePath = resolver.resolve(stage.name, stageIndex)
                    try {
                        resolver.ensureCreated(workspacePath)
                        stageWorkspace = workspacePath
                    } catch (e: java.io.IOException) {
                        currentOutcome = RunOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                                "Failed to create stage workspace '${workspacePath}': ${e.message}"
                            ),
                        )
                        return@run currentOutcome
                    }
                }
                val stageBaseOptions = if (stageWorkspace != null) shOptions.copy(workspaceRoot = stageWorkspace) else shOptions
                val stageShOptions = stage.projectShellOptions(stageBaseOptions)
                // B13/E-EM-11: parallel stages re-enter the SAME dispatch spine —
                // each branch is dispatched as a bodyPath-rooted branch with its
                // own branchIndex (deterministic durable identity via OpId -b{N}
                // journal keys). No second engine, no ParallelFrameExecutor.
                if (steps == null && stage.body is StageBody.Parallel) {
                    // Workspace creation is required before branch dispatch (D5/C1 reuse).
                    val parallelOutcome = runParallelStage(stage, stageIndex, stageShOptions, runId)
                    when (val continuation = decideContinuation(parallelOutcome, stage.name, runId.value)) {
                        CanonicalContinuation.Continue -> {
                            eventSink.append(
                                dev.rubentxu.pipeline.v2.events.StageFinished(
                                    eventId = UUID.randomUUID().toString(),
                                    runId = runId.value,
                                    sequence = 0L,
                                    occurredAt = Instant.now(),
                                    stageIndex = stageIndex,
                                    stageName = stage.name,
                                    outcome = "success",
                                ),
                            )
                        }
                        CanonicalContinuation.ContinueUnstable -> {
                            currentOutcome = RunOutcome.Unstable
                            eventSink.append(
                                dev.rubentxu.pipeline.v2.events.StageFinished(
                                    eventId = UUID.randomUUID().toString(),
                                    runId = runId.value,
                                    sequence = 0L,
                                    occurredAt = Instant.now(),
                                    stageIndex = stageIndex,
                                    stageName = stage.name,
                                    outcome = "unstable",
                                ),
                            )
                        }
                        is CanonicalContinuation.Abort -> {
                            currentOutcome = RunOutcome.Failure(continuation.failure)
                            return@run currentOutcome
                        }
                    }
                    continue@stagesLoop
                }
                val steps1 = steps
                    ?: throw IllegalArgumentException("Canonical durable coordinator supports only linear or parallel stage bodies")
                // D5: Per-stage workspaceRoot override at dispatch boundary
                // C1: Workspace pre-creation - ensure stage workspace exists before shell dispatch
                // LFC-2 / ERR-S-004: restore stage bookends lost in the LF-0208 spine migration.
                // The canonical coordinator emits StageStarted at entry and StageFinished on normal
                // completion (success/unstable). An aborting stage returns before StageFinished;
                // RunFinished carries the failure.
                eventSink.append(
                    dev.rubentxu.pipeline.v2.events.StageStarted(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                    ),
                )
                var stageUnstable = false
                for (stepIndex in steps1.indices) {
                    val step = steps1[stepIndex]
                    val outcome = dispatch(step, runId, stage.name, stageIndex, stepIndex, stageShOptions)
                    when (val continuation = decideContinuation(outcome, stage.name, runId.value)) {
                        CanonicalContinuation.Continue -> Unit
                        CanonicalContinuation.ContinueUnstable -> {
                            currentOutcome = RunOutcome.Unstable
                            stageUnstable = true
                        }
                        is CanonicalContinuation.Abort -> {
                            currentOutcome = RunOutcome.Failure(continuation.failure)
                            return@run currentOutcome
                        }
                    }
                }
                eventSink.append(
                    dev.rubentxu.pipeline.v2.events.StageFinished(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        outcome = if (stageUnstable) "unstable" else "success",
                    ),
                )
            }
            // Success: fall through to finally and return
        } catch (e: Exception) {
            // Invariants are engine failures, never ordinary infrastructure outcomes.
            if (e is IllegalStateException || e is EngineInvariantViolation) throw e
            currentOutcome = RunOutcome.Failure(
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                    "Unexpected error during pipeline run: ${e.message}"
                ),
            )
        } finally {
            // C3: Emit RunFinished in finally block, only if RunStarted was emitted
            // This ensures SKIP replay paths still get proper bookend events
            if (runStartedEmitted) {
                eventSink.append(
                    RunFinished(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        outcome = when (currentOutcome) {
                            is RunOutcome.Success -> "success"
                            is RunOutcome.Unstable -> "unstable"
                            is RunOutcome.Failure -> "failure"
                            is RunOutcome.Aborted -> "aborted"
                        },
                        diagnostics = emptyList(),
                    ),
                )
            }
        }
        return currentOutcome
    }

    /**
     * EM-5/EM-6 (catcherror-semantics-em56, D1/D2/D3/D5): folds a step outcome into a
     * continuation, publishing CatchErrorTriggered events at the point of a real failure.
     *
     * A real `StepOutcome.Failure` walks the active context stack from the innermost
     * CatchErrorOverlay outward: every enclosing catchError scope that observes the failure
     * publishes its own CatchErrorTriggered (its buildResult/stageResult/message). FAILURE
     * overlays re-throw outward (ERR-S-002 records then aborts at the outermost; ERR-S-007 lets
     * an enclosing default-UNSTABLE overlay re-catch). The first SUCCESS/UNSTABLE overlay
     * suppresses and stops the walk. An unstable()-only outcome is never a failure, so it never
     * enters the walk (ERR-S-008 emits no trigger).
     */
    private fun decideContinuation(outcome: StepOutcome, stageName: String, runIdValue: String): CanonicalContinuation =
        when (outcome) {
            StepOutcome.Success -> CanonicalContinuation.Continue
            StepOutcome.Unstable -> CanonicalContinuation.ContinueUnstable
            is StepOutcome.Failure -> walkCatchErrorChain(outcome.failure, stageName, runIdValue)
        }

    private fun walkCatchErrorChain(
        failure: PipelineFailure,
        stageName: String,
        runIdValue: String,
    ): CanonicalContinuation {
        val frames = contextStack.frames
        var i = frames.size - 1
        while (i >= 0 && frames[i] is ContextOverlay.CatchErrorOverlay) {
            val overlay = frames[i] as ContextOverlay.CatchErrorOverlay
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.CatchErrorTriggered(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdValue,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageName,
                    buildResult = overlay.buildResult,
                    stageResult = overlay.stageResult,
                    message = overlay.message,
                ),
            )
            when (overlay.buildResult) {
                "FAILURE" -> i-- // re-throw outward to the next enclosing catch scope
                "SUCCESS" -> return CanonicalContinuation.Continue
                else -> return CanonicalContinuation.ContinueUnstable
            }
        }
        // Exhausted enclosing catch scopes (or no catch overlay) without a suppressor: abort.
        return CanonicalContinuation.Abort(failure)
    }

    /**
     * Dispatches a step, routing BlockStepNode to [dispatchBody].
     */
    private suspend fun dispatch(
        step: StepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        bodyPath: List<BlockSegment> = emptyList(),
    ): StepOutcome {
        // BlockStepNode bypasses decoder and goes directly to dispatchBody (EM-4).
        // EM-4 handles only the body-execution substrate for dir/withEnv/withCredentials/
        // timeout/retry. catchError and warnError remain on the legacy linear path
        // (rewriteWorkflowControl) until EM-5/EM-6 semantics are implemented.
        if (step is BlockStepNode) {
            return dispatchBody(step, runId, stageName, stageIndex, stepIndex, stageShOptions, bodyPath)
        }

        // CDE.2-c0: durable opId/input are needed by every rejection path, so derive them first.
        val opId = OpId(runId.value, stageIndex, stepIndex, bodyPath = bodyPath)
        val operationId = opId.format()
        val input = OperationInput(
            stepId = step.pluginStepId.value,
            params = mapOf("payload" to JsonPrimitive(step.payload.encoded)),
            runId = runId.value,
            attempt = 1,
        )

        // CDE.2-c0: structural phase (envelope gate + control overlay projection) precedes any typed
        // decode. A structurally-invalid node is a terminal SCHEMA rejection (C3/C5), executor never runs.
        val structural = CanonicalStructuralPreparation.prepare(step)
        val structuralReady = when (structural) {
            is StructuralPreparation.Rejected -> return rejectSchema(operationId, input, structural.reason)
            is StructuralPreparation.Ready -> structural
        }
        applyOverlay(
            StructuralOverlayProjection.project(structuralReady.invocation.stepKey, structuralReady.envelope),
        )

        // CDE.2-b2: durable metadata resolved by structural step key BEFORE typed semantics, so
        // fingerprint and reconcile never depend on the decoded command.
        val metadata = metadataResolver.resolve(step.pluginStepId)
            ?: throw EngineInvariantViolation("No durable metadata for canonical step '${step.pluginStepId.value}'")

        val fingerprint = Fingerprint.compute(input, step.pluginStepId.value, metadata.replayPolicy, 1)
        val lifecycleContext = StepLifecycleContext(
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = step.id.value,
            stepType = CanonicalCoreStepMetadata.shortType(step.pluginStepId.value),
        )
        val journaled = journal.get(operationId, 1)
        val currentOperation = RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        when (val resolution = reconcileInvocation(metadata, journaled, currentOperation, operationId)) {
            is InvocationReconciliation.Diverged ->
                return StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                        "Canonical run diverged at '${resolution.operationId}'",
                    ),
                )
            is InvocationReconciliation.RecoverRunning -> {
                val executionResult = StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                    CommonExecutionResult(outcome = resolution.outcome, encodedOutput = null)
                }
                val outcome = executionResult.outcome
                journal.append(
                    RerunOperation(
                        id = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        output = null,
                        status = resolution.status,
                        attempt = 1,
                    ),
                )
                if (outcome is StepOutcome.Success) cursorStore.advance(runId.value, operationId, stageIndex)
                return outcome
            }
            InvocationReconciliation.ReuseCompleted -> return StepOutcome.Success
            is InvocationReconciliation.RejectedAbort ->
                return StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                    CommonExecutionResult(
                        outcome = StepOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                                "Replay aborted for '${resolution.operationId}'",
                            ),
                        ),
                        encodedOutput = null,
                    )
                }.outcome
            // Execute is the ONLY resolution that reaches the effective executor. beginOperation, the
            // StepExecutionBoundary-wrapped executor call, the terminal journal write and cursor advance
            // live here, so the concrete semantics are invoked exclusively under this decision.
            InvocationReconciliation.Execute -> {
                // CDE.3-e4.3: runtime context is hoisted here so registry prepare can derive the
                // available capabilities from the capability bridge (never the raw context to a handler).
                val runtime = CanonicalRuntimeContext(
                    opId = opId,
                    runId = runId.value,
                    stageName = stageName,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    shOptions = stageShOptions,
                    controlDirRoot = controlDirRoot,
                    eventSink = eventSink,
                )

                // CDE.2-c/d + CDE.3-b3/e4.3: strategy preparation runs ONLY on actual execution and NEVER
                // produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
                // Registry), never by concrete step name. Reuse/divergence/recover never prepare. A
                // Rejected admission (typed field / unsupported command / missing capability) is a
                // terminal SCHEMA rejection (journal FAILED, common executor never runs).
                val family = StructuralFamilyResolver.classify(step.pluginStepId, stepRegistry)
                val prepared = when (family) {
                    StructuralStepFamily.LegacyCore -> when (val admission = LegacyExecutionBoundary.prepare(step)) {
                        is ExecutionPreparation.Rejected -> return rejectSchema(
                            operationId,
                            input,
                            "schema mismatch for step '${step.pluginStepId.value}' on '${step.id.value}': ${admission.reason}",
                        )
                        is ExecutionPreparation.Ready -> admission.prepared
                    }
                    StructuralStepFamily.Registry -> {
                        val registry = stepRegistry ?: throw EngineInvariantViolation(
                            "registry family step '${step.pluginStepId.value}' reached Execute without a StepRegistry",
                        )
                        val admission = RegistryExecutionPreparation.prepare(
                            registry = registry,
                            key = step.pluginStepId,
                            encodedInput = EncodedStepValue(step.payload.encoded),
                            availableCapabilities = CanonicalRuntimeCapabilityAccess(runtime).available(),
                        )
                        when (admission) {
                            is ExecutionPreparation.Rejected -> return rejectSchema(
                                operationId,
                                input,
                                "schema mismatch for step '${step.pluginStepId.value}' on '${step.id.value}': ${admission.reason}",
                            )
                            is ExecutionPreparation.Ready -> admission.prepared
                        }
                    }
                }

                if (journaled == null) {
                    journal.beginOperation(operationId, 1, fingerprint.hex, Json.encodeToString(input))
                }

                val executionStartMs = System.currentTimeMillis()
                val executionResult = StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                    executionBoundary.execute(prepared, runtime)
                }
                val outcome = executionResult.outcome
                val executionEndMs = System.currentTimeMillis()
                journal.append(
                    RerunOperation(
                        id = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        output = executionResult.encodedOutput?.let {
                            OperationOutput(
                                result = JsonPrimitive(it.value),
                                durationMs = executionEndMs - executionStartMs,
                                finishedAt = executionEndMs,
                            )
                        },
                        status = outcome.toOperationStatus(),
                        attempt = 1,
                    ),
                )
                if (outcome !is StepOutcome.Failure) cursorStore.advance(runId.value, operationId, stageIndex)
                return outcome
            }
        }
    }

    /**
     * Applies the pre-reconcile control overlay projected from the structural envelope (CDE.2-c0),
     * establishing or closing a CatchError context frame. This runs BEFORE durable resolution, so a
     * reused CatchErrorEntered still establishes its scope without re-executing (C6: executor stays 0).
     * Only the structural overlay descriptor is consumed; no typed command is built here.
     */
    private fun applyOverlay(overlay: StructuralOverlay) {
        when (overlay) {
            StructuralOverlay.None -> Unit
            is StructuralOverlay.CatchErrorEntered -> {
                contextStack = contextStack.push(
                    ContextOverlay.CatchErrorOverlay(
                        overlay.buildResult,
                        overlay.stageResult,
                        overlay.message,
                        overlay.enteredAt?.toLongOrNull() ?: System.currentTimeMillis(),
                    ),
                )
            }
            is StructuralOverlay.CatchErrorTriggered -> {
                if (overlay.emitted) {
                    val top = contextStack.peek()
                    if (top is ContextOverlay.CatchErrorOverlay) {
                        contextStack = contextStack.pop()
                    } else {
                        throw IllegalStateException(
                            "Context stack underflow: CatchErrorTriggered without matching CatchErrorEntered",
                        )
                    }
                }
            }
        }
    }

    /**
     * Records a FAILED journal row and returns the terminal `SCHEMA` [StepOutcome]; the effective
     * executor is never invoked (C3/C5). Fingerprint uses [ReplayPolicy.RERUN] as on the rejection path.
     */
    private fun rejectSchema(operationId: String, input: OperationInput, message: String): StepOutcome {
        val fingerprint = Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, 1)
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = OperationStatus.FAILED,
                attempt = 1,
            ),
        )
        return StepOutcome.Failure(
            PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, message),
        )
    }

    /**
     * Resolves the durable replay/reconcile decision for an invocation (B1.2c2-a2.3, CDE.2-b4).
     *
     * The decision has two purity domains, kept separate:
     *  - [deterministicGate] is pure: fingerprint divergence and the effect-aware replay policy decide
     *    from their inputs alone.
     *  - running-process detection ([recoverRunningShell]) is the sole effectful part: it inspects and
     *    reattaches to a real external process. It is an explicit a2 compatibility hook, NOT generic
     *    durable-protocol semantics; it triggers only when the operation declares
     *    [RecoveryPolicy.ExternalSubprocess] AND the journal is RUNNING AND a control dir exists.
     *
     * The durable decision consumes only the typed [StepMetadata] properties resolved by step key
     * (CDE.2-b2/b4); it never selects behaviour by a concrete Step name.
     *
     * Precedence reproduces the frozen a1 flow exactly: divergence first, then recovery, then replay.
     * No journal, cursor, event or executor is touched here; terminal resolutions carry only the data
     * their own handling needs.
     */
    private fun reconcileInvocation(
        metadata: StepMetadata,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        currentOperation: RerunOperation,
        operationId: String,
    ): InvocationReconciliation {
        deterministicGate(currentOperation, journaled, operationId, metadata.effects, metadata.replayPolicy)?.let { return it }
        when (val recovery = recoverRunningShell(metadata.recoveryPolicy, journaled, operationId)) {
            RunningCanonicalShellRecovery.NotRunningShell -> Unit
            is RunningCanonicalShellRecovery.Recovered ->
                return InvocationReconciliation.RecoverRunning(recovery.outcome, recovery.status)
        }
        return replayResolution(effectReplayPolicy.decide(metadata.replayPolicy, metadata.effects, journaled != null, journaled?.status), operationId)
    }

    /**
     * Pure, deterministic part of the reconciliation: the fingerprint-divergence gate (B1.2c2-a2.3).
     * Returns a terminal divergence resolution when the fingerprints diverge, otherwise `null` so the
     * recovery hook and replay kernel can run in the frozen order. Never touches a journal, cursor,
     * process, event or executor.
     */
    private fun deterministicGate(
        currentOperation: RerunOperation,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        operationId: String,
        effects: Set<Effect>,
        replayPolicy: ReplayPolicy,
    ): InvocationReconciliation? {
        if (divergenceDetector.check(currentOperation, journaled).isFailure) {
            return InvocationReconciliation.Diverged(operationId)
        }
        return null
    }

    /**
     * Pure mapping of the effect-aware replay policy onto the reconciliation resolutions (B1.2c2-a2.3).
     */
    private fun replayResolution(decision: ReplayDecision, operationId: String): InvocationReconciliation =
        when (decision) {
            ReplayDecision.SKIP -> InvocationReconciliation.ReuseCompleted
            ReplayDecision.ABORT -> InvocationReconciliation.RejectedAbort(operationId)
            ReplayDecision.RERUN -> InvocationReconciliation.Execute
        }

    private fun recoverRunningShell(
        recoveryPolicy: RecoveryPolicy,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        operationId: String,
    ): RunningCanonicalShellRecovery {
        if (recoveryPolicy != RecoveryPolicy.ExternalSubprocess || journaled?.status != OperationStatus.RUNNING || controlDirRoot == null) {
            return RunningCanonicalShellRecovery.NotRunningShell
        }

        val reconciler = StepReconcilerL1(clock, controlDirRoot)
        val classification = reconciler.classify(operationId)
        return when (classification) {
            is StepReconcilerL1.Classification.Complete -> completedShellOutcome(classification.exitCode)
            is StepReconcilerL1.Classification.Reattach -> {
                val exitCode = DurableShellExecutor().pollResult(classification.controlDir, REATTACH_TIMEOUT_MS)
                if (exitCode == null) lostShellOutcome(operationId) else completedShellOutcome(exitCode)
            }
            is StepReconcilerL1.Classification.TimedOut -> RunningCanonicalShellRecovery.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT, "Canonical shell '$operationId' timed out"),
                ),
                OperationStatus.FAILED_TIMEOUT,
            )
            StepReconcilerL1.Classification.Lost -> lostShellOutcome(operationId)
        }
    }

    private fun completedShellOutcome(exitCode: Int): RunningCanonicalShellRecovery.Recovered =
        if (exitCode == 0) {
            RunningCanonicalShellRecovery.Recovered(StepOutcome.Success, OperationStatus.SUCCEEDED)
        } else {
            RunningCanonicalShellRecovery.Recovered(
                StepOutcome.Failure(
                    PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, "Canonical shell exited with code $exitCode"),
                ),
                OperationStatus.FAILED,
            )
        }

    private fun lostShellOutcome(operationId: String): RunningCanonicalShellRecovery.Recovered =
        RunningCanonicalShellRecovery.Recovered(
            StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, "Canonical shell '$operationId' could not be reconciled"),
            ),
            OperationStatus.LOST,
        )

    /**
     * +1 helper for INC-007 (canonical coordinator dispatchBody sibling).
     *
     * Dispatches a BlockStepNode's body children with fresh per-child journal rows
     * keyed by length-prefix bodyPath (JEP-029 exactly-once).
     *
     * @param block The BlockStepNode to dispatch
     * @param parentStack The context stack at entry (restored in finally)
     */
    /**
     * B13/E-EM-11 — parallel stage execution through the canonical spine.
     *
     * Each [StageBody.Parallel] branch is dispatched concurrently by the SAME
     * [dispatch] machinery used for linear steps; branch identity is the
     * branch-indexed OpId (`-b{N}` journal keys, pre-existing contract), so
     * every branch child gets independent durable rows and a durable rerun
     * reuses completed branch work instead of duplicating it.
     *
     * Join policy: ALL_COMPLETE (grounded in the surviving domain JoinPolicy
     * contract + the coordinator's step fail-fast semantics) — a failing branch
     * fails the aggregate; the join WAITS for all started branches so sibling
     * outcomes stay independent and journaled. No undeclared failFast.
     */
    private suspend fun runParallelStage(
        stage: StageNode,
        stageIndex: Int,
        stageShOptions: ShOptions,
        runId: RunId,
    ): StepOutcome {
        val branches = (stage.body as? StageBody.Parallel)?.branches
            ?: throw EngineInvariantViolation("runParallelStage called for non-parallel stage '${stage.name}'")

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val deferred: List<kotlinx.coroutines.Deferred<StepOutcome>> = branches.mapIndexed { branchIndex: Int, branch: StageNode ->
            scope.async {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                        ),
                    )
                    val branchOutcome = executeBranchSteps(branch, runId, stageIndex, branchIndex, stageShOptions)
                    val outcomeText = when (branchOutcome) {
                        is StepOutcome.Failure -> "failure"
                        is StepOutcome.Unstable -> "unstable"
                        else -> "success"
                    }
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchFinished(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                            outcome = outcomeText,
                        ),
                    )
                    branchOutcome
                }
        }
        val branchOutcomes: List<StepOutcome> = deferred.map { it.await() }

        // ALL_COMPLETE: first failure (deterministic: lowest branch index) is the aggregate.
        return branchOutcomes.firstOrNull { it is StepOutcome.Failure }
            ?: branchOutcomes.firstOrNull { it is StepOutcome.Unstable }
            ?: StepOutcome.Success
    }

    /**
     * Dispatches one parallel branch's linear steps through the shared spine with
     * branch-indexed journal identity. A branch failure is contained: it becomes
     * the branch outcome, never a throw (the join waits for all branches).
     */
    private suspend fun executeBranchSteps(
        branch: StageNode,
        runId: RunId,
        stageIndex: Int,
        branchIndex: Int,
        stageShOptions: ShOptions,
    ): StepOutcome {
        val steps = (branch.body as? StageBody.Steps)?.steps
            ?: throw EngineInvariantViolation("Parallel branch '${branch.name}' has a non-linear body")
        var outcome: StepOutcome = StepOutcome.Success
        for (stepIndex in steps.indices) {
            val step = steps[stepIndex]
            // Branch durable identity: dispatch() derives the journal key from the
            // bodyPath it is handed, so the branch index is encoded as the FIRST
            // deterministic BlockSegment ("b{branchIndex}:branch"). Two branches'
            // children can never collide; a durable rerun reuses the same keys.
            val bodyPath = listOf(
                BlockSegment("b$branchIndex:branch"),
                BlockSegment(stepIndex, step.pluginStepId),
            )
            val stepOutcome = dispatch(
                step,
                runId,
                branch.name,
                stageIndex,
                stepIndex,
                stageShOptions,
                bodyPath,
            )
            when (stepOutcome) {
                is StepOutcome.Failure, is StepOutcome.Unstable -> return stepOutcome
                else -> { /* continue */ }
            }
        }
        return outcome
    }

    private suspend fun dispatchBody(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
    ): StepOutcome {
        // EM-7/LFC-5.3 (INC-022): withCredentials has its own scope lifecycle
        // (acquire -> env overlay -> always close), so it bypasses the generic
        // dir/env/timestamps scope machinery entirely. Never dispatched as an
        // empty shell.
        if (block.pluginStepId.value == "core.withCredentials") {
            return dispatchWithCredentialsBlock(
                block,
                runId,
                stageName,
                stageIndex,
                stepIndex,
                stageShOptions,
                parentBodyPath,
            )
        }
        // Capture parent stack for finally restoration (BLOCK_STEP_EXECUTION.md §4 invariant)
        val parentStack = contextStack
        var outcome: StepOutcome = StepOutcome.Success
        val scope = try {
            block.projectShellScope(stageShOptions)
        } catch (error: IllegalArgumentException) {
            return StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, error.message ?: "Invalid block scope"),
            )
        }
        val childShOptions = when (scope) {
            BlockShellScope.None -> stageShOptions
            is BlockShellScope.Retry -> stageShOptions
            // B13/E-EM-11: block deadline becomes the child Sh watchdog budget —
            // the tighter of the block budget and any inherited stage timeout.
            is BlockShellScope.Timeout -> {
                val inherited = stageShOptions.timeoutMs
                val effective = when {
                    inherited == null -> scope.budgetMs
                    else -> minOf(inherited, scope.budgetMs)
                }
                stageShOptions.copy(timeoutMs = effective)
            }
            is BlockShellScope.Directory -> {
                Files.createDirectories(scope.target)
                contextStack = contextStack.push(ContextOverlay.Cwd(scope.target.toString()))
                eventSink.append(
                    DirEntered(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        path = scope.target.toString(),
                        previousPath = scope.previous.toString(),
                    ),
                )
                stageShOptions.copy(workingDirectory = scope.target)
            }
            is BlockShellScope.TimestampsScope -> {
                eventSink.append(
                    dev.rubentxu.pipeline.v2.events.TimestampsEntered(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                    ),
                )
                stageShOptions
            }
            is BlockShellScope.EnvScope -> {
                // Parse env overrides and merge into ShOptions.env
                val envOverrides = scope.overrides.associate { override ->
                    val parts = override.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0] to dev.rubentxu.pipeline.v2.domain.SecretHandle.plain(parts[1])
                    } else {
                        override to dev.rubentxu.pipeline.v2.domain.SecretHandle.plain("")
                    }
                }
                val mergedEnv = scope.parentEnv + envOverrides
                val envSpecValues = envOverrides.mapValues { it.value.borrow { bytes -> String(bytes, Charsets.UTF_8) } }
                contextStack = contextStack.push(ContextOverlay.Environment(dev.rubentxu.pipeline.v2.domain.EnvironmentSpec(envSpecValues)))
                stageShOptions.copy(env = mergedEnv)
            }
        }

        try {
            // B13/E-EM-11: `core.retry` re-dispatches the SAME body per attempt.
            // Each attempt appends a deterministic BlockSegment ("{attempt}:retry-attempt")
            // to the child bodyPath, so every attempt gets its own journal rows under
            // exactly-once OpId semantics; completed attempts are never re-executed on
            // restart/replay (journal lookup, not memory). Other scopes run the body once.
            //
            // RETRY-D (ADR-0075): when `retryControlJournal` is bound, the retry aggregate
            // is reconciled against durable state BEFORE each attempt dispatch. The legacy
            // in-memory counter is replaced by a control journal that survives restarts.
            // When the journal is NOT bound, the pre-RETRY-D inline loop is preserved
            // bit-equivalent — existing callers and tests see no change.
            if (scope is BlockShellScope.Retry && retryControlJournal != null) {
                outcome = dispatchRetryAwareBody(
                    scope = scope,
                    block = block,
                    runId = runId,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stageName = stageName,
                    stageShOptions = stageShOptions,
                    parentBodyPath = parentBodyPath,
                    childShOptions = childShOptions,
                )
            } else {
                val attemptCount = when (scope) {
                    is BlockShellScope.Retry -> scope.maxAttempts
                    else -> 1
                }
                var attempt = 1
                bodyLoop@ while (attempt <= attemptCount) {
                // Each attempt re-evaluates the body from scratch; a prior attempt's
                // failure must not survive a later successful attempt.
                outcome = StepOutcome.Success
                val attemptSegment = if (scope is BlockShellScope.Retry) {
                    listOf(BlockSegment(attempt, PluginStepId("retry-attempt")))
                } else emptyList()
                val attemptBasePath = parentBodyPath + attemptSegment

                if (scope is BlockShellScope.Retry) {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.RetryAttemptStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            attemptNumber = attempt,
                            maxAttempts = scope.maxAttempts,
                            stepName = block.id.value,
                            stepType = block.pluginStepId.value,
                            stageIndex = stageIndex,
                            stepIndex = stepIndex,
                        ),
                    )
                }

                for ((childIndex, child) in block.body.withIndex()) {
                    val childOpId = OpId(
                        runId.value,
                        stageIndex,
                        stepIndex,
                        branchIndex = null,
                        bodyPath = attemptBasePath + BlockSegment(childIndex, child.pluginStepId),
                    )

                    // Fresh StepLifecycleContext per child (JEP-029)
                    val childContext = StepLifecycleContext(
                        runId = runId.value,
                        stageIndex = stageIndex,
                        stepIndex = childIndex,
                        stepName = child.id.value,
                        stepType = child.pluginStepId.value,
                    )

                    val childOutcome = dispatch(
                        child,
                        runId,
                        stageName,
                        stageIndex,
                        stepIndex,
                        childShOptions,
                        childOpId.bodyPath,
                    )
                    when (childOutcome) {
                        is StepOutcome.Failure -> {
                            outcome = childOutcome
                            if (attempt < attemptCount) {
                                attempt++
                                continue@bodyLoop
                            }
                            break@bodyLoop // Stop on first failure after last attempt
                        }
                        is StepOutcome.Unstable -> {
                            outcome = childOutcome
                            break@bodyLoop
                        }
                        else -> { /* continue */ }
                    }
                }
                break@bodyLoop // body completed without failure — no extra attempt (WL-R2)
                }
            }
        } finally {
            if (scope is BlockShellScope.Directory) {
                eventSink.append(
                    DirExited(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        path = scope.target.toString(),
                        restoredTo = scope.previous.toString(),
                    ),
                )
            }
            if (scope is BlockShellScope.TimestampsScope) {
                eventSink.append(
                    dev.rubentxu.pipeline.v2.events.TimestampsExited(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                    ),
                )
            }
            // Restore parent context stack in finally (BLOCK_STEP_EXECUTION.md §4 invariant)
            contextStack = parentStack
        }

        return outcome
    }

    /**
     * RETRY-D (ADR-0075): dispatch the body of a `core.retry` block under the durable
     * control journal. The single-writer law applies: this function is the only path
     * that mutates [FileBasedRetryControlJournal] for the retry aggregate; the driver
     * is plan-only.
     *
     * Algorithm:
     *   1. Plan via [RetryReconciliationDriver] — a pure read of the journal.
     *   2. Branch on [RetryReconciliationDecision]:
     *      - Terminal (ReuseSuccess / ReuseFailure / CloseSuccessFromChild /
     *        RejectDivergence): return the corresponding StepOutcome.
     *      - ScheduleAttempt(n) / ResumeAttempt(n): persist RUNNING, dispatch the body
     *        children, persist SUCCEEDED / FAILED, and re-plan.
     *      - AdvanceAfterFailure(from, to): persist the new attempt row, re-plan.
     *
     * The loop is bounded by [scope.maxAttempts] + 1 ticks to guarantee progress.
     */
    private suspend fun dispatchRetryAwareBody(
        scope: BlockShellScope.Retry,
        block: BlockStepNode,
        runId: RunId,
        stageIndex: Int,
        stepIndex: Int,
        stageName: String,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        childShOptions: ShOptions,
    ): StepOutcome {
        val journal = retryControlJournal ?: return StepOutcome.Failure(
            PipelineFailure(
                dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
                "retry control journal not bound",
            ),
        )
        val controlOpId = RetryIdentityFactory.controlOperationId(runId.value, stageIndex, stepIndex, parentBodyPath)
        val fingerprint = computeRetryContractFingerprint(parentBodyPath, scope)
        val driver = RetryReconciliationDriver(
            journal = journal,
            identity = dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(operationId = controlOpId),
            controlOpId = controlOpId,
            parentBodyPath = parentBodyPath,
            fingerprint = fingerprint,
            maxAttempts = scope.maxAttempts,
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
        )

        var budget = scope.maxAttempts + 1
        while (budget-- > 0) {
            val decision = driver.plan()
            when (decision) {
                is RetryReconciliationDecision.ReuseSuccess -> return StepOutcome.Success
                is RetryReconciliationDecision.ReuseFailure -> return StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
                        "retry aggregate terminal failure at attempt ${decision.attempt}",
                    ),
                )
                is RetryReconciliationDecision.CloseSuccessFromChild -> {
                    journal.updateStatus(controlOpId, decision.attempt, OperationStatus.SUCCEEDED, fingerprint)
                    return StepOutcome.Success
                }
                is RetryReconciliationDecision.RejectDivergence -> return StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "retry control fingerprint divergence: ${decision.reason}",
                    ),
                )
                is RetryReconciliationDecision.ScheduleAttempt,
                is RetryReconciliationDecision.ResumeAttempt -> {
                    val attempt = when (decision) {
                        is RetryReconciliationDecision.ScheduleAttempt -> decision.attempt
                        is RetryReconciliationDecision.ResumeAttempt -> decision.attempt
                        else -> error("unreachable: handled by outer when")
                    }
                    journal.beginAttempt(controlOpId, attempt, fingerprint, OperationStatus.RUNNING)
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.RetryAttemptStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            attemptNumber = attempt,
                            maxAttempts = scope.maxAttempts,
                            stepName = block.id.value,
                            stepType = block.pluginStepId.value,
                            stageIndex = stageIndex,
                            stepIndex = stepIndex,
                        ),
                    )
                    val attemptSegment = listOf(BlockSegment(attempt, PluginStepId("retry-attempt")))
                    val attemptBasePath = parentBodyPath + attemptSegment

                    var attemptOutcome: StepOutcome = StepOutcome.Success
                    for ((childIndex, child) in block.body.withIndex()) {
                        val childOpId = OpId(
                            runId.value,
                            stageIndex,
                            stepIndex,
                            branchIndex = null,
                            bodyPath = attemptBasePath + BlockSegment(childIndex, child.pluginStepId),
                        )
                        val childOutcome = dispatch(
                            child,
                            runId,
                            stageName,
                            stageIndex,
                            stepIndex,
                            childShOptions,
                            childOpId.bodyPath,
                        )
                        when (childOutcome) {
                            is StepOutcome.Failure -> {
                                attemptOutcome = childOutcome
                                break
                            }
                            is StepOutcome.Unstable -> {
                                attemptOutcome = childOutcome
                                break
                            }
                            else -> { /* continue to next child */ }
                        }
                    }

                    when (attemptOutcome) {
                        is StepOutcome.Success -> {
                            persistAttemptTerminalTransition(
                                controlOpId, attempt, OperationStatus.SUCCEEDED, fingerprint,
                                parentBodyPath, runId, stageIndex, stepIndex, block, scope,
                            )
                            return StepOutcome.Success
                        }
                        is StepOutcome.Unstable -> {
                            persistAttemptTerminalTransition(
                                controlOpId, attempt, OperationStatus.FAILED, fingerprint,
                                parentBodyPath, runId, stageIndex, stepIndex, block, scope,
                            )
                            return attemptOutcome
                        }
                        is StepOutcome.Failure -> {
                            persistAttemptTerminalTransition(
                                controlOpId, attempt, OperationStatus.FAILED, fingerprint,
                                parentBodyPath, runId, stageIndex, stepIndex, block, scope,
                            )
                            if (attempt >= scope.maxAttempts) return attemptOutcome
                            // Loop again: the driver will emit AdvanceAfterFailure or ReuseFailure.
                        }
                    }
                }
                is RetryReconciliationDecision.AdvanceAfterFailure -> {
                    journal.beginAttempt(controlOpId, decision.to, fingerprint, OperationStatus.RUNNING)
                    // Loop again: the driver will emit ScheduleAttempt(decision.to).
                }
            }
        }
        return StepOutcome.Failure(
            PipelineFailure(
                dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
                "retry reconciliation loop exceeded budget (${scope.maxAttempts})",
            ),
        )
    }

    /**
     * E-EM-11 T2.1: project a retry attempt's terminal durable transition.
     * Persists the terminal status via the control journal and emits
     * [RetryAttemptFinished] only when the transition actually occurred
     * (RUNNING -> FAILED / RUNNING -> SUCCEEDED). Re-terminaling an attempt
     * that is already in the requested terminal state is a no-op: no event.
     * Replay paths (ReuseSuccess / ReuseFailure / CloseSuccessFromChild) do
     * not route through here, so replay never fabricates extra events.
     */
    private fun persistAttemptTerminalTransition(
        controlOpId: String,
        attempt: Int,
        status: OperationStatus,
        fingerprint: Fingerprint,
        parentBodyPath: List<BlockSegment>,
        runId: RunId,
        stageIndex: Int,
        stepIndex: Int,
        block: BlockStepNode,
        scope: BlockShellScope.Retry,
    ) {
        val priorStatus = retryControlJournal?.readState(
            controlOpId, runId.value, stageIndex, stepIndex,
            parentBodyPath = parentBodyPath,
            maxAttempts = scope.maxAttempts,
            currentFingerprint = fingerprint,
        )?.controlRows?.firstOrNull { it.attempt == attempt }?.status
        retryControlJournal?.updateStatus(controlOpId, attempt, status, fingerprint)
        val transitioned = priorStatus == null || priorStatus != status
        if (!transitioned) return
        val outcomeText = when (status) {
            OperationStatus.SUCCEEDED -> "succeeded"
            OperationStatus.FAILED -> "failed"
            else -> error("persistAttemptTerminalTransition requires a terminal status, got $status")
        }
        eventSink.append(
            dev.rubentxu.pipeline.v2.events.RetryAttemptFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                attemptNumber = attempt,
                maxAttempts = scope.maxAttempts,
                stepName = block.id.value,
                stepType = block.pluginStepId.value,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                outcome = outcomeText,
            ),
        )
    }

    /**
     * RETRY-D: deterministic fingerprint of a retry aggregate's contract.
     * The fingerprint is stable across attempts for a given parent bodyPath and
     * maxAttempts — divergence triggers [RetryReconciliationDecision.RejectDivergence].
     */
    private fun computeRetryContractFingerprint(
        parentBodyPath: List<BlockSegment>,
        scope: BlockShellScope.Retry,
    ): Fingerprint {
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = "core.retry",
            params = mapOf(
                "maxAttempts" to kotlinx.serialization.json.JsonPrimitive(scope.maxAttempts),
                "parentBodyPath" to kotlinx.serialization.json.JsonArray(
                    parentBodyPath.map {
                        kotlinx.serialization.json.JsonPrimitive(it.encoded)
                    },
                ),
            ),
            runId = "retry-contract", // Stable per-aggregate, NOT per-attempt.
            attempt = 1,
        )
        return Fingerprint.compute(input, "core.retry", ReplayPolicy.MEMOIZED, 1)
    }

    /**
     * EM-7/LFC-5.3 (INC-022) — resolves and acquires the withCredentials scope.
     *
     * Decodes the typed [CredentialBindingSpec] list from the node payload
     * (fail-closed on malformed input -> schema Failure) and acquires a scope via
     * [credentialScopePort]. On Unavailable/Invalid the body is NEVER dispatched
     * (fail-closed). On Acquired the body runs with the env overlay and the scope
     * is always closed; a scope whose cleanup fails folds the block outcome to a
     * typed operational Failure per design §73.
     */
    private suspend fun dispatchWithCredentialsBlock(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
    ): StepOutcome {
        val bindings: List<CredentialBindingSpec> = try {
            CredentialBindingsPayload.decode(block.payload.encoded)
        } catch (e: IllegalArgumentException) {
            return StepOutcome.Failure(
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    "withCredentials bindings invalid: ${e.message}",
                ),
            )
        }
        return when (val acquisition = credentialScopePort.acquire(bindings, runId)) {
            is CredentialScopeOutcome.Unavailable -> StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, acquisition.failure.describe()),
            )
            is CredentialScopeOutcome.Invalid -> StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA, acquisition.failure.describe()),
            )
            is CredentialScopeOutcome.Acquired -> dispatchAcquiredWithCredentialsBody(
                scope = acquisition.scope,
                block = block,
                runId = runId,
                stageName = stageName,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stageShOptions = stageShOptions,
                parentBodyPath = parentBodyPath,
            )
        }
    }

    /**
     * EM-7/LFC-5.3 (INC-022) — executes the withCredentials body under the acquired env
     * overlay and always releases the scope (idempotent, reverse-LIFO).
     *
     * Mirrors the generic block child loop (fail-on-first Failure/Unstable) but under
     * `childShOptions` whose env is `stageShOptions.env + scope.env`. The acquired
     * env is tracked on the context stack (like core.withEnv) and restored in finally.
     * Cleanup runs in the same finally that restores the parent stack.
     */
    private suspend fun dispatchAcquiredWithCredentialsBody(
        scope: AcquiredCredentialScope,
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
    ): StepOutcome {
        val parentStack = contextStack
        val childShOptions = stageShOptions.copy(env = stageShOptions.env + scope.env)
        val envSpecValues = scope.env.mapValues { (_, handle) ->
            handle.borrow { bytes -> String(bytes, Charsets.UTF_8) }
        }
        contextStack = contextStack.push(
            ContextOverlay.Environment(dev.rubentxu.pipeline.v2.domain.EnvironmentSpec(envSpecValues)),
        )
        var bodyOutcome: StepOutcome = StepOutcome.Success
        val cleanup: CredentialScopeCleanup = try {
            for ((childIndex, child) in block.body.withIndex()) {
                val childOpId = OpId(
                    runId.value,
                    stageIndex,
                    stepIndex,
                    branchIndex = null,
                    bodyPath = parentBodyPath + BlockSegment(childIndex, child.pluginStepId),
                )
                val childOutcome = dispatch(
                    child,
                    runId,
                    stageName,
                    stageIndex,
                    stepIndex,
                    childShOptions,
                    childOpId.bodyPath,
                )
                when (childOutcome) {
                    is StepOutcome.Failure -> {
                        bodyOutcome = childOutcome
                        break // Stop on first failure
                    }
                    is StepOutcome.Unstable -> {
                        bodyOutcome = childOutcome
                        break
                    }
                    else -> { /* continue */ }
                }
            }
            scope.close()
        } finally {
            // Restore parent context stack in finally (BLOCK_STEP_EXECUTION.md §4 invariant)
            contextStack = parentStack
        }
        return mergeBodyAndCleanup(bodyOutcome, cleanup)
    }

    /**
     * EM-7/LFC-5.3 — folds a body outcome and the scope cleanup outcome into a single
     * total StepOutcome per design §73. Cleanup is idempotent and never throws.
     *
     * Total algebra: cleanup Failed over Success, Unstable or Failure always yields a
     * typed operational Failure (INFRASTRUCTURE). Cleaned leaves the body outcome intact.
     */
    private fun mergeBodyAndCleanup(bodyOutcome: StepOutcome, cleanup: CredentialScopeCleanup): StepOutcome =
        when (cleanup) {
            CredentialScopeCleanup.Cleaned -> bodyOutcome
            is CredentialScopeCleanup.Failed -> StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, cleanup.message),
            )
        }

    private fun CredentialScopeFailure.describe(): String = when (this) {
        is CredentialScopeFailure.StoreUnavailable -> message
        is CredentialScopeFailure.CredentialMissing -> "Credential '${credentialsId.value}' is not present in the store"
        is CredentialScopeFailure.BindingMismatch -> message
        is CredentialScopeFailure.AcquisitionFailed -> message
        CredentialScopeFailure.ReplayUnsupported -> "Replay of an in-flight credential scope is not supported"
    }

    private companion object {
        const val REATTACH_TIMEOUT_MS = 60_000L
    }

    /**
     * +1 sibling helper for INC-007 (canonical coordinator catchError overlay handler).
     *
     * catchError semantics: executes the body. If body fails and buildResult != "FAILURE",
     * the failure is suppressed and the step succeeds (with UNSTABLE). If buildResult ==
     * "FAILURE", the failure is propagated. If body succeeds, step succeeds.
     *
     * This is the EM-4 canonical body-execution IR replacement for the legacy
     * rewriteWorkflowControl linearization (core.emit.event + shell + core.emit.event).
     */
}
