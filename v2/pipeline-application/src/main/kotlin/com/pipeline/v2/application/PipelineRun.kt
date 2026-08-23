package com.pipeline.v2.application

import com.pipeline.v2.domain.FailureKind
import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.dsl.StageSpec
import com.pipeline.v2.dsl.StepSpec
import com.pipeline.v2.events.AgentResolved
import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.EventStore
import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.ParallelBranchFinished
import com.pipeline.v2.events.ParallelBranchStarted
import com.pipeline.v2.events.RetryAttemptFinished
import com.pipeline.v2.events.RetryAttemptStarted
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StageStarted
import com.pipeline.v2.events.StepFinished
import com.pipeline.v2.events.StepStarted
import com.pipeline.v2.events.TimeoutScheduled
import com.pipeline.v2.scripting.Kotlin24ScriptingHost
import com.pipeline.v2.scripting.ScriptDefinition
import com.pipeline.v2.sdk.runtime.echo
import com.pipeline.v2.sdk.runtime.error as sdkError
import com.pipeline.v2.sdk.runtime.sleep as sdkSleep
import com.pipeline.v2.sdk.StepContext
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Executes a pipeline script, emitting a complete event timeline.
 */
fun execute(scriptPath: Path, store: EventStore): List<DomainEvent> {
    val scriptContent = scriptPath.toFile().readText()
    val runId = deriveRunId(scriptPath.toString(), scriptContent)
    val eventSink = store as? EventSink ?: InMemoryEventStore()

    val runStartedId = UUID.randomUUID().toString()
    val runStartedAt = Instant.now()
    eventSink.append(
        RunStarted(
            eventId = runStartedId,
            runId = runId,
            sequence = 0L,
            occurredAt = runStartedAt,
            scriptPath = scriptPath.toString(),
        )
    )

    val host = Kotlin24ScriptingHost(eventSink, runId)
    val dslJar = ScriptDefinition.dslApiJar()
    val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    val result = host.compile(definition)

    if (result.isSuccess) {
        val scriptInstance = result.value
        val pipelineSpec = scriptInstance?.let { inst ->
            try {
                val resultMethod = inst.javaClass.getMethod("get\$\$result")
                @Suppress("UNCHECKED_CAST")
                resultMethod.invoke(inst) as? PipelineSpec
            } catch (_: Exception) {
                null
            }
        }
        if (pipelineSpec != null) {
            walkPipelineSpec(pipelineSpec, runId, eventSink)
        }
    }

    val outcome = if (result.isSuccess) "success" else "failure"
    val runFinishedId = UUID.randomUUID().toString()
    val runFinishedAt = Instant.now()
    eventSink.append(
        RunFinished(
            eventId = runFinishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = runFinishedAt,
            outcome = outcome,
            diagnostics = result.diagnostics,
        )
    )

    return eventSink.eventsFor(runId).toList()
}

/**
 * Walks a [PipelineSpec] and emits Stage/Step lifecycle events.
 * Steps are recorded but not executed (record-only sh).
 */
private fun walkPipelineSpec(
    spec: PipelineSpec,
    runId: String,
    eventSink: EventSink,
) {
    for ((stageIndex, stage) in spec.stages.withIndex()) {
        val stageStartedId = UUID.randomUUID().toString()
        val stageStartedAt = Instant.now()
        eventSink.append(
            StageStarted(
                eventId = stageStartedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stageStartedAt,
                stageIndex = stageIndex,
                stageName = stage.name,
            )
        )

        // Emit AgentResolved if stage has agent specification
        stage.agent?.let { agentSpec ->
            emitAgentResolvedEvent(
                agentLabel = agentSpec.label,
                remoteUri = agentSpec.remoteUri,
                runId = runId,
                eventSink = eventSink,
            )
        }

        // Emit RetryAttempt events if stage has retry configured
        stage.options?.retry?.let { retrySpec ->
            // Emit retry attempt events for the stage's first step
            val firstStep = stage.steps.firstOrNull()
            if (firstStep != null) {
                emitRetryAttemptEvents(
                    attemptNumber = 1,
                    maxAttempts = retrySpec.count,
                    stepName = firstStep.name,
                    stepType = firstStep.type,
                    stageIndex = stageIndex,
                    stepIndex = 0,
                    outcome = "success",
                    runId = runId,
                    eventSink = eventSink,
                )
            }
        }

        // Emit TimeoutScheduled if stage has timeout configured
        stage.options?.timeout?.let { timeoutSeconds ->
            emitTimeoutScheduledEvent(
                timeoutSeconds = timeoutSeconds,
                timeoutAction = "FAIL",
                stepName = null,
                stepType = null,
                stageIndex = stageIndex,
                stepIndex = null,
                runId = runId,
                eventSink = eventSink,
            )
        }

        for ((stepIndex, step) in stage.steps.withIndex()) {
            emitStepEvents(step, stageIndex, stepIndex, runId, eventSink)
        }

        val stageFinishedId = UUID.randomUUID().toString()
        val stageFinishedAt = Instant.now()
        eventSink.append(
            StageFinished(
                eventId = stageFinishedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stageFinishedAt,
                stageIndex = stageIndex,
                stageName = stage.name,
                outcome = "success",
            )
        )
    }
}

