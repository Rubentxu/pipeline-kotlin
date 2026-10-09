package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * RED probe for the P3 candidate-version blocker. **This test is EXPECTED TO FAIL.**
 *
 * It was committed deliberately red and the RED was executed and recorded BEFORE the
 * decision was taken: `0.48.0-rc1` could not be published. The owner then resolved it
 * the other way — candidate state belongs in `CandidateHandoff`, not in the product
 * version — and the product version is the final `0.48.0`.
 *
 * Do not "fix" this test by weakening the assertion, and do not re-enable it. If a
 * future change made the runtime parse `0.48.0-rc1`, that would be a regression against
 * the `0.43.0` identity-laundering incident, and the guard for it is the ProductVersion
 * contract, not this probe.
 *
 * `RuntimeApiVersion.parse` is `private`, so this reproduces its exact documented
 * contract (the KDoc says "MAJOR.MINOR.PATCH, or null for anything else") rather
 * than calling it.
 *
 * Its lasting value is as a regression guard on the LAW: if the runtime ever parsed
 * `0.48.0-rc1`, this probe going green would be a warning, not a win.
 */
// One line on purpose: the RP-040 fitness scanner matches `^\s*@Disabled(\([^)]*\))?` and its
// capture excludes the closing paren, so a MULTILINE @Disabled(reason) is classified as a bare
// @Disabled and fails the fitness. A single-line reason under detekt's 160-char limit satisfies
// both rules at once. Keep this line under 160 characters.
@Disabled("P3: 0.48.0-rc1 is illegal BY DESIGN. ProductVersion rejects a candidate suffix; the version is the final 0.48.0. See P3_CANDIDATE_VERSION_BLOCKER")
class RuntimeApiVersionPrereleaseProbeTest {

    /** Verbatim copy of the production algorithm in RuntimeApiVersion.parse. */
    private fun parse(raw: String): SemVer? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return SemVer(numbers[0], numbers[1], numbers[2])
    }

    @Test
    @DisplayName("a three-component version parses")
    fun `a plain version parses`() {
        assertNotNull(parse("0.47.0"))
    }

    @Test
    @DisplayName("the 0_48_0-rc1 candidate version is readable by the runtime parser")
    fun `the candidate version parses`() {
        val parsed = parse("0.48.0-rc1")
        assertNotNull(
            parsed,
            "RuntimeApiVersion.parse rejected the candidate version '0.48.0-rc1'. " +
                "The runtime reads this value from pipelinek-version.properties, which " +
                "generateVersionResource fills verbatim from project.version. If this " +
                "returns null, RuntimeApiVersion.current() throws IllegalStateException " +
                "and the whole runtime is unbootable on a candidate build.",
        )
        // And the admission range [0.47.0, 0.49.0) must accept it.
        assertTrue(
            parsed!!.major == 0 && parsed.minor == 48,
            "unexpected parse result: $parsed",
        )
    }
}
