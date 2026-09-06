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
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
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
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Canonical plugin IDs — sourced from the single registry in CanonicalCoreStepCommand.
 */
private val canonicalCoreStepIds: Set<String> = CanonicalCoreStepCommand.ALL_PLUGIN_IDS

/** True when the compiled pipeline fits the promoted linear canonical-core subset. */
fun CompiledPipeline.supportsCanonicalDurableExecution(): Boolean = stages.all { stage ->
    (stage.body as? StageBody.Steps)?.steps?.all { step ->
        step.pluginStepId.value in canonicalCoreStepIds
    } == true
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
                val stageShOptions = if (stageWorkspace != null) shOptions.copy(workspaceRoot = stageWorkspace) else shOptions
                for (stepIndex in steps.indices) {
                    val step = steps[stepIndex]
                    val outcome = dispatch(step, runId, stage.name, stageIndex, stepIndex, stageShOptions)
                    // Scope-aware failure handling: downgrade Failure → Unstable when scope is active
                    when {
                        outcome is StepOutcome.Failure -> {
                            val top = contextStack.peek()
                            if (top is ContextOverlay.CatchErrorOverlay && top.buildResult != "FAILURE") {
                                currentOutcome = RunOutcome.Unstable
                                return@run RunOutcome.Unstable
                            } else {
                                currentOutcome = RunOutcome.Failure(outcome.failure)
                                return@run currentOutcome
                            }
                        }
                        outcome is StepOutcome.Unstable -> {
                            currentOutcome = RunOutcome.Unstable
                            return@run RunOutcome.Unstable
                        }
                        else -> { /* continue */ }
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
    ): StepOutcome {
        // BlockStepNode bypasses decoder and goes directly to dispatchBody (EM-4).
        // EM-4 handles only the body-execution substrate for dir/withEnv/withCredentials/
        // timeout/retry. catchError and warnError remain on the legacy linear path
        // (rewriteWorkflowControl) until EM-5/EM-6 semantics are implemented.
        if (step is BlockStepNode) {
            return dispatchBody(step, runId, stageName, stageIndex, stepIndex, stageShOptions, emptyList())
        }

        val typedCommand: CanonicalCoreStepCommand = try {
            CanonicalCoreStepDecoder.decode(step)
        } catch (e: IllegalArgumentException) {
            val operationId = OpId(runId.value, stageIndex, stepIndex).legacyFormat()
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
        val operationId = OpId(runId.value, stageIndex, stepIndex).legacyFormat()
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
                    opId = OpId(runId.value, stageIndex, stepIndex),
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
                status = if (outcome is StepOutcome.Success) OperationStatus.SUCCEEDED else OperationStatus.FAILED,
                attempt = 1,
            ),
        )
        if (outcome !is StepOutcome.Failure) cursorStore.advance(runId.value, operationId, stageIndex)
        return outcome
    }

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

                // Check replay status for this child
                val journaled = journal.get(childOpId.format(), 1)
                val replayDecision = effectReplayPolicy.decide(
                    ReplayPolicy.RERUN,
                    emptySet(),
                    journaled != null,
                    journaled?.status
                )

                when (replayDecision) {
                    ReplayDecision.SKIP -> continue // Child already succeeded, skip
                    ReplayDecision.ABORT -> {
                        outcome = StepOutcome.Failure(
                            PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                                "Replay aborted for body child '${child.id.value}'",
                            ),
                        )
                        break
                    }
                    ReplayDecision.RERUN -> {
                        // Execute child
                        val childOutcome = dispatch(child, runId, stageName, stageIndex, childIndex, stageShOptions)
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
                }
            }
        } finally {
            // Restore parent context stack in finally (BLOCK_STEP_EXECUTION.md §4 invariant)
            contextStack = parentStack
        }

        return outcome
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
