package dev.rubentxu.pipeline.v2.domain.step

/**
 * C5 — structural cross-check between a [PluginManifest] and the
 * [StepDefinition] set the plugin provides.
 *
 * Rejects:
 * - a [PluginManifest] that does not name every [StepDefinition] key
 *   the plugin wants registered;
 * - a [PluginManifest] whose [StepManifest.declaredCapabilities] set
 *   is not equal to the corresponding
 *   [StepContract.requiredCapabilities] set;
 * - a [PluginManifest] that names a StepKey absent from the provided
 *   definitions.
 *
 * The validator is **deterministic** (no I/O, no clock, no global state)
 * and **fail-closed**: any mismatch raises [IllegalArgumentException]
 * with a diagnostic that names the StepKey and the discrepancy, so the
 * plugin author can fix the manifest before re-registering.
 *
 * The validator does NOT touch the registry. It is invoked by plugin
 * authors at construction time of [StepRegistration]; the registry
 * itself stays semantically neutral (its only invariant is duplicate-key
 * rejection).
 */
object PluginManifestValidator {

    /**
     * Validates that the [manifest] is consistent with the [definitions]
     * the plugin author wants to register.
     *
     * @throws IllegalArgumentException on any structural mismatch.
     */
    fun validate(manifest: PluginManifest, definitions: List<StepDefinition<*, *>>) {
        val definitionKeys = definitions.map { it.contract.key }.toSet()
        val manifestKeys = manifest.stepManifests.map { it.stepKey }.toSet()

        // (a) Manifest names StepKeys absent from the supplied definitions.
        val missing = manifestKeys - definitionKeys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "PluginManifest references StepKeys not provided by these StepDefinitions: $missing",
            )
        }

        // (b) Supplied definitions without a manifest entry (also fail-closed
        //     for the C5 happy path: the manifest is the authoritative declaration).
        val undeclared = definitionKeys - manifestKeys
        if (undeclared.isNotEmpty()) {
            throw IllegalArgumentException(
                "PluginManifest is missing entries for supplied StepDefinitions: $undeclared",
            )
        }

        // (c) For each Step, declaredCapabilities must equal contract.requiredCapabilities.
        val definitionsByKey = definitions.associateBy { it.contract.key }
        for (entry in manifest.stepManifests) {
            val def = definitionsByKey[entry.stepKey]
                ?: error("internal validator invariant broken: ${entry.stepKey} resolved to null after set check")
            val declared = entry.declaredCapabilities
            val required = def.contract.requiredCapabilities
            if (declared != required) {
                throw IllegalArgumentException(
                    "PluginManifest capabilities mismatch for ${entry.stepKey.value}: " +
                        "declared=$declared contract=$required (every declared capability must appear " +
                        "in the contract and vice-versa)",
                )
            }
        }
    }
}
