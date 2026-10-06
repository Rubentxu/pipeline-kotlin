package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.ArtifactIdentityVerdict
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import java.io.InputStream
import java.security.MessageDigest

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
     * Hash the bytes the runtime read.
     *
     * Returns null when the resource cannot be re-read for measurement, which is NOT a
     * pass: [MeasuredArtifactIdentity] renders that as [ArtifactIdentityVerdict.Unverified],
     * a third state distinct from both agreement and disagreement.
     */
    fun measure(
        classLoader: ClassLoader,
        declaredDigest: Digest,
        origin: ArtifactOrigin,
    ): MeasuredArtifactIdentity {
        val measured = runCatching {
            val bytes = classLoader.getResourceAsStream(PluginManifestCodec.RESOURCE_PATH)?.use { it.readBytes() }
            bytes?.let { sha256(it) }
        }.getOrNull()

        return MeasuredArtifactIdentity(
            declaredDigest = declaredDigest,
            measuredDigest = measured?.let { Digest("sha256:$it") },
            origin = origin,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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