package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * P-UAT-01 (exact identity) and P-UAT-08 (historical canary) for the
 * release-evolution protocol, `docs/pipelinek-release-evolution/`.
 *
 * These test the **decision**, not a build. The decision is pure, so the
 * 0.43.0 incident can be reproduced exactly and cheaply, with no ZIP, no JAR
 * and no Gradle invocation. The effectful probe over a real artifact is a
 * separate concern.
 */
class DistributionIdentityVerdictTest {

    private fun facts(
        product: String? = "0.44.0",
        asset: String? = "0.44.0",
        root: String? = "0.44.0",
        impl: String? = "0.44.0",
        runtime: String? = "0.44.0",
        manifest: String? = "0.44.0",
    ) = DistributionIdentityFacts(
        listOf(
            IdentityObservation(IdentitySurface.PRODUCT_VERSION, product),
            IdentityObservation(IdentitySurface.ASSET, asset),
            IdentityObservation(IdentitySurface.ARCHIVE_ROOT, root),
            IdentityObservation(IdentitySurface.IMPLEMENTATION_VERSION, impl),
            IdentityObservation(IdentitySurface.RUNTIME_VERSION, runtime),
            IdentityObservation(IdentitySurface.MANIFEST_VERSION, manifest),
        ),
    )

    @Nested
    inner class HappyPath {

        @Test
        fun `P-UAT-01 all six surfaces agree yields Consistent`() {
            val verdict = evaluateDistributionIdentity(facts())
            assertInstanceOf(DistributionIdentityVerdict.Consistent::class.java, verdict)
            assertEquals("0.44.0", (verdict as DistributionIdentityVerdict.Consistent).version.value)
        }

        @Test
        fun `a consistent verdict renders a positive message naming the version`() {
            val rendered = evaluateDistributionIdentity(facts()).render()
            assertTrue(rendered.contains("consistent"), rendered)
            assertTrue(rendered.contains("0.44.0"), rendered)
        }
    }

    /**
     * P-UAT-08. The historical canary, in the exact shape of the v0.43.0
     * incident: the OUTER identity (asset, archive root) advertises a final GA
     * version while the bytes INSIDE carry a candidate suffix.
     *
     * This is the artifact that shipped a `pipelinek-0.43.0.zip` whose JARs
     * declared `0.43.0-rc1`. A producer that only checked the filename would
     * have passed it.
     */
    @Nested
    inner class HistoricalCanary {

        @Test
        fun `P-UAT-08 outer GA identity with an embedded rc suffix is REJECTED`() {
            val verdict = evaluateDistributionIdentity(
                facts(
                    product = "0.43.0",
                    asset = "0.43.0", // outer: looks like GA
                    root = "0.43.0", // outer: looks like GA
                    impl = "0.43.0-rc1", // inner: the lie
                    runtime = "0.43.0-rc1", // inner: the lie
                    manifest = "0.43.0",
                ),
            )
            assertInstanceOf(
                DistributionIdentityVerdict.Divergent::class.java,
                verdict,
                "a candidate whose inner bytes carry rc1 must never be handed off",
            )
        }

        @Test
        fun `the canary names BOTH lying surfaces, not just that one disagreed`() {
            val verdict = evaluateDistributionIdentity(
                facts(impl = "0.43.0-rc1", runtime = "0.43.0-rc1"),
            ) as DistributionIdentityVerdict.Divergent
            val surfaces = verdict.conflicts.map { it.surface }.toSet()
            assertEquals(
                setOf(IdentitySurface.IMPLEMENTATION_VERSION, IdentitySurface.RUNTIME_VERSION),
                surfaces,
                "a diagnostic that names one offender hides the other",
            )
            assertTrue(verdict.render().contains("rc1"), "the message must show the offending value")
        }
    }

