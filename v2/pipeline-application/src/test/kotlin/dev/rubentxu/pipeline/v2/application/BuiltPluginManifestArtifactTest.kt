package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * S6/C — the REAL utilities artifact is admitted, and only because it carries a real manifest.
 *
 * ## Why this is not the same proof as the ordering harness
 *
 * [PluginAdmissionPreLoadOrderingTest] uses a fixture JAR built inside the test, which proves
 * the gate's ORDERING. This one loads the artifact the build actually produces and proves the
 * wiring: that `emitUtilitiesManifest` ran, that the document landed at
 * [PluginManifestCodec.RESOURCE_PATH] inside the JAR, and that the runtime can admit the plugin
 * from it. A gate that is correct over a fixture and unreachable over the product is a gate
 * nobody is protected by.
 *
 * ## Why a missing JAR FAILS instead of skipping
 *
 * A test that skips when the artifact is absent reports green while proving nothing, which is
 * the exact failure mode this repository keeps paying for. The build wires the dependency, so
 * absence means the wiring broke and that is a defect worth a RED.
 *
 * ## Scope
 *
 * HF2-ish: a real artifact, loaded through a real classloader. It does not fork an installed
 * distribution; that is BLOCK 2's proof and is not claimed here.
 */
@DisplayName("S6/C the built utilities artifact is admitted from its manifest document")
class BuiltPluginManifestArtifactTest {

    private fun utilitiesJar(): Path {
        val libsDir = Paths.get("..", "pipeline-step-sdk", "utilities", "build", "libs")
        val jars = if (Files.isDirectory(libsDir)) {
            Files.list(libsDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith("utilities-") && it.toString().endsWith(".jar") }
                    .toList()
            }
        } else {
            emptyList()
        }
        // A source jar would also match the name pattern; exclude it explicitly rather than
        // admitting whichever one the directory happens to list first.
        val binary = jars.filterNot { it.fileName.toString().endsWith("-sources.jar") }
        assertTrue(
            binary.isNotEmpty(),
            "no built utilities JAR under $libsDir. The build must produce the artifact before this " +
                "proof can mean anything; a skip here would be a green that proves nothing.",
        )
        return binary.first()
    }

    @Test
    @DisplayName("the shipped JAR carries the manifest at the canonical path")
    fun jarCarriesTheManifestAtTheCanonicalPath() {
        val jar = utilitiesJar()
        val entry = java.util.zip.ZipFile(jar.toFile()).use { zip ->
            zip.getEntry(PluginManifestCodec.RESOURCE_PATH)
        }
        assertTrue(
            entry != null,
            "$jar does not contain ${PluginManifestCodec.RESOURCE_PATH}; the artifact declares nothing, " +
                "so admission will refuse it and the plugin can never run",
        )
    }

    @Test
    @DisplayName("the gate ADMITS the real utilities artifact at the running version")
    fun gateAdmitsTheRealArtifact() {
        val jar = utilitiesJar()
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), PluginManifestResourceReader::class.java.classLoader)
        try {
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = "dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor",
                classLoader = loader,
                runtimeVersion = SemVer(0, 47, 0),
                alreadyAdmitted = emptySet(),
                // strict stays ON: the manifest and the contributor class must come from the
                // same artifact, which is the substitution this check exists to refuse.
                strict = true,
            )

            assertTrue(
                result is PluginAdmissionResult.Admitted,
                "the real utilities artifact must be admitted; got $result",
            )
            val admitted = (result as PluginAdmissionResult.Admitted).plugin
            assertTrue(
                admitted.manifest.contributions.steps.isNotEmpty(),
                "admitted with no declared Steps: the manifest decoded but says nothing",
            )
        } finally {
            loader.close()
        }
    }

    @Test
    @DisplayName("the same artifact is REFUSED when the runtime version falls outside its apiRange")
    fun realArtifactIsRefusedOutsideItsApiRange() {
        val jar = utilitiesJar()
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), PluginManifestResourceReader::class.java.classLoader)
        try {
            // utilities declares [0.47.0, 0.49.0), so 0.60.0 must be outside it.
            val result = PluginAdmissionGate.admitThenLoad(
                contributorClassName = "dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor",
                classLoader = loader,
                runtimeVersion = SemVer(0, 60, 0),
                alreadyAdmitted = emptySet(),
                strict = true,
            )

            assertTrue(
                result is PluginAdmissionResult.Refused,
                "the real artifact declares [0.47.0, 0.49.0), so a 0.60.0 runtime must be refused; got $result",
            )
        } finally {
            loader.close()
        }
    }
}