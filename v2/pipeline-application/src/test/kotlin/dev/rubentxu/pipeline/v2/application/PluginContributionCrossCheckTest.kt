package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.stream.Stream

/**
 * S6/E — the cross-check between what a plugin DECLARED and what it CONTRIBUTES.
 *
 * ## Why this class exists separately from the admission proof
 *
 * `BuiltPluginManifestArtifactTest` proves the four official plugins are admitted. All four are
 * consistent, so that proof would pass unchanged if the cross-check were deleted. A check that
 * only ever sees agreement is characterisation wearing a badge; this class is what makes it a
 * gate.
 *
 * ## The mutants are real artifacts
 *
 * Each mutant is a real JAR — the built plugin with its manifest rewritten — and the class
 * stays inside it. So `strict` passes and the refusal comes from the cross-check ALONE, which
 * is the only way to attribute the refusal to the right cause.
 *
 * - **declared-but-absent** — the document names a Step the contributor does not provide. This
 *   is the mutant a plugin author produces by adding a Step and forgetting the declaration.
 * - **implemented-but-undeclared** — the contributor provides a Step the document does not
 *   name. This is the mirror mutant, and the one that would let a plugin ship behaviour
 *   invisible to any reader that consults only the manifest.
 *
 * ## The control
 *
 * [officialPluginIsConsistent] runs against all four unmutated plugins. Without it both mutants
 * could pass for the wrong reason: a cross-check that refused everything would refuse them too,
 * and the gate would look like it works while admitting nothing.
 */
@DisplayName("S6/E the cross-check refuses a plugin that does not match its declaration")
class PluginContributionCrossCheckTest {

    private fun admit(jar: Path, contributorClass: String): PluginAdmissionResult =
        PluginArtifactFixture.withScopedLoader(jar) { loader ->
            PluginAdmissionGate.admitThenLoad(
                contributorClassName = contributorClass,
                classLoader = loader,
                runtimeVersion = SemVer(0, 47, 0),
                alreadyAdmitted = emptySet(),
                // The manifest and the class come from the SAME rewritten artifact, so the
                // same-source check passes on its own terms and any refusal below is the
                // cross-check's, not an artefact of how the mutant JAR was assembled.
                strict = true,
            )
        }

    private fun contributorFor(module: String): String = when (module) {
        "http" -> "dev.rubentxu.pipeline.v2.sdk.http.HttpStepDefinitionContributor"
        "scm-git" -> "dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitStepDefinitionContributor"
        "junit" -> "dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitStepDefinitionContributor"
        else -> "dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsStepDefinitionContributor"
    }

    @Test
    @DisplayName("a manifest declaring a Step the plugin does NOT provide is refused")
    fun declaredButAbsentStepIsRefused() {
        // junit declares exactly one Step. Naming a second one is what an author produces by
        // adding a Step to the code and forgetting the document.
        val jar = PluginArtifactFixture.jarWithRewrittenManifest("junit") { text ->
            text.replace(
                "\"declaredCapabilities\": [\"runtime.execution-location\"]}],",
                "\"declaredCapabilities\": [\"runtime.execution-location\"]}, " +
                    "{\"stepKey\": \"junit.ghost\", \"declaredCapabilities\": []}],",
            )
        }

        val invalid = (admit(jar, contributorFor("junit")) as PluginAdmissionResult.Refused).rejection
            as PluginManifestRejection.InvalidManifest

        assertTrue(
            invalid.detail.contains("junit.ghost"),
            "the refusal must NAME the Step that has no implementation; got '${invalid.detail}'",
        )
        assertTrue(
            invalid.detail.contains("no implementation"),
            "the refusal must state the direction of the mismatch; got '${invalid.detail}'",
        )
    }

    @Test
    @DisplayName("a plugin contributing a Step the manifest does NOT declare is refused")
    fun implementedButUndeclaredStepIsRefused() {
        // utilities declares eight Steps. Dropping one is the mirror mutant.
        val jar = PluginArtifactFixture.jarWithRewrittenManifest("utilities") { text ->
            val rewritten = text.replace(
                "{\"stepKey\": \"core-utils.readJson\", \"declaredCapabilities\": [\"runtime.execution-location\"]}, ",
                "",
            )
            assertTrue(rewritten != text, "the utilities manifest no longer contains core-utils.readJson")
            rewritten
        }

        val invalid = (admit(jar, contributorFor("utilities")) as PluginAdmissionResult.Refused).rejection
            as PluginManifestRejection.InvalidManifest

        assertTrue(
            invalid.detail.contains("core-utils.readJson"),
            "the refusal must NAME the implemented Step that was never declared; got '${invalid.detail}'",
        )
        assertTrue(
            invalid.detail.contains("absent from the manifest"),
            "the refusal must state the direction of the mismatch; got '${invalid.detail}'",
        )
    }

    @ParameterizedTest(name = "{0} is consistent with its declaration")
    @MethodSource("modules")
    @DisplayName("CONTROL: every official plugin passes the cross-check unchanged")
    internal fun officialPluginIsConsistent(module: String) {
        val result = admit(PluginArtifactFixture.builtJar(module), contributorFor(module))
        assertTrue(
            result is PluginAdmissionResult.Admitted,
            "$module is consistent and must be admitted; got $result",
        )
    }

    companion object {
        @JvmStatic
        fun modules(): Stream<String> = Stream.of("http", "scm-git", "junit", "utilities")
    }
}