    @Nested
    inner class Divergence {

        @Test
        fun `a single mismatching surface is enough to fail closed`() {
            val verdict = evaluateDistributionIdentity(facts(manifest = "0.43.0"))
            assertInstanceOf(DistributionIdentityVerdict.Divergent::class.java, verdict)
            assertEquals(
                listOf(IdentitySurface.MANIFEST_VERSION),
                (verdict as DistributionIdentityVerdict.Divergent).conflicts.map { it.surface },
            )
        }

        @Test
        fun `every conflicting surface is reported, not only the first`() {
            val verdict = evaluateDistributionIdentity(
                facts(asset = "0.43.0", root = "0.43.0", runtime = "0.43.0"),
            ) as DistributionIdentityVerdict.Divergent
            assertEquals(3, verdict.conflicts.size, "a fix that only repairs one surface still fails")
        }
    }

    /**
     * A missing surface and a wrong surface are different defects. Collapsing
     * them would let an absent distribution manifest read as a pass.
     */
    @Nested
    inner class Incomplete {

        @Test
        fun `an unobservable surface yields Incomplete, never a pass`() {
            val verdict = evaluateDistributionIdentity(facts(manifest = null))
            assertInstanceOf(DistributionIdentityVerdict.Incomplete::class.java, verdict)
            assertEquals(
                listOf(IdentitySurface.MANIFEST_VERSION),
                (verdict as DistributionIdentityVerdict.Incomplete).missing,
            )
        }

        @Test
        fun `Incomplete is distinct from Divergent`() {
            val missing = evaluateDistributionIdentity(facts(manifest = null))
            val wrong = evaluateDistributionIdentity(facts(manifest = "0.43.0"))
            assertNotNull(missing)
            assertTrue(
                missing::class != wrong::class,
                "'nothing observed' and 'observed the wrong thing' must not share a case",
            )
        }

        @Test
        fun `a missing expected version is Incomplete on PRODUCT_VERSION`() {
            val verdict = evaluateDistributionIdentity(
                DistributionIdentityFacts(
                    listOf(IdentityObservation(IdentitySurface.ASSET, "0.44.0")),
                ),
            )
            assertInstanceOf(DistributionIdentityVerdict.Incomplete::class.java, verdict)
        }
    }

    /**
     * P0.1: candidate state is NOT product identity. `0.44.0-rc1` is a legal
     * string a build may currently declare, but it is not a ProductVersion.
     */
    @Nested
    inner class ProductVersionParsing {

        @Test
        fun `a final SemVer parses`() {
            assertEquals("0.44.0", ProductVersion.parseOrNull("0.44.0")?.value)
            val parsed = requireNotNull(ProductVersion.parseOrNull("0.44.0"))
            assertEquals(0, parsed.major)
            assertEquals(44, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        fun `a candidate suffix is NOT a valid ProductVersion`() {
            assertNull(ProductVersion.parseOrNull("0.44.0-rc1"))
            assertNull(ProductVersion.parseOrNull("0.44.0-rc1.2"))
            assertNull(ProductVersion.parseOrNull("0.44.0-SNAPSHOT"))
        }

        @Test
        fun `parseOrThrow explains that candidate state lives in the descriptor`() {
            val message = requireNotNull(
                assertThrows(IllegalArgumentException::class.java) {
                    ProductVersion.parseOrThrow("0.44.0-rc1")
                }.message,
            )
            assertTrue(message.contains("candidate"), message)
        }

        @Test
        fun `a candidate-suffixed product declaration is Divergent, not silently accepted`() {
            val verdict = evaluateDistributionIdentity(
                facts(product = "0.44.0-rc1", asset = "0.44.0-rc1", root = "0.44.0-rc1",
                    impl = "0.44.0-rc1", runtime = "0.44.0-rc1", manifest = "0.44.0-rc1"),
            )
            assertInstanceOf(
                DistributionIdentityVerdict.Divergent::class.java,
                verdict,
                "every surface agreeing on a NON-product version is still a defect: candidate " +
                    "state must not live inside the binary identity",
            )
        }
    }
}
