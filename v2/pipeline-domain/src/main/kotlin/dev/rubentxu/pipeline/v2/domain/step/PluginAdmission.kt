package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Accepted
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Rejected

/**
 * S6/D — THE single authority that decides whether a plugin may join the runtime.
 *
 * ## The responsibility this inverts
 *
 * Before this type, `PluginManifestValidator` was called from INSIDE each plugin: the
 * contributor built its own manifest, validated it, and returned registrations. That is a
 * plugin that validates itself, and a plugin can always forget to. Nothing in the runtime
 * could tell the difference between "admitted" and "this plugin happened to cooperate".
 *
 * Here the direction is reversed. The plugin declares a DOCUMENT. [admit] decides. The
 * contributor's code is not consulted until it has already been admitted, which is the
 * ordering that ADR-EVO-003 requires: read the manifest, validate it, admit it, and only
 * then load code.
 *
 * ## What [admit] does NOT do
 *
 * It never touches a `ServiceLoader`, a classpath, or a contributor. It is a pure decision
 * over three already-collected inputs, which is what makes it testable without constructing
 * a plugin, a classloader or a runtime — and what makes the ordering in the composition root
 * a property of the type rather than a comment promising somebody read it.
 *
 * ## Duplicate handling
 *
 * Neither first-wins nor last-wins: a second plugin claiming an already-admitted identity is
 * [PluginManifestRejection.DuplicateIdentity], naming both. Silently preferring either one
 * would make the resulting registry a function of classpath order, which is the property
 * this whole block exists to remove.
 */
object PluginAdmission {

    /**
     * Admit one plugin.
     *
     * @param decoded the result of reading the artifact's manifest document. Passing the
     *   decode RESULT rather than a manifest is what makes a malformed document
     *   unrepresentable at this boundary: there is no overload that accepts a manifest
     *   some earlier step invented.
     * @param identity the identity the runtime measured, kept separate from the digest the
     *   manifest claims about itself.
     * @param runtimeVersion the PipelineK version running, for the API range comparison.
     * @param alreadyAdmitted identities admitted so far in this composition.
     */
    fun admit(
        decoded: PluginManifestDecodeResult,
        identity: MeasuredArtifactIdentity,
        runtimeVersion: SemVer,
        alreadyAdmitted: Set<String>,
    ): PluginAdmissionResult {
        val manifest = when (decoded) {
            is Rejected -> return PluginAdmissionResult.Refused(decoded.rejection)
            is Accepted -> decoded.manifest
        }

        val identityValue = manifest.plugin.canonicalText()

        val incumbent = alreadyAdmitted.firstOrNull { it == identityValue }
        if (incumbent != null) {
            return PluginAdmissionResult.Refused(
                PluginManifestRejection.DuplicateIdentity(identityValue, incumbent),
            )
        }

        if (!manifest.apiRange.accepts(runtimeVersion)) {
            return PluginAdmissionResult.Refused(
                PluginManifestRejection.IncompatibleApiRange(
                    identity = identityValue,
                    declared = manifest.apiRange,
                    runtime = runtimeVersion,
                ),
            )
        }

        return PluginAdmissionResult.Admitted(AdmittedPlugin(manifest, identity))
    }
}

/**
 * Closed outcome of admission. There is no "admitted with warnings": a plugin either has a
 * proven identity and a compatible declaration, or it is refused with a reason that names
 * what was wrong. A warning path here would recreate exactly the fail-open behaviour that
 * moving validation out of the plugins was meant to end.
 */
sealed interface PluginAdmissionResult {

    data class Admitted(val plugin: AdmittedPlugin) : PluginAdmissionResult

    data class Refused(val rejection: PluginManifestRejection) : PluginAdmissionResult
}

/**
 * S6/E — a plugin that was admitted, with what it declared and what it actually provides.
 *
 * This is the shape a hot path resolves from. It deliberately holds the manifest AND the
 * runtime contributions together, so answering "who owns this StepKey" and "what does this
 * plugin require" never means asking the plugin again, and never means re-deciding whether
 * it was allowed.
 *
 * Cross-checking declaration against implementation happens in [admitContributions], NOT
 * in [PluginAdmission.admit], because it requires the contributor's output and therefore
 * runs after its code has loaded. Keeping the two apart is the point: the first gate is
 * pre-load and cheap, the second is post-load and complete, and collapsing them would either
 * run code before admission or make admission incomplete.
 */
