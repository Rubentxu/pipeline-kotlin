package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * HTTP OFFICIAL_PLUGIN contributor (LFC-2E3 / WU-093; ADR-0092 pattern).
 *
 * Discovered through `ServiceLoader`, exactly like `scm-git`, `junit` and
 * `utilities`. A `.pipeline.kts` that writes `httpRequest(...)` works because
 * this contributor is on the classpath — not because the compiler was taught
 * about the key. That is the delivery classification doing real work.
 *
 * **Digest source of truth.** The [PluginReleaseRef.digest] is computed at
 * JAR-build time by the `computeHttpDigest` Gradle task and exposed as
 * `META-INF/http-release.properties` inside the artefact, overridable by the
 * `pipeline.http.release.digest` system property so tests can pin metadata
 * without rebuilding. If neither source yields a digest, registration fails
 * closed with a diagnostic that names the missing property: F5.1 commits to
 * "no fabricated SHA", and an unprovenanced plugin must not enter a run.
 */
class HttpStepDefinitionContributor : StepDefinitionContributor {

    override val id: String = "http"

    private val requestDefinition: StepDefinition<*, *> = HttpRequestStep.definition

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(requestDefinition)

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = HttpPluginDeclaration.provider()
        // C5 cross-check before constructing the registration. Deterministic and
        // fail-closed: a manifest that disagrees with contract.requiredCapabilities
        // is rejected here, never coerced downstream.
        val manifest = HttpPluginDeclaration.manifest()
        PluginManifestValidator.validate(manifest, listOf(requestDefinition))
        @Suppress("UNCHECKED_CAST")
        val typed = requestDefinition as StepDefinition<Any, Any>
        return listOf(StepRegistration(typed, provider))
    }

}


/**
 * Registers the HTTP OFFICIAL_PLUGIN with pinned provenance.
 *
 * Mirrors `registerScmGit`: the override path exists for CI fixtures and golden
 * corpora that need a fixed digest WITHOUT going through Gradle, while still
 * exercising [PluginManifestValidator]. It is NOT the production path — that one
 * always reads the properties the build wrote.
 */
fun StepRegistryBuilder.registerHttp(
    publisher: String,
    namespace: String = "pipeline-plugin-http",
    version: String = "0.0.0-dev",
    digestSha256: String,
): StepProviderMetadata {
    require(digestSha256.startsWith("sha256:")) {
        "registerHttp: digest must be 'sha256:<64-hex>' (got '$digestSha256')"
    }
    val prev = listOf(
        "pipeline.http.publisher" to publisher,
        "pipeline.http.namespace" to namespace,
        "pipeline.http.release.version" to version,
        "pipeline.http.release.digest" to digestSha256,
    )
    val saved = prev.map { (k, _) -> k to System.getProperty(k) }
    prev.forEach { (k, v) -> System.setProperty(k, v) }
    try {
        HttpStepDefinitionContributor().registrations().forEach { add(it) }
    } finally {
        saved.forEach { (k, before) ->
            if (before == null) System.clearProperty(k) else System.setProperty(k, before)
        }
    }
    return HttpStepDefinitionContributor().registrations().first().provider
}
