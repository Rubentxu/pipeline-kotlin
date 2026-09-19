package dev.rubentxu.pipeline.v2.sdk.junit.step

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
import java.nio.file.Path

/**
 * JUnit OFFICIAL_PLUGIN contributor (F5.2 / LFC-2E2).
 *
 * Same shape as [ScmGitStepDefinitionContributor]:
 *
 *  - registered via [java.util.ServiceLoader] in
 *    META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor.
 *  - system properties win over the META-INF/junit-release.properties
 *    fallback (so tests can pin metadata without rebuilding the JAR).
 *  - fail-closed if neither source yields publisher / digest.
 *  - validator (C5) cross-checks the manifest capabilities against
 *    the contract's requiredCapabilities.
 */
class JUnitStepDefinitionContributor : StepDefinitionContributor {

    override val id: String = "junit"

    private val resultsStep: JUnitResultsStepDefinition = JUnitResultsStepDefinition()

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(resultsStep)

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = buildProvider()
        val manifest = buildManifest(provider)
        PluginManifestValidator.validate(manifest, listOf(resultsStep))
        return listOf(StepRegistration(resultsStep, provider))
    }

    private fun buildProvider(): StepProviderMetadata {
        val releaseProps = loadReleaseProperties()
        val publisher = System.getProperty("pipeline.junit.publisher")
            ?: releaseProps["pipeline.junit.publisher"]
            ?: error(
                "Missing publisher provenance (no system property 'pipeline.junit.publisher' and no " +
                    "META-INF/junit-release.properties in the JAR). The JUnit OFFICIAL_PLUGIN refuses to register without it.",
            )
        val namespace = System.getProperty("pipeline.junit.namespace")
            ?: releaseProps["pipeline.junit.namespace"]
            ?: "pipeline.junit"
        val versionRaw = System.getProperty("pipeline.junit.release.version")
            ?: releaseProps["pipeline.junit.release.version"]
            ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.junit.release.digest")
            ?: releaseProps["pipeline.junit.release.digest"]
            ?: error(
                "Missing digest provenance (no system property 'pipeline.junit.release.digest' and no " +
                    "META-INF/junit-release.properties in the JAR). The JUnit OFFICIAL_PLUGIN refuses to register " +
                    "without the real SHA-256 of its own artefact.",
            )
        require(digestRaw.startsWith("sha256:")) {
            "junit release.digest must be 'sha256:<64-hex>' (got '$digestRaw')"
        }
        val semver = parseSemVer(versionRaw)
        val plugin = ResourceRefs.plugin(namespace, "junit")
        val release = PluginReleaseRef(
            plugin = plugin,
            version = semver,
            digest = Digest(digestRaw),
        )
        return StepProviderMetadata.create(
            plugin = plugin,
            release = release,
            publisher = publisher,
            families = setOf(PluginFamily.TESTING, PluginFamily.REPORTING),
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
                stepKey = JUnitResultsKey.VALUE,
                declaredCapabilities = resultsStep.contract.requiredCapabilities,
            ),
        ),
    )

    private fun loadReleaseProperties(): Map<String, String> {
        val resource = javaClass.classLoader.getResource("META-INF/junit-release.properties") ?: return emptyMap()
        val text = resource.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val map = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val k = trimmed.substring(0, idx).trim()
                val v = trimmed.substring(idx + 1).trim()
                map[k] = v
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

    companion object {
        @JvmStatic
        fun instance(): JUnitStepDefinitionContributor = JUnitStepDefinitionContributor()
    }
}

/**
 * Convenience helper that registers the JUnit OFFICIAL_PLUGIN into
 * a caller-supplied [dev.rubentxu.pipeline.v2.domain.step.StepRegistry]
 * with explicit metadata (CI fixtures, golden tests). Production wires
 * the contributor directly through [JUnitStepDefinitionContributor]
 * and Gradle.
 */
fun dev.rubentxu.pipeline.v2.domain.step.StepRegistry.registerJUnit(
    publisher: String,
    namespace: String = "pipeline.junit",
    version: String = "0.0.0-dev",
    digestSha256: String,
): StepProviderMetadata {
    require(digestSha256.startsWith("sha256:")) {
        "registerJUnit: digest must be 'sha256:<64-hex>' (got '$digestSha256')"
    }
    val props = listOf(
        "pipeline.junit.publisher" to publisher,
        "pipeline.junit.namespace" to namespace,
        "pipeline.junit.release.version" to version,
        "pipeline.junit.release.digest" to digestSha256,
    )
    val saved = props.map { (k, _) -> k to System.getProperty(k) }
    props.forEach { (k, v) -> System.setProperty(k, v) }
    try {
        JUnitStepDefinitionContributor().registrations().forEach { register(it) }
    } finally {
        saved.forEach { (k, prev) ->
            if (prev == null) System.clearProperty(k) else System.setProperty(k, prev)
        }
    }
    return JUnitStepDefinitionContributor().registrations().first().provider
}

@Suppress("unused")
internal fun junitProviderFamilyRef(namespace: String): dev.rubentxu.pipeline.v2.domain.identity.ResourceRef =
    ResourceRefs.pluginFamily(namespace, "junit")

@Suppress("unused")
private val unused = Path.of(".") // keep import alive
