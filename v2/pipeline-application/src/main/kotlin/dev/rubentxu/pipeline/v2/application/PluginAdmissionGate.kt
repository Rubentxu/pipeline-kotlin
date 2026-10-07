package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import java.util.ServiceLoader

/**
 * S6/D — the composition gate that makes "admit, THEN load" an ordering, not a promise.
 *
 * ## Why this type exists
 *
 * [PluginAdmission] is a pure decision and [PluginManifestResourceReader] is a pure read.
 * Neither of them can enforce WHEN a plugin's code runs; a caller that loads the contributor
 * first and admits afterwards satisfies both signatures perfectly and defeats the entire
 * guarantee. That is the inversion this gate closes: the ordering lives in the type that
 * both halves are called from, so a wrong order is not possible to express here.
 *
 * ## The four phases, and why each is where it is
 *
 * ```text
 * Class.forName(name, initialize = false)   -> link, do NOT run plugin code
 * read manifest by resource name            -> the DECLARATION
 * admit(decoded, measured, runtime, seen)   -> the DECISION
 * Class.forName(name, initialize = true)    -> ONLY NOW may plugin code run
 * ServiceLoader contributions               -> and only what admission accepted
 * ```
 *
 * `initialize = false` is load-bearing and is the reason the first line is safe: it resolves
 * the class without running its static initialiser, which is what lets the manifest be read
 * "by artifact" without touching a line of plugin code. A reader that needed an instance to
 * find the declaration could not make that promise, and the difference between those two
 * readers is the whole content of ADR-EVO-003.
 *
 * ## A refused plugin is never linked
 *
 * On refusal nothing after the decision runs, and the method returns the typed rejection. It
 * does not throw, does not skip, and does not return an empty list a caller could mistake for
 * "this plugin contributed nothing" — absence of a plugin and absence of a contribution are
 * different facts and must stay different.
 *
 * ## Why all THREE families are collected here, and what it cost not to
 *
 * The cross-check takes `definitions`, `directiveKeys` and `eventKinds`. This method used to pass
 * only the first, which made the other two permanently `emptySet()` — and therefore made
 * `missingDirectives` and `missingEvents` equal to whatever the manifest declared. The
 * consequence was not subtle: **a plugin that declared a Directive or an Event could never be
 * admitted**, however faithfully it implemented them, because the comparison had nothing to
 * compare against and the declared side always lost.
 *
 * It stayed invisible for as long as it stayed true, because no plugin declared either family:
 * with empty declarations AND empty implementations, the cross-check passed trivially. It looked
 * like the cross-check covered four families and it covered one. That is the same shape of defect
 * as the prohibition over a file that no longer exists — a check whose green is produced by the
 * absence of the subject.
 *
 * The fix is not "trust the manifest" and not "trust the classes": both sides are now measured in
 * the same window, from the same artifact, under the same scoping rule.
 */
object PluginAdmissionGate {

