package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The artifact-integrity guarantee: a plugin whose measured bytes disagree with the
 * digest its own manifest declares is refused.
 * This is the one admission refusal that is not a policy decision. Every other refusal
 * asks whether an operator pinned something; this one asks whether the artifact is the
 * artifact it claims to be, and it is answerable from the bytes alone. That is why it runs
 * before the trust database is consulted — a manifest that lies about itself does not
 * become truthful because someone signed it.
 * The three cases below are the whole state space of the decision, and the third is the
 * one that makes it a guarantee rather than a formality: `measuredDigest = null` is NOT a
 * pass. Collapsing "nobody measured" into "nothing to object to" is the fail-open that
 * [MeasuredArtifactIdentity] exists to prevent, so it must stay a distinct outcome.
*/
class MeasuredVsDeclaredRefusalTest {

    private val origin = ArtifactOrigin.LocalClasspathEntry("/plugins/example.jar")

    private fun digest(seed: Char): Digest = Digest("sha256:" + seed.toString().repeat(64))

    @Test
    @DisplayName("a plugin whose measured bytes disagree with its declared digest is refused")
    fun refusesWhenMeasuredBytesDisagreeWithTheDeclaration() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = digest('a'),
            measuredDigest = digest('b'),
            origin = origin,
        )

        val refusal = PluginAdmissionGate.measuredVsDeclaredRefusal("com.example:plugin", identity)

        assertEquals(
            AdmissionRefusal.RefusedDigestMismatch(
                identity = "com.example:plugin",
                declared = digest('a'),
                measured = digest('b'),
            ),
            refusal,
        )
    }

    @Test
    @DisplayName("the refusal carries the plugin identity and BOTH digests, so a report can name what lied")
    fun refusalCarriesIdentityAndBothDigests() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = digest('a'),
            measuredDigest = digest('b'),
            origin = origin,
        )

        val refusal = PluginAdmissionGate.measuredVsDeclaredRefusal("com.example:plugin", identity)

        // Asserting on the payload, not just on "it is not null": a refusal that dropped
        // the measured digest would leave an operator unable to tell which artifact to
        // quarantine, and would still satisfy any `assertNotNull` on this decision.
        assertEquals("com.example:plugin", refusal?.identity)
        assertEquals(digest('a'), refusal?.declared)
        assertEquals(digest('b'), refusal?.measured)
    }

    @Test
    @DisplayName("equal digests do not contradict, so this decision stays silent")
    fun silentWhenMeasuredBytesAgreeWithTheDeclaration() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = digest('a'),
            measuredDigest = digest('a'),
            origin = origin,
        )

        assertNull(PluginAdmissionGate.measuredVsDeclaredRefusal("com.example:plugin", identity))
    }

    @Test
    @DisplayName("digest comparison is case-insensitive, so an uppercase hex claim is not a mismatch")
    fun comparisonIsCaseInsensitive() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = Digest("sha256:" + "A".repeat(64)),
            measuredDigest = Digest("sha256:" + "a".repeat(64)),
            origin = origin,
        )

        // Both digests are hex, and hex has no case. Treating a differently-cased but
        // identical digest as a mismatch would refuse plugins whose manifests are
        // otherwise sound — a false refusal that trains operators to distrust the gate.
        assertNull(PluginAdmissionGate.measuredVsDeclaredRefusal("com.example:plugin", identity))
    }

    @Test
    @DisplayName("an unmeasured artifact is NOT a pass: it is silent here and refused further down")
    fun unmeasuredIsNotTreatedAsAgreement() {
        val identity = MeasuredArtifactIdentity(
            declaredDigest = digest('a'),
            measuredDigest = null,
            origin = origin,
        )

        // This decision answering null does not mean admitted. It means "the bytes did
        // not contradict the claim" — and nobody measured, so nothing was compared. The
        // caller turns this into Unverified downstream. Asserting only `assertNull` here
        // would pass even if the whole comparison were deleted, which is exactly why the
        // two refusal tests above are the ones that guard the guarantee.
        assertNull(PluginAdmissionGate.measuredVsDeclaredRefusal("com.example:plugin", identity))
    }
}
