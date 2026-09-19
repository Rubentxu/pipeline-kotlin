package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef

/**
 * Provenance + distribution metadata for a Step family
 * (PLUGIN_IDENTITY_MODEL).
 *
 * The metadata carries TWO dimensions:
 * - the **logical plugin** ([plugin]: `ResourceRef(PLUGIN, namespace,
 *   identity)`)
 * - the **immutable artifact** ([release]: `PluginReleaseRef` with
 *   version + digest)
 *
 * The two dimensions are kept separate. The invariant that
 * `release.plugin == plugin` is enforced by [create] (the only public
 * constructor) and by the constructor's `init` block. A mismatched pair
 * is rejected at construction time, never silently collapsed.
 *
 * [families] is a non-empty set (multi-family allowed). [delivery] is
 * **metadata**, never a verdict (PLUGIN_IDENTITY_MODEL §"Delivery
 * classification vs trust decision"). [trust] is the typed trust ADT
 * (only [TrustMetadata.Unverified] exists today).
 *
 * The primary constructor is `internal` to force the use of [create],
 * which centralises the cross-field invariant.
 */
@ConsistentCopyVisibility
data class StepProviderMetadata internal constructor(
    val plugin: ResourceRef,
    val release: PluginReleaseRef,
    val publisher: String,
    val families: Set<PluginFamily>,
    val delivery: Delivery,
    val trust: TrustMetadata,
) {
    init {
        require(release.plugin == plugin) {
            "StepProviderMetadata invariant: release.plugin ($release.plugin) must equal plugin ($plugin)"
        }
        require(families.isNotEmpty()) {
            "StepProviderMetadata.families must be a non-empty set (got empty)"
        }
        require(publisher.isNotBlank()) {
            "StepProviderMetadata.publisher must not be blank"
        }
        require(plugin.kind == dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.PLUGIN) {
            "StepProviderMetadata.plugin must be a PLUGIN ResourceRef (was ${plugin.kind})"
        }
    }

    companion object {
        /**
         * Public factory enforcing all cross-field invariants in one place.
         * Any future invariant (cross-field sanity) belongs here.
         */
        fun create(
            plugin: ResourceRef,
            release: PluginReleaseRef,
            publisher: String,
            families: Set<PluginFamily>,
            delivery: Delivery,
            trust: TrustMetadata,
        ): StepProviderMetadata = StepProviderMetadata(
            plugin = plugin,
            release = release,
            publisher = publisher,
            families = families,
            delivery = delivery,
            trust = trust,
        )
    }
}
