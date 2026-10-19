package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that keeps a candidate from being shipped under a GA identity.
 * The v0.43.0 incident: an artifact that was internally consistent at
 * `0.43.0-rc1` was later presented under a GA identity. A gate that only
 * compared the build's own surfaces against each other would have been GREEN on
 * exactly those bytes -- all six agreed, they simply agreed on something that
 * is not a product identity.
 * So the guarantee under test is narrow and specific: **an identity carrying a
 * candidate suffix can never reach `Consistent`**, no matter how many surfaces
 * agree on it. The first test below is the one that carries that guarantee, and
 * it is built so that nothing except [ProductVersion] can decide it: every
 * surface reports the same suffixed string. Remove the refusal and the case
 * passes a perfectly harmonious distribution.
 * The `UNKNOWN` placeholder is pinned for the same reason `ApiRangeContainsTest`
 * pins its fail-closed choices: `UNKNOWN` is `0.0.0`, a real, comparable version.
 * It exists to fill the `expected` slot of `Incomplete` and `Divergent`, where
 * there is genuinely no expected value. If it ever reached `Consistent`, an
 * unreadable build would report as an identity that agrees with itself.
 * Everything here is a pure decision over strings: no build, no ZIP, no JAR and
 * no clock.
*/
class ProductVersionIdentityTest {

    // --- the incident itself: every surface agrees, and the verdict is still not green ---

    @Test
    fun `every surface agreeing on a candidate suffix is still not Consistent`() {
        // The laundering shape, exactly. Agreement among surfaces is NOT the
        // rule; the product version being a FINAL SemVer is. Nothing else in
        // this input can turn this verdict green, which is what makes the test
        // discriminating rather than merely present.
        val verdict = evaluateDistributionIdentity(everySurfaceReporting("0.44.0-rc1"))

        assertTrue(
            verdict is DistributionIdentityVerdict.Divergent,
            "a candidate suffix must never reach Consistent, got $verdict",
        )
    }

    @Test
    fun `the conflict names the surface that reported the candidate`() {
        val verdict = evaluateDistributionIdentity(everySurfaceReporting("0.44.0-rc1"))

        val conflict = (verdict as DistributionIdentityVerdict.Divergent).conflicts.single()
        assertEquals(IdentitySurface.PRODUCT_VERSION, conflict.surface)
        // The diagnostic keeps the rejected text verbatim, so an operator sees
        // the identity that was refused rather than a generic "bad version".
        assertTrue(
            conflict.reported.contains("0.44.0-rc1"),
            "the refused identity must survive into the diagnostic, got '${conflict.reported}'",
        )
    }

    // --- the positive control: without this, a verdict that is always red passes above ---

    @Test
    fun `a final version every surface agrees on is Consistent`() {
        val verdict = evaluateDistributionIdentity(everySurfaceReporting("0.51.0"))

        assertEquals(
            DistributionIdentityVerdict.Consistent(ProductVersion.parseOrThrow("0.51.0")),
            verdict,
        )
    }

    @Test
    fun `a surface that disagrees with a final product version is Divergent and names it`() {
        val facts = DistributionIdentityFacts(
            observations = IdentitySurface.entries.map { surface ->
                IdentityObservation(
                    surface,
                    // One honest typo in one surface, everything else right.
                    if (surface == IdentitySurface.MANIFEST_VERSION) "0.51.1" else "0.51.0",
                )
            },
        )

        val conflict =
            (evaluateDistributionIdentity(facts) as DistributionIdentityVerdict.Divergent).conflicts.single()
        assertEquals(IdentitySurface.MANIFEST_VERSION, conflict.surface)
        assertEquals("0.51.1", conflict.reported)
    }

    // --- the placeholder, which is a real version and therefore a real hazard ---

    @Test
    fun `an unreadable product version is Divergent and never carries UNKNOWN as Consistent`() {
        val verdict = evaluateDistributionIdentity(everySurfaceReporting("not-a-version"))

        assertTrue(
            verdict is DistributionIdentityVerdict.Divergent,
            "an unreadable product version must not read as agreement, got $verdict",
        )
        assertTrue(
            verdict !is DistributionIdentityVerdict.Consistent,
            "UNKNOWN is a placeholder for an absent expected value and must never be shipped as one",
        )
    }

    @Test
    fun `a surface nobody could observe is Incomplete, which is not Divergent`() {
        // The two failure modes are kept apart on purpose: "reported the wrong
        // thing" is a divergence defect, "reported nothing" is an incomplete
        // build. Conflating them would let a missing manifest read as a
        // mismatch, and a mismatch as a build that simply has not been observed.
        val facts = DistributionIdentityFacts(
            observations = IdentitySurface.entries
                .filter { it != IdentitySurface.ARCHIVE_ROOT }
                .map { IdentityObservation(it, "0.51.0") },
        )

        val verdict = evaluateDistributionIdentity(facts)
        assertTrue(verdict is DistributionIdentityVerdict.Incomplete, "got $verdict")
        assertEquals(
            listOf(IdentitySurface.ARCHIVE_ROOT),
            (verdict as DistributionIdentityVerdict.Incomplete).missing,
        )
    }

    // --- the type's own front door, which is the only way to build one ---

    @Test
    fun `a candidate suffix is not a product version, and no trim rescues it`() {
        assertEquals(null, ProductVersion.parseOrNull("0.44.0-rc1"))
        // Surrounding whitespace is tolerated on purpose (the value comes from
        // build files), so this pins that the tolerance is about whitespace
        // only and does not extend to the suffix itself.
        assertEquals(null, ProductVersion.parseOrNull("  0.44.0-rc1  "))
        assertEquals("0.44.0", ProductVersion.parseOrNull("  0.44.0  ")?.value)
    }

    @Test
    fun `parseOrThrow rejects the same inputs with a message that names the reason`() {
        // The thrown message is the operator-facing half of the guarantee; a
        // message that only said "bad version" would leave the candidate/GA
        // distinction as folklore.
        val thrown = runCatching { ProductVersion.parseOrThrow("0.44.0-rc1") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got $thrown")
        assertTrue(
            thrown?.message?.contains("candidate") == true,
            "the message must name the candidate suffix as the cause, got '${thrown?.message}'",
        )
    }

    private fun everySurfaceReporting(version: String) = DistributionIdentityFacts(
        observations = IdentitySurface.entries.map { IdentityObservation(it, version) },
    )
}
