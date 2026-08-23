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
import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.DivergenceException
import com.pipeline.v2.domain.durable.DurableOperation
import com.pipeline.v2.domain.durable.Fingerprint
import com.pipeline.v2.domain.durable.MemoizedOperation
import com.pipeline.v2.domain.durable.OperationInput
import com.pipeline.v2.domain.durable.OperationOutput
import com.pipeline.v2.domain.durable.OperationStatus
import com.pipeline.v2.domain.durable.RerunOperation
import com.pipeline.v2.domain.durable.ReplayPolicy as DomainReplayPolicy
import com.pipeline.v2.events.durable.OperationJournal
import com.pipeline.v2.events.durable.ReplayCursorStore
import com.pipeline.v2.sdk.Effect
import com.pipeline.v2.sdk.ReplayPolicy
import com.pipeline.v2.sdk.runtime.echo
import com.pipeline.v2.sdk.runtime.error as sdkError
import com.pipeline.v2.sdk.runtime.sleep as sdkSleep
import com.pipeline.v2.sdk.runtime.sh
import com.pipeline.v2.sdk.runtime.ShellResult
import com.pipeline.v2.sdk.StepContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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

    val runOutcome = AtomicReference("success")
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
            walkPipelineSpec(pipelineSpec, runId, eventSink, runOutcome)
        }
    }

    // If compilation failed, the run itself is a failure
    if (!result.isSuccess) {
        runOutcome.set("failure")
    }
    val outcome = runOutcome.get()
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
    runOutcome: AtomicReference<String>,
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
            emitStepEvents(step, stageIndex, stepIndex, runId, eventSink, runOutcome)
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
                outcome = runOutcome.get(),
            )
        )
    }
}

/**
 * Durable walk: extends [walkPipelineSpec] with full replay/diverge gating.
 *
 * Per [design.md §4.4], for each step this function:
 * 1. Computes the operation fingerprint from input + stepId + replayPolicy + attempt
 * 2. Looks up any prior journaled operation by opId
 * 3. Runs [DivergenceDetector.check] — fail-closed on fingerprint mismatch
 * 4. Calls [com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide] to SKIP / RERUN / ABORT
 * 5. On RERUN: executes the SDK step, appends [DurableOperation] to [journal], advances [cursorStore]
 * 6. On SKIP: emits StepStarted/StepFinished events but bypasses the executor
 * 7. On ABORT: throws [DivergenceException]
 *
 * @param spec              The pipeline specification.
 * @param runId             The deterministic run identifier.
 * @param eventSink         Event sink for the event timeline.
 * @param journal           The operation journal for durable persistence.
 * @param cursorStore       The replay cursor store for resume support.
 * @param divergenceDetector The divergence detector for fail-closed checks.
 * @param effectReplayPolicy The effect-aware replay policy.
 * @param startFromStageIndex Stage index to resume from (0 for fresh run).
 * @param startFromStepIndex Step index to resume from (0 for fresh run).
 * @return The run outcome string ("success" or "failure").
 * @throws DivergenceException When divergence is detected or ABORT decision is returned.
 */
