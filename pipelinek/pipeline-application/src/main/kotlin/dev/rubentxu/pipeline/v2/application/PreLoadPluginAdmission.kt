package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlJournal
import dev.rubentxu.pipeline.v2.application.durable.AdmissionControlRow
import dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin
import dev.rubentxu.pipeline.v2.domain.step.AdmissionRejection
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Admit every plugin artifact BEFORE a line of plugin code runs.
 *
 * ## What this closes
 * A measurement found that the classloader-based admission gate had eight callers and every one of
 * them was in `src/test`. The installed product reached plugins through `PluginComposition.resolve`,
 * which never asked any gate, so an artifact with no manifest executed, and so did a plugin whose
 * manifest declared a Step it did not implement.
 * ## Why that gate is gone
 * It was a SECOND admission authority: it admitted a contributor CLASS through a classloader, it
 * performed its own manifest read, its own digest measurement and its own contribution
 * cross-check, and the product's own path did none of that through it. Two authorities for one
 * question means an answer that depends on which one a caller happened to use, and the one the
 * product did not use was the stricter one. It was removed rather than wired: the pass below is
 * the one the product runs, it admits over artifact PATHS before any class is linked, and it now
 * also carries the operator's trust decision.
 *
 * ## Why this is pass 1 and not a check inside composition
 * [PluginComposition.resolve] discovers through `ServiceLoader`, and `ServiceLoader` **instantiates**
 * providers as it iterates them. Putting admission there would load plugin code and initialise its
 * static state before the decision that is supposed to precede exactly that — the inversion this
 * pass exists to forbid. So admission happens over a plain list of artifact PATHS, with no
 * `ClassLoader`, no `ServiceLoader` and no contributor class anywhere in this file.
 * What pass 1 decides is exactly what [PluginAdmission.admit] decides, because
 * [PluginAdmission.admit] takes a document, a measured identity, a runtime version and the set of
 * identities already admitted — and not one of those requires a loaded class. What it deliberately
 * does NOT decide is [AdmittedPlugin.admitContributions], the declaration-versus-implementation
 * cross-check, which needs the contributor's own output and therefore belongs to pass 2.
 *
 * ## Why the manifest is read from the artifact, not from a classloader
 * The existing [PluginManifestResourceReader.read] takes a contributor class because it also
 * enforces that the manifest and the class come from the same artifact. That check exists to stop a
 * plugin planting a declaration in one jar while contributing code from another. Here it is
 * structurally impossible rather than merely checked: the manifest is read out of the artifact path
 * that is being admitted, and pass 2 composes over those same admitted paths. There is no second
 * artifact to substitute.
 *
 * ## Where the operator's trust decision sits
 * After the manifest is decoded — the plugin's identity is needed to find the operator's row — and
 * before anything is admitted or loaded. That order is the whole point of the trust layer: a
 * decision about bytes and publisher that is made after the bytes have run proves nothing.
 *
 * ## Fail-closed
 * One refusal stops the whole composition and names the artifact and the reason. A missing manifest
 * is a refusal, never a default: a plugin that declares nothing is refused rather than admitted on
 * trust. There is no "skip the bad one and carry on", because a registry that admitted three of a
 * plugin's four event kinds would look like a working feature that quietly drops one of its own
 * observations.
 */
object PreLoadPluginAdmission {

