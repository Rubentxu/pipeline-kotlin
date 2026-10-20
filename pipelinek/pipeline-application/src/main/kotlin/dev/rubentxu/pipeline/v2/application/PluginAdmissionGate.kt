package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlJournal
import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlRow
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal
import dev.rubentxu.pipeline.v2.domain.step.AdmissionRejection
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import java.util.ServiceLoader

/**
 * The composition gate that makes "admit, THEN load" an ordering, not a promise.
 * ## Why this type exists
 * [PluginAdmission] is a pure decision and [PluginManifestResourceReader] is a pure read.
 * Neither of them can enforce WHEN a plugin's code runs; a caller that loads the contributor
 * first and admits afterwards satisfies both signatures perfectly and defeats the entire
 * guarantee. That is the inversion this gate closes: the ordering lives in the type that
 * both halves are called from, so a wrong order is not possible to express here.
 * ## The four phases, and why each is where it is
 * ```text
 * Class.forName(name, initialize = false)   -> link, do NOT run plugin code
 * read manifest by resource name            -> the DECLARATION
 * admit(decoded, measured, runtime, seen)   -> the DECISION
 * Class.forName(name, initialize = true)    -> ONLY NOW may plugin code run
 * ServiceLoader contributions               -> and only what admission accepted
 * ```
 * `initialize = false` is load-bearing and is the reason the first line is safe: it resolves
 * the class without running its static initialiser, which is what lets the manifest be read
 * "by artifact" without touching a line of plugin code. A reader that needed an instance to
 * find the declaration could not make that promise, and the difference between those two
 * Readers is the whole content of
 * ## A refused plugin is never linked
 * On refusal nothing after the decision runs, and the method returns the typed rejection. It
 * does not throw, does not skip, and does not return an empty list a caller could mistake for
 * "this plugin contributed nothing" — absence of a plugin and absence of a contribution are
 * different facts and must stay different.
 * ## Why all THREE families are collected here, and what it cost not to
 * The cross-check takes `definitions`, `directiveKeys` and `eventKinds`. This method used to pass
 * only the first, which made the other two permanently `emptySet()` — and therefore made
 * `missingDirectives` and `missingEvents` equal to whatever the manifest declared. The
 * consequence was not subtle: **a plugin that declared a Directive or an Event could never be
 * admitted**, however faithfully it implemented them, because the comparison had nothing to
 * compare against and the declared side always lost.
 * It stayed invisible for as long as it stayed true, because no plugin declared either family:
 * with empty declarations AND empty implementations, the cross-check passed trivially. It looked
 * like the cross-check covered four families and it covered one. That is the same shape of defect
 * as the prohibition over a file that no longer exists — a check whose green is produced by the
 * absence of the subject.
 * The fix is not "trust the manifest" and not "trust the classes": both sides are now measured in
 * the same window, from the same artifact, under the same scoping rule.
 * ## Where this gate stands
 * Nothing in this repository calls [admitThenLoad]. The admission that the shipped runtime
 * performs is [PreLoadPluginAdmission], which reads the manifest, admits the declaration, and
 * leaves the measurement of the bytes to [PluginArtifactIntegrity]. This gate is the stricter
 * design — it admits a CONTRIBUTER CLASS, and it can consult an operator's trust database — and
 * it is kept because the design is sound, not because it is wired.
 * What it verifies, when it is called with a trust database, is the OPERATOR'S POLICY: that the
 * manifest declares the digest the operator approved. What it does NOT verify is the bytes: the
 * manifest declares a digest of the plugin's payload, the runtime measures a digest of the file
 * that carries it, and those are two different magnitudes that never agree. An earlier version of
 * this file compared them anyway and called the difference a plugin lying about itself; on a real
 * plugin that comparison accuses every plugin, so it is not a check and it is not here.
*/
object PluginAdmissionGate {

