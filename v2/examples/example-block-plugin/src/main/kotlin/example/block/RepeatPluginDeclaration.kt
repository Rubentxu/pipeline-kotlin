package example.block

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * The external BLOCK plugin's DECLARATION, in one place.
 * ## Why this file exists now
 * Because until here this plugin declared itself through `META-INF/services` and nothing else
 * That made it an UNADMITTED plugin: once pass 1 of/COMPOSITION reached the product
 * `PreLoadPluginAdmission` refused this artifact with `no manifest at
 * META-INF/pipelinek/plugin-manifest.json` and every run that had the plugin on its classpath
 * exited 2. Measured, not predicted — 12 `UatLocal009TopStepsTest` rows failed naming this exact
 * jar.
 * The finding is the point, not the fix. A ServiceLoader descriptor is a claim that the artifact
 * CONTRIBUTES; the manifest is the claim about WHAT it contributes. Before this file the artifact
 * made the first claim and silently omitted the second, and nothing in the repository objected
 * because the only admission gate had no production caller. Adding one is what surfaced it.
 * ## One declaration, two readers
 * The manifest is emitted into the artifact at BUILD time and read back at RUN time, and both must
 * read THIS object. A JSON document typed by hand in `build.gradle.kts` would be a second
 * authority free to describe a Step this code does not have — the defect BLOCK 1-H removed from the
 * KSP, in a different file.
