package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * W-LPR-071 / S0-B: the self-hosted CI authority (`pipeline.kts`) MUST itself be
 * honest about the surface it claims and about the version it releases.
 *
 * These are invariants over the REAL repository script, compiled by the REAL
 * installed binary. They exist because `pipeline.kts` had three defects that no
 * existing test caught, since every prior guard used a synthetic temp script:
 *
 *  1. `PKG_VERSION` hardcoded "0.39.0" while the project version was 0.42.0-rc1.
 *     The Dist stage's `test -f "$DIST"` guard could therefore never pass, so the
 *     release pipeline could never release. A duplicated version constant is a
 *     silent, release-blocking claim.
 *  2. `ansiColor("xterm") { ... }` — UNSUPPORTED_FAIL_CLOSED in the surface
 *     manifest — made the canonical bridge reject the ENTIRE script with exit 2
 *     before a single stage ran. The self-hosted CI could not execute at all.
 *  3. The header comment advertised `agent`, `post`, `always`,
 *     `archiveArtifacts` and `cleanWs`, none of which appear in the script body.
 *     Documentation that overclaims the surface is the exact defect class the
 *     Semantic Honesty Gate exists to remove.
 *
 * A "compiles" test is not enough here: these are claims about what the script
 * MEANS, so each test asserts the discriminating property, not mere success.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SelfHostedPipelineScriptHonestyTest {

    private fun selfHostedScript(): Path {
        val root = Path.of(System.getProperty("pipeline.repoRoot") ?: ".")
        val script = root.resolve("pipeline.kts")
        assertTrue(Files.exists(script), "self-hosted CI script must exist at $script")
        return script
    }

    /**
     * The script's CODE, with every comment line removed.
     *
     * These invariants are about what the script DOES, so a name mentioned in a
     * comment (for example documenting that `whenCondition` was removed) must not
     * count as a use. Matching raw text produced false REDs against exactly the
     * honesty comments this TRAIN added.
     */
    private fun selfHostedScriptCode(): String =
        Files.readString(selfHostedScript())
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** True when [builder] is actually CALLED in code, not merely mentioned. */
    private fun String.callsBuilder(builder: String): Boolean =
        Regex("""(^|[^A-Za-z0-9_.])${Regex.escape(builder)}\s*\(""").containsMatchIn(this)

    /**
     * True when the header comment block claims [builder].
     */
    private fun String.headerClaims(builder: String): Boolean =
        Regex("""(?m)^//.*\b${Regex.escape(builder)}\b.*$""").containsMatchIn(this)

    private fun selfHostedScriptHeader(): String =
        Files.readString(selfHostedScript())
            .lineSequence()
            .takeWhile { !it.trimStart().startsWith("pipeline {") }
            .joinToString("\n")

    private fun projectVersion(): String {
        val root = Path.of(System.getProperty("pipeline.repoRoot") ?: ".")
        val buildFile = root.resolve("v2/build.gradle.kts")
        assertTrue(Files.exists(buildFile), "v2/build.gradle.kts must exist at $buildFile")
        return Files.readAllLines(buildFile)
            .first { it.trimStart().startsWith("version = ") }
            .substringAfter("version = ")
            .trim()
            .trim('"')
    }

    /**
     * CARRIER: the script derives its version from the single authority instead
     * of duplicating the literal. A hardcoded copy is a release-blocking lie.
     */
    @Test
    fun `the self-hosted script derives its version from the single authority`() {
        val code = selfHostedScriptCode()

        assertFalse(
            Regex("""val PKG_VERSION\s*(?::\s*String\s*)?=\s*["']""").containsMatchIn(code),
            "PKG_VERSION must not be assigned a string literal; derive it from v2/build.gradle.kts",
        )
        assertTrue(
            code.contains("v2/build.gradle.kts"),
            "PKG_VERSION must be derived from the single version authority (v2/build.gradle.kts)",
        )
        assertTrue(
            Regex("""val PKG_VERSION""").containsMatchIn(code),
            "the script must define PKG_VERSION",
        )
        // The derivation must actually READ the `version = ` assignment from the
        // authority. Asserting a specific stdlib call here would over-specify the
        // implementation; what matters is that the literal is gone and the
        // authority is consulted.
        assertTrue(
            code.contains("version = "),
            "the version derivation must read the `version = ` assignment from the authority",
        )
    }

    /**
     * POSITIVE: the distribution path the script guards is composed from the
     * derived PKG_VERSION, so the Dist stage's `test -f "$DIST"` guard resolves to
     * a file that is actually built.
     */
    @Test
    fun `the distribution path the script guards matches the real project version`() {
        val version = projectVersion()
        val code = selfHostedScriptCode()

        // The script is Kotlin source, so the interpolation is written as `${'$'}`.
        // The property that matters is that DIST references the DERIVED identifier
        // rather than embedding any version literal of its own.
        val distLine = code.lineSequence().firstOrNull { it.contains("val DIST") }
        assertTrue(distLine != null, "the script must define DIST")
        assertTrue(
            distLine!!.contains("PKG_VERSION"),
            "DIST must be composed from the derived PKG_VERSION, got: $distLine",
        )
        assertTrue(
            !Regex("""\d+\.\d+\.\d+""").containsMatchIn(distLine),
            "DIST must not embed a version literal, got: $distLine",
        )
        assertTrue(
            !code.contains("0.39.0"),
            "the script must not carry the stale 0.39.0 version literal",
        )
        assertTrue(
            version.isNotBlank() && version != "0.39.0",
            "project version must be a real current version, got '$version'",
        )
    }

    /**
     * NEGATIVE: the script may not CALL any builder the surface manifest
     * classifies UNSUPPORTED_FAIL_CLOSED. Such a use makes the canonical bridge
     * reject the whole script with exit 2 before any stage executes.
     */
    @Test
    fun `the self-hosted script uses no fail-closed builder`() {
        val code = selfHostedScriptCode()
        val failClosed = listOf("ansiColor", "agent", "post", "always", "whenCondition")

        val used = failClosed.filter { code.callsBuilder(it) }

        assertTrue(
            used.isEmpty(),
            "self-hosted script must not use UNSUPPORTED_FAIL_CLOSED builders " +
                "(each rejects the whole script with exit 2 before execution): $used",
        )
    }

    /**
     * OUTCOME / EVENTS: the real script COMPILES with zero diagnostics under the
     * real installed binary. This is the property whose absence made the
     * self-hosted CI unusable; it is asserted structurally (no --execute, so no
     * side effects) rather than by running the full pipeline.
     */
    @Test
    fun `the self-hosted script compiles with zero diagnostics`() {
        val appBin = AppBinSupport.discover()
        val root = Path.of(System.getProperty("pipeline.repoRoot") ?: ".").toAbsolutePath()

        val result = ProcessBuilder(
            appBin.toString(),
            "validate",
            "--workspace", root.toString(),
            root.resolve("pipeline.kts").toString(),
        )
            .redirectErrorStream(true)
            .start()

        val output = result.inputStream.bufferedReader().readText()
        val finished = result.waitFor(240, TimeUnit.SECONDS)
        if (!finished) {
            result.destroyForcibly()
            assertTrue(false, "compile check must terminate; output so far: $output")
        }

        assertFalse(
            "non-canonical plugins" in output,
            "self-hosted script must not trip the canonical bridge gate; output: $output",
        )
        assertTrue(
            "\"kind\":\"CompilationFinished\"" in output,
            "self-hosted script must reach CompilationFinished; output: $output",
        )
        assertTrue(
            "\"diagnostics\":[]" in output,
            "self-hosted script must compile with ZERO diagnostics; output: $output",
        )
    }

    /**
     * The header documents the surface the script ACTUALLY uses. Documentation
     * that advertises builders absent from the body is a semantic overclaim,
     * and it misleads every future reader about what the CI exercises.
     */
    @Test
    fun `the header claims only builders the script body actually uses`() {
        val code = selfHostedScriptCode()
        val header = selfHostedScriptHeader()

        // Only the AUTHORITATIVE capability claim counts. The header also carries
        // S0-B CORRECTION notes that deliberately name removed builders to record
        // why they were removed; naming a builder there is honesty, not a claim.
        val claim = header.lineSequence()
            .filter { it.contains("aspirational") || it.contains("Capabilities actually used") }
            .joinToString(" ")

        assertTrue(
            claim.isNotBlank(),
            "the header must carry an explicit capabilities claim to be checkable",
        )

        val overclaimed = listOf("archiveArtifacts", "cleanWs", "agent", "post", "always", "ansiColor")
            .filter { claim.headerClaims(it) && !code.callsBuilder(it) }

        assertTrue(
            overclaimed.isEmpty(),
            "the capabilities claim advertises builders the body never calls " +
                "(silent overclaim): $overclaimed",
        )
    }
}
