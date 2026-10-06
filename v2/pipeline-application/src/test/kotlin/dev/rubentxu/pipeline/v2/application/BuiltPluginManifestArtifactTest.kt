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

    private fun jarFor(artifact: PluginArtifact): Path {
        val libsDir = Paths.get("..", "pipeline-step-sdk", artifact.module, "build", "libs")
        val candidates = if (Files.isDirectory(libsDir)) {
            Files.list(libsDir).use { stream ->
                stream.filter {
                    it.fileName.toString().startsWith(artifact.jarPrefix + "-") &&
                        it.toString().endsWith(".jar") &&
                        !it.fileName.toString().endsWith("-sources.jar")
                }.toList()
            }
        } else {
            emptyList()
        }
        assertTrue(
            candidates.isNotEmpty(),
            "no built ${artifact.module} JAR under $libsDir. The build must produce the artifact " +
                "before this proof can mean anything; skipping here would be a green that proves nothing.",
        )
        return candidates.first()
    }

    /**
     * A loader whose parent is the PLATFORM loader, not the test classloader.
     *
     * This matters and was found by a RED rather than by reading. The four plugin classes are
     * on the test classpath, so with the test loader as parent the contribution class RESOLVES
     * FROM THERE and its code source is the test output directory — while the manifest resource
     * resolves from the JAR. `strict` then correctly refused, because from its point of view a
     * plugin had declared itself in one artifact and contributed code from another. The check
     * was right and the harness was wrong.
     *
     * Neither extreme works on its own. The platform loader cannot see pipeline-domain, so
     * resolving the contributor fails with NoClassDefFoundError on StepDefinitionContributor.
     * This parent delegates to the test classloader EXCEPT for the four plugin packages, which
     * is exactly the shape a real runtime has: PipelineK's own classes are visible, and a
     * plugin resolves to the artifact under test rather than to a copy on the test classpath.
     */
    private fun withLoader(artifact: PluginArtifact, block: (URLClassLoader) -> Unit) {
        val loader = URLClassLoader(arrayOf(jarFor(artifact).toUri().toURL()), PluginApiParent())
        try {
            block(loader)
        } finally {
            loader.close()
        }
    }

    @ParameterizedTest(name = "{0} ships its manifest at the canonical path")
    @MethodSource("artifacts")
    @DisplayName("the shipped JAR carries the manifest at the canonical path")
    internal fun jarCarriesTheManifestAtTheCanonicalPath(artifact: PluginArtifact) {
        val entry = java.util.zip.ZipFile(jarFor(artifact).toFile()).use { zip ->
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

    /**
     * Delegating parent that hides the four official plugin packages.
     *
     * Without this the plugin classes resolve from the test output directory and `strict`
     * refuses, because the manifest then genuinely comes from a different artifact than the
     * contribution code — which is the substitution the check exists to catch, and which here
     * would be an artefact of the harness rather than a real defect.
     */
    private class PluginApiParent(
        private val delegate: ClassLoader = BuiltPluginManifestArtifactTest::class.java.classLoader,
    ) : ClassLoader(null) {

        private val hiddenPackages = listOf(
            "dev.rubentxu.pipeline.v2.sdk.http",
            "dev.rubentxu.pipeline.v2.sdk.scm",
            "dev.rubentxu.pipeline.v2.sdk.junit",
            "dev.rubentxu.pipeline.v2.sdk.utilities",
        )

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (hiddenPackages.any { name.startsWith(it) }) {
                throw ClassNotFoundException(name)
            }
            return delegate.loadClass(name)
        }

        /**
         * Hides the canonical manifest resource as well as the plugin packages.
         *
         * Without this the parent still publishes `META-INF/pipelinek/plugin-manifest.json` —
         * and since four plugins put a copy of that SAME path on the test classpath, whichever
         * the parent answers first wins. The failure named scm-git's JAR while loading http's
         * class, which is the kind of cross-plugin substitution `strict` exists to refuse, so
         * the check was right again and the harness was wrong for the second time.
         */
        override fun getResource(name: String): java.net.URL? =
            if (name == dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH ||
                hiddenPackages.any { name.startsWith("dev/rubentxu/pipeline/v2/sdk") }
            ) {
                null
            } else {
                delegate.getResource(name)
            }
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