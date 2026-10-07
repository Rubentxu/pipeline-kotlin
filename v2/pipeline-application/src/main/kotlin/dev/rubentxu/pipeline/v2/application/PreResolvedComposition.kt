package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import java.nio.file.Path

/**
 * S6/G — everything a plugin contributes, resolved ONCE, before the run exists.
 *
 * ## What this replaces
 *
 * Composition used to happen in three places, at three different times, under three separate
 * classloader swaps:
 *
 * - `Main.kt` composed the Step registry, **twice** — once in the in-memory branch and once in
 *   the durable branch — with the same block of code written out twice;
 * - `CompositionRoot` composed the event registry inside its own body;
 * - `CompositionRoot` composed the directive registry inside its own body, further down.
 *
 * Three authorities for one question ("what does this installation contribute?") means a run
 * could observe two different answers: a plugin's Step present while its Directive was not yet
 * folded in, or an eligibility analysis already computed against a Step registry that a later
 * throw would discard. `runCanonicalPipeline` also defaulted `stepRegistry` to
 * `CoreStepRegistryFactory.registry()`, so a caller who forgot to pass one got a silent CORE-ONLY
 * registry — a fail-open path through exactly the admission guarantee BLOCK 1-D/1-E built.
 *
 * ## The law this type states
 *
 * A run either has a complete, frozen composition or it has none. There is no state in which
 * steps are composed and directives are not, because this value cannot be constructed that way:
 * the constructor runs all three compositions and a failure in any of them means the value does
 * not exist. Fail-closed is therefore a property of the construction, not a check somebody
 * remembers to run.
 *
 * The three registries are frozen independently and deliberately: they answer different
 * questions (which Steps, which Directives, which Event kinds), they are built by different
 * contributors, and forcing them into one shared map would couple three kinds that the external
 * Library Constitution requires to stay independent.
 */
data class PreResolvedComposition internal constructor(
    /** Core Steps plus every external Step the plugins contributed. Frozen. */
    val steps: StepRegistry,
    /** Core Directives plus every external Directive. Frozen. */
    val directives: DirectiveRegistry,
    /** Every Event kind the plugins declared, cross-checked against what they emit. Frozen. */
    val events: EventRegistry,
    /** Plugin ids that contributed Steps, in discovery order. Diagnostics, not authority. */
    val discoveredStepPlugins: List<String>,
    /** Plugin ids that contributed Directives, in discovery order. Diagnostics, not authority. */
    val discoveredDirectivePlugins: List<String>,
    /**
     * Every [RuntimeCapabilityContributor] the plugins declared.
     *
     * This was the FOURTH authority, and it was resolved outside this window. Discovery ran
     * `ServiceLoader` without swapping the TCCL, as a default argument evaluated AFTER the swap
     * was undone — so a plugin whose classes live below the TCCL contributed its Step and kept
     * its capability. `PluginCapabilityLoaderAlignmentTest` measured exactly that with a plugin
     * the application does not depend on, and it is the same failure
     * `ExternalCapabilityContributorDiscovery` records in its own KDoc for `http.request`.
     *
     * It belongs here because a capability is only meaningful alongside the Step that requires
     * it: resolving the two under different loaders produces a registry that admits a Step and a
     * runtime that cannot supply what the Step needs.
     */
    val capabilityContributors: List<RuntimeCapabilityContributor>,
) {
    /**
     * What the installation actually contributed, in the words an operator already reads.
     *
     * Centralised here so the three lines cannot drift apart, and so the answer travels with
     * the value that produced it instead of being printed by three callers at three moments.
     */
    fun reportTo(sink: (String) -> Unit) {
        if (discoveredStepPlugins.isNotEmpty()) {
            sink("Discovered external Step plugins: " + discoveredStepPlugins.joinToString(", "))
        }
        if (discoveredDirectivePlugins.isNotEmpty()) {
            sink(
                "Discovered external directive plugins: " +
                    discoveredDirectivePlugins.joinToString(", "),
            )
        }
        if (events.size() > 0) {
            sink("Discovered external event definitions: " + events.registeredKinds().joinToString(", "))
        }
    }
}

