package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.events.EventSink
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
