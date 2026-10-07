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
 * S6-COMPOSITION, pass 2 — the declaration/implementation cross-check, wired to the product.
 *
 * ## What this closes
 *
 * [dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin.admitContributions] compares what a plugin
 * DECLARED in its manifest against what its contributor ACTUALLY provides, across Steps,
 * Directives and Events. It existed and was correct — and was never called from any production
 * path. `PluginAdmissionGate.admitThenLoad` calls it, and every one of that gate's eight callers
 * was in `src/test`. The installed `pipelinek` reached plugins through `PluginComposition.resolve`,
 * which admitted nothing and cross-checked nothing.
 *
 * The consequence was measured on the installed distribution: a well-formed manifest declaring a
 * Step (`example.uppercase.ghost`) the plugin does not implement was ADMITTED and the run finished
 * SUCCESS. Pass 1 (pre-load, manifest-only) cannot catch it, because a lying manifest is a valid
 * document; only comparing it against real contributions can.
 *
 * ## Why this runs here, after pass 1, under the composition window
 *
 * The cross-check needs the contributor's own output, so the contributor's code has to have run —
 * which is exactly what pass 1 exists to prevent before the decision. Both facts are true, and the
 * resolution is the ordering: pass 1 admits the ARTIFACT from its manifest before any code runs;
 * only then does this phase instantiate the (already-admitted) contributors and verify their output
 * matches what they declared. A refusal here stops the composition and names the drift.
 *
 * ## Why contributions are bucketed BY ARTIFACT
 *
 * `ServiceLoader` returns EVERY contributor on the classloader. An unscoped cross-check would
 * compare one plugin's manifest against every other plugin's Steps and report them all as
 * undeclared — and, worse, the reverse direction (implemented-but-undeclared) would refuse a
 * perfectly honest plugin for a neighbour's Steps. A plugin's cross-check is about ITS OWN
 * contributions, so providers are attributed to the artifact whose code source they came from,
 * the same scoping `PluginAdmissionGate` applies. All THREE families are scoped by that one rule:
 * a Step is not "this plugin's" in some looser sense a Directive is not.
 *
 * ## Fail-closed, symmetrically
 *
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
     *
     * @throws PluginContributionRefusal naming the artifact and the typed rejection, the
     *   moment a plugin's contributions drift from its manifest.
     */
    fun verify(admitted: List<AdmittedArtifact>, classLoader: ClassLoader) {
        if (admitted.isEmpty()) return

        // Bucket each family's contributions by the ARTIFACT that produced them, in one pass per
        // family, so a plugin is only ever compared against its own output.
        val stepsByArtifact = mutableMapOf<Path, MutableList<StepDefinition<*, *>>>()
        val directivesByArtifact = mutableMapOf<Path, MutableSet<DirectiveKey>>()
        val eventsByArtifact = mutableMapOf<Path, MutableSet<String>>()

        for (provider in ServiceLoader.load(StepDefinitionContributor::class.java, classLoader)) {
            val origin = originOf(provider) ?: continue
            for (registration in provider.registrations()) {
                stepsByArtifact.getOrPut(origin) { mutableListOf() }.add(registration.definition)
            }
        }

        for (provider in ServiceLoader.load(DirectiveContributor::class.java, classLoader)) {
            val origin = originOf(provider) ?: continue
            val keys = directivesByArtifact.getOrPut(origin) { mutableSetOf() }
            for (definition in provider.definitions()) {
                keys.add(definition.key)
            }
        }

        for (provider in ServiceLoader.load(EventDefinitionContributor::class.java, classLoader)) {
            val origin = originOf(provider) ?: continue
            val kinds = eventsByArtifact.getOrPut(origin) { mutableSetOf() }
            for (creation in provider.definitions()) {
                when (creation) {
                    is EventDefinitionCreation.Invalid -> throw PluginContributionRefusal(
                        artifact = origin,
                        rejection = PluginManifestRejection.MalformedDocument(
                            "contributor ${provider.id} declared a malformed event: " +
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
                throw PluginContributionRefusal(
                    artifact = artifact.path,
                    rejection = PluginManifestRejection.InvalidManifest(
                        crossCheck.pluginId,
                        crossCheck.describe(),
                    ),
                )
            }
        }
    }

    /**
     * The artifact a provider instance was loaded from, as a normalised [Path], or null when the
     * provider has no readable code source.
     *
     * Normalisation is what lets an artifact PATH (the `--plugin-jar` string or the bundled-plan
     * entry) be compared with a provider's `codeSource` URL: both are reduced to the same absolute,
     * normalised file path. A null result — a provider with no code source — is skipped rather than
     * attributed to every artifact, because attributing it to all of them would compare one
     * plugin's manifest against a neighbour's contributions.
     */
    private fun originOf(provider: Any): Path? = runCatching {
        provider.javaClass.protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { Path.of(it) }
            ?.toAbsolutePath()
            ?.normalize()
    }.getOrNull()
}

/**
 * A plugin's contributions drifted from its manifest.
 *
 * Carries the typed [PluginManifestRejection] rather than only a message, so the admission boundary
 * converts it back into a value ([CompositionOutcome.Refused]) and the CLI can exit 2 naming the
 * artifact — instead of this exception becoming a raw stack trace at the top of `main`.
 */
class PluginContributionRefusal(
    val artifact: Path,
    val rejection: PluginManifestRejection,
) : IllegalStateException("plugin contribution cross-check failed for $artifact: $rejection")
