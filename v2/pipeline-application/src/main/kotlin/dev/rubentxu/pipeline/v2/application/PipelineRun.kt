package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.SecretHandleRef
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.AgentResolved
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.EventStore
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
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
import dev.rubentxu.pipeline.v2.application.durable.DurableWalkContext
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitChangelogWriter
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutExecutor
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRequest
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitPollExecutor
import dev.rubentxu.pipeline.v2.application.ReconciledBranch
import dev.rubentxu.pipeline.v2.application.ReconciliationStatus
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
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellState
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfigResolver
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.executeDurableShell
import dev.rubentxu.pipeline.v2.sdk.files.FileExistsExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileReadExecutor
import dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor
import dev.rubentxu.pipeline.v2.application.durable.ShExecution
import dev.rubentxu.pipeline.v2.sdk.StepContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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
 * ## Reconciliation
 *
 * M3-R5 B1 (ADR-0040 Path A): The [BranchReconciler] is constructed once in
 * [PipelineOrchestrator.run] and threaded via [DurableWalkContext.branchReconciler].
 * This function receives the LIVE reconciler through `ctx` and calls it at the start
 * of the walk to drive per-branch resume decisions (C-027).
 *
 * @param spec              The pipeline specification.
 * @param runId             The deterministic run identifier.
 * @param ctx               The [DurableWalkContext] carrying clock, journal, cursorStore,
 *                          branchReconciler, and eventSink for this run.
 * @param divergenceDetector The divergence detector for fail-closed checks.
 * @param effectReplayPolicy The effect-aware replay policy.
 * @param startFromStageIndex Stage index to resume from (0 for fresh run).
 * @param startFromStepIndex Step index to resume from (0 for fresh run).
 * @return The run outcome string ("success" or "failure").
 * @throws DivergenceException When divergence is detected or ABORT decision is returned.
 */
