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
    @DisplayName("the gate ADMITS the real artifact at the BUILD's RuntimeApiVersion")
    internal fun gateAdmitsTheRealArtifact(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val running = RuntimeApiVersion.current()
        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = running,
            alreadyAdmitted = emptySet(),
            // strict stays ON: the manifest and the contributor class must come from the same
            // artifact, which is the substitution this check exists to refuse.
            strict = true,
        )

        assertTrue(result is PluginAdmissionResult.Admitted, "${artifact.module} must be admitted at $running; got $result")
        val admitted = (result as PluginAdmissionResult.Admitted).plugin
        assertEquals(
            artifact.expectedSteps,
            admitted.manifest.contributions.steps.size,
            "${artifact.module} admitted with a Step count its declaration does not describe",
        )
    }

    @ParameterizedTest(name = "{0} is refused outside its declared apiRange")
    @MethodSource("artifacts")
    @DisplayName("the same artifact is REFUSED at 0.60.0, outside the policy-derived range")
    internal fun realArtifactIsRefusedOutsideItsApiRange(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val running = RuntimeApiVersion.current()
        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = SemVer(0, 60, 0),
            alreadyAdmitted = emptySet(),
            strict = true,
        )

        assertTrue(
            result is PluginAdmissionResult.Refused,
            "${artifact.module} at running=$running declares a range above 0.60.0's exclusive upper bound, " +
                "so a 0.60.0 runtime must be refused; got $result",
        )
    }

    /**
     * Positive admission at 0.51.0 — the running runtime version of the PK-R1 consolidated train.
     *
     * Added as part of PK-R1: the four plugins' `apiRange` was widened from `[0.47.0, 0.49.0)` to
     * `[0.47.0, 0.52.0)` to admit them on 0.51.0, then to `[0.47.0, 0.53.0)` for 0.52.0-rc1.
     * This row is the *proof* that admission actually accepts the artefact at the running
     * version (not at a synthetic substitute). A negative-only gate could pass green while
     * admitting nothing — the prior `[0.47.0, 0.49.0)` form was that gate, and the 283
     * `:pipeline-application:test` failures it produced on the 0.51.0 build were the visible
     * symptom. This row is the row that was missing.
     */
    @ParameterizedTest(name = "{0} is admitted at 0.51.0 (historical PK-R1 contract)")
    @MethodSource("artifacts")
    @DisplayName("the gate ADMITS the real artifact at 0.51.0 (PK-R1 historical)")
    internal fun gateAdmitsTheRealArtifactAt051(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = SemVer(0, 51, 0),
            alreadyAdmitted = emptySet(),
            strict = true,
        )

        assertTrue(
            result is PluginAdmissionResult.Admitted,
            "${artifact.module} must be admitted at 0.51.0 (historical PK-R1 contract); got $result",
        )
    }

    /**
     * Negative admission at the policy-derived exclusive upper bound, read from the manifest.
     *
     * The literal `0.53.0` from earlier trains is no longer the upper bound: the policy widened
     * to `[0.47.0, 0.57.0)` on the PK-1 / PK-RC-PLUGIN-001 train, so 0.53.0 is ADMITTED and a
     * row asserting refusal at 0.53.0 is now stale. The new oracle is the bound the manifest
     * itself publishes — the test fails the build if the policy ever silently widens without
     * updating the manifest, and a future bump to 0.57.0 widens the bound the test reads, not
     * a literal.
     */
    @ParameterizedTest(name = "{0} is refused at the policy-derived exclusive upper bound")
    @MethodSource("artifacts")
    @DisplayName("the gate REFUSES the real artifact at the policy-derived exclusive upper bound")
    internal fun realArtifactIsRefusedAtTheUpperBound(artifact: PluginArtifact) = withLoader(artifact) { loader ->
        val manifestText = loader.getResource(PluginManifestCodec.RESOURCE_PATH)
            ?.openStream()?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("${artifact.module} ships no manifest at ${PluginManifestCodec.RESOURCE_PATH}")
        val apiRange = extractApiRange(manifestText)
            ?: error("${artifact.module} manifest does not parse a clean apiRange field")
        val upperBound = parseExclusiveUpper(apiRange)
            ?: error("${artifact.module} apiRange literal '$apiRange' is not '[X.Y.Z, A.B.C)'")

        val result = PluginAdmissionGate.admitThenLoad(
            contributorClassName = artifact.contributorClass,
            classLoader = loader,
            runtimeVersion = upperBound,
            alreadyAdmitted = emptySet(),
            strict = true,
        )

        assertTrue(
            result is PluginAdmissionResult.Refused,
            "${artifact.module} declares '$apiRange'; a runtime at the exclusive upper bound " +
                "${upperBound.major}.${upperBound.minor}.${upperBound.patch} must be refused; got $result",
        )
    }

    /** Parse the apiRange field out of a manifest JSON. Test-only; production uses [PluginManifestCodec]. */
    private fun extractApiRange(json: String): String? {
        val key = "\"apiRange\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colon = json.indexOf(':', keyIdx + key.length)
        if (colon < 0) return null
        val open = json.indexOf('"', colon + 1)
        if (open < 0) return null
        val close = json.indexOf('"', open + 1)
        if (close < 0) return null
        return json.substring(open + 1, close)
    }

    private val upperRegex = Regex("""\[\s*\d+\.\d+\.\d+\s*,\s*(\d+)\.(\d+)\.(\d+)\s*\)""")
    private fun parseExclusiveUpper(raw: String): SemVer? {
        val m = upperRegex.matchEntire(raw.trim()) ?: return null
        return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
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
