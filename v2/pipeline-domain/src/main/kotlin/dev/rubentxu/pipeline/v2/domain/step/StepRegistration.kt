package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Composition point between a [StepDefinition] and its
 * [StepProviderMetadata] (PLUGIN_IDENTITY_MODEL §"Step registration seam").
 *
 * `StepDefinition` is the executable shape; `StepProviderMetadata` is
 * the provenance/distribution shape. They are composed here, not in
 * `StepContract` (which stays semantically minimal).
 *
 * This is the **additive** registration shape. The legacy
 * `register(StepDefinition)` overload on [StepRegistry] is unchanged.
 */
data class StepRegistration<I : Any, O : Any>(
    val definition: StepDefinition<I, O>,
    val provider: StepProviderMetadata,
) {
    /**
     * The Step key derived from the inner definition. Exposed so the
     * registry can use it as the map key without leaking the
     * `StepDefinition` boundary.
     */
    val stepKey: PluginStepId get() = definition.contract.key
}