    /**
     * @param artifacts the artifacts this installation will compose over. In the product that is
     *   the bundled plugin JARs plus every `--plugin-jar`, both already resolvable as paths before
     *   any composition begins.
     * @param measured the digest the runtime measured over each artifact's bytes, by path. These
     *   are the same measurements the composition re-checks before loading, so the trust decision
     *   and the time-of-check/time-of-use check talk about the same bytes.
     * @param runtimeVersion the running PipelineK version, compared against each declared
     *   `apiRange`. Supplied by the caller because reading it is a separate fail-closed concern.
     * @param trust the operator's trust policy, or `null` when the installation runs without one.
     *   A policy in force refuses every artifact it covers that the operator has not approved.
     * @param journal where the admission decision is recorded, when the caller has a control root
     *   to record it in. The row is written BEFORE the decision returns, so a refusal leaves
     *   evidence the operator can read without re-running anything.
     */
    fun admitAll(
        artifacts: List<Path>,
        measured: Map<Path, Digest>,
        runtimeVersion: SemVer,
        trust: PluginTrustPolicy? = null,
        journal: AdmissionControlJournal? = null,
    ): AdmittedArtifacts {
        val admitted = mutableListOf<AdmittedArtifact>()
        val alreadyAdmitted = mutableSetOf<String>()
        // Which artifact already holds each identity. The set above answers "is this identity
        // taken"; this one answers "WHICH artifact took it" -- the question an operator has when
        // two artifacts claim the same name and only one of them is told why.
        val admittedByIdentity = mutableMapOf<String, Path>()

        for (artifact in artifacts) {
            val read = readFromArtifact(artifact)

            val decoded = when (read) {
                is PluginManifestReadOutcome.NoManifest -> return AdmittedArtifacts.Refused(
                    artifact,
                    PluginManifestRejection.MalformedDocument(
                        "no manifest at ${PluginManifestCodec.RESOURCE_PATH} inside $artifact; " +
                            "a plugin declares itself, and one that declares nothing is refused " +
                            "rather than admitted on trust",
                    ),
                )

                is PluginManifestReadOutcome.Refused -> return AdmittedArtifacts.Refused(artifact, read.rejection)
                is PluginManifestReadOutcome.Read -> read.decoded
            }

            val manifest = (decoded as? PluginManifestDecodeResult.Accepted)?.manifest
                ?: return AdmittedArtifacts.Refused(
                    artifact,
                    (decoded as PluginManifestDecodeResult.Rejected).rejection,
                )

            val origin = ArtifactOrigin.LocalClasspathEntry(artifact.toString())
            val measuredDigest = measured[artifact]

            // The identity the runtime can establish today: WHERE the artifact came
            // from, and WHAT the manifest declares about itself. It carries no measured digest,
            // and that is the honest answer rather than a missing one. A manifest declares a
            // digest of the plugin's PAYLOAD; the runtime measures a digest of the FILE that
            // carries it. Those are two different magnitudes, they never agree, and comparing
            // them would report every plugin as lying about itself. The bytes of the artifact
            // are measured where they CAN be compared with something else: against themselves,
            // before and after admission, in `PluginArtifactIntegrity`, and against the
            // operator's pin, in the trust decision below.
            val identity = PluginManifestResourceReader.declaredIdentity(
                declaredDigest = manifest.release.digest,
                origin = origin,
            )

            // The operator's trust decision, taken over the bytes the runtime measured. It runs
            // BEFORE the manifest is admitted and long before a class is linked, so a refusal
            // here means nothing of the artifact ever ran.
            var trusted: PluginTrustDecision.Trusted? = null
            if (trust != null) {
                if (measuredDigest == null) {
                    val refusal = PluginManifestRejection.ArtifactNotMeasurable(
                        artifact = artifact.toString(),
                        detail = "the artifact's bytes were not measured, so the operator's trust " +
                            "policy cannot be applied to it; an artifact whose bytes are unknown is " +
                            "not one that may be loaded",
                    )
                    journal?.record(
                        AdmissionControlRow.compose(
                            pluginId = manifest.plugin.canonicalText(),
                            origin = origin,
                            declaredDigest = manifest.release.digest,
                            measuredDigest = null,
                            verdict = TrustMetadata.Unverified(
                                reason = TrustMetadata.UnverifiedReason.ArtifactNotMeasurable,
                                origin = origin,
                            ),
                            rejection = refusal,
                        ),
                    )
                    return AdmittedArtifacts.Refused(artifact, refusal)
                }

                if (trust.covers(measuredDigest)) {
                    when (val decision = trust.evaluate(artifact, manifest, measuredDigest)) {
                        is PluginTrustDecision.Refused -> {
                            journal?.record(
                                AdmissionControlRow.compose(
                                    pluginId = manifest.plugin.canonicalText(),
                                    origin = origin,
                                    declaredDigest = manifest.release.digest,
                                    measuredDigest = measuredDigest,
                                    verdict = decision.failure.refusal.trustVerdict(origin),
                                    rejection = decision.failure.refusal,
                                ),
                            )
                            return AdmittedArtifacts.Refused(
                                artifact = artifact,
                                rejection = decision.failure.refusal,
                                detail = decision.failure.detail,
                            )
                        }

                        is PluginTrustDecision.Trusted -> {
                            journal?.record(
                                AdmissionControlRow.compose(
                                    pluginId = manifest.plugin.canonicalText(),
                                    origin = origin,
                                    declaredDigest = manifest.release.digest,
                                    measuredDigest = measuredDigest,
                                    verdict = TrustMetadata.Verified(
                                        digest = decision.digest,
                                        origin = origin,
                                    ),
                                    rejection = null,
                                ),
                            )
                            trusted = decision
                        }
                    }
                }
            }

            val result = PluginAdmission.admit(
                decoded = decoded,
                identity = identity,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = alreadyAdmitted,
            )

            when (result) {
                is PluginAdmissionResult.Refused -> return AdmittedArtifacts.Refused(
                    artifact,
                    result.rejection,
                    detail = duplicateIdentityDetail(result.rejection, admittedByIdentity),
                )
                is PluginAdmissionResult.Admitted -> {
                    alreadyAdmitted.add(result.plugin.pluginId)
                    admittedByIdentity[result.plugin.pluginId] = artifact
                    admitted.add(AdmittedArtifact(artifact, result.plugin, trusted))
                }
            }
        }

        return AdmittedArtifacts.Admitted(admitted)
    }

