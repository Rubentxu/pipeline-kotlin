package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P3-CANDIDATE-VERSION-BLOCKER — the runtime version is ALWAYS MAJOR.MINOR.PATCH.
 *
 * ## The invariant
 *
 * The product version, as seen by the runtime, must be a three-component SemVer
 * with no pre-release suffix. The candidate state (-rcN) lives in the release tag
 * and the ZIP name; it is NOT carried in `pipelinek-version.properties` or in
 * the jar manifest's `Implementation-Version` attribute.
 *
 * ## Why this is enforced at the test layer
 *
 * The release model v2 carries the candidate suffix in the tag and ZIP, never in
 * the product version. Putting the suffix in the product version is the
 * `0.48.0-rc2 → FATAL` defect: `RuntimeApiVersion.parse` rejects anything that
 * is not MAJOR.MINOR.PATCH, the runtime fails-closed in preflight, and the
 * plugin SDK cannot compare `apiRange` against a non-three-component version.
 * The result is a published candidate that the certifier cannot even start.
 *
 * ## What this test does NOT do
 *
 * It does not assert that the version is "the right number". Whether
 * `0.48.0` is the correct bump is a release decision, not a property of this
 * module. It only asserts the FORMAT: three non-negative integers, no
 * pre-release separator, no build metadata.
 */
@DisplayName("P3 product version is always MAJOR.MINOR.PATCH (no -rcN in the manifest)")
class RuntimeVersionFormatGuardTest {

    @Test
    @DisplayName("the runtime reads a three-component SemVer")
    fun theRuntimeReadsAThreeComponentSemVer() {
        val raw = requireNotNull(RuntimeApiVersion.readImplementationVersion()) {
            "RuntimeApiVersion must read a build-generated version resource."
        }
        val parts = raw.split('.')
        assertEquals(
            3,
            parts.size,
            "product version '$raw' is not MAJOR.MINOR.PATCH. " +
                "PipelineK's product version is always three-component SemVer. " +
                "The candidate state (-rcN) lives in the release tag and the ZIP name, " +
                "not in pipelinek-version.properties or in the jar manifest. " +
                "Putting it here made v0.48.0-rc2 fail in preflight with FATAL.",
        )
        val numbers = parts.map { it.toIntOrNull() }
        assertTrue(
            numbers.all { it != null && it >= 0 },
            "product version '$raw' has non-integer or negative component(s). " +
                "MAJOR.MINOR.PATCH are non-negative integers.",
        )
    }

    @Test
    @DisplayName("the product version has no pre-release separator")
    fun theProductVersionHasNoPrereleaseSeparator() {
        val raw = requireNotNull(RuntimeApiVersion.readImplementationVersion()) {
            "RuntimeApiVersion must read a build-generated version resource."
        }
        assertTrue(
            !raw.contains('-'),
            "product version '$raw' contains '-' (pre-release separator). " +
                "The product version must be exactly MAJOR.MINOR.PATCH. " +
                "The -rcN suffix belongs to the release tag (e.g. v0.48.0-rc2) and the " +
                "ZIP name (e.g. pipelinek-0.48.0-rc2.zip), NOT to the runtime's version. " +
                "See P3_CANDIDATE_VERSION_BLOCKER.",
        )
        assertTrue(
            !raw.contains('+'),
            "product version '$raw' contains '+' (build-metadata separator). " +
                "The product version is exactly MAJOR.MINOR.PATCH; build metadata is not " +
                "part of the product version contract.",
        )
    }
}