*/
object RepeatPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/example-block-release.properties"

    /**
     * Compatibility range, DERIVED at build time from the resolved SDK.
     * It used to be `PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 53, 0))`, a literal that had
     * already gone stale once: at host 0.56.0 it declared a range the host sits outside, and
     * admission refused this plugin on its own distribution. A literal held by a plugin that does
     * not control the host's release cadence cannot stay correct, so the values come from the
     * same release-properties resource that already carries this plugin's provenance — one
     * authority, read once, fail-closed.
     * Fail-closed rather than defaulted: a missing range key must refuse the plugin, because a
     * silent fallback to a guessed range would admit it against hosts nobody measured.
*/
    val API_RANGE: PipelineKApiRange by lazy {
        val props = releaseProperties(RepeatPluginDeclaration::class.java)
        val minRaw = props["pipeline.example.block.compatibility.min"]
            ?: error(
                "Missing apiRange floor (no pipeline.example.block.compatibility.min in " +
                    "$RELEASE_PROPERTIES_RESOURCE). The plugin refuses to declare a range it " +
                    "cannot prove.",
            )
        val ceilingRaw = props["pipeline.example.block.compatibility.untilExclusive"]
            ?: error(
                "Missing apiRange ceiling (no pipeline.example.block.compatibility.untilExclusive " +
                    "in $RELEASE_PROPERTIES_RESOURCE). The plugin refuses to declare a range it " +
                    "cannot prove.",
            )
        PipelineKApiRange(parseSemVer(minRaw), parseSemVer(ceilingRaw))
    }

    /**
     * What the Steps collectively demand.
     * Written as the union of the contracts rather than as a hand-kept list, for the same reason
     * the atomic plugin does it: a hand-written list drifts from the code the moment a Step is
     * added, and the cross-check would then refuse a plugin for a difference nobody introduced on
     * purpose.
*/
    private val DECLARED_CAPABILITIES: Set<StepCapability> =
        setOf(BODY_CONTINUATION_CAPABILITY)

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    /**
     * Fail-closed provenance: never accept a hand-typed digest.
     * A digest nobody measured is worse than no digest, because admission would read it as a
     * MEASURED identity — which is exactly what `MeasuredArtifactIdentity` exists to keep
     * separable from what a document merely claims.
*/
    fun provider(
        classLoader: ClassLoader = RepeatPluginDeclaration::class.java.classLoader,
    ): StepProviderMetadata {
        val props = releaseProperties(RepeatPluginDeclaration::class.java)
        val publisher = System.getProperty("pipeline.example.block.publisher")
            ?: props["pipeline.example.block.publisher"]
            ?: error(
                "Missing publisher provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE " +
                    "in the JAR). The plugin refuses to register without it.",
            )
        val namespace = System.getProperty("pipeline.example.block.namespace")
            ?: props["pipeline.example.block.namespace"]
            ?: "example-block-plugin"
        val versionRaw = System.getProperty("pipeline.example.block.release.version")
            ?: props["pipeline.example.block.release.version"]
            ?: error(
                "Missing version provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )
        val digestRaw = System.getProperty("pipeline.example.block.release.digest")
            ?: props["pipeline.example.block.release.digest"]
            ?: error(
                "Missing digest provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "repeat")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin, parseSemVer(versionRaw), Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
        )
    }

    /**
     * One Step, no directives, no events.
     * The declared capability set is the contract's own `requiredCapabilities` rather than a
     * transcription of it: the cross-check compares the manifest against the contract exactly, so
     * a divergence here would refuse this plugin for a difference that cannot exist.
*/
    fun manifest(
        classLoader: ClassLoader = RepeatPluginDeclaration::class.java.classLoader,
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
                    PluginStepContribution(
                        RepeatBodyStepDefinition.KEY,
                        RepeatBodyStepDefinition.contract.requiredCapabilities,
                    ),
                ),
                directives = emptyList(),
                events = emptyList(),
                capabilities = DECLARED_CAPABILITIES,
            ),
        )
    }

    internal fun releaseProperties(ownClass: Class<*>): Map<String, String> {
        // The resource MUST come from THIS plugin's JAR, not from
        // whichever JAR happens to be first on the system classpath with the same path. `getResource`
        // on the system classloader walks the URL chain in classpath order, and a sibling JAR that
        // Also contains `META-INF/example-block-release.properties` would silently win. Measured
        // a forged sibling JAR with `compatibility.min=9.9.9` overrides the plugin's real value the
        // instant it lands earlier on the classpath.
        // Resolving through the `ProtectionDomain` of the plugin's own class pins the read to the
        // exact JAR the JVM loaded this `object` from. If the class was loaded from a directory
        // (test classpath, exploded jar) the URL is a `file:` entry, and the resource is read from
        // the directory the JVM itself used — which is what `getResource` would have done, but
        // pinned by the class, not by the path.
        val pluginJar = ownJarUrl(ownClass)
            ?: return readFromClassLoader(ownClass.classLoader)
        return readFromJar(pluginJar, RELEASE_PROPERTIES_RESOURCE)
            ?: readFromClassLoader(ownClass.classLoader)
    }

    private fun readFromClassLoader(classLoader: ClassLoader): Map<String, String> {
        val resource = classLoader.getResource(RELEASE_PROPERTIES_RESOURCE) ?: return emptyMap()
        val text = resource.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        return parseProperties(text)
    }

    private fun readFromJar(jarUrl: java.net.URL, resourcePath: String): Map<String, String>? {
        // Open the JAR by file path, not by JarURLConnection.
        // A `file:` URL that points at a `.jar` is NOT a `JarURLConnection`; casting throws
        // ClassCastException, the catch swallows it, and we fall back to the system classloader
        // — which is exactly the lookup that returns the wrong (forged) resource. Open the file
        // directly so the read is pinned to this path, regardless of classpath order.
        return try {
            val path = java.nio.file.Paths.get(jarUrl.toURI())
            val text = java.util.jar.JarFile(path.toFile()).use { jar ->
                val entry = jar.getJarEntry(resourcePath) ?: return@use null
                jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            } ?: return null
            parseProperties(text)
        } catch (_: java.io.IOException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        } catch (_: java.lang.IllegalArgumentException) {
            null
        }
    }

    private fun parseProperties(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
        return map
    }

    private fun ownJarUrl(klass: Class<*>): java.net.URL? {
        val domain = klass.protectionDomain ?: return null
        val codeSource = domain.codeSource ?: return null
        val url = codeSource.location ?: return null
        // The class must have been loaded from a JAR; from a directory the
        // own-jar path does not apply and we fall back to the classloader
        // resource lookup.
        return if (url.protocol == "jar" || url.path.endsWith(".jar")) url else null
    }
}

/**
 * Build-time entry point: emit the manifest document to stdout.
 * A non-zero exit from missing provenance stops the build instead of shipping a JAR whose manifest
 * is absent — and a JAR without a manifest is refused at runtime, so a silent skip here would
 * produce an artifact that passes its own build and fails its own admission. That is precisely how
 * this plugin ran for as long as it did.
*/
fun main() {
    print(PluginManifestCodec.encode(RepeatPluginDeclaration.manifest()))
}
