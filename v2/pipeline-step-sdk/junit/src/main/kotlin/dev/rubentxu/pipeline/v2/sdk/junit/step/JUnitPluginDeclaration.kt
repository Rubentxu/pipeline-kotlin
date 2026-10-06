package dev.rubentxu.pipeline.v2.sdk.junit.step

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
 * S6/C — the junit plugin's DECLARATION, in one place.
 *
 * Same contract as the other three official plugins: one declaration read by both the
 * contributor at runtime and the build at build time, so the emitted document cannot drift
 * away from the code it describes.
 */
object JUnitPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/junit-release.properties"

    /** Exclusive upper bound: 0.49 is where support stops, not where parsing stops. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    /** Fail-closed provenance: never accept a hand-typed digest. */
    fun provider(classLoader: ClassLoader = JUnitPluginDeclaration::class.java.classLoader): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.junit.publisher")
            ?: props["pipeline.junit.publisher"]
            ?: error(
                "Missing publisher provenance (no system property 'pipeline.junit.publisher' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The junit OFFICIAL_PLUGIN refuses to register " +
                    "without it.",
            )
        val namespace = System.getProperty("pipeline.junit.namespace")
            ?: props["pipeline.junit.namespace"]
            ?: "pipeline.junit"
        val versionRaw = System.getProperty("pipeline.junit.release.version")
            ?: props["pipeline.junit.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.junit.release.digest")
            ?: props["pipeline.junit.release.digest"]
            ?: error(
                "Missing digest provenance (no system property 'pipeline.junit.release.digest' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The junit OFFICIAL_PLUGIN refuses to register " +
                    "without the real SHA-256 of its own artefact.",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "junit")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin = plugin, version = parseSemVer(versionRaw), digest = Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.TESTING, PluginFamily.REPORTING),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
    }

    fun manifest(classLoader: ClassLoader = JUnitPluginDeclaration::class.java.classLoader): PluginManifest {
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
                    PluginStepContribution(JUnitResultsKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
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
    print(PluginManifestCodec.encode(JUnitPluginDeclaration.manifest()))
}