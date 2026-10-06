package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.ArtifactIdentityVerdict
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import java.io.InputStream

/**
 * S6/C — reads a plugin's manifest DOCUMENT out of its artifact.
 *
 * ## The ordering this exists to establish
 *
 * [read] takes a [ClassLoader] and asks for a resource BY NAME. It never touches
 * `ServiceLoader`, never names a contributor class, and never causes a plugin class to be
 * initialised. That is the whole point: admission has to be able to say "no" about a plugin
 * whose code has not run, and a reader that resolved a class to find its declaration could
 * not make that promise.
 *
 * A caller that wants the stronger guarantee — that the artifact declaring this manifest is
 * the artifact whose classes will later be loaded — passes [strict]. It is on by default, and
 * a caller has to actively turn it off, because the unchecked mode admits any jar on the
 * classpath that carries the resource, including a classpath entry this runtime never
 * intended to trust.
 *
 * ## Measured vs declared identity
 *
 * [measure] hashes the bytes the runtime actually read. It is a separate call from [read] on
 * purpose: the manifest's own `releaseDigest` is a claim, and a claim is only worth
 * comparing once somebody has measured the thing it claims about. [MeasuredArtifactIdentity]
 * keeps the two apart so admission cannot silently treat "unchecked" as "verified".
 */
object PluginManifestResourceReader {

    /**
     * Read and decode the manifest carried at [PluginManifestCodec.RESOURCE_PATH].
     *
     * @param strict when true (the default), the artifact the resource came from must also
     *   be the code source of [contributorClass]. A mismatch is refused rather than
     *   tolerated, because a plugin can plant a manifest in one jar and contribute classes
     *   from another, and admitting on the manifest's word is exactly the substitution this
     *   blocks.
     */
    fun read(
        classLoader: ClassLoader,
        contributorClass: Class<*>,
        strict: Boolean = true,
    ): PluginManifestReadOutcome {
        val resource: InputStream = classLoader.getResourceAsStream(PluginManifestCodec.RESOURCE_PATH)
            ?: return PluginManifestReadOutcome.NoManifest

        val text = resource.use { it.readBytes().toString(Charsets.UTF_8) }
        val decoded = PluginManifestCodec.decode(text)

        if (strict && decoded is PluginManifestDecodeResult.Accepted) {
            val manifestUrl = classLoader.getResource(PluginManifestCodec.RESOURCE_PATH)
                ?: return PluginManifestReadOutcome.Refused(
                    dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.MalformedDocument(
                        "manifest resource vanished between read and verification",
                    ),
                )
            val codeSource = contributorClass.protectionDomain?.codeSource?.location
            if (codeSource != null) {
                // Compare the ARTIFACT, not the resource. A resource inside a JAR resolves
                // to `jar:file:/path/plugin.jar!/META-INF/...` while the code source is
                // `file:/path/plugin.jar`, so comparing the two URLs directly compares a
                // container to its contents and rejects every legitimate plugin. This bug
                // was found by the harness, which loaded a real artifact for the first
                // time rather than a parent-classloader class.
                val manifestArtifact = artifactRootOf(manifestUrl)
                if (!sameArtifact(manifestArtifact, codeSource)) {
                    return PluginManifestReadOutcome.Refused(
                        dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection.MalformedDocument(
                            "manifest is declared by $manifestArtifact but the contributor class is " +
                                "loaded from $codeSource; a plugin may not declare itself in one " +
                                "artifact and contribute code from another",
                        ),
                    )
                }
            }
        }

        return PluginManifestReadOutcome.Read(text, decoded)
    }

    /**
 * Record the artifact identity S6 can actually establish — and no more.
 *
 * ## Why this returns an unmeasured identity rather than hashing something
 *
 * An earlier version of this method read the manifest resource back and hashed it, then
 * offered that hash as `measuredDigest`. That was fabrication with a SHA-256 on top: the
 * declared digest covers the whole artifact, while the bytes reachable through a
 * [ClassLoader] are the declaration document, so the comparison is between a container and
 * its contents and can only ever disagree. Worse, it disagreed *meaningfully* — a Mismatch
 * verdict reads as "this plugin lied", and the plugin would have been telling the truth.
 *
 * Producing a false accusation is worse than producing no measurement, so this returns
 * `measuredDigest = null`, which [MeasuredArtifactIdentity] renders as
 * [ArtifactIdentityVerdict.Unverified]: a named third state rather than a pass.
 *
 * ## What would close it
 *
 * Measuring the artifact needs the artifact's bytes, which means resolving where it came
 * from — a [ArtifactOrigin] the runtime does not have today. That capability is the opening
 * condition of EVO-M3b's generic artifact model. Until it exists, admission is a
 * DECLARATION gate: it refuses malformed, incompatible, duplicate and unverifiable
 * manifests, and it says nothing at all about whether the bytes match the claim.
 */
fun measure(
    declaredDigest: Digest,
    origin: ArtifactOrigin,
): MeasuredArtifactIdentity = MeasuredArtifactIdentity(
    declaredDigest = declaredDigest,
    measuredDigest = null,
    origin = origin,
)

    /**
     * The artifact that CONTAINS a resource URL.
     *
     * A JAR resource resolves as `jar:file:/dir/plugin.jar!/META-INF/pipelinek/x.json`, and
     * the artifact is everything before the `!/`. A directory classpath entry resolves as
     * `file:/dir/META-INF/pipelinek/x.json`, and the artifact is everything before the
     * resource path. Handling both is what lets the same check work for a shaded JAR and for
     * an exploded build directory.
     */
    private fun artifactRootOf(resourceUrl: java.net.URL): java.net.URL {
        val spec = resourceUrl.toString()
        val jarSeparator = spec.indexOf("!/")
        if (jarSeparator >= 0) {
            // Everything before "!/", MINUS the "jar:" prefix: the artifact is the file the
            // JAR contains, not the JAR handler. Keeping "jar:" here would hand
            // `jar:file:/x.jar` back to the jar URL handler, which demands a "!/" segment
            // and throws MalformedURLException.
            val inner = spec.substring(0, jarSeparator).removePrefix("jar:")
            return java.net.URL(inner)
        }
        val marker = "/${PluginManifestCodec.RESOURCE_PATH}"
        val idx = spec.indexOf(marker)
        return if (idx >= 0) java.net.URL(spec.substring(0, idx)) else resourceUrl
    }

    /** Same artifact, ignoring a trailing slash and any scheme casing. */
    private fun sameArtifact(a: java.net.URL, b: java.net.URL): Boolean =
        normalise(a) == normalise(b)

    private fun normalise(url: java.net.URL): String =
        url.toString().trimEnd('/').lowercase()
}

/** Closed outcome of locating a manifest in an artifact. Absence is a case, not an exception. */
sealed interface PluginManifestReadOutcome {

    /** The artifact carries no manifest. Admission refuses this rather than defaulting. */
    data object NoManifest : PluginManifestReadOutcome

    data class Read(val text: String, val decoded: PluginManifestDecodeResult) : PluginManifestReadOutcome

    data class Refused(
        val rejection: dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection,
    ) : PluginManifestReadOutcome
}