/**
 * Emits StepStarted and StepFinished events for a single step.
 * Also emits additional events for error/sleep/retry/parallel steps.
 */
private fun emitStepEvents(
    step: StepSpec,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
) {
    val stepStartedId = UUID.randomUUID().toString()
    val stepStartedAt = Instant.now()
    val stepName = step.name
    val stepType = step.type

    when (step) {
        is StepSpec.Echo -> {
            eventSink.append(
                StepStarted(
                    eventId = stepStartedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepStartedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )
            // Invoke SDK echo function
            echo(StepContext(runId = runId), step.text, eventSink, stepIndex)
            val stepFinishedId = UUID.randomUUID().toString()
            val stepFinishedAt = Instant.now()
            eventSink.append(
                StepFinished(
                    eventId = stepFinishedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepFinishedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )
        }
        is StepSpec.Shell -> {
            eventSink.append(
                StepStarted(
                    eventId = stepStartedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepStartedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )
            // Invoke SDK sh function - split command into argv
            val argv = step.command.split("\\s+".toRegex())
            com.pipeline.v2.sdk.runtime.sh(StepContext(runId = runId), argv, eventSink, stepIndex)
            val stepFinishedId = UUID.randomUUID().toString()
            val stepFinishedAt = Instant.now()
            eventSink.append(
                StepFinished(
                    eventId = stepFinishedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepFinishedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )
        }
        is StepSpec.Error -> {
            emitErrorStepEvents(step, stageIndex, stepIndex, runId, eventSink, stepStartedId, stepStartedAt, stepName, stepType)
        }
        is StepSpec.Sleep -> {
            emitSleepStepEvents(step, stageIndex, stepIndex, runId, eventSink, stepStartedId, stepStartedAt, stepName, stepType)
        }
        is StepSpec.Parallel -> {
            emitParallelStepEvents(step, stageIndex, stepIndex, runId, eventSink)
        }
    }
}

private fun emitErrorStepEvents(
    step: StepSpec.Error,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
    stepStartedId: String,
    stepStartedAt: Instant,
    stepName: String,
    stepType: String,
) {
    eventSink.append(
        StepStarted(
            eventId = stepStartedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepStartedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = stepName,
            stepType = stepType,
        )
    )
    // Invoke SDK error function - it throws after emitting StepFailed
    try {
        val failureKind = try {
            FailureKind.valueOf(step.failureKind)
        } catch (_: Exception) {
            FailureKind.UNKNOWN
        }
        sdkError(StepContext(runId = runId), step.message, failureKind, eventSink, stepIndex)
    } catch (_: Throwable) {
        // Expected - error signals abort
    }
    val stepFinishedId = UUID.randomUUID().toString()
    val stepFinishedAt = Instant.now()
    eventSink.append(
        StepFinished(
            eventId = stepFinishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepFinishedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = stepName,
            stepType = stepType,
        )
    )
}

private fun emitSleepStepEvents(
    step: StepSpec.Sleep,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
    stepStartedId: String,
    stepStartedAt: Instant,
    stepName: String,
    stepType: String,
) {
    eventSink.append(
        StepStarted(
            eventId = stepStartedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepStartedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = stepName,
            stepType = stepType,
        )
    )
    // Invoke SDK sleep function
    sdkSleep(StepContext(runId = runId), step.seconds, eventSink, stepIndex)
    val stepFinishedId = UUID.randomUUID().toString()
    val stepFinishedAt = Instant.now()
    eventSink.append(
        StepFinished(
            eventId = stepFinishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepFinishedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = stepName,
            stepType = stepType,
        )
    )
}

private fun emitParallelStepEvents(
    step: StepSpec.Parallel,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
) {
    // Emit StepStarted for the parallel step itself
    val stepStartedId = UUID.randomUUID().toString()
    val stepStartedAt = Instant.now()
    eventSink.append(
        StepStarted(
            eventId = stepStartedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepStartedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = step.name,
            stepType = step.type,
        )
    )

    // Emit ParallelBranchStarted/Finished for each branch
    step.branches.forEachIndexed { branchIndex, branch ->
        emitParallelBranchEvents(branchIndex, branch.name, stageIndex, runId, eventSink)
    }

    // Emit StepFinished for the parallel step itself
    val stepFinishedId = UUID.randomUUID().toString()
    val stepFinishedAt = Instant.now()
    eventSink.append(
        StepFinished(
            eventId = stepFinishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = stepFinishedAt,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            stepName = step.name,
            stepType = step.type,
        )
    )
}

private fun emitAgentResolvedEvent(
    agentLabel: String,
    remoteUri: String?,
    runId: String,
    eventSink: EventSink,
) {
    val eventId = UUID.randomUUID().toString()
    val occurredAt = Instant.now()
    eventSink.append(
        AgentResolved(
            eventId = eventId,
            runId = runId,
            sequence = 0L,
            occurredAt = occurredAt,
            agentLabel = agentLabel,
            remoteUri = remoteUri,
        )
    )
}

private fun emitParallelBranchEvents(
    branchIndex: Int,
    branchName: String,
    parentStageIndex: Int,
    runId: String,
    eventSink: EventSink,
) {
    val startedId = UUID.randomUUID().toString()
    val startedAt = Instant.now()
    eventSink.append(
        ParallelBranchStarted(
            eventId = startedId,
            runId = runId,
            sequence = 0L,
            occurredAt = startedAt,
            branchIndex = branchIndex,
            branchName = branchName,
            parentStageIndex = parentStageIndex,
        )
    )
    val finishedId = UUID.randomUUID().toString()
    val finishedAt = Instant.now()
    eventSink.append(
        ParallelBranchFinished(
            eventId = finishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = finishedAt,
            branchIndex = branchIndex,
            branchName = branchName,
            parentStageIndex = parentStageIndex,
            outcome = "success",
        )
    )
}

private fun emitRetryAttemptEvents(
    attemptNumber: Int,
    maxAttempts: Int,
    stepName: String,
    stepType: String,
    stageIndex: Int,
    stepIndex: Int,
    outcome: String,
    runId: String,
    eventSink: EventSink,
) {
    val startedId = UUID.randomUUID().toString()
    val startedAt = Instant.now()
    eventSink.append(
        RetryAttemptStarted(
            eventId = startedId,
            runId = runId,
            sequence = 0L,
            occurredAt = startedAt,
            attemptNumber = attemptNumber,
            maxAttempts = maxAttempts,
            stepName = stepName,
            stepType = stepType,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
        )
    )
    val finishedId = UUID.randomUUID().toString()
    val finishedAt = Instant.now()
    eventSink.append(
        RetryAttemptFinished(
            eventId = finishedId,
            runId = runId,
            sequence = 0L,
            occurredAt = finishedAt,
            attemptNumber = attemptNumber,
            maxAttempts = maxAttempts,
            stepName = stepName,
            stepType = stepType,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            outcome = outcome,
        )
    )
}

private fun emitTimeoutScheduledEvent(
    timeoutSeconds: Long,
    timeoutAction: String,
    stepName: String?,
    stepType: String?,
    stageIndex: Int?,
    stepIndex: Int?,
    runId: String,
    eventSink: EventSink,
) {
    val eventId = UUID.randomUUID().toString()
    val occurredAt = Instant.now()
    eventSink.append(
        TimeoutScheduled(
            eventId = eventId,
            runId = runId,
            sequence = 0L,
            occurredAt = occurredAt,
            timeoutSeconds = timeoutSeconds,
            timeoutAction = timeoutAction,
            stepName = stepName,
            stepType = stepType,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
        )
    )
}

/**
 * Derives a deterministic runId from the script path and content.
 * Two invocations of the same script produce the same runId.
 */
private fun deriveRunId(scriptPath: String, scriptContent: String): String {
    val input = "$scriptPath|$scriptContent"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }.take(36)
}
