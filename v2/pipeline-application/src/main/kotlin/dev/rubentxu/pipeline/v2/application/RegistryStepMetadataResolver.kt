package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * CDE.3-d5b: [StepMetadataResolver] composite over an open [StepRegistry] plus the legacy core catalog.
 *
 * The durable protocol resolves pre-decode metadata by structural step key only (never a concrete Step
 * name). This composite keeps that true across both worlds:
 *  - a legacy core key (in [CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS]) delegates to the legacy core
 *    authority [CanonicalCoreStepMetadata], so core semantics (and their sh behaviour) are unchanged
 *    even if a definition with the same key is also registered;
 *  - any OTHER key resolves through the registry: its definition's descriptor
 *    ([StepContract.descriptor]) provides the durable [StepMetadata] (effects + replayPolicy), which is
 *    exactly the pre-decode contract the coordinator fingerprints and reconciles on;
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

    private fun resolve(registry: StepRegistry, stepKey: PluginStepId): StepMetadata =
        if (stepKey.value in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            // Legacy core authority owns legacy executable keys; core behaviour must not change.
            CanonicalCoreStepMetadata.metadata(stepKey.value)
        } else {
            val definition = registry.definition(stepKey)
                ?: throw EngineInvariantViolation(
                    "No durable metadata for step '${stepKey.value}': not a legacy executable and not in the registry",
                )
            val contract = definition.contract
            StepMetadata(
                effects = contract.descriptor.effects.toSet(),
                replayPolicy = contract.descriptor.replayPolicy,
            )
        }
}
