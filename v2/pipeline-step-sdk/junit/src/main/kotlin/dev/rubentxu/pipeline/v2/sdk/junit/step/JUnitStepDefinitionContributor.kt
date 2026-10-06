package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
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
        val provider = JUnitPluginDeclaration.provider()
        val manifest = JUnitPluginDeclaration.manifest()
        PluginManifestValidator.validate(manifest, listOf(resultsStep))
        return listOf(StepRegistration(resultsStep, provider))
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
