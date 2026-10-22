package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * The declaration/implementation cross-check, wired to the product.
 * ## What this closes
 * [dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin.admitContributions] compares what a plugin
 * DECLARED in its manifest against what its contributor ACTUALLY provides, across Steps,
 * Directives and Events. It existed and was correct — and was never called from any production
 * path. The gate that called it had eight callers, and every one of them was in `src/test`.
 * The installed `pipelinek` reached plugins through `PluginComposition.resolve`, which
 * admitted nothing and cross-checked nothing.
 * The consequence was measured on the installed distribution: a well-formed manifest declaring a
 * Step (`example.uppercase.ghost`) the plugin does not implement was ADMITTED and the run finished
 * SUCCESS. Pass 1 (pre-load, manifest-only) cannot catch it, because a lying manifest is a valid
 * document; only comparing it against real contributions can.
 * ## Why this runs here, after pass 1, under the composition window
 * The cross-check needs the contributor's own output, so the contributor's code has to have run —
 * which is exactly what pass 1 exists to prevent before the decision. Both facts are true, and the
 * resolution is the ordering: pass 1 admits the ARTIFACT from its manifest before any code runs;
 * only then does this phase instantiate the (already-admitted) contributors and verify their output
 * matches what they declared. A refusal here stops the composition and names the drift.
 * ## Why contributions are bucketed BY ARTIFACT
 * `ServiceLoader` returns EVERY contributor on the classloader. An unscoped cross-check would
 * compare one plugin's manifest against every other plugin's Steps and report them all as
 * undeclared — and, worse, the reverse direction (implemented-but-undeclared) would refuse a
 * perfectly honest plugin for a neighbour's Steps. A plugin's cross-check is about ITS OWN
 * contributions, so providers are attributed to the artifact whose code source they came from,
 * the same scoping the admission gate applies. All THREE families are scoped by that one rule:
 * a Step is not "this plugin's" in some looser sense a Directive is not.
 * ## Why a provider that cannot say where it came from stops the composition
 * Bucketing by artifact only means something when every provider can say which artifact it is.
 * A provider whose origin cannot be measured has no bucket, and a contribution with no bucket
 * cannot be compared against any manifest: it would be invisible to the cross-check in BOTH
 * directions, so a plugin could ship a Step nobody declared and the comparison would not see it.
 * Such a provider is refused, by name, instead of being skipped. It is not attributed to every
 * artifact either: that would compare one plugin's manifest against a neighbour's contributions
 * and hide a real drift behind somebody else's implementation.
 * ## Fail-closed, symmetrically
 * The check is exact in BOTH directions — a declared Step with no definition AND a definition with
 * no declaration are both refusals — and it never warns. A malformed event declaration aborts here
 * with its reasons, matching `ExternalEventDefinitionDiscovery`, rather than being counted as a
 * missing kind and letting the plugin through on a family it got wrong.
*/
internal object PluginContributionVerifier {

    /**
     * Verify every admitted artifact's declarations against the contributions that artifact
     * actually produced. Called from inside [PluginComposition.resolve]'s classloader window with
     * the SAME loader that produced the registries, so the two sides of the comparison are resolved
     * against one loader rather than two that could disagree.
     * @throws PluginContributionRefusal naming the artifact and the typed rejection, the
     *   moment a plugin's contributions drift from its manifest, or naming the provider that
     *   could not be attributed to an artifact.
    */
    fun verify(admitted: List<AdmittedArtifact>, classLoader: ClassLoader) {
        verify(
            admitted = admitted,
            steps = providers(StepDefinitionContributor::class.java, classLoader),
            directives = providers(DirectiveContributor::class.java, classLoader),
            events = providers(EventDefinitionContributor::class.java, classLoader),
        )
    }