    /**
     * Admit a contributor class and, only if admitted, let its code run.
     *
     * @param contributorClassName the ServiceLoader provider class name.
     * @param classLoader loader that will also supply the manifest resource.
     * @param runtimeVersion the running PipelineK version, for the API range comparison.
     * @param alreadyAdmitted plugin identities admitted so far in this composition.
     * @param strict whether the manifest must come from the same code source as the
     *   contributor. Left on in production; tests inject manifests for classes they cannot
     *   repackage and pass `false`, which is a weaker promise and says so.
     */
    fun admitThenLoad(
        contributorClassName: String,
        classLoader: ClassLoader,
        runtimeVersion: SemVer,
        alreadyAdmitted: Set<String>,
        strict: Boolean = true,
    ): PluginAdmissionResult {
        // Phase 1: LINK ONLY. initialize=false, so no static initialiser in the plugin runs.
        val contributorClass = try {
            Class.forName(contributorClassName, /* initialize = */ false, classLoader)
        } catch (e: ClassNotFoundException) {
            return PluginAdmissionResult.Refused(
                dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.MalformedDocument(
                    "contributor class $contributorClassName is not on the runtime classpath",
                ),
            )
        }

        // Phase 2: read the DECLARATION, by resource name, with no reference to the class.
        val read = PluginManifestResourceReader.read(classLoader, contributorClass, strict)
        val decoded = when (read) {
            is PluginManifestReadOutcome.NoManifest ->
                return PluginAdmissionResult.Refused(
                    dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.MalformedDocument(
                        "no manifest at ${dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH} " +
                            "for contributor $contributorClassName; a plugin declares itself, and one that " +
                            "declares nothing is refused rather than admitted on trust",
                    ),
                )

            is PluginManifestReadOutcome.Refused -> return PluginAdmissionResult.Refused(read.rejection)
            is PluginManifestReadOutcome.Read -> read.decoded
        }

        val accepted = decoded as? dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Accepted
            ?: return PluginAdmissionResult.Refused(
                (decoded as dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Rejected).rejection,
            )

        // Phase 3: MEASURE what the runtime can measure, then decide.
        val codeSource = contributorClass.protectionDomain?.codeSource?.location?.toString() ?: "unknown"
        val origin = ArtifactOrigin.LocalClasspathEntry(codeSource)
        val identity = PluginManifestResourceReader.measure(
            declaredDigest = accepted.manifest.release.digest,
            origin = origin,
        )

        val admitted = PluginAdmission.admit(
            decoded = decoded,
            identity = identity,
            runtimeVersion = runtimeVersion,
            alreadyAdmitted = alreadyAdmitted,
        )
        if (admitted is PluginAdmissionResult.Refused) {
            // Nothing below this line runs for a refused plugin. That is the guarantee.
            return admitted
        }

        val admittedPlugin = (admitted as PluginAdmissionResult.Admitted).plugin

        // Phase 4: ONLY NOW may plugin code initialise and contribute.
        val crossCheck = try {
            Class.forName(contributorClassName, /* initialize = */ true, classLoader)

            // ServiceLoader is Iterable but NOT Closeable, so `use` does not apply here.
            //
            // The filter is load-bearing and was found by a failing test. ServiceLoader returns
            // EVERY contributor on the classloader, so an unscoped cross-check compares one
            // plugin's manifest against every other plugin's Steps and reports them all as
            // undeclared. A plugin's cross-check is about ITS OWN contributions, so providers
            // are selected by the artifact they come from — the same scoping `strict` applies
            // to the manifest. Without it every plugin refuses and the gate admits nothing.
            //
            // All THREE families are scoped by that same rule, and they must be: a Step is not
            // "this plugin's" in some looser sense that a Directive is not. They are one plugin's
            // contributions or the cross-check is comparing a manifest against a neighbour.
            fun fromSameArtifact(provider: Any): Boolean =
                provider.javaClass.protectionDomain?.codeSource?.location?.toString() == codeSource

            val definitions = mutableListOf<StepDefinition<*, *>>()
            for (provider in ServiceLoader.load(StepDefinitionContributor::class.java, classLoader)) {
                if (!fromSameArtifact(provider)) continue
                provider.registrations().forEach { definitions.add(it.definition) }
            }

            val directiveKeys = mutableSetOf<DirectiveKey>()
            for (provider in ServiceLoader.load(DirectiveContributor::class.java, classLoader)) {
                if (!fromSameArtifact(provider)) continue
                provider.definitions().forEach { directiveKeys.add(it.key) }
            }

            val eventKinds = mutableSetOf<String>()
            for (provider in ServiceLoader.load(EventDefinitionContributor::class.java, classLoader)) {
                if (!fromSameArtifact(provider)) continue
                for (creation in provider.definitions()) {
                    // Fail closed rather than skipping: a contributor that declares a malformed
                    // kind must abort admission, exactly as it aborts composition in
                    // ExternalEventDefinitionDiscovery. Counting only the Valid ones here would
                    // make a broken declaration look like a missing one and let a plugin through
                    // on a family it got wrong.
                    when (creation) {
                        is EventDefinitionCreation.Invalid -> return PluginAdmissionResult.Refused(
                            PluginManifestRejection.MalformedDocument(
                                "contributor ${provider.id} declared a malformed event: " +
                                    creation.problems.joinToString("; "),
                            ),
                        )

                        is EventDefinitionCreation.Valid -> eventKinds.add(creation.definition.kind)
                    }
                }
            }

            // S6/E — the cross-check. It runs HERE, after the contributor's code has run,
            // because comparing a declaration against contributions is only possible once
            // the contributions exist. Splitting it from phase 3 is the point: the first gate
            // is pre-load and cheap, the second is post-load and complete, and collapsing
            // them would either run code before admission or make admission incomplete.
            admittedPlugin.admitContributions(
                definitions = definitions,
                directiveKeys = directiveKeys,
                eventKinds = eventKinds,
            )
        } catch (e: Throwable) {
            return PluginAdmissionResult.Refused(
                dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.MalformedDocument(
                    "contributor $contributorClassName initialised but failed to provide: ${e.message}",
                ),
            )
        }

        if (!crossCheck.isConsistent) {
            // The plugin RAN and then failed to match what it declared. Refusing here does not
            // undo the execution, which is precisely why the pre-load gate in phase 1 exists:
            // this check catches drift, the earlier one prevents untrusted code from starting.
            return PluginAdmissionResult.Refused(
                dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.InvalidManifest(
                    admittedPlugin.pluginId,
                    crossCheck.describe(),
                ),
            )
        }

        return admitted
    }
}
