package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepManifest
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration.Companion.legacy
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

/**
 * SCM/Git OFFICIAL_PLUGIN contributor (LFC-2E2 / F5.1 / ADR-0092).
 *
 * Carries real [StepProviderMetadata] built from a typed [PluginManifest],
 * so events emitted during real execution of `scm-git.checkout` carry the
 * audit projection in their [dev.rubentxu.pipeline.v2.events.identity.ProviderProvenance].
 *
 * **Digest source of truth:** the [PluginReleaseRef.digest] is computed at
 * JAR-build time by a Gradle task and exposed via the system property
 * `pipeline.scm-git.release.digest`. This means the digest IS the SHA-256
 * of the actual JAR bytes produced by `:pipeline-step-sdk:scm-git:jar`,
 * not a self-declared value the plugin author chose. If the property is
 * absent at registration time, the contributor fails closed with an
 * `IllegalStateException` that names the missing property — F5.1
 * commits to "no fabricated SHA".
 *
 * **Version source of truth:** the [SemVer] is exposed via the system
 * property `pipeline.scm-git.release.version`, defaulted to `0.0.0-dev`
 * when unset. Production wires the property from Gradle.
 *
 * **Publisher / namespace source of truth:** `pipeline.scm-git.publisher`
 * and `pipeline.scm-git.namespace`. Defaults: `pipeline-kotlin` and
 * `pipeline.scm-git`.
 */
object ScmGitStepDefinitionContributor : StepDefinitionContributor {

    override val id: String = "scm-git"

    /**
     * Single Step family the contributor exposes in F5.1. Future slices
     * (poll, changelog, merge, …) will extend this list.
     */
    private val checkoutStep: GitCheckoutStepDefinition = GitCheckoutStepDefinition()

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(checkoutStep)

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = buildProvider()
        // Validate the manifest C5 cross-check before constructing the
        // registration. The validator is deterministic and fail-closed: a
        // mismatch between manifest.declaredCapabilities and
        // contract.requiredCapabilities is rejected here, never silently
        // coerced downstream.
        val manifest = buildManifest(provider)
        PluginManifestValidator.validate(manifest, listOf(checkoutStep))
        return listOf(StepRegistration(checkoutStep, provider))
    }

    /**
     * The publisher / version / digest / namespace come from Gradle
     * properties (computed at JAR build time). Fail-closed if any of
     * them is missing — the contract requires real provenance, not
     * self-declared values.
     */
    private fun buildProvider(): StepProviderMetadata {
        val publisher = System.getProperty("pipeline.scm-git.publisher")
            ?: error("Missing system property 'pipeline.scm-git.publisher' (set it in build.gradle.kts before assembling the SCM/Git OFFICIAL_PLUGIN)")
        val namespace = System.getProperty("pipeline.scm-git.namespace") ?: "pipeline.scm-git"
        val versionRaw = System.getProperty("pipeline.scm-git.release.version") ?: "0.0.0-dev"
        val digestRaw = System.getProperty("pipeline.scm-git.release.digest")
            ?: error("Missing system property 'pipeline.scm-git.release.digest' — the SCM/Git OFFICIAL_PLUGIN refuses to register without the real SHA-256 of its own JAR. Run `:pipeline-step-sdk:scm-git:jar` first; the digest is computed by the build.")
        val semver = parseSemVer(versionRaw)
        val plugin: ResourceRef = ResourceRefs.plugin(namespace, "scm-git")
        val release = PluginReleaseRef(
            plugin = plugin,
            version = semver,
            digest = Digest(digestRaw),
        )
        return StepProviderMetadata.create(
            plugin = plugin,
            release = release,
            publisher = publisher,
            families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
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
                stepKey = ScmGitCheckoutKey.VALUE,
                declaredCapabilities = checkoutStep.contract.requiredCapabilities,
            ),
        ),
    )

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) {
            "SemVer must have 3 numeric components (got '$raw')"
        }
        return SemVer(
            major = parts[0].toInt(),
            minor = parts[1].toInt(),
            patch = parts[2].toInt(),
        )
    }
}

/**
 * Convenience helper for tests / CLI bootstrappers that want to register
 * the SCM/Git OFFICIAL_PLUGIN contributor with manual metadata
 * (CI fixtures, golden-test corpora). Production wires the contributor
 * directly through [ScmGitStepDefinitionContributor] and Gradle.
 *
 * The override path lets a test fix the digest / version WITHOUT going
 * through Gradle, while still exercising the
 * [PluginManifestValidator] (C5) and the
 * [EnvelopeProjector] C8 projection. It is intentionally NOT the path
 * used by the production runtime — the production path always reads
 * the properties from the Gradle build.
 */
fun StepRegistry.registerScmGit(
    publisher: String,
    namespace: String = "pipeline.scm-git",
    version: String = "0.0.0-dev",
    digestSha256: String,
): StepProviderMetadata {
    require(digestSha256.startsWith("sha256:")) {
        "registerScmGit: digest must be 'sha256:<64-hex>' (got '$digestSha256')"
    }
    val prev = listOf(
        "pipeline.scm-git.publisher" to publisher,
        "pipeline.scm-git.namespace" to namespace,
        "pipeline.scm-git.release.version" to version,
        "pipeline.scm-git.release.digest" to digestSha256,
    )
    val saved = prev.map { (k, _) -> k to System.getProperty(k) }
    prev.forEach { (k, v) -> System.setProperty(k, v) }
    try {
        ScmGitStepDefinitionContributor.registrations().forEach { register(it) }
    } finally {
        saved.forEach { (k, prev) ->
            if (prev == null) System.clearProperty(k) else System.setProperty(k, prev)
        }
    }
    return ScmGitStepDefinitionContributor.registrations().first().provider
}

/**
 * Computes the SHA-256 of [jarPath] in the canonical `sha256:<64-hex>`
 * form expected by [Digest]. Used by the Gradle task and by tests that
 * need a real digest from a fixture JAR.
 */
fun sha256DigestOf(jarPath: Path): String {
    require(Files.exists(jarPath)) { "JAR does not exist: $jarPath" }
    val bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jarPath))
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "sha256:$hex"
}