    /**
     * The cross-check itself, over contributions whose origin has already been measured.
     *
     * The origin is measured by the caller and arrives here as a value, so the decision this
     * function makes — that a provider which cannot be attributed stops the composition — is a
     * decision about data and not about a classloader, and it can be exercised without a
     * classpath that hides its own origins.
     * @throws PluginContributionRefusal.Unattributed when a provider does not say which artifact
     *   provides it. Its contributions would have no bucket, and a contribution with no bucket is
     *   invisible to the comparison in both directions.
     * @throws PluginContributionRefusal.Drift when an admitted artifact's contributions do not
     *   match what its manifest declared.
    */
    fun verify(
        admitted: List<AdmittedArtifact>,
        steps: List<Attributed<StepDefinitionContributor>>,
        directives: List<Attributed<DirectiveContributor>>,
        events: List<Attributed<EventDefinitionContributor>>,
    ) {
        if (admitted.isEmpty()) return

        // Bucket each family's contributions by the ARTIFACT that produced them, in one pass per
        // family, so a plugin is only ever compared against its own output.
        val stepsByArtifact = mutableMapOf<Path, MutableList<StepDefinition<*, *>>>()
        val directivesByArtifact = mutableMapOf<Path, MutableSet<DirectiveKey>>()
        val eventsByArtifact = mutableMapOf<Path, MutableSet<String>>()

        for (attributed in steps) {
            val origin = attributed.artifact()
            for (registration in attributed.provider.registrations()) {
                stepsByArtifact.getOrPut(origin) { mutableListOf() }.add(registration.definition)
            }
        }

        for (attributed in directives) {
            val origin = attributed.artifact()
            val keys = directivesByArtifact.getOrPut(origin) { mutableSetOf() }
            for (definition in attributed.provider.definitions()) {
                keys.add(definition.key)
            }
        }

        for (attributed in events) {
            val origin = attributed.artifact()
            val kinds = eventsByArtifact.getOrPut(origin) { mutableSetOf() }
            for (creation in attributed.provider.definitions()) {
                when (creation) {
                    is EventDefinitionCreation.Invalid -> throw PluginContributionRefusal.Drift(
                        artifact = origin,
                        rejection = PluginManifestRejection.MalformedDocument(
                            "contributor ${attributed.provider.id} declared a malformed event: " +
                                creation.problems.joinToString("; "),
                        ),
                    )

                    is EventDefinitionCreation.Valid -> kinds.add(creation.definition.kind)
                }
            }
        }

        for (artifact in admitted) {
            val origin = artifact.path.toAbsolutePath().normalize()
            val crossCheck = artifact.plugin.admitContributions(
                definitions = stepsByArtifact[origin].orEmpty(),
                directiveKeys = directivesByArtifact[origin].orEmpty(),
                eventKinds = eventsByArtifact[origin].orEmpty(),
            )
            if (!crossCheck.isConsistent) {
                throw PluginContributionRefusal.Drift(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.InvalidManifest(
                        crossCheck.pluginId,
                        crossCheck.describe(),
                    ),
                )
            }
        }
    }

    /** Every provider of one family in the window, each with the origin measured for it. */
    private fun <T : Any> providers(type: Class<T>, classLoader: ClassLoader): List<Attributed<T>> =
        ServiceLoader.load(type, classLoader).map { Attributed(it, provenanceOf(it)) }

    /** The origin of the code the provider's class was loaded from. */
    private fun provenanceOf(provider: Any): ProviderOrigin =
        ProviderProvenance.of(provider.javaClass.protectionDomain?.codeSource?.location)
}

/**
 * A contribution provider together with the artifact it was measured to come from.
 */
internal data class Attributed<T : Any>(val provider: T, val origin: ProviderOrigin) {

    /**
     * The artifact that provides this contribution, which is the bucket it belongs to.
     * @throws PluginContributionRefusal.Unattributed when the origin could not be measured. The
     *   provider is named, because no artifact can be named for it.
    */
    fun artifact(): Path = when (val measured = origin) {
        is ProviderOrigin.Resolved -> measured.artifact
        is ProviderOrigin.Missing -> throw PluginContributionRefusal.Unattributed(
            providerClass = provider.javaClass.name,
            reason = "the class declares no code source, so no artifact can be named for it",
        )

        is ProviderOrigin.Unreadable -> throw PluginContributionRefusal.Unattributed(
            providerClass = provider.javaClass.name,
            reason = "its code source ${measured.location} is not a file on disk (${measured.reason})",
        )
    }
}

/**
 * The contribution cross-check could not clear the composition.
 *
 * Two shapes, because there are two different facts to report and neither one can stand in for
 * the other:
 *  - [Drift]: an admitted artifact's contributions do not match its manifest. The artifact is
 *    known and the drift is named.
 *  - [Unattributed]: a provider in the composition window does not say which artifact provides
 *    it, so it cannot be compared against anything. There is no artifact to name, and the
 *    provider itself is named instead.
 *
 * The refusal carries the typed reason rather than only a message, so the admission boundary
 * converts it back into a value ([CompositionOutcome.Refused]) and the CLI can exit 2 naming
 * what was wrong — instead of this exception becoming a raw stack trace at the top of `main`.
*/
sealed class PluginContributionRefusal(message: String) : IllegalStateException(message) {

    /** An admitted artifact's contributions drifted from its manifest. */
    class Drift(val artifact: Path, val rejection: PluginManifestRejection) :
        PluginContributionRefusal("plugin contribution cross-check failed for $artifact: $rejection")

    /** A provider could not be attributed to an artifact, so it could not be judged. */
    class Unattributed(val providerClass: String, val reason: String) :
        PluginContributionRefusal(
            "plugin contribution cross-check cannot judge provider $providerClass: $reason",
        )
}
