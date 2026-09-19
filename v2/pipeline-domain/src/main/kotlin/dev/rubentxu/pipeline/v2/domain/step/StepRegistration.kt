package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs

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

    companion object {
        /**
         * Wraps a [StepDefinition] into a [StepRegistration] with the
         * **legacy-shape** provider metadata (LFC-2E2 / F5.1 / ADR-0092 /
         * C10 backwards-compat). Used by the default
         * [StepDefinitionContributor.registrations] implementation so
         * legacy contributors (CORE Steps, `example.uppercase`, …) keep
         * working under the additive loader without any code change.
         *
         * The legacy shape:
         * - publisher = [publisher] (caller-supplied; the contributor picks it)
         * - plugin = `ResourceRefs.plugin("legacy", "<key.value>")` — the
         *   logical plugin identity is synthesised from the Step key, so
         *   legacy Steps remain addressable as resources in audit
         *   projections but never claim an OFFICIAL_PLUGIN delivery.
         * - release = a `PluginReleaseRef` bound to the synthetic plugin,
         *   version `0.0.0`, digest `sha256:` + 64 zeros (sentinel; never
         *   asserted as a real artefact digest).
         * - families = a single-element set `[SCM]` if the key starts with
         *   `scm-git.`, otherwise `[GENERIC]` — this is metadata, not a
         *   verdict; the registry never branches on it.
         * - delivery = [Delivery.CORE] when [publisher] starts with
         *   `legacy-core`, otherwise [Delivery.EXTERNAL_REFERENCE].
         * - trust = [TrustMetadata.Unverified] (only existing state).
         *
         * Legacy registrations DO carry a [StepProviderMetadata] entry in
         * the registry (so [StepRegistry.providerOf] returns non-null for
         * legacy keys). The C8 projector still returns `null` for the
         * legacy path because the runner passes `null` as `providerLookup`
         * to the projection seam (see `EnvelopeProjectingEventSink`
         * wiring in F5.1 / CanonicalDurableRunCoordinator).
         */
        fun legacy(definition: StepDefinition<*, *>, publisher: String): StepRegistration<*, *> {
            val key = definition.contract.key
            val syntheticNs = "legacy"
            val plugin: ResourceRef = ResourceRefs.plugin(syntheticNs, key.value)
            val release = PluginReleaseRef(
                plugin = plugin,
                version = SemVer(0, 0, 0),
                digest = Digest("sha256:" + "0".repeat(64)),
            )
            val families: Set<PluginFamily> = if (key.value.startsWith("scm-git.")) {
                setOf(PluginFamily.SCM)
            } else {
                setOf(PluginFamily.UTILITIES)
            }
            val delivery = if (publisher.startsWith("legacy-core")) Delivery.CORE else Delivery.EXTERNAL_REFERENCE
            val provider = StepProviderMetadata.create(
                plugin = plugin,
                release = release,
                publisher = publisher,
                families = families,
                delivery = delivery,
                trust = TrustMetadata.Unverified,
            )
            return StepRegistration(definition, provider)
        }
    }
}
