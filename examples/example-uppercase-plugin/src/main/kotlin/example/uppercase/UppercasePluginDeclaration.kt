package example.uppercase

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginDirectiveContribution
import dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY

/**
 * S6/I — the external plugin's DECLARATION, in one place.
 *
 * ## Why this file exists now and not in BLOCK 1-A..1-H
 *
 * Because until here this plugin had no manifest at all. That was not a smaller plugin, it was an
 * UNADMITTED one: `PluginAdmissionGate` refuses any contributor whose artifact has no
 * `META-INF/pipelinek/plugin-manifest.json`, and the reason this JAR was running at all is that
 * it is discovered by a path — `PluginComposition.resolve` — that never asks the gate. Two
 * routes, one of them guarded. Naming that here matters more than the code below.
 *
 * ## One declaration, two readers
 *
 * The manifest is emitted into the artifact at BUILD time and read back at RUN time. Both readers
 * must read THIS object. A JSON document typed by hand in `build.gradle.kts` would be a second
 * authority able to describe a Step this code does not have — the same defect as the KSP in
 * BLOCK 1-H, in a different file.
 */
object UppercasePluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/example-uppercase-release.properties"

    /** Exclusive upper bound: 0.49 is where support stops, not where parsing stops. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    /**
     * What the Steps collectively demand, and it is written as the union of the three contracts
     * rather than as a hand-kept list.
     *
     * That is the whole reason the validator can be a cross-check instead of a comparison against
     * prose: `uppercase` demands nothing, `uppercaseObserved` demands the host's event-emission
     * seam, and `uppercase.cased` demands the table this JAR supplies. Note the second one is a
     * seam the plugin does NOT supply and is not expected to — a manifest's capability set states
     * what the plugin NEEDS, not what it hands over. `http` declares `BASIC_CREDENTIALS` for the
     * same reason, and a model that conflated the two would refuse it.
     */
    private val DECLARED_CAPABILITIES: Set<StepCapability> =
        setOf(PLUGIN_EVENT_EMISSION_CAPABILITY, EXAMPLE_CASE_TABLE_CAPABILITY)

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    /**
     * Fail-closed provenance: never accept a hand-typed digest.
     *
     * If the build did not write a release-properties document, this throws instead of inventing
     * one. A digest nobody measured is worse than no digest, because admission would read it as a
     * measured identity — that is the whole claim `MeasuredArtifactIdentity` exists to keep
     * separable from what is declared.
     */
    fun provider(
        classLoader: ClassLoader = UppercasePluginDeclaration::class.java.classLoader,
    ): StepProviderMetadata {
        val props = releaseProperties(classLoader)
        val publisher = System.getProperty("pipeline.example.uppercase.publisher")
            ?: props["pipeline.example.uppercase.publisher"]
            ?: error(
                "Missing publisher provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE " +
                    "in the JAR). The plugin refuses to register without it.",
            )
        val namespace = System.getProperty("pipeline.example.uppercase.namespace")
            ?: props["pipeline.example.uppercase.namespace"]
            ?: "example-uppercase-plugin"
        val versionRaw = System.getProperty("pipeline.example.uppercase.release.version")
            ?: props["pipeline.example.uppercase.release.version"]
            ?: error(
                "Missing version provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )
        val digestRaw = System.getProperty("pipeline.example.uppercase.release.digest")
            ?: props["pipeline.example.uppercase.release.digest"]
            ?: error(
                "Missing digest provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "uppercase")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin, parseSemVer(versionRaw), Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified,
        )
    }

    /** All four families, from one artifact. The subject of BLOCK 1-I and of 1-J. */
    fun manifest(
        classLoader: ClassLoader = UppercasePluginDeclaration::class.java.classLoader,
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
                    PluginStepContribution(UppercaseStepDefinition.KEY, emptySet()),
                    PluginStepContribution(
                        UppercaseObservedStepDefinition.KEY,
                        setOf(PLUGIN_EVENT_EMISSION_CAPABILITY),
                    ),
                    PluginStepContribution(
                        UppercaseCasedStepDefinition.KEY,
                        setOf(EXAMPLE_CASE_TABLE_CAPABILITY),
                    ),
                ),
                directives = listOf(
                    PluginDirectiveContribution(UppercaseCasedOnDirectiveDefinition.KEY),
                ),
                events = listOf(PluginEventContribution("example.uppercase.applied")),
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
    print(PluginManifestCodec.encode(UppercasePluginDeclaration.manifest()))
}
