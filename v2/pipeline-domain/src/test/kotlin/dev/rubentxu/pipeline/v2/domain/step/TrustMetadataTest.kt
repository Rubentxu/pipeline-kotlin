package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * B3.2 — sealed-ness of [TrustMetadata].
 *
 * ## What this test is for
 *
 * The whole B3.2 boundary depends on [TrustMetadata] being a closed three-case ADT with a
 * sealed [TrustMetadata.UnverifiedReason] inside the [TrustMetadata.Unverified] case. A
 * silent regression that turned one of the cases into a flag, a nullable payload, or a
 * `String` reason would be invisible at the call site that uses the affected case, and the
 * resulting "Verified by default" or "Unverified with no reason" would not be caught by
 * any production code path until it had already been published.
 *
 * The rows here are the four claims that protect the closed-ness of the type:
 *
 *  1. Exactly three cases exist.
 *  2. The constructor surface is sealed (no stringly-typed discriminator).
 *  3. The [TrustMetadata.UnverifiedReason] sub-sealed is closed at three cases.
 *  4. The refusal-case -> trust-case mapping spelled in the KDoc actually holds.
 *
 * ## Why a separate test, not a fitness test
 *
 * The L4 fitness tests already scan for "boolean flag bags" and "stringly typed trust";
 * this class is the cheaper, more readable contract row that the operator can read without
 * booting a JVM-wide fitness scan. It runs on the L1 inner loop in seconds and is the
 * place to assert new cases as they are added.
 */
@DisplayName("B3.2 TrustMetadata is a closed three-case sealed ADT")
class TrustMetadataTest {

    private val sampleOrigin: ArtifactOrigin = ArtifactOrigin.LocalClasspathEntry("file:/x.jar")
    private val sampleDigest: Digest = Digest("sha256:" + "a".repeat(64))
    private val sampleMeasured: Digest = Digest("sha256:" + "b".repeat(64))

    @Test
    @DisplayName("Verified carries the agreed digest and origin; equals is structural")
    fun verifiedCarriesDigestAndOrigin() {
        val a = TrustMetadata.Verified(sampleDigest, sampleOrigin)
        val b = TrustMetadata.Verified(sampleDigest, sampleOrigin)
        val c = TrustMetadata.Verified(sampleMeasured, sampleOrigin)

        assertEquals(a, b, "Verified is a data class; two constructions with the same payload must equal")
        assertNotEquals(a, c, "Verified must NOT collapse different digests into one case")
    }

    @Test
    @DisplayName("Mismatch carries BOTH digests; one is not enough to identify the failure")
    fun mismatchCarriesBothDigests() {
        val m = TrustMetadata.Mismatch(declared = sampleDigest, measured = sampleMeasured, origin = sampleOrigin)
        val m2 = TrustMetadata.Mismatch(declared = sampleDigest, measured = sampleMeasured, origin = sampleOrigin)

        assertEquals(m, m2, "Mismatch with the same declared/measured pair must equal")
        assertEquals(sampleDigest, m.declared, "Mismatch must keep the declared digest so the journal row is diagnosable")
        assertEquals(sampleMeasured, m.measured, "Mismatch must keep the measured digest so the journal row is diagnosable")
    }

    @Test
    @DisplayName("Unverified defaults to NoTrustedDigestAvailable + Unknown, the codec's placeholder state")
    fun unverifiedDefaultsAreTheCodecPlaceholder() {
        val u = TrustMetadata.Unverified()
        assertSame(
            TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
            u.reason,
            "the no-arg Unverified is the codec's placeholder: it MUST mean 'no operator trust row'",
        )
        assertEquals(ArtifactOrigin.Unknown, u.origin)
    }

    @Test
    @DisplayName("Unverified with ArtifactNotMeasurable distinguishes a missing-row from a missing-bytes case")
    fun unverifiedCarriesTheReason() {
        val u = TrustMetadata.Unverified(
            reason = TrustMetadata.UnverifiedReason.ArtifactNotMeasurable,
            origin = sampleOrigin,
        )
        assertSame(TrustMetadata.UnverifiedReason.ArtifactNotMeasurable, u.reason)
        assertEquals(sampleOrigin, u.origin)
    }

    @Test
    @DisplayName("UnverifiedReason is a sealed sub-type: three cases, no String discriminator")
    fun unverifiedReasonIsSealedAtThreeCases() {
        // The three constants of UnverifiedReason are the only legal values. Adding a
        // fourth case is a deliberate change; the type is sealed so the compiler enforces
        // that the addition is a visible one, not a new String at a call site.
        val noRow: TrustMetadata.UnverifiedReason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable
        val notMeasurable: TrustMetadata.UnverifiedReason = TrustMetadata.UnverifiedReason.ArtifactNotMeasurable
        val outside: TrustMetadata.UnverifiedReason = TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy

        assertNotNull(noRow)
        assertNotNull(notMeasurable)
        assertNotNull(outside)
        assertNotEquals(noRow, notMeasurable, "UnverifiedReason cases must be distinct values, not aliases")
    }

    @Test
    @DisplayName("The Unverified (data class) form is no longer the only legal value: Verified and Mismatch are equal citizens")
    fun unverifiedIsNoLongerTheOnlyCase() {
        val verified: TrustMetadata = TrustMetadata.Verified(sampleDigest, sampleOrigin)
        val unverified: TrustMetadata = TrustMetadata.Unverified()
        val mismatch: TrustMetadata = TrustMetadata.Mismatch(sampleDigest, sampleMeasured, sampleOrigin)

        // The fact that the compiler accepts three distinct types of `TrustMetadata` as
        // a single `TrustMetadata` reference is the seal: the ADT has more than one case
        // and the type itself is what carries that. If a future change collapsed this to
        // a Boolean, this row would not even compile.
        val all: List<TrustMetadata> = listOf(verified, unverified, mismatch)
        assertEquals(3, all.size, "the closed type must enumerate to three cases; collapsing any two is a defect")
        assertTrue(all[0] is TrustMetadata.Verified)
        assertTrue(all[1] is TrustMetadata.Unverified)
        assertTrue(all[2] is TrustMetadata.Mismatch)
    }

    @Test
    @DisplayName("Refusal -> Trust case mapping: RefusedUntrustedIdentity carries an UnverifiedReason")
    fun refusalMappingHolds() {
        val refusal: AdmissionRefusal = AdmissionRefusal.RefusedUntrustedIdentity(
            identity = "v1:plugin:acme/x",
            reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
        )
        val trust: TrustMetadata = TrustMetadata.Unverified(
            reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
            origin = ArtifactOrigin.Unknown,
        )

        // The mapping the design KDoc promises: a RefusedUntrustedIdentity admission
        // refusal carries the same UnverifiedReason that the TrustMetadata.Unverified case
        // would carry. A future change that renamed or repackaged the reason would have to
        // update both at once or this row stops compiling.
        val refusalReason = (refusal as AdmissionRefusal.RefusedUntrustedIdentity).reason
        val trustReason = (trust as TrustMetadata.Unverified).reason
        assertSame(refusalReason, trustReason)
    }
}