internal fun walkPipelineSpecDurable(
    spec: PipelineSpec,
    runId: String,
    eventSink: EventSink,
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    divergenceDetector: DivergenceDetector,
    effectReplayPolicy: com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    startFromStageIndex: Int = 0,
    startFromStepIndex: Int = 0,
): String {
    var runOutcome = "success"
    val runOutcomeRef = java.util.concurrent.atomic.AtomicReference(runOutcome)

    for ((stageIndex, stage) in spec.stages.withIndex()) {
        // Resume gate: skip stages before the cursor position
        if (stageIndex < startFromStageIndex) continue

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

        stage.agent?.let { agentSpec ->
            emitAgentResolvedEvent(
                agentLabel = agentSpec.label,
                remoteUri = agentSpec.remoteUri,
                runId = runId,
                eventSink = eventSink,
            )
        }

        stage.options?.retry?.let { retrySpec ->
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

        val stepsToProcess = if (stageIndex == startFromStageIndex) {
            stage.steps.drop(startFromStepIndex)
        } else {
            stage.steps
        }

        val firstStepIndex = if (stageIndex == startFromStageIndex) startFromStepIndex else 0
        for ((relativeIdx, step) in stepsToProcess.withIndex()) {
            val stepIndex = firstStepIndex + relativeIdx
            val stepOutcome = emitDurableStepEvents(
                step = step,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                runId = runId,
                eventSink = eventSink,
                journal = journal,
                cursorStore = cursorStore,
                divergenceDetector = divergenceDetector,
                effectReplayPolicy = effectReplayPolicy,
                runOutcomeRef = runOutcomeRef,
            )
            if (stepOutcome == "failure") {
                runOutcomeRef.set("failure")
            }
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
                outcome = runOutcomeRef.get(),
            )
        )

        if (runOutcomeRef.get() == "failure") {
            break // abort pipeline on failure
        }
    }

    return runOutcomeRef.get()
}

/**
 * Emits durable step events with full replay/diverge gating.
 *
 * @return The step outcome string.
 * @throws DivergenceException When ABORT decision is reached.
 */
private fun emitDurableStepEvents(
    step: StepSpec,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    divergenceDetector: DivergenceDetector,
    effectReplayPolicy: com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
): String {
    val (stepType, effects, domainPolicy) = stepTypeMetadata(step)
    val opId = "$runId-s$stageIndex-$stepIndex"
    val attempt = 1 // M3-R1: single attempt; retry deferred to M3-R2

    // Build OperationInput
    val params = stepToParams(step)
    val input = OperationInput(
        stepId = stepType,
        params = params,
        runId = runId,
        attempt = attempt,
    )

    // Compute fingerprint
    val fingerprint = Fingerprint.compute(input, stepType, domainPolicy, attempt)

    // Look up journaled operation
    val journaledOp = journal.get(opId)
    val hasJournalEntry = journaledOp != null

    // Build current operation for divergence check
    val currentOp: DurableOperation = if (journaledOp is MemoizedOperation) {
        MemoizedOperation(
            id = opId,
            fingerprint = fingerprint,
            input = input,
            output = journaledOp.output,
            status = journaledOp.status,
            attempt = attempt,
            cachedOutput = journaledOp.cachedOutput,
        )
    } else {
        RerunOperation(
            id = opId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = attempt,
        )
    }

    // Fail-closed divergence check
    val divergenceResult = divergenceDetector.check(currentOp, journaledOp)
    if (divergenceResult.isFailure) {
        throw divergenceResult.exceptionOrNull() as DivergenceException
    }

    // Map SDK ReplayPolicy to domain ReplayPolicy for the decision call
    val sdkPolicy = toSdkReplayPolicy(domainPolicy)
    val journaledOutcome = journaledOp?.status
    val decision = effectReplayPolicy.decide(sdkPolicy, effects, hasJournalEntry, journaledOutcome)

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

    return when (decision) {
        com.pipeline.v2.sdk.runtime.durable.ReplayDecision.SKIP -> {
            // Bypass executor; emit StepFinished with cached output
            emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, "success")
            "success"
        }
        com.pipeline.v2.sdk.runtime.durable.ReplayDecision.RERUN -> {
            // Execute step and journal
            val stepOutcome = executeDurableStep(step, stageIndex, stepIndex, runId, eventSink, runOutcomeRef)
            // Journal the operation
            val outputOp = RerunOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = if (stepOutcome == "success") OperationStatus.SUCCEEDED else OperationStatus.FAILED,
                attempt = attempt,
            )
            journal.append(outputOp)
            // Advance cursor after successful journal append (per R-C mitigation)
            cursorStore.advance(runId, opId, stageIndex)
            emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, stepOutcome)
            stepOutcome
        }
        com.pipeline.v2.sdk.runtime.durable.ReplayDecision.ABORT -> {
            // Emit StepFailed and throw
            emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, "failure")
            runOutcomeRef.set("failure")
            val divEx = DivergenceException(
                expected = fingerprint,
                actual = fingerprint,
                opId = opId,
                runId = runId,
                stageIndex = stageIndex,
            )
            throw divEx
        }
    }
}

