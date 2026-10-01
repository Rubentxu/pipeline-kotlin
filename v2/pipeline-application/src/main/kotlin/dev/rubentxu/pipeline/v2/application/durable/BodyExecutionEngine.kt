package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.durable.Clock
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
        leaseBindings: List<CredentialBindingSpec>,
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
}