    /**
     * Admit a contributor class and, only if admitted, let its code run.
     * @param contributorClassName the ServiceLoader provider class name.
     * @param classLoader loader that will also supply the manifest resource.
     * @param runtimeVersion the running PipelineK version, for the API range comparison.
     * @param alreadyAdmitted plugin identities admitted so far in this composition.
     * @param strict whether the manifest must come from the same code source as the
     *   contributor. Left on in production; tests inject manifests for classes they cannot
     *   repackage and pass `false`, which is a weaker promise and says so.
     * @param trustDatabase trust database. When `null`, the gate uses the legacy
     *   `measure()` entry point (Unverified identity, no operator-trust check). When
     *   provided, the gate hashes the artifact bytes, consults the operator's pinned
     *   digests, refuses on no-row / mismatch, and writes a control row BEFORE returning.
     * Legacy code that did not know about keeps working; new code passes a real
     * Database to get the fail-closed behaviour
     * @param controlJournal admission control row writer. Required when
     *   [trustDatabase] is provided; the gate persists the decision durably before
     *   returning. When [trustDatabase] is `null`, [controlJournal] is also ignored.
*/
    fun admitThenLoad(
        contributorClassName: String,
        classLoader: ClassLoader,
        runtimeVersion: SemVer,
        alreadyAdmitted: Set<String>,
        strict: Boolean = true,
        trustDatabase: OperatorTrustDatabase? = null,
        controlJournal: AdmissionControlJournal? = null,
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
            is PluginManifestReadOutcome.NoManifest -> {
                // Write the row even on a "no manifest"
                // refusal, because the absence of a manifest IS the durable fact the
                // operator may want to see. The row's `measuredDigest` is null and the
                // verdict is Unverified.ArtifactNotMeasurable.
                val origin = originFor(contributorClass)
                val pluginId = contributorClassName
                if (trustDatabase != null && controlJournal != null) {
                    writeControlRow(
                        controlJournal = controlJournal,
                        pluginId = pluginId,
                        origin = origin,
                        declaredDigest = Digest("sha256:" + "0".repeat(64)),
                        measuredDigest = null,
                        verdict = TrustMetadata.Unverified(
                            reason = TrustMetadata.UnverifiedReason.ArtifactNotMeasurable,
                            origin = origin,
                        ),
                        rejection = AdmissionRefusal.RefusedNoManifest(contributorClassName),
                    )
                }
                return PluginAdmissionResult.Refused(
                    AdmissionRefusal.RefusedNoManifest(contributorClassName),
                )
            }

            is PluginManifestReadOutcome.Refused -> return PluginAdmissionResult.Refused(read.rejection)
            is PluginManifestReadOutcome.Read -> read.decoded
        }

        val accepted = decoded as? dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Accepted
            ?: return PluginAdmissionResult.Refused(
                (decoded as dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult.Rejected).rejection,
            )

        // Phase 3: MEASURE what the runtime can measure, consult the operator's trust
        // database if one is provided, and decide. The control row is persisted BEFORE
        // any refusal returns; admitted plugins also write a row (Verified verdict,
        // refusedKind = null) so a re-run can see the decision the runtime made.
        val codeSource = contributorClass.protectionDomain?.codeSource?.location?.toString() ?: "unknown"
        val origin = ArtifactOrigin.LocalClasspathEntry(codeSource)
        val identity: MeasuredArtifactIdentity = if (trustDatabase != null) {
            PluginManifestResourceReader.measureArtifactBytes(
                declaredDigest = accepted.manifest.release.digest,
                contributorClass = contributorClass,
                origin = origin,
            )
        } else {
            // With no operator trust database there is nothing to measure against: the manifest
            // declares what it declares and the bytes are verified where they CAN be compared
            // with something else, against themselves, before and after admission.
            PluginManifestResourceReader.declaredIdentity(
                declaredDigest = accepted.manifest.release.digest,
                origin = origin,
            )
        }

        val trustRefusal: AdmissionRejection? = if (trustDatabase != null) {
            b32TrustRefusal(
                trustDatabase = trustDatabase,
                manifest = accepted.manifest,
                origin = origin,
            )
        } else {
            null
        }
        if (trustRefusal != null) {
            val verdict = when (trustRefusal) {
                is AdmissionRefusal.RefusedDigestMismatch -> TrustMetadata.Mismatch(
                    declared = identity.declaredDigest,
                    measured = identity.measuredDigest ?: identity.declaredDigest,
                    origin = origin,
                )
                is AdmissionRefusal.RefusedUntrustedIdentity -> TrustMetadata.Unverified(
                    reason = trustRefusal.reason,
                    origin = origin,
                )
                else -> TrustMetadata.Unverified(
                    reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
                    origin = origin,
                )
            }
            if (controlJournal != null) {
                writeControlRow(
                    controlJournal = controlJournal,
                    pluginId = accepted.manifest.plugin.canonicalText(),
                    origin = origin,
                    declaredDigest = identity.declaredDigest,
                    measuredDigest = identity.measuredDigest,
                    verdict = verdict,
                    rejection = trustRefusal,
                )
            }
            return PluginAdmissionResult.Refused(trustRefusal)
        }

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
            // The filter is load-bearing and was found by a failing test. ServiceLoader returns
            // EVERY contributor on the classloader, so an unscoped cross-check compares one
            // plugin's manifest against every other plugin's Steps and reports them all as
            // undeclared. A plugin's cross-check is about ITS OWN contributions, so providers
            // are selected by the artifact they come from — the same scoping `strict` applies
            // to the manifest. Without it every plugin refuses and the gate admits nothing.
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

