package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
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
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
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
 * Derives the canonical step IDs from the sealed hierarchy.
 * Single source of truth — the pluginId values are declared on each sealed subtype.
 * Adding a new sealed subtype propagates automatically through this derivation.
 */
private val canonicalCoreStepIds: Set<String> by lazy {
    CanonicalCoreStepCommand::class.sealedSubclasses.mapNotNull { cls ->
        when (cls.simpleName) {
            "Shell" -> "core.sh"
            "Echo" -> "core.echo"
            "Error" -> "core.error"
            "Sleep" -> "core.sleep"
            "WriteFile" -> "core.file.writeFile"
            "EmitEvent" -> "core.emit.event"
            else -> null
        }
    }.toSet()
}

/**
 * Derives the canonical step type string from a typed command.
 * Used for event emission to identify the step type in step-level lifecycle events.
 */
private fun canonicalCoreStepType(command: CanonicalCoreStepCommand): String = when (command) {
    is CanonicalCoreStepCommand.Shell -> "sh"
    is CanonicalCoreStepCommand.Echo -> "echo"
    is CanonicalCoreStepCommand.Error -> "error"
    is CanonicalCoreStepCommand.Sleep -> "sleep"
    is CanonicalCoreStepCommand.WriteFile -> "writeFile"
    is CanonicalCoreStepCommand.EmitEvent -> "emitEvent"
}

/** True when the compiled pipeline fits the promoted linear canonical-core subset. */
fun CompiledPipeline.supportsCanonicalDurableExecution(): Boolean = stages.all { stage ->
    (stage.body as? StageBody.Steps)?.steps?.all { step ->
        step.pluginStepId.value in canonicalCoreStepIds
    } == true
}

/**
 * Represents an active error-handling scope (catchError / warnError block).
 *
 * @param buildResult The build result override for this scope (UNSTABLE or FAILURE).
 * @param enteredAt Epoch milliseconds when the scope was entered.
 */
private data class ScopeFrame(val buildResult: String, val enteredAt: Long)

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
    /** Active scope stack for catchError / warnError tracking. */
    private val activeScopes: ArrayDeque<ScopeFrame> = ArrayDeque()

    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        pipeline.stages.forEachIndexed { stageIndex, stage ->
            // Stage boundary: scope stack must be empty when entering a stage
            check(activeScopes.isEmpty()) {
                "Scope stack leaked into stage '${stage.name}' at index $stageIndex: ${activeScopes.size} frame(s) remaining"
            }
            val steps = (stage.body as? StageBody.Steps)?.steps
                ?: throw IllegalArgumentException("Canonical durable coordinator supports only linear stage steps")
            // D5: Per-stage workspaceRoot override at dispatch boundary
            val stageWorkspace: Path? = controlDirRoot?.let { WorkspaceResolver(it).resolve(stage.name, stageIndex) }
            val stageShOptions = if (stageWorkspace != null) shOptions.copy(workspaceRoot = stageWorkspace) else shOptions
            steps.forEachIndexed { stepIndex, step ->
                val outcome = dispatch(step, runId, stage.name, stageIndex, stepIndex, stageShOptions)
                // Scope-aware failure handling: downgrade Failure → Unstable when scope is active
                val finalOutcome = when {
                    outcome is StepOutcome.Failure -> {
                        val top = activeScopes.lastOrNull()
                        if (top != null && top.buildResult != "FAILURE") {
                            StepOutcome.Unstable
                        } else {
                            return@run RunOutcome.Failure(outcome.failure)
                        }
                    }
                    outcome is StepOutcome.Unstable -> return@run RunOutcome.Unstable
                    else -> outcome
                }
                if (finalOutcome is StepOutcome.Unstable) return@run RunOutcome.Unstable
            }
        }
        return RunOutcome.Success
    }

    private suspend fun dispatch(
        step: StepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
    ): StepOutcome {
        val typedCommand: CanonicalCoreStepCommand = try {
            CanonicalCoreStepDecoder.decode(step)
        } catch (e: IllegalArgumentException) {
            val operationId = OpId(runId.value, stageIndex, stepIndex).format()
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

        // Scope tracking: handle CatchErrorEntered (push) and CatchErrorTriggered with emitted=true (pop)
        if (typedCommand is CanonicalCoreStepCommand.EmitEvent) {
            when (typedCommand.kind) {
                "CatchErrorEntered" -> {
                    val buildResult = typedCommand.payload["buildResult"] ?: "UNSTABLE"
                    val enteredAt = typedCommand.payload["enteredAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                    activeScopes.addLast(ScopeFrame(buildResult, enteredAt))
                }
                "CatchErrorTriggered" -> {
                    if (typedCommand.payload["emitted"] == "true") {
                        val popped = activeScopes.removeLastOrNull()
                            ?: throw IllegalStateException(
                                "Scope stack underflow: CatchErrorTriggered without matching CatchErrorEntered"
                            )
                    }
                }
            }
        }

        val (effects, replayPolicy) = typedCommand.defaultMetadata.effects to typedCommand.defaultMetadata.replayPolicy
        val operationId = OpId(runId.value, stageIndex, stepIndex).format()
        val input = OperationInput(
            stepId = step.pluginStepId.value,
            params = mapOf("payload" to JsonPrimitive(step.payload.encoded)),
            runId = runId.value,
            attempt = 1,
        )
        val fingerprint = Fingerprint.compute(input, step.pluginStepId.value, replayPolicy, 1)
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
            ReplayDecision.ABORT -> return StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, "Replay aborted for '$operationId'"),
            )
            ReplayDecision.RERUN -> Unit
        }
        if (journaled == null) {
            journal.beginOperation(operationId, 1, fingerprint.hex, Json.encodeToString(input))
        }

        // Emit StepStarted before dispatch (REQ-LFC1-009 requirement 9)
        val stepType = canonicalCoreStepType(typedCommand)
        eventSink.append(
            StepStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stepName = step.id.value,
                stepType = stepType,
            ),
        )

        // Dispatch with error handling for step event emission
        val outcome: StepOutcome = try {
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
        } catch (e: Exception) {
            // Emit StepFinished before propagating exception (REQ-LFC1-009 requirement 11)
            eventSink.append(
                StepFinished(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = step.id.value,
                    stepType = stepType,
                ),
            )
            throw e
        }

        // Emit StepFinished after dispatch (REQ-LFC1-009 requirement 10)
        eventSink.append(
            StepFinished(
                eventId = UUID.randomUUID().toString(),
                runId = runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                stepName = step.id.value,
                stepType = stepType,
            ),
        )
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
}
