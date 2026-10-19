package example.saga

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
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
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_COMPENSATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_PLAN_CAPABILITY

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
object SagaPluginDeclaration {

    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/example-saga-release.properties"

    /**
     * Compatibility range, DERIVED at build time from the resolved SDK.
     *
     * It used to be `PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 57, 0))`, a literal that had
     * already gone stale once: at host 0.56.0 it declared a range the host sits outside, and
     * admission refused this plugin on its own distribution. A literal held by a plugin that does
     * not control the host's release cadence cannot stay correct, so the values come from the
     * same release-properties resource that already carries this plugin's provenance — one
     * authority, read once, fail-closed.
     *
     * Fail-closed rather than defaulted: a missing range key must refuse the plugin, because a
     * silent fallback to a guessed range would admit it against hosts nobody measured.
     */
    val API_RANGE: PipelineKApiRange by lazy {
        val props = releaseProperties(SagaPluginDeclaration::class.java)
        val minRaw = props["pipeline.example.saga.compatibility.min"]
            ?: error(
                "Missing apiRange floor (no pipeline.example.saga.compatibility.min in " +
                    "$RELEASE_PROPERTIES_RESOURCE). The plugin refuses to declare a range it " +
                    "cannot prove.",
            )
        val ceilingRaw = props["pipeline.example.saga.compatibility.untilExclusive"]
            ?: error(
                "Missing apiRange ceiling (no pipeline.example.saga.compatibility.untilExclusive " +
                    "in $RELEASE_PROPERTIES_RESOURCE). The plugin refuses to declare a range it " +
                    "cannot prove.",
            )
        PipelineKApiRange(parseSemVer(minRaw), parseSemVer(ceilingRaw))
    }

    /**
     * What the Steps collectively demand, and it is written as the union of the contracts
     * rather than as a hand-kept list.
     *
     * This plugin declares exactly TWO capabilities, the Saga intent port and the Saga plan
     * port, and they are seams the plugin does NOT supply and is not expected to: a manifest's
     * capability set states what the plugin NEEDS, not what it hands over. The host provides
     * the ports because only the host knows the run, the durable store and the lease.
     */
    private val DECLARED_CAPABILITIES: Set<StepCapability> =
        setOf(SAGA_COMPENSATION_CAPABILITY, SAGA_PLAN_CAPABILITY)

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
        classLoader: ClassLoader = SagaPluginDeclaration::class.java.classLoader,
    ): StepProviderMetadata {
        val props = releaseProperties(SagaPluginDeclaration::class.java)
        val publisher = System.getProperty("pipeline.example.saga.publisher")
            ?: props["pipeline.example.saga.publisher"]
            ?: error(
                "Missing publisher provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE " +
                    "in the JAR). The plugin refuses to register without it.",
            )
        val namespace = System.getProperty("pipeline.example.saga.namespace")
            ?: props["pipeline.example.saga.namespace"]
            ?: "example-saga-plugin"
        val versionRaw = System.getProperty("pipeline.example.saga.release.version")
            ?: props["pipeline.example.saga.release.version"]
            ?: error(
                "Missing version provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )
        val digestRaw = System.getProperty("pipeline.example.saga.release.digest")
            ?: props["pipeline.example.saga.release.digest"]
            ?: error(
                "Missing digest provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )

        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "saga")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin, parseSemVer(versionRaw), Digest(digestRaw)),
            publisher = publisher,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
        )
    }

    /** One family, one Step: the external consumer of the Saga contract. */
    fun manifest(
        classLoader: ClassLoader = SagaPluginDeclaration::class.java.classLoader,
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
                        SagaObligationStepDefinition.KEY,
                        setOf(SAGA_COMPENSATION_CAPABILITY, SAGA_PLAN_CAPABILITY),
                    ),
                ),
                directives = emptyList(),
                events = emptyList(),
                capabilities = DECLARED_CAPABILITIES,
            ),
        )
    }

    internal fun releaseProperties(ownClass: Class<*>): Map<String, String> {
        // PK-RELEASE-CONSOLIDATION D1.9: the resource MUST come from THIS plugin's JAR, not from
        // whichever JAR happens to be first on the system classpath with the same path. `getResource`
        // on the system classloader walks the URL chain in classpath order, and a sibling JAR that
        // also contains `META-INF/example-saga-release.properties` would silently win.
        // Measured: a forged sibling JAR with `compatibility.min=9.9.9` overrides the plugin's real
        // value the instant it lands earlier on the classpath.
        //
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
        // PK-RELEASE-CONSOLIDATION D1.9: open the JAR by file path, not by JarURLConnection.
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
 *
 * A non-zero exit from missing provenance stops the build instead of shipping a JAR whose manifest
 * is absent — and a JAR without a manifest is refused at runtime, so a silent skip here would
 * produce an artifact that passes its own build and fails its own admission.
 */
fun main() {
    print(PluginManifestCodec.encode(SagaPluginDeclaration.manifest()))
}
