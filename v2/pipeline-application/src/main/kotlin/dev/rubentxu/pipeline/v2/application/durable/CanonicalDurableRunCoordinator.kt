package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

private val canonicalCoreStepIds = setOf(
    "core.sh", "core.echo", "core.error", "core.sleep",
    "core.file.writeFile", "core.emit.event",
)

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
    suspend fun run(pipeline: CompiledPipeline, runId: RunId): RunOutcome {
        pipeline.stages.forEachIndexed { stageIndex, stage ->
            val steps = (stage.body as? StageBody.Steps)?.steps
                ?: throw IllegalArgumentException("Canonical durable coordinator supports only linear stage steps")
            steps.forEachIndexed { stepIndex, step ->
                val outcome = dispatch(step, runId, stage.name, stageIndex, stepIndex)
                if (outcome is StepOutcome.Failure) return RunOutcome.Failure(outcome.failure)
                if (outcome is StepOutcome.Unstable) return RunOutcome.Unstable
            }
        }
        return RunOutcome.Success
    }

    private suspend fun dispatch(step: StepNode, runId: RunId, stageName: String, stageIndex: Int, stepIndex: Int): StepOutcome {
        val (effects, replayPolicy) = metadata(step)
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
        val typedCommand: CanonicalCoreStepCommand = try {
            CanonicalCoreStepDecoder.decode(step)
        } catch (e: IllegalArgumentException) {
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
        val outcome = dispatcher.dispatch(
            typedCommand,
            CanonicalRuntimeContext(
                opId = OpId(runId.value, stageIndex, stepIndex),
                runId = runId.value,
                stageName = stageName,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                shOptions = shOptions,
                controlDirRoot = controlDirRoot,
                eventSink = eventSink,
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

    private fun metadata(step: StepNode): Pair<Set<Effect>, ReplayPolicy> = when (step.pluginStepId.value) {
        "core.sh" -> setOf(Effect.EXECUTES_SUBPROCESS) to ReplayPolicy.RERUN
        "core.echo", "core.sleep" -> setOf(Effect.READ_ONLY) to ReplayPolicy.MEMOIZED
        "core.error" -> setOf(Effect.ABORTS_PIPELINE) to ReplayPolicy.NEVER
        "core.file.writeFile" -> setOf(Effect.WRITES_WORKSPACE) to ReplayPolicy.MEMOIZED
        "core.emit.event" -> setOf(Effect.READ_ONLY) to ReplayPolicy.MEMOIZED
        else -> throw IllegalArgumentException("Unsupported canonical core step '${step.pluginStepId.value}'")
    }
}
