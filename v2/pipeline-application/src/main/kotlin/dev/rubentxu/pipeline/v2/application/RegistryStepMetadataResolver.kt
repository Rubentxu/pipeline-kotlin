package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * CDE.3-d5b / LB-02 G3-A4.1.2: [StepMetadataResolver] composite over an open [StepRegistry] plus the
 * legacy core catalog.
 *
 * The durable protocol resolves pre-decode metadata by structural step key only (never a concrete Step
 * name). This composite keeps that true across both worlds:
 *  - a legacy core key (in [CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS]) delegates to the legacy core
 *    authority [CanonicalCoreStepMetadata], so core semantics (and their sh behaviour) are unchanged
 *    even if a definition with the same key is also registered;
 *  - any OTHER key resolves through the registry: the definition's contract descriptor is the
 *    SINGLE source of pre-decode metadata (effects + replayPolicy + recoveryPolicy). The composite
 *    propagates them without adding per-Step branches, so adding a recoverable external plugin only
 *    requires a `StepDescriptor(recoveryPolicy = RecoveryPolicy.ExternalSubprocess)` — no change to
 *    this resolver.
 *  - a key neither core nor registered is a hard defect (mirrors the legacy fail-fast default), never a
 *    silent lookup miss.
 *
 * Additive seam: the coordinator still defaults to [CoreLegacyStepMetadataResolver]; callers opt into the
 * composite by injecting it. Once the coordinator is wired to the composite + the prepare selector, a
 * registered plugin step can flow through the durable spine.
 */
object RegistryStepMetadataResolver {
    fun composite(registry: StepRegistry): StepMetadataResolver =
        StepMetadataResolver { stepKey -> resolve(registry, stepKey) }

    private fun resolve(registry: StepRegistry, stepKey: PluginStepId): StepMetadata {
        if (stepKey.value in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            // Legacy core authority owns legacy executable keys; core behaviour must not change.
            return CanonicalCoreStepMetadata.metadata(stepKey.value)
        }
        val definition = registry.definition(stepKey)
            ?: throw EngineInvariantViolation(
                "No durable metadata for step '${stepKey.value}': not a legacy executable and not in the registry",
            )
        val descriptor = definition.contract.descriptor
        return StepMetadata(
            effects = descriptor.effects.toSet(),
            replayPolicy = descriptor.replayPolicy,
            recoveryPolicy = descriptor.recoveryPolicy,
        )
    }
}
