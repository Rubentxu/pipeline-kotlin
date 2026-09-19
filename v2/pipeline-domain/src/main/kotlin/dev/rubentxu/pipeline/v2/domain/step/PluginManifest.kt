package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Plugin manifest declaration (PLUGIN_IDENTITY_MODEL §"Plugin Manifest").
 *
 * A plugin author constructs one [PluginManifest] for the whole plugin
 * and one [StepManifest] per Step the plugin provides. The
 * [PluginManifestValidator] cross-checks each [StepManifest.declaredCapabilities]
 * against the corresponding [StepContract.requiredCapabilities]; a
 * mismatch is rejected at registration time, never silently coerced.
 *
 * The manifest is a **declaration** by the plugin author. It is NOT a
 * trust verdict: [PluginManifest.trust] only carries `Unverified` today.
 * The validation enforced here is structural, not trust-based.
 */
data class PluginManifest(
    val plugin: dev.rubentxu.pipeline.v2.domain.identity.ResourceRef,
    val release: PluginReleaseRef,
    val publisher: String,
    val families: Set<PluginFamily>,
    val delivery: Delivery,
    val trust: TrustMetadata,
    val stepManifests: List<StepManifest>,
) {
    init {
        require(families.isNotEmpty()) {
            "PluginManifest.families must be a non-empty set"
        }
        require(publisher.isNotBlank()) {
            "PluginManifest.publisher must not be blank"
        }
        require(stepManifests.isNotEmpty()) {
            "PluginManifest.stepManifests must be non-empty"
        }
    }
}

/**
 * One Step family's declaration inside a [PluginManifest].
 *
 * [declaredCapabilities] is what the plugin author claims the Step
 * requires. The runtime contract is the [StepContract.requiredCapabilities]
 * the registry sees. Cross-check is the validator's job.
 */
data class StepManifest(
    val stepKey: PluginStepId,
    val declaredCapabilities: Set<StepCapability>,
)
