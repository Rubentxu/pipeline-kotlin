package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * WU-RP-031 E3: typed input preparation extracted from [CanonicalDurableRunCoordinator]
 * (CDE.2-c/d + CDE.3-b3/e4.3). Strategy preparation runs ONLY on actual execution and NEVER
 * produces Step side effects. Selection is by the CLOSED structural family (LegacyCore vs
 * Registry), never by concrete step name. Reuse/divergence/recover never prepare.
 */
internal class DurableTypedInputPreparation(
    private val stepRegistry: StepRegistry?,
    private val milestoneStateStore: MilestoneStateStore,
    private val artifactIndex: ArtifactIndexCapability?,
) {

    /** Closed outcome of typed preparation; the coordinator interprets each case. */
    sealed interface TypedPreparation {
        data class Ready(val prepared: PreparedExecution) : TypedPreparation
        data class Rejected(val reason: String) : TypedPreparation
    }

    fun prepare(step: StepNode, runtime: CanonicalRuntimeContext): TypedPreparation {
        val family = StructuralFamilyResolver.classify(step.pluginStepId, stepRegistry)
        return when (family) {
            StructuralStepFamily.LegacyCore -> when (val admission = LegacyExecutionBoundary.prepare(step)) {
                is ExecutionPreparation.Rejected -> TypedPreparation.Rejected(admission.reason)
                is ExecutionPreparation.Ready -> TypedPreparation.Ready(admission.prepared)
            }
            StructuralStepFamily.Registry -> {
                val registry = stepRegistry ?: throw EngineInvariantViolation(
                    "registry family step '${step.pluginStepId.value}' reached Execute without a StepRegistry",
                )
                val admission = RegistryExecutionPreparation.prepare(
                    registry = registry,
                    key = step.pluginStepId,
                    encodedInput = EncodedStepValue(step.payload.encoded),
                    availableCapabilities = CanonicalRuntimeCapabilityAccess(
                        runtime,
                        milestoneStateStore = milestoneStateStore,
                        artifactIndex = artifactIndex,
                    ).available(),
                )
                when (admission) {
                    is ExecutionPreparation.Rejected -> TypedPreparation.Rejected(admission.reason)
                    is ExecutionPreparation.Ready -> TypedPreparation.Ready(admission.prepared)
                }
            }
        }
    }
}
