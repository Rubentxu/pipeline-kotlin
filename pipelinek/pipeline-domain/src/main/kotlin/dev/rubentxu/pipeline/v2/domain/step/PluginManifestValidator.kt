package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

/**
 * Structural cross-check between a [PluginManifest] and the contributions the
 * plugin actually provides.
 *
 * ## What it checks
 *
 * - every Step in the supplied definitions has a manifest entry, and every manifest
 *   Step has a definition (neither direction may drift);
 * - per Step, `declaredCapabilities` EQUALS `contract.requiredCapabilities` — a
 *   plugin cannot under-claim to slip past admission, nor over-claim to make the
 *   registry promise a capability the handler never asks for;
 * - every declared Directive and Event resolves against the supplied definitions;
 * - every Step contract's declared required capabilities appear in the manifest's
 *   top-level capability set.
 *
 * ## What it is NOT
 *
 * It is deterministic (no I/O, no clock, no global state) and fail-closed: any
 * mismatch throws with a diagnostic naming the key and the discrepancy.
 *
 * It does NOT decide whether the plugin may be admitted at runtime. Admission (S6/C)
 * runs BEFORE any contributor class is loaded;
 * this validator runs AFTER loading, to prove the implementation matches the
 * declaration. Keeping the two apart is the whole point of the two-phase check.
 */
object PluginManifestValidator {

    /**
     * Validates that [manifest] is consistent with the contributions the plugin supplies.
     *
     * @param manifest the declaration read from the artifact.
     * @param definitions the StepDefinitions the plugin's contributor produced.
     * @param directiveKeys the DirectiveKeys the plugin's directive contributor produced.
     * @param eventKinds the event kinds the plugin's event contributor produced.
     *
     * @throws IllegalArgumentException on any structural mismatch.
     */
    fun validate(
        manifest: PluginManifest,
        definitions: List<StepDefinition<*, *>>,
        directiveKeys: Set<DirectiveKey> = emptySet(),
        eventKinds: Set<String> = emptySet(),
    ) {
        validateSteps(manifest, definitions)
        validateDirectives(manifest, directiveKeys)
        validateEvents(manifest, eventKinds)
        validateDeclaredCapabilitiesAppear(manifest, definitions)
    }

    private fun validateSteps(manifest: PluginManifest, definitions: List<StepDefinition<*, *>>) {
        val definitionKeys = definitions.map { it.contract.key }.toSet()
        val manifestKeys = manifest.contributions.steps.map { it.stepKey }.toSet()

        val absent = manifestKeys - definitionKeys
        require(absent.isEmpty()) {
            "PluginManifest declares StepKeys the plugin does not provide: $absent"
        }

        val undeclared = definitionKeys - manifestKeys
        require(undeclared.isEmpty()) {
            "PluginManifest is missing entries for supplied StepDefinitions: $undeclared"
        }

        val definitionsByKey = definitions.associateBy { it.contract.key }
        for (entry in manifest.contributions.steps) {
            val contract = definitionsByKey.getValue(entry.stepKey).contract
            val declared = entry.declaredCapabilities
            val required = contract.requiredCapabilities
            require(declared == required) {
                "PluginManifest capabilities mismatch for ${entry.stepKey.value}: " +
                    "declared=$declared contract=$required (the declared set must equal the " +
                    "contract set exactly; under-claiming and over-claiming are both defects)"
            }
        }
    }

    private fun validateDirectives(manifest: PluginManifest, provided: Set<DirectiveKey>) {
        val declared = manifest.contributions.directives.map { it.directiveKey }.toSet()

        val absent = declared - provided
        require(absent.isEmpty()) {
            "PluginManifest declares Directives the plugin does not provide: $absent"
        }

        val undeclared = provided - declared
        require(undeclared.isEmpty()) {
            "PluginManifest is missing entries for supplied Directives: $undeclared"
        }
    }

    private fun validateEvents(manifest: PluginManifest, provided: Set<String>) {
        val declared = manifest.contributions.events.map { it.eventKind }.toSet()

        val absent = declared - provided
        require(absent.isEmpty()) {
            "PluginManifest declares Events the plugin does not provide: $absent"
        }

        val undeclared = provided - declared
        require(undeclared.isEmpty()) {
            "PluginManifest is missing entries for supplied Events: $undeclared"
        }
    }

    /**
     * The manifest's top-level capability set and the set its Step contracts actually require
     * must be EQUAL, and both directions are checked.
     *
     * Only one direction was enforced here for a while, and the KDoc claimed the other. That is
     * the defect this row exists to close: the class documented "every required capability
     * appears in the top-level set" while the code computed the reverse, so a plugin whose Step
     * required a capability it never asked for was admitted against something the plugin had
     * not declared it needed. One direction is not a weaker check, it is a check of the wrong
     * set.
     *
     * Over-claiming is the first direction because it is the one that widens what the runtime
     * promises: a capability nobody uses is a broader declaration than the plugin needs.
     * Under-claiming is the second because it is the one that lets a Step demand something the
     * plugin never requested to be supplied.
     */
    private fun validateDeclaredCapabilitiesAppear(
        manifest: PluginManifest,
        definitions: List<StepDefinition<*, *>>,
    ) {
        val topLevel = manifest.contributions.capabilities
        val usedBySteps = definitions.flatMap { it.contract.requiredCapabilities }.toSet()

        val unbacked = topLevel - usedBySteps
        require(unbacked.isEmpty()) {
            "PluginManifest declares top-level capabilities that no Step contract requires: " +
                "$unbacked. A capability nobody uses is a broader declaration than the one the " +
                "plugin actually needs"
        }

        val unrequested = usedBySteps - topLevel
        require(unrequested.isEmpty()) {
            "PluginManifest is missing top-level capability requests that its Step contracts " +
                "require: $unrequested. A Step would be admitted against a capability the plugin " +
                "never declared it needs"
        }
    }
}
