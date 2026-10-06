package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepManifest
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
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
    private val readYamlStep: CoreUtilsReadYamlStepDefinition = CoreUtilsReadYamlStepDefinition()
    private val writeYamlStep: CoreUtilsWriteYamlStepDefinition = CoreUtilsWriteYamlStepDefinition()
    private val findFilesStep: CoreUtilsFindFilesStepDefinition = CoreUtilsFindFilesStepDefinition()
    private val zipStep: CoreUtilsZipStepDefinition = CoreUtilsZipStepDefinition()
    private val unzipStep: CoreUtilsUnzipStepDefinition = CoreUtilsUnzipStepDefinition()

    override fun definitions(): Iterable<StepDefinition<*, *>> =
        listOf(readJsonStep, writeJsonStep, sha256Step, readYamlStep, writeYamlStep, findFilesStep, zipStep, unzipStep)

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = UtilitiesPluginDeclaration.provider()
        val manifest = UtilitiesPluginDeclaration.manifest()
        PluginManifestValidator.validate(
            manifest,
            listOf(readJsonStep, writeJsonStep, sha256Step, readYamlStep, writeYamlStep, findFilesStep, zipStep, unzipStep),
        )
        return listOf(
            StepRegistration(readJsonStep, provider),
            StepRegistration(writeJsonStep, provider),
            StepRegistration(sha256Step, provider),
            StepRegistration(readYamlStep, provider),
            StepRegistration(writeYamlStep, provider),
            StepRegistration(findFilesStep, provider),
            StepRegistration(zipStep, provider),
            StepRegistration(unzipStep, provider),
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
