package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * Typed input preparation extracted from [CanonicalDurableRunCoordinator].
 * Preparation runs ONLY on actual execution and NEVER produces Step side effects.
 * There is exactly ONE way to prepare a step — registry resolution, capability
 * admission and typed decode through [RegistryExecutionPreparation] — because the
 * competing structural families are gone. Reuse/divergence/recover never prepare.
*/
internal class DurableTypedInputPreparation(
    private val stepRegistry: StepRegistry,
    /**
     * The collaborator set the capability table is built from, shared with the
     * execution boundary.
     *
     * One holder, two moments, one answer: PREPARE asks it for the available
     * capabilities and EXECUTE asks it for the same table, so a Step can never
     * pass admission against one table and run against another.
     */
    private val capabilityBindings: CanonicalCapabilityBindings,
) {

    /** Closed outcome of typed preparation; the coordinator interprets each case. */
    sealed interface TypedPreparation {
        data class Ready(val prepared: PreparedRegistryExecution) : TypedPreparation
        data class Rejected(val reason: String) : TypedPreparation
    }

    fun prepare(step: StepNode, runtime: CanonicalRuntimeContext): TypedPreparation {
        val admission = RegistryExecutionPreparation.prepare(
            registry = stepRegistry,
            key = step.pluginStepId,
            encodedInput = EncodedStepValue(step.payload.encoded),
            availableCapabilities = capabilityBindings.access(runtime).available(),
        )
        return when (admission) {
            is ExecutionPreparation.Rejected -> TypedPreparation.Rejected(admission.reason)
            is ExecutionPreparation.Ready -> TypedPreparation.Ready(admission.prepared)
        }
    }
}
