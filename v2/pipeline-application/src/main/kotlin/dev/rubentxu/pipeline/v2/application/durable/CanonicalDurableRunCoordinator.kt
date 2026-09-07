package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ContextStack
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
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
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * Canonical plugin IDs — sourced from the single registry in CanonicalCoreStepCommand.
 */
private val canonicalCoreStepIds: Set<String> = CanonicalCoreStepCommand.ALL_PLUGIN_IDS
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
fun CompiledPipeline.analyzeCanonicalDurableExecution(): List<NonCanonicalStep> {
    val nonCanonical = mutableListOf<NonCanonicalStep>()
    for ((stageIndex, stage) in stages.withIndex()) {
        val steps = (stage.body as? StageBody.Steps)?.steps ?: continue
        for ((stepIndex, step) in steps.withIndex()) {
            val issue = step.checkCanonicalExecution()
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
fun CompiledPipeline.supportsCanonicalDurableExecution(): Boolean =
    analyzeCanonicalDurableExecution().isEmpty()

private fun StepNode.checkCanonicalExecution(): String? {
    return when (this) {
        is BlockStepNode -> {
            if (pluginStepId.value !in canonicalBodyStepIds) {
                "block step plugin not in canonical body step IDs"
            } else {
                body.forEach { child ->
                    val childIssue = child.checkCanonicalExecution()
                    if (childIssue != null) return childIssue
                }
                null
            }
        }
        is OpaqueStepNode -> {
            if (pluginStepId.value !in canonicalCoreStepIds) {
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

private fun StepOutcome.continuation(contextStack: ContextStack): CanonicalContinuation = when (this) {
    StepOutcome.Success -> CanonicalContinuation.Continue
    StepOutcome.Unstable -> CanonicalContinuation.ContinueUnstable
    is StepOutcome.Failure -> when (val overlay = contextStack.peek()) {
        is ContextOverlay.CatchErrorOverlay -> when (overlay.buildResult) {
            "FAILURE" -> CanonicalContinuation.Abort(failure)
            "SUCCESS" -> CanonicalContinuation.Continue
            else -> CanonicalContinuation.ContinueUnstable
        }
        else -> CanonicalContinuation.Abort(failure)
    }
}

private sealed interface RunningCanonicalShellRecovery {
    data object NotRunningShell : RunningCanonicalShellRecovery
    data class Recovered(val outcome: StepOutcome, val status: OperationStatus) : RunningCanonicalShellRecovery
}

private sealed interface BlockShellScope {
    data object None : BlockShellScope
    data class Directory(val target: Path, val previous: Path) : BlockShellScope
    data class TimestampsScope(val runId: String) : BlockShellScope
    data class EnvScope(val overrides: List<String>, val parentEnv: Map<String, dev.rubentxu.pipeline.v2.domain.SecretHandle>) : BlockShellScope
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
    private val controlDirRoot: Path? = null,
    private val shOptions: ShOptions = ShOptions.EMPTY,
    private val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector(),
) {
    /** Active context stack for body scope tracking (EM-4). */
    private var contextStack: ContextStack = ContextStack.EMPTY

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
            for (stageIndex in pipeline.stages.indices) {
                val stage = pipeline.stages[stageIndex]
                // Stage boundary: context stack must be empty when entering a stage
                check(contextStack.isEmpty) {
                    "Scope stack leaked into stage '${stage.name}' at index $stageIndex: ${contextStack.size} frame(s) remaining"
                }
                val steps = (stage.body as? StageBody.Steps)?.steps
                    ?: throw IllegalArgumentException("Canonical durable coordinator supports only linear stage steps")
                // D5: Per-stage workspaceRoot override at dispatch boundary
                // C1: Workspace pre-creation - ensure stage workspace exists before shell dispatch
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
                for (stepIndex in steps.indices) {
                    val step = steps[stepIndex]
                    val outcome = dispatch(step, runId, stage.name, stageIndex, stepIndex, stageShOptions)
                    when (val continuation = outcome.continuation(contextStack)) {
                        CanonicalContinuation.Continue -> Unit
                        CanonicalContinuation.ContinueUnstable -> currentOutcome = RunOutcome.Unstable
                        is CanonicalContinuation.Abort -> {
                            currentOutcome = RunOutcome.Failure(continuation.failure)
                            return@run currentOutcome
                        }
                    }
                }
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

        val typedCommand: CanonicalCoreStepCommand = try {
            CanonicalCoreStepDecoder.decode(step)
        } catch (e: IllegalArgumentException) {
            val operationId = OpId(runId.value, stageIndex, stepIndex, bodyPath = bodyPath).format()
            val input = OperationInput(
                stepId = step.pluginStepId.value,
                params = mapOf("payload" to JsonPrimitive(step.payload.encoded)),
                runId = runId.value,
                attempt = 1,
            )
            val fingerprint = Fingerprint.compute(input, step.pluginStepId.value, ReplayPolicy.RERUN, 1)
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
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                    "schema mismatch for step '${step.pluginStepId.value}' on '${step.id.value}': ${e.message}",
                ),
            )
        }

        // Scope tracking via ContextOverlay (EM-4 migration from ScopeFrame)
        if (typedCommand is CanonicalCoreStepCommand.EmitEvent) {
            when (typedCommand.kind) {
                "CatchErrorEntered" -> {
                    val buildResult = typedCommand.payload["buildResult"] ?: "UNSTABLE"
                    val enteredAt = typedCommand.payload["enteredAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                    contextStack = contextStack.push(ContextOverlay.CatchErrorOverlay(buildResult, enteredAt))
                }
                "CatchErrorTriggered" -> {
                    if (typedCommand.payload["emitted"] == "true") {
                        val top = contextStack.peek()
                        if (top is ContextOverlay.CatchErrorOverlay) {
                            contextStack = contextStack.pop()
                        } else {
                            throw IllegalStateException(
                                "Context stack underflow: CatchErrorTriggered without matching CatchErrorEntered"
                            )
                        }
                    }
                }
            }
        }

        val (effects, replayPolicy) = typedCommand.defaultMetadata.effects to typedCommand.defaultMetadata.replayPolicy
        val opId = OpId(runId.value, stageIndex, stepIndex, bodyPath = bodyPath)
        val operationId = opId.format()
        val input = OperationInput(
            stepId = step.pluginStepId.value,
            params = mapOf("payload" to JsonPrimitive(step.payload.encoded)),
            runId = runId.value,
            attempt = 1,
        )
        val fingerprint = Fingerprint.compute(input, step.pluginStepId.value, replayPolicy, 1)
        val lifecycleContext = StepLifecycleContext(
            runId = runId.value,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = step.id.value,
            stepType = CanonicalCoreStepCommand.pluginIdToShortType(typedCommand.pluginId),
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
        if (divergenceDetector.check(currentOperation, journaled).isFailure) {
            return StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, "Canonical run diverged at '$operationId'"),
            )
        }
        when (val recovery = recoverRunningShell(typedCommand, journaled, operationId)) {
            RunningCanonicalShellRecovery.NotRunningShell -> Unit
            is RunningCanonicalShellRecovery.Recovered -> {
                val outcome = StepExecutionBoundary(eventSink).execute(lifecycleContext) { recovery.outcome }
                journal.append(
                    RerunOperation(
                        id = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        output = null,
                        status = recovery.status,
                        attempt = 1,
                    ),
                )
                if (outcome is StepOutcome.Success) cursorStore.advance(runId.value, operationId, stageIndex)
                return outcome
            }
        }
        when (effectReplayPolicy.decide(replayPolicy, effects, journaled != null, journaled?.status)) {
            ReplayDecision.SKIP -> return StepOutcome.Success
            ReplayDecision.ABORT -> return StepExecutionBoundary(eventSink).execute(lifecycleContext) {
                StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                        "Replay aborted for '$operationId'",
                    ),
                )
            }
            ReplayDecision.RERUN -> Unit
        }
        if (journaled == null) {
            journal.beginOperation(operationId, 1, fingerprint.hex, Json.encodeToString(input))
        }

        val outcome = StepExecutionBoundary(eventSink).execute(lifecycleContext) {
            dispatcher.dispatch(
                typedCommand,
                CanonicalRuntimeContext(
                    opId = opId,
                    runId = runId.value,
                    stageName = stageName,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    shOptions = stageShOptions,
                    controlDirRoot = controlDirRoot,
                    eventSink = eventSink,
                ),
            )
        }
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = outcome.toOperationStatus(),
                attempt = 1,
            ),
        )
        if (outcome !is StepOutcome.Failure) cursorStore.advance(runId.value, operationId, stageIndex)
        return outcome
    }

    private fun recoverRunningShell(
        command: CanonicalCoreStepCommand,
        journaled: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation?,
        operationId: String,
    ): RunningCanonicalShellRecovery {
        if (command !is CanonicalCoreStepCommand.Shell || journaled?.status != OperationStatus.RUNNING || controlDirRoot == null) {
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
    private suspend fun dispatchBody(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
    ): StepOutcome {
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
            for ((childIndex, child) in block.body.withIndex()) {
                val childOpId = OpId(
                    runId.value,
                    stageIndex,
                    stepIndex,
                    branchIndex = null,
                    bodyPath = parentBodyPath + BlockSegment(childIndex, child.pluginStepId)
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
                        break // Stop on first failure
                    }
                    is StepOutcome.Unstable -> {
                        outcome = childOutcome
                        break
                    }
                    else -> { /* continue */ }
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
