package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream

/**
 * S6/C — every official plugin artifact carries a manifest, and the gate admits it.
 *
 * ## Why this is over the four, not over one
 *
 * `utilities` was wired first to prove the mechanism end to end. A mechanism proved on one
 * artifact proves nothing about the other three: the whole failure mode being guarded against
 * is a plugin shipping without a declaration, and that is a per-plugin property. A shared
 * helper called once per plugin keeps each one covered without four near-identical classes.
 *
 * ## Why a missing JAR FAILS instead of skipping
 *
 * A test that skips when the artifact is absent reports green while proving nothing — the
 * exact failure mode this repository keeps paying for. The build wires the dependency, so
 * absence means the wiring broke and that deserves a RED.
 *
 * ## Non-vacuity
 *
 * The two extremes are mutually falsifiable. Admitting proves the document is read; refusing
 * outside the declared range proves it is read CORRECTLY, because all four declare
 * `[0.47.0, 0.49.0)` and an admission that ignored the manifest would accept any version.
 */
@DisplayName("S6/C every official plugin artifact is admitted from its manifest document")
class BuiltPluginManifestArtifactTest {

    internal data class PluginArtifact(
        val module: String,
        val jarPrefix: String,
        val contributorClass: String,
        val expectedSteps: Int,
    )

    private fun pluginArtifacts(): List<PluginArtifact> = listOf(
        PluginArtifact("http", "http", "dev.rubentxu.pipeline.v2.sdk.http.HttpStepDefinitionContributor", 1),
        PluginArtifact("scm-git", "scm-git", "dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor", 1),
        PluginArtifact("junit", "junit", "dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor", 1),
        PluginArtifact("utilities", "utilities", "dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor", 8),
    )

    private fun withLoader(artifact: PluginArtifact, block: (java.net.URLClassLoader) -> Unit) =
        PluginArtifactFixture.withScopedLoader(PluginArtifactFixture.builtJar(artifact.module), block)

    @ParameterizedTest(name = "{0} ships its manifest at the canonical path")
    @MethodSource("artifacts")
    @DisplayName("the shipped JAR carries the manifest at the canonical path")
    internal fun jarCarriesTheManifestAtTheCanonicalPath(artifact: PluginArtifact) {
        val entry = java.util.zip.ZipFile(PluginArtifactFixture.builtJar(artifact.module).toFile()).use { zip ->
            zip.getEntry(PluginManifestCodec.RESOURCE_PATH)
        }
        assertTrue(
            entry != null,
            "${artifact.module} does not contain ${PluginManifestCodec.RESOURCE_PATH}; the artifact " +
                "declares nothing, so admission refuses it and the plugin can never run",
        )
    }

    @ParameterizedTest(name = "{0} is admitted at the running version")
    @MethodSource("artifacts")
    @DisplayName("the gate ADMITS the real artifact at 0.47.0")
    internal fun gateAdmitsTheRealArtifact(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = SemVer(0, 47, 0),
            alreadyAdmitted = emptySet(),
            // strict stays ON: the manifest and the contributor class must come from the same
            // artifact, which is the substitution this check exists to refuse.
            strict = true,
        )

        assertTrue(result is PluginAdmissionResult.Admitted, "${artifact.module} must be admitted; got $result")
        val admitted = (result as PluginAdmissionResult.Admitted).plugin
        assertEquals(
            artifact.expectedSteps,
            admitted.manifest.contributions.steps.size,
            "${artifact.module} admitted with a Step count its declaration does not describe",
        )
    }

    @ParameterizedTest(name = "{0} is refused outside its declared apiRange")
    @MethodSource("artifacts")
    @DisplayName("the same artifact is REFUSED at 0.60.0, outside [0.47.0, 0.49.0)")
    internal fun realArtifactIsRefusedOutsideItsApiRange(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = SemVer(0, 60, 0),
            alreadyAdmitted = emptySet(),
            strict = true,
        )

        assertTrue(
            result is PluginAdmissionResult.Refused,
            "${artifact.module} declares [0.47.0, 0.49.0), so a 0.60.0 runtime must be refused; got $result",
        )
    }

    companion object {
        @JvmStatic
        fun artifacts(): Stream<Arguments> = Stream.of(
            Arguments.of(
                PluginArtifact(
                    "http", "http",
                    "dev.rubentxu.pipeline.v2.sdk.http.HttpStepDefinitionContributor", 1,
                ),
            ),
            Arguments.of(
                PluginArtifact(
                    "scm-git", "scm-git",
                    "dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor", 1,
                ),
            ),
            Arguments.of(
                PluginArtifact(
                    "junit", "junit",
                    "dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor", 1,
                ),
            ),
            Arguments.of(
                PluginArtifact(
                    "utilities", "utilities",
                    "dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor", 8,
                ),
            ),
        )
    }
}