/**
 * S6-COMPOSITION — the product's ONE path from "these artifacts are on the classpath" to "a frozen
 * composition exists".
 *
 * ## Why this is a single function and not two call sites
 *
 * `Main` reaches composition from two branches, the in-memory default and the durable one. Before
 * this function each one wrote its own `pluginClassLoaderFor(...)` followed by its own
 * `PluginComposition.resolve(...)` — and BLOCK 1-G already had to delete a duplicated Step-registry
 * composition for exactly that reason. Adding admission to both would have re-created the same
 * defect with a worse consequence: one branch could admit what the other refused.
 *
 * ## The ordering, and why it is the whole point
 *
 * ```text
 * enumerate artifacts   (BundledPluginClasspathPlan, by service-file TEXT, no class loading)
 *      -> pass 1        (PreLoadPluginAdmission: read manifest, admit, refuse named)
 *      -> pass 2        (ServiceLoader over ONLY admitted artifacts, then freeze)
 * ```
 *
 * A refusal means pass 2 never starts. That is not a policy choice bolted on: `ServiceLoader`
 * instantiates providers while iterating, so composing first and admitting after would run plugin
 * code before the decision that is supposed to precede it, and would be worse than the current
 * no-admission-at-all state because it would read as compliant.
 */
object PluginCompositionAdmitter {

    /**
     * @param pluginJars the `--plugin-jar` entries. Bundled plugins are discovered from the
     *   runtime classpath, so passing only these is correct and passing a list that omits them
     *   would under-admit.
     */
    fun admitThenCompose(pluginJars: List<String>): CompositionOutcome {
        // `distinct()` because a user may name a bundled JAR again on --plugin-jar. Admitting the
        // same artifact twice would refuse it as a duplicate identity, which is the correct verdict
        // for two DIFFERENT artifacts and a bug report for the same path listed twice.
        val artifacts = (computeBundledPlugins() + pluginJars)
            .distinct()
            .map { Path.of(it) }

        if (artifacts.isEmpty()) {
            // Not a degraded case: an installation with no plugin JARs still composes, it just
            // composes core only. Written out rather than defaulted so "no plugins" stays a
            // decision rather than an accident.
            return CompositionOutcome.Composed(
                composition = PluginComposition.resolve(null),
                admitted = emptyList(),
            )
        }

        val runtimeVersion = try {
            RuntimeApiVersion.current()
        } catch (e: IllegalStateException) {
            // A packaging defect, reported as its own case rather than as a plugin rejection: no
            // plugin is at fault and naming one would be a lie.
            return CompositionOutcome.RuntimeVersionUnavailable(e.message ?: "unknown version defect")
        }

        return when (val admission = PreLoadPluginAdmission.admitAll(artifacts, runtimeVersion)) {
            is AdmittedArtifacts.Refused -> CompositionOutcome.Refused(
                artifact = admission.artifact,
                rejection = admission.rejection,
            )

            is AdmittedArtifacts.Admitted -> {
                val loader = pluginClassLoaderFor(admission.artifacts.map { it.path.toString() })
                try {
                    CompositionOutcome.Composed(
                        // Pass 2 sees ONLY admitted artifacts. The parent loader still exposes the
                        // bundled JARs, and every one of them is in `admission.artifacts`, so nothing
                        // reachable through ServiceLoader escaped pass 1. The same list is handed to
                        // the cross-check so a plugin is verified against its OWN contributions.
                        composition = PluginComposition.resolve(loader, admission.artifacts),
                        admitted = admission.artifacts,
                    )
                } catch (e: PluginContributionRefusal) {
                    // Pass 1 admitted the artifact; pass 2 caught the artifact LYING about what it
                    // provides. Turn it back into the same typed refusal a pass-1 rejection produces,
                    // so the CLI exits 2 naming the artifact and the drift, uniformly.
                    CompositionOutcome.Refused(e.artifact, e.rejection)
                }
            }
        }
    }
}

/** Closed outcome of admitting then composing. Pass 2 does not run on a refusal. */
sealed interface CompositionOutcome {

    data class Composed(
        val composition: PreResolvedComposition,
        val admitted: List<AdmittedArtifact>,
    ) : CompositionOutcome

    /** One named artifact, one typed reason. Never "some plugin could not be admitted". */
    data class Refused(
        val artifact: Path,
        val rejection: dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection,
    ) : CompositionOutcome

    /** The running artifact carries no usable version, so no plugin could be judged. */
    data class RuntimeVersionUnavailable(val detail: String) : CompositionOutcome
}

/**
 * The ONE composition authority. Nothing else in the codebase may build a Step, Directive or
 * Event registry for a run.
 */
object PluginComposition {

