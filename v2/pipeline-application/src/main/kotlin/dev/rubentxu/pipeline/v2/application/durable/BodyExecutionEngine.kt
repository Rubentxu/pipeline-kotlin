package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import java.time.Instant
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import java.nio.file.Files
import java.util.UUID

/**
 * The body-execution machinery, extracted from the durable coordinator
 * (TRAIN H2 / PR-018 slice 1).
 *
 * The coordinator remains the composition point: it constructs this engine
 * and hands it its own child dispatcher, so the body loop re-enters the SAME
 * dispatch path every other caller uses — composition, not a second engine.
 * This first slice moves the child-iteration loop verbatim; the scope
 * projections and the scope-specific engines follow in later slices.
 *
 * Bit-equivalence contract: pinned by `BodyExecutionCharacterizationTest`
 * (dir/retry/withEnv rows and segments) — the engine must satisfy it
 * UNCHANGED.
 */

/** The single seam by which body children re-enter the coordinator's dispatch. */
fun interface BodyChildDispatcher {
    suspend fun dispatchChild(
        child: StepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        bodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    ): StepOutcome
}

internal class BodyExecutionEngine(
    private val eventSink: EventSink,
    private val clock: Clock,
    private val bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
    private val retryControlJournal: FileBasedRetryControlJournal? = null,
    private val waitUntilControlJournal: WaitUntilControlJournal? = null,
) {

    /**
     * Iterates a block's body children through the shared dispatch path.
     * Moved verbatim from the coordinator: same deterministic child OpIds
     * (`parentBodyPath + BlockSegment(childIndex, childKey)`), same
     * per-USE CredentialUsed emission, same first-failure short circuit.
     */
    suspend fun invokeBodyChildren(
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
        leaseBindings: List<CredentialBindingSpec> = emptyList(),
        dispatcher: BodyChildDispatcher,
    ): StepOutcome {
        for ((childIndex, child) in block.body.withIndex()) {
            val childOpId = dev.rubentxu.pipeline.v2.application.durable.OpId(
                runId.value,
                stageIndex,
                stepIndex,
                branchIndex = null,
                bodyPath = parentBodyPath + BlockSegment(childIndex, child.pluginStepId),
            )
            val childOutcome = dispatcher.dispatchChild(
                child,
                runId,
                stageName,
                stageIndex,
                stepIndex,
                childShOptions,
                childOpId.bodyPath,
                executionContext,
            )
            if (leaseBindings.isNotEmpty()) {
                for (binding in leaseBindings) {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.CredentialUsed(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = clock.now(),
                            credentialsId = binding.credentialsId,
                            purpose = when (binding.kind) {
                                "string" -> BoundPurpose.API_KEY
                                "usernamePassword" -> BoundPurpose.USERNAME_PASSWORD
                                "sshUserPrivateKey" -> BoundPurpose.SSH_KEY
                                else -> BoundPurpose.API_KEY
                            },
                            stepIndex = childIndex,
                        ),
                    )
                }
            }
            when (childOutcome) {
                is StepOutcome.Failure, is StepOutcome.Unstable -> return childOutcome
                else -> { /* continue to the next child */ }
            }
        }
        return StepOutcome.Success
    }

    /** The projected child ShOptions plus the context after the scope's overlays. */
    data class ScopedBody(val shOptions: ShOptions, val context: ExecutionContext)

    /**
     * Projects a block's [BlockShellScope] onto the child execution surface:
     * derives the child ShOptions, emits the scope's scheduling/entry events,
     * and pushes the context overlays. Moved verbatim from the coordinator,
     * including its quirks (DirEntered timestamps via Instant.now() while
     * TimeoutScheduled uses the durable clock).
     */
    fun projectScope(
        scope: BlockShellScope,
        block: BlockStepNode,
        runId: RunId,
        stageIndex: Int,
        stepIndex: Int,
        stageShOptions: ShOptions,
        context: ExecutionContext,
    ): ScopedBody = when (scope) {
        BlockShellScope.None -> ScopedBody(stageShOptions, context)
        is BlockShellScope.Retry -> ScopedBody(stageShOptions, context)
        // B13/E-EM-11: block deadline becomes the child Sh watchdog budget -
        // the tighter of the block budget and any inherited stage timeout.
        is BlockShellScope.Timeout -> {
            val inherited = stageShOptions.timeoutMs
            val effective = when {
                inherited == null -> scope.budgetMs
                else -> minOf(inherited, scope.budgetMs)
            }
            // E-EM-11 T2.2: the timeout is now ADMITTED. Project the scheduling
            // transition once, BEFORE any child StepStarted.
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.TimeoutScheduled(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = clock.now(),
                    timeoutSeconds = effective / 1000L,
                    timeoutAction = "abort",
                    stepName = block.id.value,
                    stepType = block.pluginStepId.value,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                ),
            )
            ScopedBody(stageShOptions.copy(timeoutMs = effective), context)
        }
        is BlockShellScope.Directory -> {
            Files.createDirectories(scope.target)
            val contextAfterPush = context.pushed(
                dev.rubentxu.pipeline.v2.domain.ContextOverlay.Cwd(scope.target.toString()),
            )
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.DirEntered(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                    path = scope.target.toString(),
                    previousPath = scope.previous.toString(),
                ),
            )
            ScopedBody(stageShOptions.copy(workingDirectory = scope.target), contextAfterPush)
        }
        is BlockShellScope.TimestampsScope -> {
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.TimestampsEntered(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                ),
            )
            ScopedBody(stageShOptions, context)
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
            val envSpecValues = envOverrides.mapValues {
                it.value.borrow { bytes -> String(bytes, Charsets.UTF_8) }
            }
            val contextAfterPush = context.pushed(
                dev.rubentxu.pipeline.v2.domain.ContextOverlay.Environment(
                    dev.rubentxu.pipeline.v2.domain.EnvironmentSpec(envSpecValues),
                ),
            )
            ScopedBody(stageShOptions.copy(env = mergedEnv), contextAfterPush)
        }
        // WU-G5R.3: waitUntil scope passes through ShOptions without special flags.
        // The polling backoff is handled in the executeWaitUntilBody loop below.
        is BlockShellScope.WaitUntilScope -> ScopedBody(stageShOptions, context)
    }

    suspend fun executeWaitUntilInline(
        scope: BlockShellScope.WaitUntilScope,
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        executionContext: ExecutionContext,
    dispatcher: BodyChildDispatcher,
    ): StepOutcome {
        val overallStartMs = System.currentTimeMillis()
        // WU-G5R.3: initial sleep before first poll
        if (scope.initialRecurrencePeriod > 0) {
            kotlinx.coroutines.delay(scope.initialRecurrencePeriod)
        }

        var currentBackoffMs = scope.initialRecurrencePeriod
        val maxBackoffMs = scope.maxBackoffMs
        var pollCount = 0

        waitUntilPollLoop@ while (true) {
            pollCount++
            val pollAttemptPath = parentBodyPath + BlockSegment(pollCount, dev.rubentxu.pipeline.v2.domain.PluginStepId("wait-until-poll"))
            val pollStartMs = System.currentTimeMillis()

            // Emit WaitUntilPolled before the poll attempt
            eventSink.append(
                WaitUntilPolled(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    attempt = pollCount,
                    durationMs = 0L,
                    conditionResult = false, // unknown until body runs
                ),
            )

            val pollOutcome = invokeBodyChildren(
                block,
                runId,
                stageName,
                stageIndex,
                stepIndex,
                childShOptions,
                pollAttemptPath,
                executionContext,
                dispatcher = dispatcher,
            )

            val pollDurationMs = System.currentTimeMillis() - pollStartMs

            // Update the WaitUntilPolled event with actual duration
            eventSink.append(
                WaitUntilPolled(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    attempt = pollCount,
                    durationMs = pollDurationMs,
                    conditionResult = pollOutcome is StepOutcome.Success,
                ),
            )

            when (pollOutcome) {
                is StepOutcome.Success -> {
                    // Condition satisfied
                    val totalDurationMs = System.currentTimeMillis() - overallStartMs
                    eventSink.append(
                        WaitUntilCompleted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            totalAttempts = pollCount,
                            totalDurationMs = totalDurationMs,
                            outcome = "completed",
                        ),
                    )
                    return StepOutcome.Success
                }
                else -> {
                    // Condition not satisfied (failure, unstable, etc.) — retry with backoff
                    if (currentBackoffMs >= maxBackoffMs) {
                        // Backoff exceeded — deadline exceeded
                        val totalDurationMs = System.currentTimeMillis() - overallStartMs
                        eventSink.append(
                            WaitUntilCompleted(
                                eventId = UUID.randomUUID().toString(),
                                runId = runId.value,
                                sequence = 0L,
                                occurredAt = Instant.now(),
                                totalAttempts = pollCount,
                                totalDurationMs = totalDurationMs,
                                outcome = "deadline-exceeded",
                            ),
                        )
                        return StepOutcome.Failure(
                            dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                                dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
                                "waitUntil condition not met after $pollCount polls (${maxBackoffMs}ms backoff ceiling exceeded)",
                            ),
                        )
                    }
                    // Exponential backoff: double currentBackoffMs, cap at maxBackoffMs
                    currentBackoffMs = minOf(currentBackoffMs * 2, maxBackoffMs)
                    kotlinx.coroutines.delay(currentBackoffMs)
                }
            }
        }
    }

    /**
     * Executes the projected scope end to end: the body-reentry binding, the
     * scope's execution (engine delegation or inline loop per journal
     * availability), and the bracketed bookends. Moved verbatim from the
     * coordinator. The credential-lease path composes through the
     * credentialLeasedBody callback the caller passes.
     */
    @Suppress("LongMethod")
    suspend fun executeScope3b(
        scope: BlockShellScope,
        block: BlockStepNode,
        runId: RunId,
        stageName: String,
        stageIndex: Int,
        stepIndex: Int,
        childShOptions: ShOptions,
        parentBodyPath: List<BlockSegment>,
        context: ExecutionContext,
        dispatcher: BodyChildDispatcher,
        bodyInvokerAdapter: CanonicalBodyInvokerAdapter,
    ): StepOutcome {
        var contextInBody = context
        var outcome: StepOutcome = StepOutcome.Success
        val bodyRef = dev.rubentxu.pipeline.v2.domain.step.BodyRefs.childBody(parentBodyPath)
        bodyInvokerAdapter.open(bodyRef) { ctx ->
            // Phase 1b: derive the per-call attemptSegment from the context the
            // caller hands to [BodyInvoker.invoke]. An attempt N on the same bodyRef
            // produces a distinct deterministic bodyPath (parentBodyPath +
            // BlockSegment(N, retry-attempt)); this is the semantic application of
            // `BodyInvocationContext.attempt` onto durable identity.
            val attemptSegment = ctx.attempt?.let { seg ->
                listOf(dev.rubentxu.pipeline.v2.domain.BlockSegment(seg.index, seg.key))
            } ?: emptyList()
            val attemptBasePath = parentBodyPath + attemptSegment
            // Phase 1b: project `context.patch` onto the parent ExecutionContext.
            // Unsupported patches fall through to the parent unchanged (the seam
            // is honest about what it does not do; rejection stays inside the
            // typed algebra, never as an exception).
            val ctxForCall = applyPatchToContext(contextInBody, ctx.patch)
            invokeBodyChildren(
                block = block,
                runId = runId,
                stageName = stageName,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                childShOptions = childShOptions,
                parentBodyPath = attemptBasePath,
                executionContext = ctxForCall,
                dispatcher = dispatcher,
            )
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
                // WU-LPR-302 Phase 2: retry aggregate is owned by RetryEngine
                // (dev.rubentxu.pipeline.v2.application.durable.retry.RetryEngine).
                // The coordinator hands the body to the engine through the
                // public BodyInvoker port (the body-ref was opened by the
                // adapter above); the engine plans, persists, invokes and
                // folds outcomes, all `StepKey`-blind. The retry legacy loop
                // (no retryControlJournal) remains bit-equivalent inline below.
                val controlOpId = dev.rubentxu.pipeline.v2.application.durable.RetryIdentityFactory.controlOperationId(
                    runId.value, stageIndex, stepIndex, parentBodyPath,
                )
                val fingerprint = computeRetryContractFingerprint(parentBodyPath, scope)
                val retryEngine = dev.rubentxu.pipeline.v2.application.durable.retry.RetryEngine(
                    journal = retryControlJournal,
                    eventSink = eventSink,
                    bodyInvoker = bodyInvokerAdapter,
                    identity = dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity(
                        operationId = controlOpId,
                    ),
                    controlOpId = controlOpId,
                    parentBodyPath = parentBodyPath,
                    fingerprint = fingerprint,
                    maxAttempts = scope.maxAttempts,
                    runId = runId,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    blockId = block.id,
                    blockPluginStepId = block.pluginStepId,
                )
                outcome = retryEngine.execute(bodyRef)
            } else if (scope is BlockShellScope.WaitUntilScope && waitUntilControlJournal != null) {
                // WU-LPR-302 Phase 3: waitUntil aggregate is owned by WaitUntilEngine
                // (dev.rubentxu.pipeline.v2.application.durable.waituntil.WaitUntilEngine).
                // The coordinator hands the body to the engine through the public
                // BodyInvoker port; the engine plans, persists, polls and folds
                // outcomes, all `StepKey`-blind. The waitUntil legacy loop (no
                // waitUntilControlJournal) remains bit-equivalent inline below.
                val waitUntilControlOpId =
                    dev.rubentxu.pipeline.v2.application.durable.WaitUntilIdentityFactory.controlOperationId(
                        runId.value, stageIndex, stepIndex, parentBodyPath,
                    )
                val waitUntilFingerprint = computeWaitUntilContractFingerprint(parentBodyPath, scope)
                val waitUntilEngine =
                    dev.rubentxu.pipeline.v2.application.durable.waituntil.WaitUntilEngine(
                        journal = waitUntilControlJournal,
                        eventSink = eventSink,
                        bodyInvoker = bodyInvokerAdapter,
                        controlOpId = waitUntilControlOpId,
                        parentBodyPath = parentBodyPath,
                        fingerprint = waitUntilFingerprint,
                        initialRecurrencePeriodMs = scope.initialRecurrencePeriod,
                        maxBackoffMs = scope.maxBackoffMs,
                        runId = runId,
                        stageIndex = stageIndex,
                        stepIndex = stepIndex,
                        blockId = block.id,
                        blockPluginStepId = block.pluginStepId,
                    )
                outcome = waitUntilEngine.execute(bodyRef)
            } else if (scope is BlockShellScope.WaitUntilScope) {
                // Legacy waitUntil (no journal): the pre-WU-G5R.5 inline polling
                // loop remains bit-equivalent.
                outcome = executeWaitUntilInline(
                    scope = scope,
                    block = block,
                    runId = runId,
                    stageName = stageName,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    childShOptions = childShOptions,
                    parentBodyPath = parentBodyPath,
                    executionContext = contextInBody,
                    dispatcher = dispatcher,
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

                val attemptOutcome = invokeBodyChildren(
                    block,
                    runId,
                    stageName,
                    stageIndex,
                    stepIndex,
                    childShOptions,
                    attemptBasePath,
                    contextInBody,
                    dispatcher = dispatcher,
                )
                when (attemptOutcome) {
                    is StepOutcome.Failure -> {
                        outcome = attemptOutcome
                        if (attempt < attemptCount) {
                            attempt++
                            continue@bodyLoop
                        }
                        break@bodyLoop // Stop on first failure after last attempt
                    }
                    is StepOutcome.Unstable -> {
                        outcome = attemptOutcome
                        break@bodyLoop
                    }
                    else -> {
                        // Body completed without failure — no extra attempt (WL-R2).
                        break@bodyLoop
                    }
                }
                }
            }
        } finally {
            // B11 / W2: close the body-reentry seam unconditionally so a thrown outcome
            // still drains the adapter's `openBodies` map. Mirrors the existing DirExited /
            // TimestampsExited emission: the bracketed finally must always run.
            bodyInvokerAdapter.close(bodyRef)
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
            // B13/E-EM-11 block authority closure: `TimeoutScheduled` is projected when the
            // block deadline is admitted (above), but the deadline is ENFORCED by the child
            // shell watchdog, which surfaces only the child's own StepFailed(TIMEOUT). Without
            // this event the block's `timeout()` has an admission record but no breach record,
            // and a block deadline that fires is indistinguishable from a plain script timeout.
            // `TimeoutTriggered` already exists in the vocabulary, JSON codec, Sqlite store,
            // sequence assigner and identity projector; it had NO producer, so the event was
            // unreachable from any observable timeline. Emit it here, at the block authority
            // seam, keyed on the TYPED body outcome — never on a concrete StepKey — so a
            // deadline breach is observable from a separate process observing the event log.
            if (scope is BlockShellScope.Timeout) {
                val deadlineBreached = (outcome as? StepOutcome.Failure)
                    ?.failure
                    ?.kind == dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT
                if (deadlineBreached) {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.TimeoutTriggered(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            stageOrStep = block.id.value,
                            action = "abort",
                            durationMs = scope.budgetMs,
                        ),
                    )
                }
            }
        }

        return outcome
        return outcome
    }

}

    /**
     * RETRY-D: deterministic fingerprint of a retry aggregate's contract.
     * The fingerprint is stable across attempts for a given parent bodyPath and
     * maxAttempts — divergence triggers [RetryReconciliationDecision.RejectDivergence].
     */
    internal fun computeRetryContractFingerprint(
        parentBodyPath: List<BlockSegment>,
        scope: BlockShellScope.Retry,
    ): Fingerprint {
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = BodyAggregateIdentity.RetryControlRow.key.value,
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
        return Fingerprint.compute(
            input,
            BodyAggregateIdentity.RetryControlRow.key.value,
            ReplayPolicy.MEMOIZED,
            1,
        )
    }

    internal fun computeWaitUntilContractFingerprint(
        parentBodyPath: List<BlockSegment>,
        scope: BlockShellScope.WaitUntilScope,
    ): Fingerprint {
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = BodyAggregateIdentity.WaitUntilControlRow.key.value,
            params = mapOf(
                "initialRecurrencePeriodMs" to kotlinx.serialization.json.JsonPrimitive(scope.initialRecurrencePeriod),
                "maxBackoffMs" to kotlinx.serialization.json.JsonPrimitive(scope.maxBackoffMs),
                "parentBodyPath" to kotlinx.serialization.json.JsonArray(
                    parentBodyPath.map { kotlinx.serialization.json.JsonPrimitive(it.encoded) },
                ),
            ),
            runId = "wait-until-contract", // Stable per-aggregate, NOT per-attempt.
            attempt = 1,
        )
        return Fingerprint.compute(
            input,
            BodyAggregateIdentity.WaitUntilControlRow.key.value,
            ReplayPolicy.NEVER,
            1,
        )
    }
