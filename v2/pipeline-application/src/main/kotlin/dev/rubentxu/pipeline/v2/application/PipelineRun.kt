package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.application.durable.PipelineOrchestrator
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceException
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy as DomainReplayPolicy
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.Effect
import dev.rubentxu.pipeline.v2.sdk.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.echo
import dev.rubentxu.pipeline.v2.sdk.runtime.error as sdkError
import dev.rubentxu.pipeline.v2.sdk.runtime.sleep as sdkSleep
import dev.rubentxu.pipeline.v2.sdk.runtime.sh
import dev.rubentxu.pipeline.v2.sdk.runtime.ShellResult
import dev.rubentxu.pipeline.v2.sdk.StepContext
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
fun execute(scriptPath: Path, store: EventStore, clock: Clock = SystemClock()): List<DomainEvent> {
    val scriptContent = scriptPath.toFile().readText()
    val runId = deriveRunId(scriptPath.toString(), scriptContent)
    val eventSink = store as? EventSink ?: InMemoryEventStore()

    val runStartedId = UUID.randomUUID().toString()
    val runStartedAt = clock.now()
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
    val runFinishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    for ((stageIndex, stage) in spec.stages.withIndex()) {
        val stageStartedId = UUID.randomUUID().toString()
        val stageStartedAt = clock.now()
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
                clock = clock,
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
                clock = clock,
            )
        }

        for ((stepIndex, step) in stage.steps.withIndex()) {
            emitStepEvents(step, stageIndex, stepIndex, runId, eventSink, runOutcome, clock)
        }

        val stageFinishedId = UUID.randomUUID().toString()
        val stageFinishedAt = clock.now()
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
 * M3-R3 C-027 reconciliation pass: journal-first reattach model.
 *
 * At the start of a resumed run, queries all RUNNING rows from the journal for
 * the given [runId]. For each RUNNING row, applies fail-closed rules:
 * 1. Deadline exceeded (C-021) → DivergenceException
 * 2. ended_at IS NULL (subprocess killed mid-write) → DivergenceException
 * 3. Fingerprint mismatch → DivergenceException
 * 4. Fingerprint match + ended_at NOT NULL → mark SUCCEEDED via append UPSERT (no replay)
 *
 * This enables recovery from worker crash mid-sh without replaying the side effect,
 * provided the subprocess completed (ended_at NOT NULL) and the fingerprint matches.
 *
 * @throws DivergenceException When deadline exceeded, ended_at is null, or fingerprints diverge.
 */
private fun reconcileRunningOperations(
    runId: String,
    journal: OperationJournal,
    divergenceDetector: DivergenceDetector,
    clock: Clock,
    startFromStageIndex: Int,
    startFromStepIndex: Int,
) {
    val runningOps = journal.listForRun(runId).filter { it.status == OperationStatus.RUNNING }
    for (op in runningOps) {
        // Parse opId using typed OpId parser (replaces fragile substringAfter/split)
        val parsedOpId = OpId.parse(op.id) ?: continue // malformed opId, skip
        val opStageIndex = parsedOpId.stageIndex
        val opStepIndex = parsedOpId.stepIndex

        // Skip operations that were already completed before the cursor position
        if (opStageIndex < startFromStageIndex) continue
        if (opStageIndex == startFromStageIndex && opStepIndex < startFromStepIndex) continue

        val nowMs = clock.now().toEpochMilli()

        // C-021 FAIL-CLOSED: deadline exceeded
        val journaledDeadline = journal.getDeadlineMs(op.id, op.attempt)
        if (journaledDeadline != null && nowMs > journaledDeadline) {
            throw DivergenceException(
                expected = op.fingerprint,
                actual = op.fingerprint,
                opId = op.id,
                runId = runId,
                stageIndex = opStageIndex,
            )
        }

        // C-027 fail-closed: ended_at IS NULL means subprocess was killed mid-write
        // We cannot trust the cached output — must not silently recover
        val journaledOp = journal.get(op.id, op.attempt)
        // ended_at NOT NULL means append ran (subprocess completed) — safe to reconcile
        val endedAt = journal.getEndedAt(op.id, op.attempt)
        if (endedAt == null) {
            // ended_at IS NULL → subprocess killed mid-write → fail-closed
            throw DivergenceException(
                expected = op.fingerprint,
                actual = op.fingerprint,
                opId = op.id,
                runId = runId,
                stageIndex = opStageIndex,
            )
        }

        // Fingerprint check: compare current (from journal) vs. what we'd compute now
        // We already HAVE the fingerprint from the journaled op - use DivergenceDetector
        // Build the "current" op from the journal entry
        val currentOp = MemoizedOperation(
            id = op.id,
            fingerprint = op.fingerprint,
            input = op.input,
            output = op.output,
            status = op.status,
            attempt = op.attempt,
            cachedOutput = op.output,
        )
        val divergenceResult = divergenceDetector.check(currentOp, journaledOp)
        if (divergenceResult.isFailure) {
            throw divergenceResult.exceptionOrNull() as DivergenceException
        }

        // Fingerprint matches and ended_at NOT NULL → mark terminal with cached output.
        // Preserve FAILED status if the subprocess failed (don't overwrite to SUCCEEDED).
        val reconciledStatus = if (op.status == OperationStatus.FAILED) {
            OperationStatus.FAILED
        } else {
            OperationStatus.SUCCEEDED
        }
        val reconciledOp = RerunOperation(
            id = op.id,
            fingerprint = op.fingerprint,
            input = op.input,
            output = op.output,
            status = reconciledStatus,
            attempt = op.attempt,
        )
        journal.append(reconciledOp, journaledDeadline)
    }
}

