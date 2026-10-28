package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
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
    private val milestoneStateStore: MilestoneStateStore,
    private val artifactIndex: ArtifactIndexCapability?,
    /**
     * H2b: the SAME contributor EXECUTE will consult.
     * Without this, PREPARE built its own capability view and EXECUTE built
     * another, and a Step could pass admission and then find its capability
     * missing. One contributor, two moments, one answer.
     */
    private val capabilityContributor: dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor =
        dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor { emptyMap() },
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
            availableCapabilities = CanonicalRuntimeCapabilityAccess(
                runtime,
                milestoneStateStore = milestoneStateStore,
                artifactIndex = artifactIndex,
                capabilityContributor = capabilityContributor,
            ).available(),
        )
        return when (admission) {
            is ExecutionPreparation.Rejected -> TypedPreparation.Rejected(admission.reason)
            is ExecutionPreparation.Ready -> TypedPreparation.Ready(admission.prepared)
        }
    }
}
