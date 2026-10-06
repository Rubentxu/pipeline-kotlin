package dev.rubentxu.pipeline.v2.sdk.http

// HTTP_TRANSPORT_CAPABILITY is declared in THIS package and therefore needs no import; the
// other two come from the domain and from credentials-api respectively. The three live in
// three different modules, which is itself the reason a manifest cannot simply say "http needs
// network access" in prose.
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
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
 * S6/C — the http plugin's DECLARATION, in one place.
 *
 * Same contract as [dev.rubentxu.pipeline.v2.sdk.utilities.step.UtilitiesPluginDeclaration], and
 * for the same reason: the manifest is emitted into the artifact at BUILD time and read back
 * at RUN time, so both readers must read ONE declaration. A JSON document typed by hand in
 * `build.gradle.kts` would be a second authority able to describe a Step the code does not have.
 *
 * The declared digest covers the artifact content EXCLUDING this document, matching the
 * `http-release.properties` convention already in use. A digest that included the manifest
 * carrying it would have no fixed point.
 */
object HttpPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/http-release.properties"

    /** Exclusive upper bound: 0.49 is where support stops, not where parsing stops. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    private val DECLARED_CAPABILITIES =
        setOf(HTTP_TRANSPORT_CAPABILITY, NETWORK_EGRESS_CAPABILITY, BASIC_CREDENTIALS_CAPABILITY)

    /** Fail-closed provenance: never accept a hand-typed digest. */
    fun provider(classLoader: ClassLoader = HttpPluginDeclaration::class.java.classLoader): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.http.publisher")
            ?: props["pipeline.http.publisher"]
            ?: error(
                "Missing publisher provenance (no system property 'pipeline.http.publisher' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The HTTP OFFICIAL_PLUGIN refuses to register " +
                    "without it.",
            )
        val namespace = System.getProperty("pipeline.http.namespace")
            ?: props["pipeline.http.namespace"]
            ?: "pipeline-plugin-http"
        val versionRaw = System.getProperty("pipeline.http.release.version")
            ?: props["pipeline.http.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.http.release.digest")
            ?: props["pipeline.http.release.digest"]
            ?: error(
                "Missing digest provenance (no system property 'pipeline.http.release.digest' and no " +
                    "$RELEASE_PROPERTIES_RESOURCE in the JAR). The HTTP OFFICIAL_PLUGIN refuses to register " +
                    "without the real SHA-256 of its own artefact.",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "http")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin = plugin, version = parseSemVer(versionRaw), digest = Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.NETWORK),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
    }

    fun manifest(classLoader: ClassLoader = HttpPluginDeclaration::class.java.classLoader): PluginManifest {
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
                    PluginStepContribution(HttpRequestKey.VALUE, DECLARED_CAPABILITIES),
                ),
                capabilities = DECLARED_CAPABILITIES,
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
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(
            major = parts[0].toInt(),
            minor = parts[1].toInt(),
            patch = parts[2].toInt(),
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
    print(PluginManifestCodec.encode(HttpPluginDeclaration.manifest()))
}