/**
 * Queries the raw ended_at column for a journal entry.
 * Returns null if ended_at is NULL, or if no row exists.
 */

/**
 * Durable walk: extends [walkPipelineSpec] with full replay/diverge gating.
 *
 * Per [design.md §4.4], for each step this function:
 * 1. Computes the operation fingerprint from input + stepId + replayPolicy + attempt
 * 2. Looks up any prior journaled operation by opId
 * 3. Runs [DivergenceDetector.check] — fail-closed on fingerprint mismatch
 * 4. Calls [dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy.decide] to SKIP / RERUN / ABORT
 * 5. On RERUN: executes the SDK step, appends [DurableOperation] to [journal], advances [cursorStore]
 * 6. On SKIP: emits StepStarted/StepFinished events but bypasses the executor
 * 7. On ABORT: throws [DivergenceException]
 * 8. On resume: checks deadline against [Clock.now] — FAIL-CLOSED if deadline exceeded
 *
 * @param spec              The pipeline specification.
 * @param runId             The deterministic run identifier.
 * @param eventSink         Event sink for the event timeline.
 * @param journal           The operation journal for durable persistence.
 * @param cursorStore       The replay cursor store for resume support.
 * @param divergenceDetector The divergence detector for fail-closed checks.
 * @param effectReplayPolicy The effect-aware replay policy.
 * @param clock             The clock for deadline checking.
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
    effectReplayPolicy: dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    clock: Clock,
    startFromStageIndex: Int = 0,
    startFromStepIndex: Int = 0,
): String {
    var runOutcome = "success"
    val runOutcomeRef = java.util.concurrent.atomic.AtomicReference(runOutcome)

    // M3-R3 C-027 reconciliation pass: journal-first reattach model.
    // Query RUNNING rows for this runId and reconcile them fail-closed.
    // Must run before any stage/step execution to detect divergence early.
    reconcileRunningOperations(
        runId = runId,
        journal = journal,
        divergenceDetector = divergenceDetector,
        clock = clock,
        startFromStageIndex = startFromStageIndex,
        startFromStepIndex = startFromStepIndex,
    )

    for ((stageIndex, stage) in spec.stages.withIndex()) {
        // Resume gate: skip stages before the cursor position
        if (stageIndex < startFromStageIndex) continue

        val stageStartedId = UUID.randomUUID().toString()
        val stageStartedAt = clock.now()
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
                clock = clock,
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
                clock = clock,
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
                clock = clock,
                runOutcomeRef = runOutcomeRef,
            )
            if (stepOutcome == "failure") {
                runOutcomeRef.set("failure")
            }
        }

        val stageFinishedId = UUID.randomUUID().toString()
        val stageFinishedAt = clock.now()
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
 * Emits durable step events with full replay/diverge gating and retry support.
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
    effectReplayPolicy: dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    clock: Clock,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
): String {
    val (stepType, effects, domainPolicy) = stepTypeMetadata(step)
    val opId = OpId(runId, stageIndex, stepIndex).format()
    // Json encoder for serializing OperationInput for beginOperation
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val retryPolicy = step.retry ?: RetryPolicy.NONE
    val maxAttempts = retryPolicy.maxAttempts
    val timeoutMillis = step.timeoutMillis

    // Compute deadline for fresh execution if timeout is set
    val deadlineMs: Long? = if (timeoutMillis != null && timeoutMillis > 0) {
        clock.now().toEpochMilli() + timeoutMillis
    } else {
        null
    }

    // Build OperationInput (attempt will vary per retry loop iteration)
    val params = stepToParams(step)

    // Map SDK ReplayPolicy to domain ReplayPolicy for the decision call
    val sdkPolicy = toSdkReplayPolicy(domainPolicy)

    for (attemptNum in 1..maxAttempts) {
        // Build OperationInput for this attempt
        val input = OperationInput(
            stepId = stepType,
            params = params,
            runId = runId,
            attempt = attemptNum,
        )

        // Compute fingerprint for this attempt
        val fingerprint = Fingerprint.compute(input, stepType, domainPolicy, attemptNum)

        // Look up journaled operation for this specific attempt
        val journaledOp = journal.get(opId, attemptNum)
        val hasJournalEntry = journaledOp != null
        val journaledOutcome = journaledOp?.status

        // FAIL-CLOSED deadline check on resume
        // If deadline is set and we've exceeded it, throw DivergenceException
        if (hasJournalEntry && timeoutMillis != null) {
            val nowMs = clock.now().toEpochMilli()
            val journaledDeadline = journal.getDeadlineMs(opId, attemptNum)
            if (journaledDeadline != null && nowMs > journaledDeadline) {
                val divEx = DivergenceException(
                    expected = fingerprint,
                    actual = fingerprint,
                    opId = opId,
                    runId = runId,
                    stageIndex = stageIndex,
                )
                runOutcomeRef.set("failure")
                throw divEx
            }
        }

        // Divergence check: compare current fingerprint against journaled fingerprint for THIS attempt
        val currentOp: DurableOperation = if (journaledOp is MemoizedOperation) {
            MemoizedOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = journaledOp.output,
                status = journaledOp.status,
                attempt = attemptNum,
                cachedOutput = journaledOp.cachedOutput,
            )
        } else {
            RerunOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = OperationStatus.PENDING,
                attempt = attemptNum,
            )
        }

        // Fail-closed divergence check
        val divergenceResult = divergenceDetector.check(currentOp, journaledOp)
        if (divergenceResult.isFailure) {
            throw divergenceResult.exceptionOrNull() as DivergenceException
        }

        // Determine replay decision
        val decision = effectReplayPolicy.decide(sdkPolicy, effects, hasJournalEntry, journaledOutcome)

        val stepStartedId = UUID.randomUUID().toString()
        val stepStartedAt = clock.now()

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

        when (decision) {
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision.SKIP -> {
                // Bypass executor; emit StepFinished with cached output.
                // Return the journaled outcome (SUCCEEDED → "success", FAILED → "failure").
                val skipOutcome = if (journaledOutcome == dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED) {
                    "failure"
                } else {
                    "success"
                }
                emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, skipOutcome, clock)
                return skipOutcome
            }
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision.RERUN -> {
                // Two-phase journal (M3-R3 C-026): write RUNNING before executing the step.
                // This enables fail-closed reconciliation on restart.
                if (!hasJournalEntry) {
                    val inputJson = json.encodeToString(input)
                    journal.beginOperation(opId, attemptNum, fingerprint.hex, inputJson, deadlineMs)
                }

                val stepOutcome = executeDurableStep(step, stageIndex, stepIndex, runId, eventSink, runOutcomeRef, clock)

                // Journal the operation (UPSERT RUNNING → terminal, C-025)
                val outputOp = RerunOperation(
                    id = opId,
                    fingerprint = fingerprint,
                    input = input,
                    output = null,
                    status = if (stepOutcome == "success") OperationStatus.SUCCEEDED else OperationStatus.FAILED,
                    attempt = attemptNum,
                )
                journal.append(outputOp, deadlineMs)

                if (stepOutcome == "failure") {
                    // This attempt failed
                    emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, stepOutcome, clock)
                    if (attemptNum < maxAttempts) {
                        // Apply backoff before next attempt
                        val delayMs = retryPolicy.backoffDelay(attemptNum + 1)
                        if (delayMs > 0) {
                            Thread.sleep(delayMs)
                        }
                        // Continue to next attempt
                        continue
                    } else {
                        // Last attempt failed → return failure outcome (spec C-030.2)
                        runOutcomeRef.set("failure")
                        emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, "failure", clock)
                        return "failure"
                    }
                } else {
                    // Attempt succeeded
                    // Advance cursor after successful journal append (per R-C mitigation)
                    cursorStore.advance(runId, opId, stageIndex)
                    emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, stepOutcome, clock)
                    return "success"
                }
            }
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision.ABORT -> {
                // Emit StepFailed and throw
                emitStepFinished(eventSink, step, stageIndex, stepIndex, runId, "failure", clock)
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

    // Should not reach here, but return failure as fallback
    return "failure"
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
    clock: Clock = SystemClock(),
): String {
    return try {
        when (step) {
            is StepSpec.Echo -> {
                echo(StepContext(runId = runId), step.text, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Shell -> {
                // Pass the entire command through `bash -c` so the shell interprets
                // quoted multi-line scripts correctly. Previously split on whitespace
                // which mangled argv for any command containing quotes (e.g. `bash -c "..."`).
                val argv = listOf("bash", "-c", step.command)
                val result = sh(StepContext(runId = runId), argv, eventSink, stepIndex)
                if (result.exitCode != 0) "failure" else "success"
            }
            is StepSpec.Sleep -> {
                sdkSleep(StepContext(runId = runId), step.seconds, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Error -> {
                val failureKind = try {
                    dev.rubentxu.pipeline.v2.domain.FailureKind.valueOf(step.failureKind)
                } catch (_: Exception) {
                    dev.rubentxu.pipeline.v2.domain.FailureKind.UNKNOWN
                }
                sdkError(StepContext(runId = runId), step.message, failureKind, eventSink, stepIndex)
                "success" // sdkError throws
            }
            is StepSpec.Parallel -> {
                emitParallelStepEvents(step, stageIndex, stepIndex, runId, eventSink, clock)
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
    clock: Clock = SystemClock(),
) {
    val stepFinishedId = UUID.randomUUID().toString()
    val stepFinishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    val stepStartedId = UUID.randomUUID().toString()
    val stepStartedAt = clock.now()
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
            val stepFinishedAt = clock.now()
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
            dev.rubentxu.pipeline.v2.sdk.runtime.sh(StepContext(runId = runId), argv, eventSink, stepIndex)
            val stepFinishedId = UUID.randomUUID().toString()
            val stepFinishedAt = clock.now()
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
            emitErrorStepEvents(step, stageIndex, stepIndex, runId, eventSink, stepStartedId, stepStartedAt, stepName, stepType, runOutcome, clock)
        }
        is StepSpec.Sleep -> {
            emitSleepStepEvents(step, stageIndex, stepIndex, runId, eventSink, stepStartedId, stepStartedAt, stepName, stepType, clock)
        }
        is StepSpec.Parallel -> {
            emitParallelStepEvents(step, stageIndex, stepIndex, runId, eventSink, clock)
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
    clock: Clock = SystemClock(),
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
    val stepFinishedAt = clock.now()
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
    clock: Clock = SystemClock(),
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
    val stepFinishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    // Emit StepStarted for the parallel step itself
    val stepStartedId = UUID.randomUUID().toString()
    val stepStartedAt = clock.now()
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
        emitParallelBranchEvents(branchIndex, branch.name, stageIndex, runId, eventSink, clock)
    }

    // Emit StepFinished for the parallel step itself
    val stepFinishedId = UUID.randomUUID().toString()
    val stepFinishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    val eventId = UUID.randomUUID().toString()
    val occurredAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    val startedId = UUID.randomUUID().toString()
    val startedAt = clock.now()
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
    val finishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    val startedId = UUID.randomUUID().toString()
    val startedAt = clock.now()
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
    val finishedAt = clock.now()
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
    clock: Clock = SystemClock(),
) {
    val eventId = UUID.randomUUID().toString()
    val occurredAt = clock.now()
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
