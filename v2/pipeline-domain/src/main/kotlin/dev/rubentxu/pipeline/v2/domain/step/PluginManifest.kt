package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef

/**
 * Plugin manifest declaration (PLUGIN_IDENTITY_MODEL, evolved by S6/B1).
 *
 * A plugin author constructs one [PluginManifest] for the whole plugin. The
 * manifest is the machine-readable declaration of **who the plugin is**, **which
 * PipelineK versions it supports**, and **what structural shapes it contributes**.
 *
 * ## The one authority
 *
 * This type is the single declaration authority. Section 2 of the plugin SDK
 * package spells the same shape as `PipelineKPluginManifest`, but that is
 * contractual pseudocode: minting a second type beside this one would leave two
 * models of "what a plugin declares", and the second would be authoritative for
 * nobody. So the package's name is documentation, and this type is the domain.
 *
 * ## What changed in S6/B1, and what did not
 *
 * Added: [schemaVersion], [apiRange], and [contributions] covering the four
 * supported families (Step, Directive, Event, Capability).
 *
 * Kept: [families], which answers "what domain is this for" and is NOT derivable
 * from [contributions]. See [PluginContributions] for why the two are orthogonal.
 *
 * ## This is a DECLARATION, not a trust verdict
 *
 * [trust] only carries `Unverified` today. Validation enforced here is structural,
 * never trust-based.
 */
data class PluginManifest(
    val schemaVersion: ManifestSchemaVersion,
    val plugin: ResourceRef,
    val release: PluginReleaseRef,
    val apiRange: PipelineKApiRange,
    val publisher: String,
    val families: Set<PluginFamily>,
    val delivery: Delivery,
    val trust: TrustMetadata,
    val contributions: PluginContributions,
) {
    init {
        require(schemaVersion == ManifestSchemaVersion.CURRENT) {
            "PluginManifest.schemaVersion must be ${ManifestSchemaVersion.CURRENT} (was $schemaVersion); " +
                "an unknown schema is refused rather than guessed"
        }
        require(plugin == release.plugin) {
            "PluginManifest.plugin ($plugin) must be the same ResourceRef as release.plugin " +
                "(${release.plugin}); a manifest whose identity and release disagree has no " +
                "single answer to 'which plugin is this'"
        }
        require(families.isNotEmpty()) {
            "PluginManifest.families must be a non-empty set"
        }
        require(publisher.isNotBlank()) {
            "PluginManifest.publisher must not be blank"
        }
        require(!contributions.isEmpty) {
            "PluginManifest.contributions must declare at least one contribution. A plugin that " +
                "contributes nothing has no reason to be admitted, and admitting one would put " +
                "an empty plugin on the classpath with an identity"
        }
    }
}

/**
 * One Step family's declaration inside a [PluginManifest].
 *
 * [declaredCapabilities] is what the plugin author claims the Step requires. The
 * runtime contract is the [StepContract.requiredCapabilities] the registry sees.
 * Cross-checking the two is [PluginManifestValidator]'s job.
 *
 * Retained as a named alias for [PluginStepContribution] so existing readers keep
 * their vocabulary while there is exactly ONE contribution type for a Step.
 */
typealias StepManifest = PluginStepContribution
