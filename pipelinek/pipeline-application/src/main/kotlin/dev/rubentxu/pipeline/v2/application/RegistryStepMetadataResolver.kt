package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * [StepMetadataResolver] composite over an open [StepRegistry].
 * The durable protocol resolves pre-decode metadata by structural step key only (never a concrete
 * Step name). This composite keeps that true across every world:
 *  - ANY key resolves through the registry: the definition's contract descriptor is the
 *    SINGLE source of pre-decode metadata (effects + replayPolicy + recoveryPolicy). The composite
 *    propagates them without adding per-Step branches, so adding a recoverable external plugin only
 *    requires a `StepDescriptor(recoveryPolicy = RecoveryPolicy.ExternalSubprocess)` — no change to
 *    this resolver.
 *  - a key the registry does not know is a hard defect, never a silent lookup miss: an
 *    unregistered Step can never be executed, so admitting it would be a lie.
 * There is no separate core table any more: core Steps are registered through the SAME mechanism
 * an external plugin uses, so the registry already holds their descriptors.
*/
object RegistryStepMetadataResolver {
    fun composite(registry: StepRegistry): StepMetadataResolver =
        StepMetadataResolver { stepKey -> resolve(registry, stepKey) }

    private fun resolve(registry: StepRegistry, stepKey: PluginStepId): StepMetadata {
        val definition = registry.definition(stepKey)
            ?: throw EngineInvariantViolation(
                "No durable metadata for step '${stepKey.value}': not in the registry",
            )
        val descriptor = definition.contract.descriptor
        return StepMetadata(
            effects = descriptor.effects.toSet(),
            replayPolicy = descriptor.replayPolicy,
            recoveryPolicy = descriptor.recoveryPolicy,
        )
    }
}
