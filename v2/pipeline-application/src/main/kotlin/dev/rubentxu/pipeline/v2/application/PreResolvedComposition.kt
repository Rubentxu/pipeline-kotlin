package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry

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
     * @throws IllegalStateException if any contributor is broken or any key collides. There is
     *   no partial composition and no partial-success mode: a registry that admitted three of a
     *   plugin's four event kinds would look like a working feature that quietly drops one of
     *   its own observations.
     */
    fun resolve(pluginClassLoader: ClassLoader?): PreResolvedComposition {
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
