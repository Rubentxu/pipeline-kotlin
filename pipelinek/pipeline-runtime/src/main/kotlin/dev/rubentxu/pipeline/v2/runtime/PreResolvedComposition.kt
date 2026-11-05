package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.runtime.durable.AdmissionControlJournal
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything a plugin contributes, resolved ONCE, before the run exists.
 * ## Why one place
 * A run asks ONE question — "what does this installation contribute?" — and it
 * gets ONE answer. Steps, directives, events and capabilities are resolved
 * together, under ONE classloader window, so a plugin's Step and its Directive
 * cannot arrive from different classpath views, and a capability cannot be
 * resolved apart from the Step that requires it.
 *
 * `PluginComposition.resolve` is the only builder of this value. It returns a
 * complete composition or it throws: a failure in any of the four resolutions
 * means there is no composition at all, and a half-built one is never handed
 * out. There is no default Step registry to fall back on, so a caller that does
 * not pass one has nothing to compose with.
 * ## The law this type states
 * A run either has a complete, frozen composition or it has none. Fail-closed
 * is therefore a property of the construction, not a check somebody has to
 * remember to run.
 * The three registries are frozen independently and deliberately: they answer different
 * questions (which Steps, which Directives, which Event kinds), they are built by different
 * contributors, and forcing them into one shared map would couple three kinds that the external
 * plugin contract requires to stay independent: each registry answers its own question and is
 * frozen on its own.
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
     * Resolved inside the same classloader window as the Steps, and for the same
     * reason: a capability that arrives under a different loader than the Step
     * that requires it is a Step that is admitted and then refused for something
     * its own plugin never got to provide. It belongs here because a capability
     * is only meaningful alongside the Step that requires it.
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
        requireTrust: Boolean = false,
        /**
         * Where the admission's report goes.
         *
         * The admission prints what it decided about each artifact, and the caller owns
         * the stream it is printed on. The destination is therefore a parameter all the
         * way down, and never a global the caller has to trust to be the same one.
         */
        err: PrintStream = System.err,
        /**
         * Where the private copies of the artifacts go.
         *
         * The copies exist so that the bytes the runtime verified are the bytes the runtime
         * loads, and they are named by digest so a second run over the same plugin reuses the
         * same file instead of accumulating one per run. `null` means a temporary directory of
         * this process's own: a composition that cannot be built without a private place for
         * the copies is not a composition this runtime may build.
         */
        copyRoot: Path? = null,
        /**
         * The plugins the INSTALLATION bundles, resolved by the caller.
         *
         * Where a JAR comes from is a decision of the installation, not of the
         * engine: the classpath of the process is what says which plugins are
         * bundled, and only the caller that owns the classpath can say it. The
         * engine receives the resolved list and treats it like any other
         * candidate — measured, deduplicated and admitted on its own merits.
         *
         * It has NO default on purpose. A default here would be a second way
         * of admitting plugins, and the quiet one: a caller that forgot the
         * installation's plugins would compose a run that silently lacks
         * them. The compiler is the cheaper place to catch that.
         */
        bundledPlugins: List<String>,
        /**
         * The classloader the admitted artifacts are loaded through.
         *
         * The engine decides WHICH artifacts are admitted; the caller decides
         * HOW they are loaded, because the loader's parent is the caller's own
         * classloader and only the caller knows it.
         *
         * It has NO default on purpose, for the same reason: a loader built
         * here would be a second loader, and the two would differ in the
         * parent they hand to the plugin's own classes.
         */
        classLoaderFor: (List<String>) -> ClassLoader,
    ): CompositionOutcome {
        val candidates = (bundledPlugins + pluginJars).map { Path.of(it) }

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
            when (val outcome = PluginArtifactDigest.measure(candidate)) {
                is PluginArtifactDigest.Outcome.Measured ->
                    PluginArtifactIntegrity.Measurement.Measured(candidate, outcome.digest)

                is PluginArtifactDigest.Outcome.Refused ->
                    PluginArtifactIntegrity.Measurement.Unreadable(candidate, outcome.reason)
            }
        }

        val selection = PluginArtifactIntegrity.select(measurements)
        val admitted = when (selection) {
            is PluginArtifactIntegrity.Selection.NotMeasurable -> return CompositionOutcome.Refused(
                artifact = selection.artifact,
                rejection = PluginManifestRejection.ArtifactNotMeasurable(
                    artifact = selection.artifact.toString(),
                    detail = "the artifact could not be read, so its bytes cannot be measured; " +
                        "an artifact whose bytes are unknown is not one that may be loaded: " +
                        selection.reason.describe(),
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
                // The operator's OWN artifacts, by the bytes the operator brought. The
                // policy covers exactly these, and the requirement applies to exactly
                // these: the plugins bundled with the installation are covered by the
                // distribution they shipped in.
                operatorDigests = operatorArtifacts.keys,
                requireTrust = requireTrust,
                err = err,
            )
        ) {
            is AdmittedArtifacts.Refused -> CompositionOutcome.Refused(
                artifact = admission.artifact,
                rejection = admission.rejection,
                detail = admission.detail,
            )

            is AdmittedArtifacts.Admitted -> {
                // Time-of-check/time-of-use, closed by COPYING rather than by reading the
                // original a second time. A second reading still leaves a window between the
                // reading and the load, and the classloader reads the file AFTER the reading.
                // The copy is verified against the digest the admission decided on and is
                // then the artifact that is loaded, so what was verified is what runs.
                val copies = when (
                    val outcome = PrivatePluginArtifacts.copyAll(
                        admitted = admission.artifacts,
                        into = copyRoot ?: Files.createTempDirectory("pipelinek-plugin-copies"),
                    )
                ) {
                    is PrivatePluginArtifacts.CopyOutcome.Copied -> outcome.artifacts

                    is PrivatePluginArtifacts.CopyOutcome.Refused -> return CompositionOutcome.Refused(
                        artifact = outcome.artifact,
                        rejection = outcome.rejection,
                        detail = outcome.detail,
                    )
                }

                val loader = classLoaderFor(copies.map { it.path.toString() })
                try {
                    CompositionOutcome.Composed(
                        // Pass 2 sees ONLY admitted artifacts. The parent loader still exposes the
                        // bundled JARs, and every one of them is in `copies`, so nothing
                        // reachable through ServiceLoader escaped pass 1. The same list is handed to
                        // the cross-check so a plugin is verified against its OWN contributions.
                        composition = PluginComposition.resolve(loader, copies),
                        admitted = copies,
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
