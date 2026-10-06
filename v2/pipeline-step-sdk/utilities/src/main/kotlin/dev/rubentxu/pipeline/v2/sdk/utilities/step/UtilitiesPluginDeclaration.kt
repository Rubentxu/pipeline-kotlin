package dev.rubentxu.pipeline.v2.sdk.utilities.step

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
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * S6/C — the utilities plugin's DECLARATION, in one place.
 *
 * ## Why this object exists
 *
 * The manifest has to be emitted into the artifact at BUILD time and read back at RUN time.
 * The tempting way to do that is to write the JSON by hand in `build.gradle.kts`, and that
 * would be a second authority: a hand-typed list of Step keys that the Kotlin code never
 * consults, free to drift until a plugin ships a document describing steps it does not have.
 *
 * So the declaration lives here, in code, and BOTH consumers read it:
 *
 * - [CoreUtilsStepDefinitionContributor] at runtime, when it builds its provider metadata;
 * - [main] below, invoked by Gradle at build time to emit the document.
 *
 * One authority, two readers. If a Step is added, both sides change together or neither does.
 *
 * ## Why the manifest declares a digest it cannot cover
 *
 * [manifest] reports the digest of the artifact's content EXCLUDING the manifest document
 * itself, which is the same convention the existing `utilities-release.properties` digest
 * uses. The alternative — a digest over the JAR including the manifest that carries it — has
 * no fixed point and cannot be computed at all. This is stated rather than left implicit
 * because "what the digest covers" is a contract question, and ADR-EVO-003 makes it a
 * contested one.
 */
object UtilitiesPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/utilities-release.properties"

    /**
     * PipelineK versions this artifact supports.
     *
     * Exclusive upper bound: 0.49 is where support stops, not where the file stops parsing.
     */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    /** Build-time provenance, fail-closed: never accept a hand-typed digest. */
    fun provider(classLoader: ClassLoader = UtilitiesPluginDeclaration::class.java.classLoader): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.utilities.publisher")
            ?: props["pipeline.utilities.publisher"]
            ?: error(
                "Missing publisher provenance (no system property 'pipeline.utilities.publisher' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The utilities OFFICIAL_PLUGIN refuses to " +
                    "register without it.",
            )
        val namespace = System.getProperty("pipeline.utilities.namespace")
            ?: props["pipeline.utilities.namespace"]
            ?: "pipeline.utilities"
        val versionRaw = System.getProperty("pipeline.utilities.release.version")
            ?: props["pipeline.utilities.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.utilities.release.digest")
            ?: props["pipeline.utilities.release.digest"]
            ?: error(
                "Missing digest provenance (no system property 'pipeline.utilities.release.digest' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The utilities OFFICIAL_PLUGIN refuses to " +
                    "register without the real SHA-256 of its own artefact.",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "utilities")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin = plugin, version = parseSemVer(versionRaw), digest = Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
    }

    /**
     * The declaration, as the domain type.
     *
     * The Step list is the SAME literal the contributor validates against at runtime, which
     * is what makes the emitted document trustworthy rather than a second opinion.
     */
    fun manifest(
        classLoader: ClassLoader = UtilitiesPluginDeclaration::class.java.classLoader,
    ): PluginManifest {
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
                    PluginStepContribution(CoreUtilsReadJsonKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsWriteJsonKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsSha256Key.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsReadYamlKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsWriteYamlKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsFindFilesKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsZipKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
                    PluginStepContribution(CoreUtilsUnzipKey.VALUE, setOf(EXECUTION_LOCATION_CAPABILITY)),
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
        val cleaned = raw.removePrefix("v")
        val parts = cleaned.split(".")
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
 * Gradle runs this through `JavaExec` after the provenance digest exists and redirects the
 * output to `build/resources/main/META-INF/pipelinek/plugin-manifest.json`, so the document
 * ships inside the JAR at [dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH].
 *
 * It prints rather than writes so the build decides the destination, and it FAILS LOUDLY:
 * a non-zero exit from missing provenance stops the build instead of shipping a JAR whose
 * manifest is absent.
 */
fun main() {
    val manifest = UtilitiesPluginDeclaration.manifest()
    print(dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.encode(manifest))
}