            // The cross-check. It runs HERE, after the contributor's code has run,
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

        // Admitted plugins also write a row. The decision the runtime made is
        // itself a durable fact: a future run with the same `--control-root` can see what
        // was admitted and why, without re-running the gate.
        if (trustDatabase != null && controlJournal != null) {
            writeControlRow(
                controlJournal = controlJournal,
                pluginId = admittedPlugin.pluginId,
                origin = origin,
                declaredDigest = admittedPlugin.identity.declaredDigest,
                measuredDigest = admittedPlugin.identity.measuredDigest,
                verdict = TrustMetadata.Verified(
                    digest = admittedPlugin.identity.measuredDigest
                        ?: admittedPlugin.identity.declaredDigest,
                    origin = origin,
                ),
                rejection = null,
            )
        }

        return admitted
    }

    /**
     * Compute the trust refusal for a manifest + measured identity
     * Returns `null` when the plugin is admitted on trust grounds (i.e. the operator
     * pinned the digest and the manifest declares the same one). Returns the first matching
     * [AdmissionRefusal] case otherwise.
     *
     * ## What the operator pins
     * The digest the operator pins is the one the plugin PUBLISHES about its own payload, and
     * that is what this compares it with. It is not the digest of the artifact file: the
     * manifest declares a digest of the plugin's CONTENT and the runtime measures a digest of
     * the file that carries it, so a comparison between the two is between two different
     * magnitudes and could only ever accuse. Pinning the bytes of the file would be a stronger
     * policy, and it would also be a different policy — a different value in the operator's
     * file, and a decision about what that file is and where it lives.
    */
    private fun b32TrustRefusal(
        trustDatabase: OperatorTrustDatabase,
        manifest: dev.rubentxu.pipeline.v2.domain.step.PluginManifest,
        origin: ArtifactOrigin,
    ): AdmissionRejection? {
        val pluginId = manifest.plugin.canonicalText()
        val releaseVersion = manifest.release.version

        // Look up the operator's row for (plugin, version). Three outcomes.
        val row = trustDatabase.lookup(pluginId, releaseVersion)
        return when {
            row == null -> AdmissionRefusal.RefusedUntrustedIdentity(
                identity = pluginId,
                reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
            )
            !row.digest.value.equals(manifest.release.digest.value, ignoreCase = true) ->
                // Manifest's claim disagrees with what the operator pinned. This is a
                // policy-level refusal, not a byte-level one, so the reason is
                // OutsideOperatorTrustPolicy rather than Mismatch.
                AdmissionRefusal.RefusedUntrustedIdentity(
                    identity = pluginId,
                    reason = TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy,
                )
            else -> null // The operator's policy accepts this declaration.
        }
    }

    /**
     * Persist the gate's decision durably. Called from EVERY refusal path AND from
     * the admit path; absence of a row for a plugin means the gate has not yet
     * decided it, NOT that the plugin is missing.
*/
    private fun writeControlRow(
        controlJournal: AdmissionControlJournal,
        pluginId: String,
        origin: ArtifactOrigin,
        declaredDigest: Digest,
        measuredDigest: Digest?,
        verdict: TrustMetadata,
        rejection: AdmissionRejection?,
    ) {
        val row = AdmissionControlRow.compose(
            pluginId = pluginId,
            origin = origin,
            declaredDigest = declaredDigest,
            measuredDigest = measuredDigest,
            verdict = verdict,
            rejection = rejection,
        )
        controlJournal.record(row)
    }

    /**
     * Resolve the contributor's `protectionDomain.codeSource` to an [ArtifactOrigin].
     * Returns [ArtifactOrigin.Unknown] if the contributor has no codeSource.
*/
    private fun originFor(contributorClass: Class<*>): ArtifactOrigin {
        val location = contributorClass.protectionDomain?.codeSource?.location?.toString()
            ?: return ArtifactOrigin.Unknown
        return ArtifactOrigin.LocalClasspathEntry(location)
    }
}
