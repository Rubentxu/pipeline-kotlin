package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * S6/C — the scm-git plugin's DECLARATION, in one place.
 *
 * Same contract as `UtilitiesPluginDeclaration` and `HttpPluginDeclaration`: the manifest is
 * emitted at BUILD time and read at RUN time, so both readers read ONE declaration rather than
 * a document typed into the build by hand.
 */
object ScmGitPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/scm-git-release.properties"

    /** Exclusive upper bound: 0.49 is where support stops, not where parsing stops. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    /** Fail-closed provenance: never accept a hand-typed digest. */
    fun provider(classLoader: ClassLoader = ScmGitPluginDeclaration::class.java.classLoader): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.scm-git.publisher")
            ?: props["pipeline.scm-git.publisher"]
            ?: error(
                "Missing publisher provenance (no system property 'pipeline.scm-git.publisher' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The scm-git OFFICIAL_PLUGIN refuses to " +
                    "register without it.",
            )
        val namespace = System.getProperty("pipeline.scm-git.namespace")
            ?: props["pipeline.scm-git.namespace"]
            ?: "pipeline.scm-git"
        val versionRaw = System.getProperty("pipeline.scm-git.release.version")
            ?: props["pipeline.scm-git.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.scm-git.release.digest")
            ?: props["pipeline.scm-git.release.digest"]
            ?: error(
                "Missing digest provenance (no system property 'pipeline.scm-git.release.digest' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The scm-git OFFICIAL_PLUGIN refuses to " +
                    "register without the real SHA-256 of its own artefact.",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "scm-git")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin = plugin, version = parseSemVer(versionRaw), digest = Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
    }

    fun manifest(classLoader: ClassLoader = ScmGitPluginDeclaration::class.java.classLoader): PluginManifest {
        val provider = provider(classLoader)
        return PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = provider.plugin,
            release = provider.release,
            apiRange = API_RANGE,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            contributions = PluginContributions(
                steps = listOf(
                    PluginStepContribution(ScmGitCheckoutKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                ),
                capabilities = setOf(EXECUTION_LOCATION_CAPABILITY),
            ),
        )
    }

    internal fun releaseProperties(classLoader: ClassLoader): Map<String, String> {
        val resource = classLoader.getResource(RELEASE_PROPERTIES_RESOURCE) ?: return emptyMap()
        val text = resource.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val map = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
        return map
    }

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.removePrefix("v").split(".")
        return SemVer(
            major = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            patch = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }
}

/**
 * Build-time entry point: emit the manifest document to stdout.
 *
 * A non-zero exit from missing provenance stops the build instead of shipping a JAR whose
 * manifest is absent — and a JAR without a manifest is refused at runtime.
 */
fun main() {
    print(PluginManifestCodec.encode(ScmGitPluginDeclaration.manifest()))
}