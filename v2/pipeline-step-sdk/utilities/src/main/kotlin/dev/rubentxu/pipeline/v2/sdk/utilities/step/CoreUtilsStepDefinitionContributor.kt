package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepManifest
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.io.File

/**
 * LFC-2E2 utilities OFFICIAL_PLUGIN contributor.
 *
 * Mirrors the proven scm-git pattern (F5.1 / ADR-0092):
 *
 * - Real [StepProviderMetadata] built from a typed [PluginManifest].
 * - Build-time provenance is required: the publisher / namespace / version /
 *   digest come from `META-INF/utilities-release.properties` inside the JAR
 *   (Gradle writes them via the `computeUtilitiesDigest` task in
 *   `build.gradle.kts`).
 * - If `META-INF/utilities-release.properties` is absent at registration
 *   time the contributor fails closed with an `IllegalStateException`
 *   naming the missing property. We never accept a hand-typed digest.
 *
 * - `registrations()` is the additive path used by
 *   `ExternalStepPluginDiscovery.registerInto`; it lets the runtime
 *   validate every registration through `StepRegistry.register` and
 *   fail-closed on duplicate keys.
 *
 * - `definitions()` is retained for backwards-compat with any caller that
 *   still wraps registrations manually.
 */
class CoreUtilsStepDefinitionContributor : StepDefinitionContributor {

    override val id: String = "utilities"

    private val readJsonStep: CoreUtilsReadJsonStepDefinition = CoreUtilsReadJsonStepDefinition()
    private val writeJsonStep: CoreUtilsWriteJsonStepDefinition = CoreUtilsWriteJsonStepDefinition()
    private val sha256Step: CoreUtilsSha256StepDefinition = CoreUtilsSha256StepDefinition()

    override fun definitions(): Iterable<StepDefinition<*, *>> =
        listOf(readJsonStep, writeJsonStep, sha256Step)

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = buildProvider()
        val manifest = buildManifest(provider)
        PluginManifestValidator.validate(manifest, listOf(readJsonStep, writeJsonStep, sha256Step))
        return listOf(
            StepRegistration(readJsonStep, provider),
            StepRegistration(writeJsonStep, provider),
            StepRegistration(sha256Step, provider),
        )
    }

    /**
     * Build-time provenance is consumed from `META-INF/utilities-release.properties`
     * inside the JAR. System properties override the resource values when
     * present (so unit tests can pin metadata without rebuilding).
     *
     * Fail-closed: if neither source yields publisher / digest, the
     * contributor refuses to register. Production wiring always threads
     * these values through Gradle.
     */
    private fun buildProvider(): StepProviderMetadata {
        val releaseProps = loadReleaseProperties()
        val publisher = System.getProperty("pipeline.utilities.publisher")
            ?: releaseProps["pipeline.utilities.publisher"]
            ?: error("Missing publisher provenance (no system property 'pipeline.utilities.publisher' and no META-INF/utilities-release.properties in the JAR). The utilities OFFICIAL_PLUGIN refuses to register without it.")
        val namespace = System.getProperty("pipeline.utilities.namespace")
            ?: releaseProps["pipeline.utilities.namespace"]
            ?: "pipeline.utilities"
        val versionRaw = System.getProperty("pipeline.utilities.release.version")
            ?: releaseProps["pipeline.utilities.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.utilities.release.digest")
            ?: releaseProps["pipeline.utilities.release.digest"]
            ?: error("Missing digest provenance (no system property 'pipeline.utilities.release.digest' and no META-INF/utilities-release.properties in the JAR). The utilities OFFICIAL_PLUGIN refuses to register without the real SHA-256 of its own artefact.")
        val semver = parseSemVer(versionRaw)
        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "utilities")
        val release = PluginReleaseRef(
            plugin = plugin,
            version = semver,
            digest = Digest(digestRaw),
        )
        return StepProviderMetadata.create(
            plugin = plugin,
            release = release,
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
    }

    private fun buildManifest(provider: StepProviderMetadata): PluginManifest = PluginManifest(
        plugin = provider.plugin,
        release = provider.release,
        publisher = provider.publisher,
        families = provider.families,
        delivery = provider.delivery,
        trust = provider.trust,
        stepManifests = listOf(
            StepManifest(
                stepKey = CoreUtilsReadJsonKey.VALUE,
                declaredCapabilities = readJsonStep.contract.requiredCapabilities,
            ),
            StepManifest(
                stepKey = CoreUtilsWriteJsonKey.VALUE,
                declaredCapabilities = writeJsonStep.contract.requiredCapabilities,
            ),
            StepManifest(
                stepKey = CoreUtilsSha256Key.VALUE,
                declaredCapabilities = sha256Step.contract.requiredCapabilities,
            ),
        ),
    )

    private fun loadReleaseProperties(): Map<String, String> {
        val resource = "/META-INF/utilities-release.properties"
        val stream = javaClass.classLoader.getResourceAsStream(resource)
            ?: return emptyMap()
        return stream.use { input ->
            val map = linkedMapOf<String, String>()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val k = trimmed.substring(0, idx).trim()
                        val v = trimmed.substring(idx + 1).trim()
                        map[k] = v
                    }
                }
            }
            map
        }
    }

    private fun parseSemVer(raw: String): SemVer {
        // Tolerate a leading `v` (matches the scm-git implementation pattern).
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
 * Internal helper used by tests / build scripts to load the build-time
 * release properties file from the class loader.
 */
internal fun utilitiesReleasePropertiesFromClasspath(): File? {
    val url = CoreUtilsStepDefinitionContributor::class.java.classLoader
        .getResource("META-INF/utilities-release.properties")
        ?: return null
    return File(url.toURI())
}