    /**
     * Resolve every plugin contribution, once, under a single classloader swap.
     *
     * The swap is the point of doing this in one place rather than three: a registry naming a
     * codec the executing side cannot load otherwise fails at emission as a ClassCastException
     * rather than at composition as a refusal. One window means every registry is resolved
     * against the same loader that will run the Step.
     *
     * A null [pluginClassLoader] is the CORE-ONLY case, and it is a real case rather than a
     * degraded one: it is what a run with no external JARs gets. It produces empty plugin
     * registries on purpose, and it is written out rather than defaulted so that "no plugins"
     * is a decision somebody made instead of a value nobody supplied.
     *
     * @param admittedArtifacts the artifacts pass 1 admitted, so the contribution cross-check can
     *   verify each manifest against what its OWN code contributes. Defaults to empty, which skips
     *   the cross-check entirely — correct for the CORE-ONLY case and for tests that exercise
     *   composition in isolation, and the reason the existing composition tests are unaffected.
     *
     * @throws IllegalStateException if any contributor is broken or any key collides. There is
     *   no partial composition and no partial-success mode: a registry that admitted three of a
     *   plugin's four event kinds would look like a working feature that quietly drops one of
     *   its own observations.
     * @throws PluginContributionRefusal if an admitted artifact's contributions drift
     *   from its manifest. Distinct from the above: the plugin WORKED and still lied about itself.
     */
    fun resolve(
        pluginClassLoader: ClassLoader?,
        admittedArtifacts: List<AdmittedArtifact> = emptyList(),
    ): PreResolvedComposition {
        if (pluginClassLoader == null) {
            return PreResolvedComposition(
                steps = CoreStepRegistryFactory.registry(),
                directives = coreDirectives(),
                events = EventRegistry.create(),
                discoveredStepPlugins = emptyList(),
                discoveredDirectivePlugins = emptyList(),
                capabilityContributors = emptyList(),
            )
        }

        val previousTccl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = pluginClassLoader
        try {
            val stepBuilder = CoreStepRegistryFactory.builder()
            val discoveredSteps = ExternalStepPluginDiscovery.registerInto(stepBuilder)

            val directiveBuilder = DirectiveRegistry.Builder()
            directiveBuilder.add(ErasedDirectiveDefinition(WhenDirectiveDefinition()))
            directiveBuilder.add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
            val discoveredDirectives = ExternalDirectivePluginDiscovery.registerInto(directiveBuilder)

            // Last, and deliberately: the event cross-check must run against the same loader
            // that just produced the Steps, so a declared kind that nothing emits is refused
            // before the run exists rather than observed as a missing event later.
            val events = ExternalEventDefinitionDiscovery.compose()

            // Inside the same window, for the same reason: a capability that is resolved under a
            // different loader than the Step that requires it is a Step that is admitted and then
            // refused for something its own plugin never got to provide.
            val capabilities = ExternalCapabilityContributorDiscovery.discover()

            // S6-COMPOSITION pass 2: with every registry now built under this one loader, verify
            // that each admitted artifact's manifest matches what it actually contributed. It runs
            // HERE, and not after the window closes, so both sides of the comparison are resolved
            // against the same loader that will run the Steps. A refusal throws
            // PluginContributionRefusal, which PluginCompositionAdmitter converts back
            // into a typed CompositionOutcome.Refused.
            PluginContributionVerifier.verify(admittedArtifacts, pluginClassLoader)

            return PreResolvedComposition(
                steps = stepBuilder.build(),
                directives = directiveBuilder.build(),
                events = events,
                discoveredStepPlugins = discoveredSteps,
                discoveredDirectivePlugins = discoveredDirectives,
                capabilityContributors = capabilities,
            )
        } finally {
            Thread.currentThread().contextClassLoader = previousTccl
        }
    }

    /**
     * The CORE directive set.
     *
     * Not a separate authority: `core.when` and `core.agent` enter the SAME open registry as
     * any vendor directive, which is what keeps "open by key" honest. If either had needed its
     * own registration path, the seam would not be open. They live in this module rather than in
     * the domain because they wrap gates and execution-target resolution, which are adapter
     * concerns — that placement is not changed here.
     */
    private fun coreDirectives(): DirectiveRegistry =
        DirectiveRegistry.Builder().apply {
            add(ErasedDirectiveDefinition(WhenDirectiveDefinition()))
            add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
        }.build()
}
