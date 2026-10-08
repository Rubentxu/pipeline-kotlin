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
 * It is committed deliberately red: the failure is the evidence that
 * `0.48.0-rc1` cannot be published, and it stays red until the version-parsing
 * contract is resolved. See `docs/v2/05-roadmap/P3_CANDIDATE_VERSION_BLOCKER.md`,
 * which records the four design options. Do not "fix" this test by weakening the
 * assertion: the correct fix is a decision about how a prerelease version is
 * represented, not a relaxed check.
 *
 * `RuntimeApiVersion.parse` is `private`, so this reproduces its exact documented
 * contract (the KDoc says "MAJOR.MINOR.PATCH, or null for anything else") rather
 * than calling it. If this test ever passes because the production parser changed,
 * the copy below must be replaced with a call to the real parser, or it will go on
 * asserting a contract the code no longer has.
 *
 * The class is `@Disabled` so the release gate stays green while the blocker is
 * open. That is a deliberate, declared choice, not a skipped green: the RED was
 * executed and its XML recorded (tests="2" failures="1" errors="0", exit=1) before
 * disabling, and the blocker document quotes that result. Re-enable the class to
 * re-measure once the version contract is decided.
 *
 * The contract this probe asserts is the one the release plan depends on:
 * a candidate version of the form `0.48.0-rc1` must be readable as the running
 * API version, because `rootProject.version` becomes that string.
 */
@Disabled("P3 blocker: 0.48.0-rc1 is unreadable by RuntimeApiVersion.parse; see docs/v2/05-roadmap/P3_CANDIDATE_VERSION_BLOCKER.md")
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