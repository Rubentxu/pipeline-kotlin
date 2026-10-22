package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlJournal
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import java.nio.file.Path

/**
 * Everything a plugin contributes, resolved ONCE, before the run exists.
 * ## What this replaces
 * Composition used to happen in three places, at three different times, under three separate
 * classloader swaps:
 * - `Main.kt` composed the Step registry, **twice** — once in the in-memory branch and once in
 *   the durable branch — with the same block of code written out twice;
 * - `CompositionRoot` composed the event registry inside its own body;
 * - `CompositionRoot` composed the directive registry inside its own body, further down.
 * Three authorities for one question ("what does this installation contribute?") means a run
 * could observe two different answers: a plugin's Step present while its Directive was not yet
 * folded in, or an eligibility analysis already computed against a Step registry that a later
 * throw would discard. `runCanonicalPipeline` also defaulted `stepRegistry` to
 * `CoreStepRegistryFactory.registry()`, so a caller who forgot to pass one got a silent
 * registry — a fail-open path through exactly the admission guarantee the composition builds.
 * ## The law this type states
 * A run either has a complete, frozen composition or it has none. There is no state in which
 * steps are composed and directives are not, because this value cannot be constructed that way:
 * the constructor runs all three compositions and a failure in any of them means the value does
 * not exist. Fail-closed is therefore a property of the construction, not a check somebody
 * remembers to run.
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
     * This was a FOURTH authority, and it was resolved outside this window. Discovery ran
     * `ServiceLoader` without swapping the TCCL, as a default argument evaluated AFTER the swap
     * was undone — so a plugin whose classes live below the TCCL contributed its Step and kept
     * its capability. The failure was found with a plugin the application does not depend on,
     * and it is the same failure `ExternalCapabilityContributorDiscovery` records in its own
     * KDoc for `http.request`. It belongs here because a capability is only meaningful
     * alongside the Step that requires it: resolving the two under different loaders produces
     * a registry that admits a Step and a runtime that cannot supply what the Step needs.
*/
    val capabilityContributors: List<RuntimeCapabilityContributor>,
) {
    /**
     * What the installation actually contributed, in the words an operator already reads.
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
 * The product's ONE path from "these artifacts are on the classpath" to "a frozen
 * composition exists".
 * ## Why this is a single function and not two call sites
 * `Main` reaches composition from two branches, the in-memory default and the durable one. Before
 * this function each one wrote its own `pluginClassLoaderFor(...)` followed by its own
 * `PluginComposition.resolve(...)` — and the composition had to delete a duplicated Step-registry
 * composition for exactly that reason. Adding admission to both would have re-created the same
 * defect with a worse consequence: one branch could admit what the other refused.
 * ## The ordering, and why it is the whole point
 * ```text
 * enumerate artifacts   (BundledPluginClasspathPlan, by service-file TEXT, no class loading)
 *      -> pass 1        (PreLoadPluginAdmission: read manifest, admit, refuse named)
 *      -> pass 2        (ServiceLoader over ONLY admitted artifacts, then freeze)
 * ```
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
     * @param trust the operator's trust material, or `null` when the installation runs without a
     *   trust policy. When it is there, every artifact the operator named with `--plugin-jar` has
     *   to be the one their row pins, signed by the publisher they named.
     * @param journal where the admission decision is recorded, when the caller has a control root.
*/
    fun admitThenCompose(
        pluginJars: List<String>,
        trust: PluginTrustSource? = null,
        journal: AdmissionControlJournal? = null,
    ): CompositionOutcome {
        val candidates = (computeBundledPlugins() + pluginJars).map { Path.of(it) }

        // Measure the REAL bytes of every candidate BEFORE any class from any of them is loaded.
        // The previous `.distinct()` compared PATH STRINGS, which only covered the operator naming
        // the bundled JAR by its installed path. The same plugin named from the source tree, from a
        // copy, or through a symlink produced a different string, survived deduplication, and was
        // refused as `DuplicateIdentity` — a rejection that protects against two DIFFERENT artifacts
        // being applied to one name, fired at one artifact named twice.
        // What makes two candidates "the same artifact" is their bytes, and the bytes are read here
        // Through the same `hashArtifactAt` that uses when it measures a linked contributor, so a
        // path measured now and a class measured later agree by construction rather than by two
        // implementations happening to match.
        // Deliberately NOT used as the identity: the manifest's own `releaseDigest`. It is written by
        // the plugin, so a plugin claiming the bytes of another plugin is exactly the case where it
        // would lie, and using it here would let one plugin erase another from the composition by
        // declaring the same digest.
        // The READING happens here; the DECISION about what the readings mean lives in
        // `PluginArtifactIntegrity`, where it can be exercised without touching a file.
        val measurements = candidates.map { candidate ->
            val digest = PluginManifestResourceReader.digestOfArtifactAt(candidate)
            if (digest == null) {
                PluginArtifactIntegrity.Measurement.Unreadable(candidate)
            } else {
                PluginArtifactIntegrity.Measurement.Measured(candidate, digest)
            }
        }

        val selection = PluginArtifactIntegrity.select(measurements)
        val admitted = when (selection) {
            is PluginArtifactIntegrity.Selection.NotMeasurable -> return CompositionOutcome.Refused(
                artifact = selection.artifact,
                rejection = PluginManifestRejection.ArtifactNotMeasurable(
                    artifact = selection.artifact.toString(),
                    detail = "the artifact could not be read, so its bytes cannot be measured; " +
                        "an artifact whose bytes are unknown is not one that may be loaded",
                ),
            )

            is PluginArtifactIntegrity.Selection.Admitted -> selection.artifacts
        }
        val artifacts = admitted.map { it.path }

        // The digests the OPERATOR brought, and WHERE their copy is. The trust policy covers the
        // artifacts named with `--plugin-jar`, and it covers them BY BYTES: an operator who names
        // a plugin by two paths, or who names a copy of a plugin the installation already bundles,
        // gets one answer about one artifact. The digests are taken from the readings made BEFORE
        // deduplication, because deduplication may keep the bundled copy of an artifact the
        // operator named by another path — and the operator's answer has to be about the bytes
        // they named, not about the path that happened to survive. The publisher's signature
        // travels beside the operator's copy, so that path is kept too.
        // The bundled plugins the operator did not name are covered by the distribution they
        // installed, not by a row they would have to write for each.
        val operatorNamed = pluginJars.map { Path.of(it) }.toSet()
        val measuredByPath = admitted.associate { measurement -> measurement.path to measurement.digest }
        val operatorArtifacts = measurements
            .filterIsInstance<PluginArtifactIntegrity.Measurement.Measured>()
            .filter { measurement -> operatorNamed.contains(measurement.path) }
            .associate { measurement -> measurement.digest.value.lowercase() to measurement.path }
        val trustPolicy = trust?.let { source -> PluginTrustPolicy(source, operatorArtifacts) }

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

        return when (
            val admission = PreLoadPluginAdmission.admitAll(
                artifacts = artifacts,
                measured = measuredByPath,
                runtimeVersion = runtimeVersion,
                trust = trustPolicy,
                journal = journal,
            )
        ) {
            is AdmittedArtifacts.Refused -> CompositionOutcome.Refused(
                artifact = admission.artifact,
                rejection = admission.rejection,
                detail = admission.detail,
            )

            is AdmittedArtifacts.Admitted -> {
                // Time-of-check/time-of-use. Admission read every artifact's bytes; composition is
                // about to LOAD them. If a file was replaced in between, the digest measured above
                // describes bytes that are no longer there, and admitting on the strength of it would
                // make the measurement decorative. Re-measured with the same function, refused with
                // both digests so the drift is auditable.
                val current = admitted.associate { measurement ->
                    measurement.path to PluginManifestResourceReader.digestOfArtifactAt(measurement.path)
                }
                val changed = PluginArtifactIntegrity.changed(admitted, current)
                if (changed != null) {
                    return CompositionOutcome.Refused(
                        artifact = changed.artifact,
                        rejection = PluginManifestRejection.ArtifactChangedDuringAdmission(
                            artifact = changed.artifact.toString(),
                            measuredDigest = changed.measured.value,
                            foundDigest = changed.found?.value ?: "<unreadable at composition time>",
                        ),
                    )
                }

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
                } catch (e: PluginContributionRefusal.Drift) {
                    // Pass 1 admitted the artifact; pass 2 caught the artifact LYING about what it
                    // provides. Turn it back into the same typed refusal a pass-1 rejection produces,
                    // so the CLI exits 2 naming the artifact and the drift, uniformly.
                    CompositionOutcome.Refused(e.artifact, e.rejection)
                } catch (e: PluginContributionRefusal.Unattributed) {
                    // Pass 2 could not even tell WHICH artifact a provider belongs to, so there is
                    // no artifact to name: the provider is named instead, and the composition stops
                    // rather than admitting a contribution that no manifest can be compared against.
                    CompositionOutcome.UnattributedProvider(e.providerClass, e.reason)
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
        val rejection: dev.rubentxu.pipeline.v2.domain.step.AdmissionRejection,
        /** The human sentence behind the refusal, when it has one. */
        val detail: String? = null,
    ) : CompositionOutcome

    /**
     * A provider in the composition window could not be attributed to an artifact, so the
     * cross-check could not judge it.
     *
     * The provider is named instead of an artifact, because no artifact can be named for it: that
     * is the whole difference from [Refused], which always has one.
     */
    data class UnattributedProvider(val providerClass: String, val reason: String) : CompositionOutcome

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
     * The swap is the point of doing this in one place rather than three: a registry naming a
     * codec the executing side cannot load otherwise fails at emission as a ClassCastException
     * rather than at composition as a refusal. One window means every registry is resolved
     * against the same loader that will run the Step.
     * A null [pluginClassLoader] is the case, and it is a real case rather than a
     * degraded one: it is what a run with no external JARs gets. It produces empty plugin
     * registries on purpose, and it is written out rather than defaulted so that "no plugins"
     * is a decision somebody made instead of a value nobody supplied.
     * @param admittedArtifacts the artifacts pass 1 admitted, so the contribution cross-check can
     *   verify each manifest against what its OWN code contributes. Defaults to empty, which skips
     * The cross-check entirely — correct for the case and for tests that exercise
     *   composition in isolation, and the reason the existing composition tests are unaffected.
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

            // With every registry now built under this one loader, verify
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
