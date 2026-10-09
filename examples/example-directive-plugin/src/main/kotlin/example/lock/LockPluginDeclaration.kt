package example.lock

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginDirectiveContribution
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * S6/I — the external DIRECTIVE plugin's DECLARATION, in one place.
 *
 * ## Why this file exists now
 *
 * Because until here this plugin declared itself through `META-INF/services` and nothing else,
 * which made it an UNADMITTED plugin: pass 1 of S6/COMPOSITION refuses any discovered artifact
 * without `META-INF/pipelinek/plugin-manifest.json`. Its sibling `example-block-plugin` was
 * refused for exactly this reason, and this one would have been next.
 *
 * A `ServiceLoader` descriptor is a claim that the artifact CONTRIBUTES; the manifest is the claim
 * about WHAT it contributes. This artifact made the first and omitted the second.
 *
 * ## One declaration, two readers
 *
 * Emitted into the artifact at BUILD time and read back at RUN time; both read THIS object. A
 * hand-typed JSON document in `build.gradle.kts` would be a second authority free to describe a
 * directive this code does not have.
 */
object LockPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/example-directive-release.properties"

    /** Exclusive upper bound: 0.49 is where support stops, not where parsing stops. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    /**
     * Fail-closed provenance: never accept a hand-typed digest.
     *
     * A digest nobody measured is worse than no digest, because admission would read it as a
     * MEASURED identity — which is exactly what `MeasuredArtifactIdentity` exists to keep
     * separable from what a document merely claims.
     */
    fun provider(
        classLoader: ClassLoader = LockPluginDeclaration::class.java.classLoader,
    ): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.example.directive.publisher")
            ?: props["pipeline.example.directive.publisher"]
            ?: error(
                "Missing publisher provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE " +
                    "in the JAR). The plugin refuses to register without it.",
            )
        val namespace = System.getProperty("pipeline.example.directive.namespace")
            ?: props["pipeline.example.directive.namespace"]
            ?: "example-directive-plugin"
        val versionRaw = System.getProperty("pipeline.example.directive.release.version")
            ?: props["pipeline.example.directive.release.version"]
            ?: error(
                "Missing version provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )
        val digestRaw = System.getProperty("pipeline.example.directive.release.digest")
            ?: props["pipeline.example.directive.release.digest"]
            ?: error(
                "Missing digest provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "lock")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin, parseSemVer(versionRaw), Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified,
        )
    }

    /**
     * One directive, no Steps, no events.
     *
     * The directive key is taken from the definition's own `KEY` rather than retyped, for the same
     * reason the Step plugins take their capabilities from the contract: the cross-check compares
     * the manifest against real contributions exactly, so a transcription here would be a
     * difference nobody introduced on purpose.
     */
    fun manifest(
        classLoader: ClassLoader = LockPluginDeclaration::class.java.classLoader,
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
                steps = emptyList(),
                directives = listOf(PluginDirectiveContribution(LockDirectiveDefinition.KEY)),
                events = emptyList(),
                capabilities = emptySet(),
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
            if (idx > 0) map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
        return map
    }
}

/**
 * Build-time entry point: emit the manifest document to stdout.
 *
 * A non-zero exit from missing provenance stops the build instead of shipping a JAR whose manifest
 * is absent — and a JAR without a manifest is refused at runtime, so a silent skip here would
 * produce an artifact that passes its own build and fails its own admission.
 */
fun main() {
    print(PluginManifestCodec.encode(LockPluginDeclaration.manifest()))
}
