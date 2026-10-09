package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmission
import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * S6-COMPOSITION, pass 1 — admit every plugin artifact BEFORE a line of plugin code runs.
 *
 * ## What this closes
 *
 * BLOCK 2 measured that [PluginAdmissionGate] had eight callers and every one of them was in
 * `src/test`. The installed product reached plugins through `PluginComposition.resolve`, which
 * never asked the gate, so the whole of S6/B..E was unreachable from any shipped command: an
 * artifact with no manifest executed, and so did a plugin whose manifest declared a Step it did
 * not implement.
 *
 * ## Why this is pass 1 and not a check inside composition
 *
 * [PluginComposition.resolve] discovers through `ServiceLoader`, and `ServiceLoader` **instantiates**
 * providers as it iterates them. Putting admission there would load plugin code and initialise its
 * static state before the decision that is supposed to precede exactly that — the inversion
 * ADR-EVO-003 exists to forbid. So admission happens over a plain list of artifact PATHS, with no
 * `ClassLoader`, no `ServiceLoader` and no contributor class anywhere in this file.
 *
 * What pass 1 decides is exactly what [PluginAdmission.admit] decides, because
 * [PluginAdmission.admit] takes a document, a measured identity, a runtime version and the set of
 * identities already admitted — and not one of those requires a loaded class. What it deliberately
 * does NOT decide is [AdmittedPlugin.admitContributions], the declaration-versus-implementation
 * cross-check, which needs the contributor's own output and therefore belongs to pass 2.
 *
 * ## Why the manifest is read from the artifact, not from a classloader
 *
 * The existing [PluginManifestResourceReader.read] takes a contributor class because it also
 * enforces that the manifest and the class come from the same artifact. That check exists to stop a
 * plugin planting a declaration in one jar while contributing code from another. Here it is
 * structurally impossible rather than merely checked: the manifest is read out of the artifact path
 * that is being admitted, and pass 2 composes over those same admitted paths. There is no second
 * artifact to substitute.
 *
 * ## Fail-closed
 *
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
     * @param runtimeVersion the running PipelineK version, compared against each declared
     *   `apiRange`. Supplied by the caller because reading it is a separate fail-closed concern.
     */
    fun admitAll(artifacts: List<Path>, runtimeVersion: SemVer): AdmittedArtifacts {
        val admitted = mutableListOf<AdmittedArtifact>()
        val alreadyAdmitted = mutableSetOf<String>()

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

            // The origin is the artifact PATH this decision is about. That is not a placeholder:
            // it is the identity the runtime can actually establish today, and the one thing that
            // distinguishes "admitted from here" from "admitted from somewhere on the classpath".
            val identity = PluginManifestResourceReader.measure(
                declaredDigest = manifest.release.digest,
                origin = ArtifactOrigin.LocalClasspathEntry(artifact.toString()),
            )

            val result = PluginAdmission.admit(
                decoded = decoded,
                identity = identity,
                runtimeVersion = runtimeVersion,
                alreadyAdmitted = alreadyAdmitted,
            )

            when (result) {
                is PluginAdmissionResult.Refused -> return AdmittedArtifacts.Refused(artifact, result.rejection)
                is PluginAdmissionResult.Admitted -> {
                    alreadyAdmitted.add(result.plugin.pluginId)
                    admitted.add(AdmittedArtifact(artifact, result.plugin))
                }
            }
        }

        return AdmittedArtifacts.Admitted(admitted)
    }

    /**
     * Read the manifest DOCUMENT out of an artifact path, with no classloader involved.
     *
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

/** One artifact that passed admission, with the plugin it declared. */
data class AdmittedArtifact(val path: Path, val plugin: AdmittedPlugin)

/**
 * Closed outcome of pass 1. There is no partial admission: either every artifact this installation
 * will compose over was admitted, or the composition does not start.
 */
sealed interface AdmittedArtifacts {

    data class Admitted(val artifacts: List<AdmittedArtifact>) : AdmittedArtifacts

    /** Which artifact failed, and what was wrong with it. Never "an artifact somewhere". */
    data class Refused(val artifact: Path, val rejection: PluginManifestRejection) : AdmittedArtifacts
}