/**
 * Executes a step and returns the outcome string.
 */
private fun executeDurableStep(
    step: StepSpec,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
): String {
    return try {
        when (step) {
            is StepSpec.Echo -> {
                echo(StepContext(runId = runId), step.text, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Shell -> {
                val argv = step.command.split("\\s+".toRegex())
                sh(StepContext(runId = runId), argv, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Sleep -> {
                sdkSleep(StepContext(runId = runId), step.seconds, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Error -> {
                val failureKind = try {
                    com.pipeline.v2.domain.FailureKind.valueOf(step.failureKind)
                } catch (_: Exception) {
                    com.pipeline.v2.domain.FailureKind.UNKNOWN
                }
                sdkError(StepContext(runId = runId), step.message, failureKind, eventSink, stepIndex)
                "success" // sdkError throws
            }
            is StepSpec.Parallel -> {
                emitParallelStepEvents(step, stageIndex, stepIndex, runId, eventSink)
                "success"
            }
        }
    } catch (_: Throwable) {
        runOutcomeRef.set("failure")
        "failure"
    }
}

private fun emitStepFinished(
    eventSink: EventSink,
    step: StepSpec,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    outcome: String,
) {
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

/**
 * Returns (stepType, effects, domainReplayPolicy) for a given [StepSpec].
 */
private fun stepTypeMetadata(step: StepSpec): Triple<String, Set<Effect>, DomainReplayPolicy> {
    return when (step) {
        is StepSpec.Echo -> Triple("echo", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
        is StepSpec.Shell -> if (step.isScriptBlock) {
            // script {} block: use MEMOIZED + READ_ONLY so replay returns SKIP when
            // journaled (C-022.1). Fingerprint catches mutations (C-022.2).
            Triple("sh", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
        } else {
            Triple("sh", setOf(Effect.EXECUTES_SUBPROCESS), DomainReplayPolicy.RERUN)
        }
        is StepSpec.Sleep -> Triple("sleep", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
        is StepSpec.Error -> Triple("error", setOf(Effect.ABORTS_PIPELINE), DomainReplayPolicy.NEVER)
        is StepSpec.Parallel -> Triple("parallel", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
    }
}

/**
 * Converts a domain [DomainReplayPolicy] to the SDK [ReplayPolicy].
 */
private fun toSdkReplayPolicy(domain: DomainReplayPolicy): ReplayPolicy {
    return when (domain) {
        DomainReplayPolicy.MEMOIZED -> ReplayPolicy.MEMOIZED
        DomainReplayPolicy.RERUN -> ReplayPolicy.RERUN
        DomainReplayPolicy.NEVER -> ReplayPolicy.NEVER
    }
}

/**
 * Converts a [StepSpec] to a JSON params map for [OperationInput].
 */
private fun stepToParams(step: StepSpec): Map<String, JsonElement> {
    return when (step) {
        is StepSpec.Echo -> mapOf("text" to JsonPrimitive(step.text))
        is StepSpec.Shell -> mapOf("command" to JsonPrimitive(step.command))
        is StepSpec.Sleep -> mapOf("seconds" to JsonPrimitive(step.seconds))
        is StepSpec.Error -> mapOf(
            "message" to JsonPrimitive(step.message),
            "failureKind" to JsonPrimitive(step.failureKind),
        )
        is StepSpec.Parallel -> mapOf("branchCount" to JsonPrimitive(step.branches.size))
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
    runOutcome: AtomicReference<String>,
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
            emitErrorStepEvents(step, stageIndex, stepIndex, runId, eventSink, stepStartedId, stepStartedAt, stepName, stepType, runOutcome)
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
    runOutcome: AtomicReference<String>,
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
        // Expected - error signals abort; mark run as failed
        runOutcome.set("failure")
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