data class AdmittedPlugin(
    val manifest: PluginManifest,
    val identity: MeasuredArtifactIdentity,
) {
    val pluginId: String get() = manifest.plugin.canonicalText()

    val apiRange: PipelineKApiRange get() = manifest.apiRange

    /**
     * Post-load cross-check: the declaration must match the implementation exactly, in both
     * directions, for every family.
     *
     * Runs after contributors are instantiated, which is the earliest moment the comparison
     * is possible. Nothing here completes by default: a declared Step with no definition, a
     * definition with no declaration, or a capability set that differs from the contract is
     * a refusal, never a warning and never a silent success.
     */
    fun admitContributions(
        definitions: List<StepDefinition<*, *>>,
        directiveKeys: Set<DirectiveKey> = emptySet(),
        eventKinds: Set<String> = emptySet(),
    ): PluginContributionCrossCheck {
        val declared = manifest.contributions
        val providedKeys = definitions.map { it.contract.key }.toSet()
        val declaredKeys = declared.steps.map { it.stepKey }.toSet()

        val missingImplementation = declaredKeys - providedKeys
        val undeclaredImplementation = providedKeys - declaredKeys

        val mismatchedCapabilities = declared.steps.mapNotNull { entry ->
            val contract = definitions.firstOrNull { it.contract.key == entry.stepKey }?.contract
            val required = contract?.requiredCapabilities
            if (required != null && required != entry.declaredCapabilities) {
                entry.stepKey to (entry.declaredCapabilities to required)
            } else {
                null
            }
        }

        val declaredDirectives = declared.directives.map { it.directiveKey }.toSet()
        val declaredEvents = declared.events.map { it.eventKind }.toSet()

        return PluginContributionCrossCheck(
            pluginId = pluginId,
            missingImplementation = missingImplementation,
            undeclaredImplementation = undeclaredImplementation,
            mismatchedCapabilities = mismatchedCapabilities,
            missingDirectives = declaredDirectives - directiveKeys,
            undeclaredDirectives = directiveKeys - declaredDirectives,
            missingEvents = declaredEvents - eventKinds,
            undeclaredEvents = eventKinds - declaredEvents,
        )
    }
}

/**
 * The result of comparing a declaration against real contributions.
 *
 * Every difference is kept as a named set rather than collapsing to a boolean, because the
 * caller has to report WHICH key failed and a boolean would make it re-derive the same
 * comparison to produce that message.
 */
data class PluginContributionCrossCheck(
    val pluginId: String,
    val missingImplementation: Set<PluginStepId>,
    val undeclaredImplementation: Set<PluginStepId>,
    val mismatchedCapabilities: List<Pair<PluginStepId, Pair<Set<StepCapability>, Set<StepCapability>>>>,
    val missingDirectives: Set<DirectiveKey>,
    val undeclaredDirectives: Set<DirectiveKey>,
    val missingEvents: Set<String>,
    val undeclaredEvents: Set<String>,
) {
    /** True when declaration and implementation agree exactly. Nothing here defaults. */
    val isConsistent: Boolean
        get() = missingImplementation.isEmpty() &&
            undeclaredImplementation.isEmpty() &&
            mismatchedCapabilities.isEmpty() &&
            missingDirectives.isEmpty() &&
            undeclaredDirectives.isEmpty() &&
            missingEvents.isEmpty() &&
            undeclaredEvents.isEmpty()

    /** Human-readable diagnosis naming the actual key, for the refusal message. */
    fun describe(): String = buildString {
        append(pluginId).append(": ")
        if (missingImplementation.isNotEmpty()) append("declared Steps with no implementation: $missingImplementation. ")
        if (undeclaredImplementation.isNotEmpty()) append("implemented Steps absent from the manifest: $undeclaredImplementation. ")
        if (mismatchedCapabilities.isNotEmpty()) {
            append("capabilities that differ from the contract: ")
            append(mismatchedCapabilities.joinToString("; ") { (key, pair) -> "$key declared=${pair.first} contract=${pair.second}" })
            append(". ")
        }
        if (missingDirectives.isNotEmpty()) append("declared Directives with no contributor: $missingDirectives. ")
        if (undeclaredDirectives.isNotEmpty()) append("contributed Directives absent from the manifest: $undeclaredDirectives. ")
        if (missingEvents.isNotEmpty()) append("declared Events with no contributor: $missingEvents. ")
        if (undeclaredEvents.isNotEmpty()) append("contributed Events absent from the manifest: $undeclaredEvents. ")
    }
}