    /**
     * The artifact that had ALREADY CLAIMED the identity when the duplicate was found, and nothing
     * for any other refusal.
     *
     * [PluginManifestRejection.DuplicateIdentity] carries the identity twice and no artifact at
     * all: the vocabulary of refusals is published, and adding a third field to it would break the
     * consumers that read it. The earlier artifact is therefore carried by the refusal's
     * `detail`, which the CLI already prints next to the reason.
     *
     * Without it the operator reads "this artifact was refused because another one already has
     * this name" and has no way of learning WHICH other one: the refused artifact is named by the
     * message, and the one that claimed the name first is named nowhere. That is the difference
     * between a diagnosis and a rumour.
     *
     * ## What this detail does NOT say
     *
     * It does not say the earlier artifact "stays in the composition", because **nothing stays**:
     * one refusal stops the whole composition, and this one is raised while the artifacts are
     * being pre-admitted, before there is a composition to stay in. The earlier artifact had
     * claimed the identity first — that is the only fact worth reporting — but the run refuses to
     * start either way. A detail that says the artifact "stays" teaches the operator that the
     * pipeline they just refused is running with their plugin in it, and that is false.
     */
    internal fun duplicateIdentityDetail(
        rejection: AdmissionRejection,
        admittedByIdentity: Map<String, Path>,
    ): String? {
        if (rejection !is PluginManifestRejection.DuplicateIdentity) return null
        val incumbent = admittedByIdentity[rejection.identity] ?: return null
        return "the same identity was already claimed by $incumbent, admitted earlier in this " +
            "pre-admission pass; the whole composition is refused"
    }

    /**
     * Read the manifest DOCUMENT out of an artifact path, with no classloader involved.
     * Reuses [PluginManifestReadOutcome] rather than inventing a second outcome type: absence, a
     * decoded document and a typed refusal are the same three facts whoever opened the artifact, and
     * two spellings of them would be two authorities for one question.
     */
    fun readFromArtifact(artifact: Path): PluginManifestReadOutcome {
        if (!Files.exists(artifact)) {
            return PluginManifestReadOutcome.Refused(
                PluginManifestRejection.MalformedDocument("plugin artifact does not exist: $artifact"),
            )
        }

        if (Files.isDirectory(artifact)) {
            // An exploded build directory is a legitimate artifact; its manifest is a plain file.
            val manifestFile = artifact.resolve(PluginManifestCodec.RESOURCE_PATH)
            if (!Files.isRegularFile(manifestFile)) return PluginManifestReadOutcome.NoManifest
            val text = try {
                Files.readString(manifestFile)
            } catch (e: IOException) {
                return PluginManifestReadOutcome.Refused(
                    PluginManifestRejection.MalformedDocument("manifest at $manifestFile is unreadable: ${e.message}"),
                )
            }
            return PluginManifestReadOutcome.Read(text, PluginManifestCodec.decode(text))
        }

        return try {
            JarFile(artifact.toFile()).use { jar ->
                val entry = jar.getEntry(PluginManifestCodec.RESOURCE_PATH)
                    ?: return PluginManifestReadOutcome.NoManifest
                val text = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                PluginManifestReadOutcome.Read(text, PluginManifestCodec.decode(text))
            }
        } catch (e: IOException) {
            PluginManifestReadOutcome.Refused(
                PluginManifestRejection.MalformedDocument("cannot read $artifact as a plugin JAR: ${e.message}"),
            )
        }
    }
}

/**
 * The trust verdict a refusal implies.
 *
 * A refusal and a verdict are two views of one fact, and the mapping is fixed: a digest mismatch is
 * a [TrustMetadata.Mismatch] with both digests, and every other refusal is an [TrustMetadata.Unverified]
 * carrying the reason the refusal named. The durable row is written from this, so a row and the
 * refusal the operator read can never disagree about what happened.
 */
private fun dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal.trustVerdict(
    origin: ArtifactOrigin,
): TrustMetadata = when (this) {
    is dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal.RefusedDigestMismatch ->
        TrustMetadata.Mismatch(declared = declared, measured = measured, origin = origin)

    is dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal.RefusedUntrustedIdentity ->
        TrustMetadata.Unverified(reason = reason, origin = origin)

    else -> TrustMetadata.Unverified(
        reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
        origin = origin,
    )
}

/**
 * One artifact that passed admission, with the plugin it declared.
 *
 * @property trust the operator's trust decision, when a trust policy covered this artifact and
 *   approved it. It is carried HERE rather than printed from somewhere else because the admission
 *   pass is the only place that knows it: an operator who cannot see which of their plugins the
 *   policy approved, and on what evidence, cannot tell a policy in force from a policy that was
 *   never consulted.
 */
data class AdmittedArtifact(
    val path: Path,
    val plugin: AdmittedPlugin,
    val trust: PluginTrustDecision.Trusted? = null,
)

/**
 * Closed outcome of pass 1. There is no partial admission: either every artifact this installation
 * will compose over was admitted, or the composition does not start.
 */
sealed interface AdmittedArtifacts {

    data class Admitted(val artifacts: List<AdmittedArtifact>) : AdmittedArtifacts

    /**
     * Which artifact failed, and what was wrong with it. Never "an artifact somewhere".
     *
     * @property detail the human sentence behind the refusal, when the refusal has one. The
     *   [rejection] is the typed fact; this is what the operator reads.
     */
    data class Refused(
        val artifact: Path,
        val rejection: AdmissionRejection,
        val detail: String? = null,
    ) : AdmittedArtifacts
}