internal suspend fun walkPipelineSpecDurable(
    spec: PipelineSpec,
    runId: String,
    ctx: DurableWalkContext,
    divergenceDetector: DivergenceDetector,
    effectReplayPolicy: dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    startFromStageIndex: Int = 0,
    startFromStepIndex: Int = 0,
): String {
    var runOutcome = "success"
    val runOutcomeRef = java.util.concurrent.atomic.AtomicReference(runOutcome)

    // M3-R5 B1 (ADR-0040 Path A): Use the LIVE BranchReconciler from ctx.
    // Constructed once in PipelineOrchestrator.run() and threaded here.
    // M3-R3 C-027: reconciliation pass — journal-first reattach model.
    // Query RUNNING rows for this runId and reconcile them fail-closed.
    // Must run before any stage/step execution to detect divergence early.
    val reconciledBranches: Map<Int, ReconciledBranch> = ctx.branchReconciler
        .reconcileRunningOperations(runId)
        .associateBy { OpId.parse(it.opId)?.branchIndex ?: -1 }

    // ML-R1 C1: Step-level reconcile for durable shell.
    // After branch reconciliation, reconcile RUNNING shell steps via StepReconcilerL1.
    // This classifies each RUNNING sh step as COMPLETE/REATTACH/LOST based on
    // result.txt + heartbeat, enabling the Shell branch to handle each case.
    val stepClassifications: Map<String, dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification> =
        ctx.stepReconcilerL1?.reconcile(runId) ?: emptyMap()

    for ((stageIndex, stage) in spec.stages.withIndex()) {
        // Resume gate: skip stages before the cursor position
        if (stageIndex < startFromStageIndex) continue

        val stageStartedId = UUID.randomUUID().toString()
        val stageStartedAt = ctx.clock.now()
        ctx.eventSink.append(
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
                eventSink = ctx.eventSink,
                clock = ctx.clock,
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
                    eventSink = ctx.eventSink,
                )
            }
        }

        // TMO-S-002: Thread stage-level timeout into shOptions for steps that don't set step-level timeout.
        // stage.options.timeout is in seconds; convert to milliseconds for execution.
        val stageTimeoutMs = stage.options?.timeout?.times(1000L)

        stage.options?.timeout?.let { timeoutSeconds ->
            emitTimeoutScheduledEvent(
                timeoutSeconds = timeoutSeconds,
                timeoutAction = "FAIL",
                stepName = null,
                stepType = null,
                stageIndex = stageIndex,
                stepIndex = null,
                runId = runId,
                eventSink = ctx.eventSink,
                clock = ctx.clock,
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
                ctx = ctx,
                divergenceDetector = divergenceDetector,
                effectReplayPolicy = effectReplayPolicy,
                runOutcomeRef = runOutcomeRef,
                reconciledBranches = reconciledBranches,
                stepClassifications = stepClassifications,
                stageTimeout = stageTimeoutMs,
                stageEnvironment = stage.environment,
            )
            if (stepOutcome == "failure") {
                runOutcomeRef.set("failure")
            }
        }

        val stageFinishedId = UUID.randomUUID().toString()
        val stageFinishedAt = ctx.clock.now()
        ctx.eventSink.append(
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
private suspend fun emitDurableStepEvents(
    step: StepSpec,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    ctx: DurableWalkContext,
    divergenceDetector: DivergenceDetector,
    effectReplayPolicy: dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
    reconciledBranches: Map<Int, ReconciledBranch>? = null,
    stepClassifications: Map<String, dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification> = emptyMap(),
    // TMO-S-002: Stage-level timeout (milliseconds) — stage options { timeout } is the source.
    stageTimeout: Long? = null,
    // Stage-level environment: StageSpec.environment merged at stage entry (WS-S-005).
    stageEnvironment: Map<String, String>? = null,
): String {
    val (stepType, effects, domainPolicy) = stepTypeMetadata(step)
    val opId = OpId(runId, stageIndex, stepIndex).format()
    // Json encoder for serializing OperationInput for beginOperation
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val retryPolicy = step.retry ?: RetryPolicy.NONE
    val maxAttempts = retryPolicy.maxAttempts
    // TMO-S-002: timeout precedence = step-level timeoutMillis (internal; populated
    // programmatically or by a future timeout{} wrapper) ?: stage-level options{timeout}.
    // The sh KEYWORD does not expose timeout (Jenkins-faithful); StepSpec field remains
    // the internal carrier (UatDurable005 C-024 journals deadline_ms from it).
    val timeoutMillis = step.timeoutMillis ?: stageTimeout

    // Compute deadline for fresh execution if timeout is set
    val deadlineMs: Long? = if (timeoutMillis != null && timeoutMillis > 0) {
        ctx.clock.now().toEpochMilli() + timeoutMillis
    } else {
        null
    }

    // Build OperationInput (attempt will vary per retry loop iteration)
    val baseParams = stepToParams(step)
    // ML-R3: Add sandboxProfile to params for LOCAL profile (NONE is byte-identical to ML-R2)
    val params = if (ctx.sandboxProfile != SandboxProfile.NONE) {
        baseParams + ("sandboxProfile" to JsonPrimitive(ctx.sandboxProfile.name))
    } else {
        baseParams
    }

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
        val journaledOp = ctx.opJournal.get(opId, attemptNum)
        val hasJournalEntry = journaledOp != null
        val journaledOutcome = journaledOp?.status

        // FAIL-CLOSED deadline check on resume
        // If deadline is set and we've exceeded it, throw DivergenceException
        if (hasJournalEntry && timeoutMillis != null) {
            val nowMs = ctx.clock.now().toEpochMilli()
            val journaledDeadline = ctx.opJournal.getDeadlineMs(opId, attemptNum)
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
        val stepStartedAt = ctx.clock.now()

        ctx.eventSink.append(
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
                emitStepFinished(ctx.eventSink, step, stageIndex, stepIndex, runId, skipOutcome, ctx.clock)
                return skipOutcome
            }
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision.RERUN -> {
                // Two-phase journal (M3-R3 C-026): write RUNNING before executing the step.
                // This enables fail-closed reconciliation on restart.
                if (!hasJournalEntry) {
                    val inputJson = json.encodeToString(input)
                    ctx.opJournal.beginOperation(opId, attemptNum, fingerprint.hex, inputJson, deadlineMs)
                }

                val stepOutcome = executeDurableStep(
                    step = step,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    runId = runId,
                    eventSink = ctx.eventSink,
                    journal = ctx.opJournal,
                    cursorStore = ctx.cursorStore,
                    divergenceDetector = divergenceDetector,
                    effectReplayPolicy = effectReplayPolicy,
                    clock = ctx.clock,
                    runOutcomeRef = runOutcomeRef,
                    controlDirRoot = ctx.controlDirRoot,
                    workspaceResolver = ctx.workspaceResolver,
                    shOptions = ctx.shOptions,
                    stepClassifications = stepClassifications,
                    stageTimeout = stageTimeout,
                    stageEnvironment = stageEnvironment,
                    sandboxProfile = ctx.sandboxProfile,
                    secretStore = ctx.secretStore,
                )

                // Journal the operation (UPSERT RUNNING → terminal, C-025)
                val outputOp = RerunOperation(
                    id = opId,
                    fingerprint = fingerprint,
                    input = input,
                    output = null,
                    status = when {
                        stepOutcome == "success" -> OperationStatus.SUCCEEDED
                        stepOutcome == "lost" -> OperationStatus.LOST  // fail-closed per UAT-REC-002
                        stepOutcome == "timeout" -> OperationStatus.FAILED_TIMEOUT  // TMO-S-001: watchdog killed the process
                        else -> OperationStatus.FAILED
                    },
                    attempt = attemptNum,
                )
                ctx.opJournal.append(outputOp, deadlineMs)

                if (stepOutcome == "failure" || stepOutcome == "lost" || stepOutcome == "timeout") {
                    // This attempt failed (or LOST/TIMEOUT which are also terminal failures)
                    emitStepFinished(ctx.eventSink, step, stageIndex, stepIndex, runId, stepOutcome, ctx.clock)
                    if (stepOutcome == "lost" || stepOutcome == "timeout") {
                        // LOST and TIMEOUT are terminal - do not retry (fail-closed per UAT-REC-002, TMO-S-001)
                        runOutcomeRef.set("failure")
                        return "failure"
                    }
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
                        emitStepFinished(ctx.eventSink, step, stageIndex, stepIndex, runId, "failure", ctx.clock)
                        return "failure"
                    }
                } else {
                    // Attempt succeeded
                    // Advance cursor after successful journal append (per R-C mitigation)
                    ctx.cursorStore.advance(runId, opId, stageIndex)
                    emitStepFinished(ctx.eventSink, step, stageIndex, stepIndex, runId, stepOutcome, ctx.clock)
                    return "success"
                }
            }
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision.ABORT -> {
                // Emit StepFailed and throw
                emitStepFinished(ctx.eventSink, step, stageIndex, stepIndex, runId, "failure", ctx.clock)
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
 *
 * ## @compat permanent
 *
 * This overload is retained for backward compatibility with existing call sites
 * that pass individual parameters. New code should prefer [DurableWalkContext] to
 * reduce parameter sprawl. This overload delegates to [executeDurableStepImpl] and
 * is guaranteed to remain stable per the compatibility policy.
 *
 * @see executeDurableStepImpl for the internal implementation.
 */
private suspend fun executeDurableStep(
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
    reconciledBranches: Map<Int, ReconciledBranch>? = null,
    controlDirRoot: Path? = null,
    workspaceResolver: dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver? = null,
    shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions? = null,
    stepClassifications: Map<String, dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification> = emptyMap(),
    stageTimeout: Long? = null,
    stageEnvironment: Map<String, String>? = null,
    sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? = null,
): String {
    return executeDurableStepImpl(
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
        reconciledBranches = reconciledBranches,
        controlDirRoot = controlDirRoot,
        workspaceResolver = workspaceResolver,
        shOptions = shOptions,
        stepClassifications = stepClassifications,
        stageTimeout = stageTimeout,
        stageEnvironment = stageEnvironment,
        sandboxProfile = sandboxProfile,
        secretStore = secretStore,
    )
}

/**
 * Crash-safe shell step execution using durable shell pattern (ML-R1 / ADR-0046).
 *
 * ## Execution Order
 *
 * The crash-safe order is:
 * 1. **begin** → journal RUNNING row (done by caller)
 * 2. **create** → control dir + script.sh
 * 3. **launch** → ProcessHandle acquired, wrapper forked
 * 4. **poll** → wait for result.txt (100ms polling)
 * 5. **append** → journal COMPLETE/LOST (done by caller)
 * 6. **cleanup** → delete control dir (unless retainOnFailure)
 *
 * ## P2 Invariant
 *
 * User script content NEVER appears in any argv. The script is written to
 * script.sh on the filesystem, and only the wrapper command (with paths)
 * is passed to `sh -c`.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */

/**
 * Internal implementation of [executeDurableStep]. Both public overloads delegate here
 * to avoid code duplication.
 */
private suspend fun executeDurableStepImpl(
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
    reconciledBranches: Map<Int, ReconciledBranch>? = null,
    controlDirRoot: Path? = null,
    workspaceResolver: dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver? = null,
    shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions? = null,
    stepClassifications: Map<String, dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification> = emptyMap(),
    // TMO-S-002: Stage-level timeout via options{} — stageTimeout is the canonical source.
    stageTimeout: Long? = null,
    // Stage-level environment: StageSpec.environment merged at stage entry (WS-S-005).
    stageEnvironment: Map<String, String>? = null,
    // ML-R3: Sandbox profile for this step.
    sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    // T11: Secret store for credential resolution in withCredentials blocks.
    secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? = null,
): String {
    // Build opId for durable shell control dir
    val opId = OpId(runId, stageIndex, stepIndex).format()

    return try {
        when (step) {
            is StepSpec.Echo -> {
                echo(StepContext(runId = runId), step.text, eventSink, stepIndex)
                "success"
            }
            is StepSpec.Shell -> {
                // ML-R1 C1: Check step-level classification from resume reconciliation
                val classification = stepClassifications[opId]
                when (classification) {
                    is dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification.Complete -> {
                        // Step completed before JVM death; use cached exit code (no re-execution)
                        if (classification.exitCode == 0) "success" else "failure"
                    }
                    is dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification.Reattach -> {
                        // Step may still be running; poll existing control-dir result (no relaunch)
                        val config = dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig.fromSystemProperties()
                        val executor = dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor()
                        val exitCode = executor.pollResult(classification.controlDir, timeoutMs = 60_000) ?: -1
                        if (exitCode == 0) "success" else "failure"
                    }
                    is dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification.TimedOut -> {
                        // Timeout was triggered before JVM death; return failure (not lost)
                        "failure"
                    }
                    is dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification.Lost,
                    null -> {
                        // LOST: return "lost" to signal to caller to journal LOST (not FAILED)
                        // null: no pre-existing classification; proceed with normal durable execution
                        // Build effective ShOptions from step configuration
                        val workspaceRoot = workspaceResolver?.resolve("stage-$stageIndex", stageIndex)
                            ?: java.nio.file.Files.createTempDirectory("shoptions")
                        val shellStep = step as dev.rubentxu.pipeline.v2.dsl.StepSpec.Shell

                        // ML-R3: Resolve sandbox config from sysprops/env vars (controller JVM)
                        val sandboxConfig = SandboxConfigResolver.resolve(
                            syspropAllowExtra = System.getProperty("pipeline.sandbox.allow.extra"),
                            syspropPathKeep = System.getProperty("pipeline.sandbox.path.keep"),
                            envAllowExtra = System.getenv("PIPELINE_SANDBOX_ALLOW_EXTRA"),
                            envPathKeep = System.getenv("PIPELINE_SANDBOX_PATH_KEEP"),
                            baseProfile = sandboxProfile,
                        )

                        // WS-S-005: env via StageSpec.environment — stageEnvironment is the env source
                        // TMO-S-002: stage-level timeout via options{} — stageTimeout is the timeout source
                        // T2 migration: stageEnvironment (Map<String,String>) is widened to Map<String,SecretHandle>
                        val effectiveEnv: Map<String, SecretHandle> = (stageEnvironment ?: emptyMap())
                            .mapValues { SecretHandle.plain(it.value) }
                        val effectiveShOptions = shOptions?.copy(
                            workspaceRoot = workspaceRoot,
                            captureStdout = shellStep.returnStdout,
                            timeoutMs = shOptions.timeoutMs ?: stageTimeout,
                            env = effectiveEnv,
                            sandbox = sandboxConfig,
                        ) ?: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions(
                            workspaceRoot = workspaceRoot,
                            captureStdout = shellStep.returnStdout,
                            timeoutMs = stageTimeout,
                            env = effectiveEnv,
                            sandbox = sandboxConfig,
                        )
                        val opIdObj = OpId(runId, stageIndex, stepIndex)
                        val result = ShExecution.runShStep(shellStep, opIdObj, runId, stageIndex, stepIndex, effectiveShOptions, controlDirRoot, eventSink)
                        if (classification is dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1.Classification.Lost) {
                            // Override result to signal LOST to caller
                            "lost"
                        } else {
                            result
                        }
                    }
                }
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
                // Convert DSL StepSpec.Parallel to domain ParallelFrame
                val joinPolicy = when (step.branches.size) {
                    0 -> dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy.ALL_COMPLETE
                    else -> dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy.ALL_COMPLETE
                }
                val parallelFrame = dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame(
                    branches = step.branches.map { branch ->
                        dev.rubentxu.pipeline.v2.domain.durable.BranchSpec(
                            name = branch.name,
                            steps = branch.steps,
                        )
                    },
                    joinPolicy = joinPolicy,
                )
                walkParallelFrame(
                    frame = parallelFrame,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    runId = runId,
                    eventSink = eventSink,
                    journal = journal,
                    cursorStore = cursorStore,
                    clock = clock,
                    runOutcomeRef = runOutcomeRef,
                    controlDirRoot = controlDirRoot,
                    workspaceResolver = workspaceResolver,
                    shOptions = shOptions,
                    reconciledBranches = reconciledBranches,
                    stageEnvironment = stageEnvironment,
                )
            }
            is StepSpec.WithCredentialsBlock -> {
                // T11: Wire real withCredentials block execution.
                // If secretStore is available, resolve credentials and inject into inner steps.
                // If not available, execute inner steps without credential injection.
                if (secretStore != null) {
                    // Collect active handles for cleanup
                    val activeHandles = mutableListOf<dev.rubentxu.pipeline.v2.domain.SecretHandle>()
                    var firstException: Throwable? = null

                    try {
                        // Build credential env map from bindings
                        val credentialEnv = mutableMapOf<String, dev.rubentxu.pipeline.v2.domain.SecretHandle>()
                        for (binding in step.bindings) {
                            try {
                                val handle = secretStore.getAsSecretHandle(binding.credentialsId)
                                activeHandles.add(handle)
                                when (binding.kind) {
                                    StepSpec.CredentialsBinding.Kind.STRING -> {
                                        binding.variable?.let { varName ->
                                            credentialEnv[varName] = handle
                                        }
                                    }
                                    StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD -> {
                                        binding.usernameVariable?.let { varName ->
                                            credentialEnv[varName] = handle
                                        }
                                        binding.passwordVariable?.let { varName ->
                                            credentialEnv[varName] = handle
                                        }
                                    }
                                }
                            } catch (e: dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException) {
                                // Credential resolution failed - propagate failure
                                return "failure"
                            }
                        }

                        // Merge credential env into shOptions for inner step execution
                        val effectiveShOptions = shOptions?.copy(
                            env = (shOptions.env ?: emptyMap()) + credentialEnv
                        ) ?: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions(
                            workspaceRoot = workspaceResolver?.resolve("stage-$stageIndex", stageIndex)
                                ?: java.nio.file.Files.createTempDirectory("withcreds"),
                            captureStdout = false,
                            timeoutMs = stageTimeout,
                            env = credentialEnv,
                            sandbox = SandboxConfigResolver.resolve(sandboxProfile),
                        )

                        // Execute inner steps with credential env injected
                        var innerOutcome = "success"
                        for ((innerStepIdx, innerStep) in step.steps.withIndex()) {
                            val innerStepOutcome = executeDurableStep(
                                step = innerStep,
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
                                reconciledBranches = reconciledBranches,
                                controlDirRoot = controlDirRoot,
                                workspaceResolver = workspaceResolver,
                                shOptions = effectiveShOptions,
                                stepClassifications = stepClassifications,
                                stageTimeout = stageTimeout,
                                stageEnvironment = stageEnvironment,
                                sandboxProfile = sandboxProfile,
                                secretStore = secretStore,
                            )
                            if (innerStepOutcome != "success") {
                                innerOutcome = innerStepOutcome
                                break
                            }
                        }
                        innerOutcome
                    } finally {
                        // Wipe all active handles (INV-CR-CR7)
                        for (handle in activeHandles) {
                            try {
                                handle.close()
                            } catch (t: Throwable) {
                                if (firstException == null) {
                                    firstException = t
                                } else {
                                    firstException.addSuppressed(t)
                                }
                            }
                        }
                    }
                } else {
                    // No secretStore available - execute inner steps without credential injection
                    var innerOutcome = "success"
                    for (innerStep in step.steps) {
                        val innerStepOutcome = executeDurableStep(
                            step = innerStep,
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
                            reconciledBranches = reconciledBranches,
                            controlDirRoot = controlDirRoot,
                            workspaceResolver = workspaceResolver,
                            shOptions = shOptions,
                            stepClassifications = stepClassifications,
                            stageTimeout = stageTimeout,
                            stageEnvironment = stageEnvironment,
                            sandboxProfile = sandboxProfile,
                            secretStore = null,
                        )
                        if (innerStepOutcome != "success") {
                            innerOutcome = innerStepOutcome
                            break
                        }
                    }
                    innerOutcome
                }
            }
            is StepSpec.Checkout -> {
                // ML-R5: Wire real checkout execution via GitCheckoutExecutor.
                // C1 (P1): Full durable execution with credential resolution.
                val scm = step.scm as? GitScm
                    ?: return "failure"
                // C6: Validate URL is non-blank (Jenkins-verbatim error)
                if (scm.url.isBlank()) {
                    throw IllegalArgumentException("Missing required parameter: url")
                }
                // Resolve workspace root
                val workspaceRoot = workspaceResolver?.resolve("stage-${stageIndex}", stageIndex)
                    ?: Files.createTempDirectory("checkout-workspace")
                Files.createDirectories(workspaceRoot)
                // Create credentials temp dir with 0700 perms
                val credsDir = Files.createTempDirectory("git-creds")
                credsDir.let { dir ->
                    Files.setPosixFilePermissions(dir, setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                    ))
                }
                // Build GitCheckoutRequest
                val checkoutSpec = CheckoutSpec(scm)
                val javaClock = java.time.Clock.systemUTC()
                val checkoutRequest = GitCheckoutRequest(
                    spec = checkoutSpec,
                    runId = runId,
                    workspaceRoot = workspaceRoot,
                    eventSink = eventSink,
                    clock = javaClock,
                    secretStore = secretStore,
                    stepIndex = stepIndex,
                    previousRemoteSha = null,
                )
                // Execute checkout via GitCheckoutExecutor
                val pollExecutor = GitPollExecutor()
                val changelogWriter = GitChangelogWriter()
                val credentialsApplier = GitCredentialsApplier(credsDir, GitCredentials(), secretStore)
                val checkoutExecutor = GitCheckoutExecutor(pollExecutor, changelogWriter, credentialsApplier, javaClock, secretStore)
                try {
                    val result = checkoutExecutor.execute(checkoutRequest)
                    if (result.isSuccess) "success" else "failure"
                } catch (e: Exception) {
                    "failure"
                } finally {
                    checkoutExecutor.close()
                }
            }
            // L7 Jenkins top-steps (ML-R7) — T-09 full dispatch
            is StepSpec.WriteFile -> {
                val resolver = workspaceResolver
                    ?: return "failure"
                val executor = FileWriteExecutor(
                    workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
                    eventSink = eventSink
                )
                val result = executor.execute(stageIndex, stepIndex, step)
                eventSink.append(
                    FileWritten(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId,
                        sequence = 0L,
                        occurredAt = clock.now(),
                        path = result.path,
                        sha256 = result.sha256,
                        size = result.size,
                        atomicallyMoved = result.atomicallyMoved,
                    )
                )
                "success"
            }
            is StepSpec.ReadFile -> {
                val resolver = workspaceResolver
                    ?: return "failure"
                val executor = FileReadExecutor(
                    workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
                    eventSink = eventSink
                )
                val result = executor.execute(stageIndex, stepIndex, step)
                eventSink.append(
                    FileRead(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId,
                        sequence = 0L,
                        occurredAt = clock.now(),
                        path = result.path,
                        sha256 = result.sha256,
                        size = result.size,
                    )
                )
                "success"
            }
            is StepSpec.FileExists -> {
                val resolver = workspaceResolver
                    ?: return "failure"
                val executor = FileExistsExecutor(
                    workspaceResolver = { name, idx -> resolver.resolve(name, idx) }
                )
                executor.execute(stageIndex, stepIndex, step)
                "success"
            }
            is StepSpec.WithEnv -> {
                // Parse "VAR=value" / "PATH+X=/dir" overrides into Map<String, String>
                val overridesMap = mutableMapOf<String, String>()
                for (entry in step.overrides) {
                    val eqIdx = entry.indexOf('=')
                    if (eqIdx > 0) {
                        val key = entry.substring(0, eqIdx)
                        val value = entry.substring(eqIdx + 1)
                        overridesMap[key] = value
                    }
                }
                // Apply EnvModel PATH+= prepend semantics
                val effectiveEnv = EnvModel.apply(overridesMap)
                // Merge with existing stageEnvironment
                val mergedEnv = (stageEnvironment ?: emptyMap()).toMutableMap()
                mergedEnv.putAll(effectiveEnv)
                // Execute nested steps with the overridden environment
                var outcome = "success"
                for (innerStep in step.steps) {
                    val innerOutcome = executeDurableStep(
                        step = innerStep,
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
                        reconciledBranches = reconciledBranches,
                        controlDirRoot = controlDirRoot,
                        workspaceResolver = workspaceResolver,
                        shOptions = shOptions,
                        stepClassifications = stepClassifications,
                        stageTimeout = stageTimeout,
                        stageEnvironment = mergedEnv,
                        sandboxProfile = sandboxProfile,
                        secretStore = secretStore,
                    )
                    if (innerOutcome != "success") {
                        outcome = innerOutcome
                        break
                    }
                }
                outcome
            }
            is StepSpec.ArchiveArtifacts -> {
                // T-10 (LocalArtifactStore) provides the actual archive implementation.
                // Until then, return failure so the step is visible as not-implemented.
                "failure"
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
        is StepSpec.WithCredentialsBlock -> Triple("withCredentials", setOf(Effect.EXECUTES_SUBPROCESS), DomainReplayPolicy.RERUN)
        is StepSpec.Checkout -> Triple("checkout", setOf(Effect.EXECUTES_SUBPROCESS), DomainReplayPolicy.RERUN)
        // L7 Jenkins top-steps (ML-R7) — T-09 adds full dispatch
        is StepSpec.WriteFile -> Triple("writeFile", setOf(Effect.WRITES_WORKSPACE), DomainReplayPolicy.MEMOIZED)
        is StepSpec.ReadFile -> Triple("readFile", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
        is StepSpec.FileExists -> Triple("fileExists", setOf(Effect.READ_ONLY), DomainReplayPolicy.MEMOIZED)
        is StepSpec.WithEnv -> Triple("withEnv", setOf(Effect.EXECUTES_SUBPROCESS), DomainReplayPolicy.RERUN)
        is StepSpec.ArchiveArtifacts -> Triple("archiveArtifacts", setOf(Effect.WRITES_WORKSPACE), DomainReplayPolicy.MEMOIZED)
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
 *
 * IMPORTANT: For WithCredentialsBlock, only the credentialsId and purpose are
 * emitted - NEVER the secret value. This preserves the Fingerprint.compute
 * invariant (step params determine the cache key, not secret values).
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
        is StepSpec.WithCredentialsBlock -> {
            val bindingsArray = step.bindings.map { binding ->
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive(binding.kind.name),
                        "credentialsId" to JsonPrimitive(binding.credentialsId.value),
                    ) + when (binding.kind) {
                        StepSpec.CredentialsBinding.Kind.STRING -> {
                            mapOf("variable" to JsonPrimitive(binding.variable ?: ""))
                        }
                        StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD -> {
                            mapOf(
                                "usernameVariable" to JsonPrimitive(binding.usernameVariable ?: ""),
                                "passwordVariable" to JsonPrimitive(binding.passwordVariable ?: ""),
                            )
                        }
                    }
                )
            }
            mapOf(
                "credentialsId" to JsonPrimitive(step.credentialsId.value),
                "purpose" to JsonPrimitive(step.purpose),
                "bindings" to JsonArray(bindingsArray),
            )
        }
        is StepSpec.Checkout -> {
            // ML-R5: Serialize Checkout step params
            val scm = step.scm
            when (scm) {
                is dev.rubentxu.pipeline.v2.domain.scm.GitScm -> {
                    mapOf(
                        "scmType" to JsonPrimitive("git"),
                        "url" to JsonPrimitive(scm.url),
                        "branch" to JsonPrimitive(scm.branch),
                        "credentialsId" to JsonPrimitive(scm.credentialsId?.value ?: ""),
                    )
                }
                else -> {
                    mapOf("scmType" to JsonPrimitive("unknown"))
                }
            }
        }
        // L7 Jenkins top-steps (ML-R7) — T-09 adds full dispatch; stubs here make the code compile
        is StepSpec.WriteFile -> mapOf(
            "file" to JsonPrimitive(step.file),
            "text" to JsonPrimitive(step.text),
            "encoding" to JsonPrimitive(step.encoding),
        )
        is StepSpec.ReadFile -> mapOf(
            "file" to JsonPrimitive(step.file),
            "encoding" to JsonPrimitive(step.encoding),
        )
        is StepSpec.FileExists -> mapOf(
            "file" to JsonPrimitive(step.file),
        )
        is StepSpec.WithEnv -> mapOf(
            "overrides" to JsonArray(step.overrides.map { JsonPrimitive(it) }),
        )
        is StepSpec.ArchiveArtifacts -> mapOf(
            "artifacts" to JsonPrimitive(step.artifacts),
            "allowEmptyArchive" to JsonPrimitive(step.allowEmptyArchive),
            "fingerprint" to JsonPrimitive(step.fingerprint),
            "onlyIfSuccessful" to JsonPrimitive(step.onlyIfSuccessful),
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
        is StepSpec.WithCredentialsBlock -> {
            // Emit StepStarted for the withCredentials block itself
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
        }
        is StepSpec.Checkout -> {
            // ML-R5: Checkout step execution via GitCheckoutExecutor
            // C1 (P1): Events fire from REAL execution lifecycle, not emitted unconditionally.
            val scm = step.scm as? GitScm
            if (scm == null) {
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
                return
            }
            // C6: Validate URL non-blank (Jenkins-verbatim error)
            if (scm.url.isBlank()) {
                throw IllegalArgumentException("Missing required parameter: url")
            }
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
            // Non-durable execution: use temp workspace (no durable context available)
            val workspace = Files.createTempDirectory("checkout-workspace")
            val credsDir = Files.createTempDirectory("git-creds")
            credsDir.let { dir ->
                Files.setPosixFilePermissions(dir, setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                ))
            }
            val checkoutSpec = CheckoutSpec(scm)
            val checkoutRequest = GitCheckoutRequest(
                spec = checkoutSpec,
                runId = runId,
                workspaceRoot = workspace,
                eventSink = eventSink,
                clock = java.time.Clock.systemUTC(),
                secretStore = null,
                stepIndex = stepIndex,
                previousRemoteSha = null,
            )
            val pollExecutor = GitPollExecutor()
            val changelogWriter = GitChangelogWriter()
            val credentialsApplier = GitCredentialsApplier(credsDir, GitCredentials())
            val checkoutExecutor = GitCheckoutExecutor(pollExecutor, changelogWriter, credentialsApplier, java.time.Clock.systemUTC(), null)
            val stepOutcome = try {
                val result = checkoutExecutor.execute(checkoutRequest)
                if (result.isSuccess) "success" else "failure"
            } catch (e: Exception) {
                "failure"
            } finally {
                checkoutExecutor.close()
            }
            runOutcome.set(stepOutcome)
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
        // L7 Jenkins top-steps (ML-R7) — T-09 StepStarted/StepFinished events for file/env steps
        is StepSpec.WriteFile -> {
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
            // Execution is handled in executeDurableStepImpl; emit StepFinished here
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
        is StepSpec.ReadFile -> {
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
        is StepSpec.FileExists -> {
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
        is StepSpec.WithEnv -> {
            // WithEnv is a scope carrier; nested steps emit their own StepStarted/StepFinished events.
            // Emit StepStarted/StepFinished for the withEnv itself.
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
        is StepSpec.ArchiveArtifacts -> {
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

/**
 * Emits a [ParallelBranchStarted] event for the given branch.
 */
private fun emitParallelBranchStarted(
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
}

/**
 * Emits a [ParallelBranchFinished] event for the given branch with the specified outcome.
 */
private fun emitParallelBranchFinished(
    branchIndex: Int,
    branchName: String,
    parentStageIndex: Int,
    runId: String,
    eventSink: EventSink,
    clock: Clock = SystemClock(),
    outcome: String = "success",
) {
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
            outcome = outcome,
        )
    )
}

/**
 * Durable walk for a [ParallelFrame].
 *
 * Writes a `parallel_frame_started` journal entry, then walks each branch
 * sequentially (branch concurrency is delegated to [dev.rubentxu.pipeline.v2.sdk.runtime.ParallelFrameExecutor]
 * in T-05). Each branch receives a branch-specific [OpId] with `branchIndex`.
 * When all branches complete, writes `parallel_frame_joined`.
 *
 * This is the M3-R4.2 durable walk for parallel frames, replacing the
 * stub [emitParallelStepEvents] which only emitted events without durable semantics.
 *
 * @param frame The domain parallel frame to walk.
 * @param stageIndex The current stage index.
 * @param stepIndex The current step index within the stage.
 * @param runId The pipeline run identifier.
 * @param eventSink The event sink for journal entries.
 * @param journal The operation journal for durable recording.
 * @param cursorStore The replay cursor store.
 * @param clock The clock for timestamps.
 * @param runOutcomeRef Atomic reference for run outcome propagation.
 * @param workspaceResolver The workspace resolver for per-stage workspaces.
 * @param shOptions Default shell execution options.
 * @param reconciledBranches Map of reconciled branches from branch reconciliation.
 * @return The step outcome string ("success" or "failure").
 */
private suspend fun walkParallelFrame(
    frame: dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame,
    stageIndex: Int,
    stepIndex: Int,
    runId: String,
    eventSink: EventSink,
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    clock: Clock,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
    controlDirRoot: java.nio.file.Path? = null,
    workspaceResolver: dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver? = null,
    shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions? = null,
    reconciledBranches: Map<Int, ReconciledBranch>? = null,
    // WS-S-005: Stage-level environment from StageSpec.environment
    stageEnvironment: Map<String, String>? = null,
): String {
    val parentOpId = OpId(runId, stageIndex, stepIndex)

    // Emit StepStarted for the parallel frame
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
            stepName = "parallel",
            stepType = "parallel",
        )
    )

    // M3-R4.4: BranchReconciler drives per-branch resume decisions.
    // SUCCEEDED branches skip execution (retain journaled ParallelBranchStarted/Finished).
    // NEEDS_REATTACH branches get fresh ParallelBranchStarted + walkBranchDurable.
    // STUCK branches fail-closed.
    // Concurrent execution via coroutineScope: branches run in parallel, wall-clock ≈ slowest branch.
    var overallOutcome = "success"

    // Phase 1: Sequential setup + concurrent dispatch for NEEDS_REATTACH/null branches
    val branchResults: List<Pair<Int, String>> = coroutineScope {
        val deferreds = mutableListOf<Pair<Int, kotlinx.coroutines.Deferred<String>>>()

        frame.branches.forEachIndexed { branchIndex, branch ->
            val branchOpId = OpId.forBranch(runId, stageIndex, stepIndex, branchIndex)
            val reconciled = reconciledBranches?.get(branchIndex)

            when (reconciled?.status) {
                ReconciliationStatus.SUCCESS -> {
                    // Branch completed — skip, retain journaled events
                }
                ReconciliationStatus.NEEDS_REATTACH, null -> {
                    emitParallelBranchStarted(branchIndex, branch.name, stageIndex, runId, eventSink, clock)
                    journal.beginOperation(
                        opId = branchOpId.format(),
                        attempt = 1,
                        fingerprint = Fingerprint.compute(
                            OperationInput(
                                stepId = "parallel-branch",
                                params = mapOf("branchName" to JsonPrimitive(branch.name)),
                                runId = runId,
                                attempt = 1,
                            ),
                            "parallel-branch",
                            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                            1,
                        ).hex,
                        inputJson = """{"branchName":"${branch.name}","branchIndex":$branchIndex,"parentOpId":"${parentOpId.format()}"}""",
                        deadlineMs = null,
                    )

                    val deferred = async(Dispatchers.IO) {
                        val branchOutcome = walkBranchDurable(
                            branch = branch,
                            branchIndex = branchIndex,
                            parentOpId = parentOpId,
                            runId = runId,
                            eventSink = eventSink,
                            journal = journal,
                            cursorStore = cursorStore,
                            clock = clock,
                            runOutcomeRef = runOutcomeRef,
                            controlDirRoot = controlDirRoot,
                            workspaceResolver = workspaceResolver,
                            shOptions = shOptions,
                            stageEnvironment = stageEnvironment,
                        )
                        emitParallelBranchFinished(branchIndex, branch.name, stageIndex, runId, eventSink, clock, branchOutcome)
                        branchOutcome
                    }
                    deferreds.add(branchIndex to deferred)
                }
                ReconciliationStatus.STUCK -> {
                    throw DivergenceException(
                        expected = Fingerprint.compute(
                            OperationInput(
                                stepId = "parallel-branch",
                                params = mapOf("branchName" to JsonPrimitive(branch.name)),
                                runId = runId,
                                attempt = 1,
                            ),
                            "parallel-branch",
                            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                            1,
                        ),
                        actual = Fingerprint.compute(
                            OperationInput(
                                stepId = "parallel-branch",
                                params = mapOf("branchName" to JsonPrimitive(branch.name)),
                                runId = runId,
                                attempt = 1,
                            ),
                            "parallel-branch",
                            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                            1,
                        ),
                        opId = branchOpId.format(),
                        runId = runId,
                        stageIndex = stageIndex,
                    )
                }
            }
        }

        // Await all concurrent branch executions
        deferreds.map { (idx, deferred) -> idx to deferred.await() }
    }

    // Phase 2: Aggregate outcomes
    for ((_, outcome) in branchResults) {
        if (outcome == "failure") {
            overallOutcome = "failure"
        }
    }

    // Emit StepFinished for the parallel frame
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
            stepName = "parallel",
            stepType = "parallel",
        )
    )

    // Advance cursor past parallel frame (join barrier - ADR-0035)
    // Uses max stage index from all branches
    val maxBranchStageIndex = stageIndex + frame.branches.size
    cursorStore.advancePastParallelFrame(runId, frame, emptyList(), maxBranchStageIndex)

    return overallOutcome
}

/**
 * Durable walk for a single [BranchSpec] within a parallel frame.
 *
 * Executes each step in the branch sequentially using the existing durable
 * step execution path, with a branch-specific [OpId].
 *
 * W8 fold: Shell steps in branches now route through ShExecution.executeBranchStep
 * for consistent durable semantics with single-frame steps.
 *
 * @param branch The branch specification to walk.
 * @param branchIndex The index of this branch within the parallel frame.
 * @param parentOpId The parent [OpId] of the parallel frame.
 * @param runId The pipeline run identifier.
 * @param eventSink The event sink for journal entries.
 * @param journal The operation journal.
 * @param cursorStore The replay cursor store.
 * @param clock The clock.
 * @param runOutcomeRef Atomic reference for run outcome.
 * @param workspaceResolver The workspace resolver.
 * @param shOptions Default shell execution options.
 * @return The branch outcome string.
 */
private suspend fun walkBranchDurable(
    branch: dev.rubentxu.pipeline.v2.domain.durable.BranchSpec,
    branchIndex: Int,
    parentOpId: OpId,
    runId: String,
    eventSink: EventSink,
    journal: OperationJournal,
    cursorStore: ReplayCursorStore,
    clock: Clock,
    runOutcomeRef: java.util.concurrent.atomic.AtomicReference<String>,
    controlDirRoot: java.nio.file.Path? = null,
    workspaceResolver: dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver? = null,
    shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions? = null,
    // WS-S-005: Stage-level environment from StageSpec.environment
    stageEnvironment: Map<String, String>? = null,
): String {

    // For now, each branch step is processed at the parent stage/step level
    // with the branch-specific OpId. The actual step execution reuses the
    // existing durable step path but with branch awareness.
    //
    // Branch steps are executed sequentially within the branch.
    // Concurrency across branches is handled by ParallelFrameExecutor (T-05).
    var branchOutcome = "success"
    for ((stepOffset, step) in branch.steps.withIndex()) {
        val stepStartedId = UUID.randomUUID().toString()
        val stepStartedAt = clock.now()
        eventSink.append(
            StepStarted(
                eventId = stepStartedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stepStartedAt,
                stageIndex = parentOpId.stageIndex,
                stepIndex = parentOpId.stepIndex + stepOffset,
                stepName = "${branch.name}:${step.name}",
                stepType = step.type,
            )
        )

        // Execute the step using existing durable path (simplified - no replay/gating for branch steps yet)
        val stepType = step.type
        val stepOutcome = when (step) {
            is StepSpec.Echo -> {
                dev.rubentxu.pipeline.v2.sdk.runtime.echo(
                    dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                    step.text,
                    eventSink,
                    stepOffset,
                )
                "success"
            }
            is StepSpec.Shell -> {
                // W8 fold: route through ShExecution.executeBranchStep for consistent durable semantics
                val branchOpId = OpId.forBranch(runId, parentOpId.stageIndex, parentOpId.stepIndex, branchIndex)
                // WS-S-005: env via StageSpec.environment; TMO-S-002: timeout via options{}
                // T2 migration: stageEnvironment (Map<String,String>) is widened to Map<String,SecretHandle>
                val branchEnv: Map<String, SecretHandle> = (stageEnvironment ?: emptyMap())
                    .mapValues { SecretHandle.plain(it.value) }
                val effectiveShOptions = shOptions?.copy(
                    captureStdout = step.returnStdout,
                    timeoutMs = shOptions.timeoutMs,
                    env = branchEnv,
                ) ?: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions(
                    workspaceRoot = java.nio.file.Files.createTempDirectory("shoptions"),
                    captureStdout = step.returnStdout,
                    timeoutMs = null,
                    env = branchEnv,
                )
                ShExecution.executeBranchStep(
                    stageIndex = parentOpId.stageIndex,
                    stepIndex = parentOpId.stepIndex + stepOffset,
                    branchOpId = branchOpId,
                    runId = runId,
                    command = step.command,
                    shOptions = effectiveShOptions,
                    controlDirRoot = controlDirRoot,
                    eventSink = eventSink,
                )
            }
            is StepSpec.Sleep -> {
                dev.rubentxu.pipeline.v2.sdk.runtime.sleep(
                    dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                    step.seconds,
                    eventSink,
                    stepOffset,
                )
                "success"
            }
            is StepSpec.Error -> {
                val failureKind = try {
                    dev.rubentxu.pipeline.v2.domain.FailureKind.valueOf(step.failureKind)
                } catch (_: Exception) {
                    dev.rubentxu.pipeline.v2.domain.FailureKind.UNKNOWN
                }
                dev.rubentxu.pipeline.v2.sdk.runtime.error(
                    dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                    step.message,
                    failureKind,
                    eventSink,
                    stepOffset,
                )
                "success" // sdkError throws
            }
            is StepSpec.Parallel -> {
                // Nested parallel frame - recurse via walkParallelFrame
                val nestedFrame = dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame(
                    branches = step.branches.map { b ->
                        dev.rubentxu.pipeline.v2.domain.durable.BranchSpec(b.name, b.steps)
                    },
                    joinPolicy = dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy.ALL_COMPLETE,
                )
                walkParallelFrame(
                    frame = nestedFrame,
                    stageIndex = parentOpId.stageIndex,
                    stepIndex = parentOpId.stepIndex + stepOffset,
                    runId = runId,
                    eventSink = eventSink,
                    journal = journal,
                    cursorStore = cursorStore,
                    clock = clock,
                    runOutcomeRef = runOutcomeRef,
                    controlDirRoot = controlDirRoot,
                    workspaceResolver = workspaceResolver,
                    shOptions = shOptions,
                    stageEnvironment = stageEnvironment,
                )
            }
            // L7 Jenkins top-steps (ML-R7) — T-09 branch dispatch for parallel branch steps
            is StepSpec.WriteFile -> {
                val resolver = workspaceResolver
                if (resolver != null) {
                    val executor = FileWriteExecutor(
                        workspaceResolver = { name: String, idx: Int -> resolver.resolve(name, idx) },
                        eventSink = eventSink
                    )
                    val result = executor.execute(
                        parentOpId.stageIndex,
                        parentOpId.stepIndex + stepOffset,
                        step
                    )
                    eventSink.append(
                        FileWritten(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId,
                            sequence = 0L,
                            occurredAt = clock.now(),
                            path = result.path,
                            sha256 = result.sha256,
                            size = result.size,
                            atomicallyMoved = result.atomicallyMoved,
                        )
                    )
                    "success"
                } else {
                    "failure"
                }
            }
            is StepSpec.ReadFile -> {
                val resolver = workspaceResolver
                if (resolver != null) {
                    val executor = FileReadExecutor(
                        workspaceResolver = { name: String, idx: Int -> resolver.resolve(name, idx) },
                        eventSink = eventSink
                    )
                    val result = executor.execute(
                        parentOpId.stageIndex,
                        parentOpId.stepIndex + stepOffset,
                        step
                    )
                    eventSink.append(
                        FileRead(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId,
                            sequence = 0L,
                            occurredAt = clock.now(),
                            path = result.path,
                            sha256 = result.sha256,
                            size = result.size,
                        )
                    )
                    "success"
                } else {
                    "failure"
                }
            }
            is StepSpec.FileExists -> {
                val resolver = workspaceResolver
                if (resolver != null) {
                    val executor = FileExistsExecutor(
                        workspaceResolver = { name: String, idx: Int -> resolver.resolve(name, idx) }
                    )
                    executor.execute(parentOpId.stageIndex, parentOpId.stepIndex + stepOffset, step)
                    "success"
                } else {
                    "failure"
                }
            }
            is StepSpec.WithEnv -> {
                // Apply env overrides using EnvModel and execute nested steps directly in branch context.
                val overridesMap = mutableMapOf<String, String>()
                for (entry in step.overrides) {
                    val eqIdx = entry.indexOf('=')
                    if (eqIdx > 0) {
                        val key = entry.substring(0, eqIdx)
                        val value = entry.substring(eqIdx + 1)
                        overridesMap[key] = value
                    }
                }
                val effectiveEnv = EnvModel.apply(overridesMap)
                val mergedEnv = (stageEnvironment ?: emptyMap()).toMutableMap()
                mergedEnv.putAll(effectiveEnv)
                // Execute each nested step directly in branch context with the merged env
                var outcome = "success"
                for (innerStep in step.steps) {
                    val innerStepOffset = step.steps.indexOf(innerStep)
                    val innerStepStartedId = UUID.randomUUID().toString()
                    val innerStepStartedAt = clock.now()
                    eventSink.append(
                        StepStarted(
                            eventId = innerStepStartedId,
                            runId = runId,
                            sequence = 0L,
                            occurredAt = innerStepStartedAt,
                            stageIndex = parentOpId.stageIndex,
                            stepIndex = parentOpId.stepIndex + stepOffset + innerStepOffset + 1,
                            stepName = "${branch.name}:${step.name}:${innerStep.name}",
                            stepType = innerStep.type,
                        )
                    )
                    // Execute via existing durable path with merged env
                    val innerOpId = OpId.forBranch(runId, parentOpId.stageIndex, parentOpId.stepIndex, branchIndex)
                    val innerBranchEnv: Map<String, SecretHandle> = mergedEnv
                        .mapValues { SecretHandle.plain(it.value) }
                    val effectiveShOptions = shOptions?.copy(
                        env = innerBranchEnv,
                    ) ?: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions(
                        workspaceRoot = java.nio.file.Files.createTempDirectory("branch-withenv"),
                        captureStdout = false,
                        timeoutMs = null,
                        env = innerBranchEnv,
                    )
                    val innerOutcome: String = when (innerStep) {
                        is StepSpec.Shell -> {
                            ShExecution.executeBranchStep(
                                stageIndex = parentOpId.stageIndex,
                                stepIndex = parentOpId.stepIndex + stepOffset + innerStepOffset + 1,
                                branchOpId = OpId.forBranch(runId, parentOpId.stageIndex, parentOpId.stepIndex, branchIndex),
                                runId = runId,
                                command = innerStep.command,
                                shOptions = effectiveShOptions,
                                controlDirRoot = controlDirRoot,
                                eventSink = eventSink,
                            )
                        }
                        is StepSpec.Echo -> {
                            dev.rubentxu.pipeline.v2.sdk.runtime.echo(
                                dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                                innerStep.text,
                                eventSink,
                                stepOffset + innerStepOffset + 1,
                            )
                            "success"
                        }
                        is StepSpec.Sleep -> {
                            dev.rubentxu.pipeline.v2.sdk.runtime.sleep(
                                dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                                innerStep.seconds,
                                eventSink,
                                stepOffset + innerStepOffset + 1,
                            )
                            "success"
                        }
                        is StepSpec.Error -> {
                            try {
                                val failureKind = try {
                                    dev.rubentxu.pipeline.v2.domain.FailureKind.valueOf(innerStep.failureKind)
                                } catch (_: Exception) {
                                    dev.rubentxu.pipeline.v2.domain.FailureKind.UNKNOWN
                                }
                                dev.rubentxu.pipeline.v2.sdk.runtime.error(
                                    dev.rubentxu.pipeline.v2.sdk.StepContext(runId = runId),
                                    innerStep.message,
                                    failureKind,
                                    eventSink,
                                    stepOffset + innerStepOffset + 1,
                                )
                                "success"
                            } catch (_: Throwable) {
                                "failure"
                            }
                        }
                        else -> {
                            // For other step types (Parallel, nested WithEnv, etc.), fall back to failure
                            // The full nested step support requires deeper integration
                            "failure"
                        }
                    }
                    val innerStepFinishedId = UUID.randomUUID().toString()
                    val innerStepFinishedAt = clock.now()
                    eventSink.append(
                        StepFinished(
                            eventId = innerStepFinishedId,
                            runId = runId,
                            sequence = 0L,
                            occurredAt = innerStepFinishedAt,
                            stageIndex = parentOpId.stageIndex,
                            stepIndex = parentOpId.stepIndex + stepOffset + innerStepOffset + 1,
                            stepName = "${branch.name}:${step.name}:${innerStep.name}",
                            stepType = innerStep.type,
                        )
                    )
                    if (innerOutcome != "success") {
                        outcome = innerOutcome
                        break
                    }
                }
                outcome
            }
            is StepSpec.ArchiveArtifacts -> {
                // T-10 (LocalArtifactStore) provides the actual implementation
                "failure"
            }
            else -> {
                // Unknown step type - treat as error
                runOutcomeRef.set("failure")
                "failure"
            }
        }

        if (stepOutcome == "failure") {
            branchOutcome = "failure"
            runOutcomeRef.set("failure")
        }

        val stepFinishedId = UUID.randomUUID().toString()
        val stepFinishedAt = clock.now()
        eventSink.append(
            StepFinished(
                eventId = stepFinishedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stepFinishedAt,
                stageIndex = parentOpId.stageIndex,
                stepIndex = parentOpId.stepIndex + stepOffset,
                stepName = "${branch.name}:${step.name}",
                stepType = step.type,
            )
        )
    }
    return branchOutcome
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
