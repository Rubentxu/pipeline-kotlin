package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * The operator's trust decision, observed where it is decided.
 *
 * The runtime admits a plugin the operator brought only when the bytes are the ones the operator
 * pinned AND the publisher is the one they named AND that publisher signed those bytes. Each of
 * those three can fail on its own, and each failure has a different remedy, so the decision is
 * exercised over values in memory: no artifact, no keyring, no file, no process.
 *
 * What this cannot say is that the composition root asks the question before loading anything.
 * That is what the executable examples are for, and they ask it of the shipped binary.
 */
class PluginTrustDecisionTest {

    private val pluginId = "v1:plugin:example-uppercase-plugin/plugin/uppercase"
    private val releaseVersion = SemVer(0, 1, 0)
    private val publisher = "example-uppercase"
    private val measured = Digest("sha256:" + "a".repeat(64))
    private val otherBytes = Digest("sha256:" + "b".repeat(64))

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(message)
        return signer.sign()
    }

    private fun publicKeyBase64(pair: KeyPair): String =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun signatureOver(pair: KeyPair, digest: Digest, keyId: String = "example-key"): PluginArtifactSignature {
        val bytes = digest.rawBytes() ?: error("the digest is not a SHA-256 this test can sign over")
        return PluginArtifactSignature(
            keyId = keyId,
            algorithm = PluginArtifactSignatureCodec.ALGORITHM,
            signature = Base64.getEncoder().encodeToString(sign(pair.private, bytes)),
            digest = digest,
        )
    }

    private fun facts(
        row: OperatorTrustDatabase.Row? = OperatorTrustDatabase.Row(
            digest = measured,
            publisher = publisher,
            signed = true,
            signedBy = "example-key",
        ),
        declaredPublisher: String = publisher,
        signature: PluginArtifactSignature? = null,
        publicKey: String? = null,
        measuredDigest: Digest = measured,
    ) = PluginTrustFacts(
        pluginId = pluginId,
        releaseVersion = releaseVersion,
        declaredPublisher = declaredPublisher,
        measuredDigest = measuredDigest,
        operatorRow = row,
        signature = signature,
        publisherPublicKey = publicKey,
    )

    private fun refusalOf(decision: PluginTrustDecision): AdmissionRefusal {
        assertTrue(decision is PluginTrustDecision.Refused, "expected a refusal, got $decision")
        return (decision as PluginTrustDecision.Refused).failure.refusal
    }

    private fun kindOf(decision: PluginTrustDecision): PluginTrustFailureKind {
        assertTrue(decision is PluginTrustDecision.Refused, "expected a refusal, got $decision")
        return (decision as PluginTrustDecision.Refused).failure.kind
    }

    @Test
    @DisplayName("a release the operator has not approved is refused, not admitted on the manifest's word")
    fun aReleaseTheOperatorHasNotApprovedIsRefused() {
        val decision = PluginTrustDecider.decide(facts(row = null))

        assertEquals(PluginTrustFailureKind.NoOperatorRow, kindOf(decision))
        assertEquals(
            AdmissionRefusal.RefusedUntrustedIdentity(
                identity = pluginId,
                reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
            ),
            refusalOf(decision),
        )
    }

    @Test
    @DisplayName("bytes that are not the ones the operator pinned are refused, naming both digests")
    fun bytesThatAreNotTheOnesPinnedAreRefused() {
        val decision = PluginTrustDecider.decide(facts(measuredDigest = otherBytes))

        assertEquals(PluginTrustFailureKind.DigestMismatch, kindOf(decision))
        // The refusal carries both digests: an operator who is told only that "something did not
        // match" cannot tell which file to quarantine.
        assertEquals(
            AdmissionRefusal.RefusedDigestMismatch(
                identity = pluginId,
                declared = measured,
                measured = otherBytes,
            ),
            refusalOf(decision),
        )
    }

    @Test
    @DisplayName("the operator's digest written in a different hex case is the same digest")
    fun theOperatorsDigestInAnotherCaseIsTheSameDigest() {
        val row = OperatorTrustDatabase.Row(
            digest = Digest("sha256:" + "A".repeat(64)),
            publisher = publisher,
            signed = true,
            signedBy = "example-key",
        )

        val decision = PluginTrustDecider.decide(facts(row = row))

        // Not a refusal about the digest: hex has no case, and a policy that refused an operator
        // for writing their digest in capital letters would be a policy nobody could use. The
        // decision gets as far as the missing signature, which is the next anchor in line.
        assertEquals(PluginTrustFailureKind.SignatureMissing, kindOf(decision))
    }

    @Test
    @DisplayName("a publisher the operator did not approve is refused")
    fun aPublisherTheOperatorDidNotApproveIsRefused() {
        val decision = PluginTrustDecider.decide(facts(declaredPublisher = "someone-else"))

        assertEquals(PluginTrustFailureKind.PublisherMismatch, kindOf(decision))
        assertEquals(
            AdmissionRefusal.RefusedUntrustedIdentity(
                identity = pluginId,
                reason = TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy,
            ),
            refusalOf(decision),
        )
    }

    @Test
    @DisplayName("a release the operator did not record as signed is refused")
    fun aReleaseTheOperatorDidNotRecordAsSignedIsRefused() {
        val row = OperatorTrustDatabase.Row(
            digest = measured,
            publisher = publisher,
            signed = false,
            signedBy = null,
        )

        val decision = PluginTrustDecider.decide(facts(row = row))

        assertEquals(PluginTrustFailureKind.PublisherNotSigned, kindOf(decision))
    }

    @Test
    @DisplayName("an artifact with no signature is refused: an unverified publisher is not a verified one")
    fun anArtifactWithNoSignatureIsRefused() {
        val pair = keyPair()

        val decision = PluginTrustDecider.decide(
            facts(signature = null, publicKey = publicKeyBase64(pair)),
        )

        assertEquals(PluginTrustFailureKind.SignatureMissing, kindOf(decision))
    }

    @Test
    @DisplayName("a signature made by a key the operator did not name is refused")
    fun aSignatureByAnotherKeyIsRefused() {
        val pair = keyPair()

        val decision = PluginTrustDecider.decide(
            facts(
                signature = signatureOver(pair, measured, keyId = "another-key"),
                publicKey = publicKeyBase64(pair),
            ),
        )

        assertEquals(PluginTrustFailureKind.SignatureKeyMismatch, kindOf(decision))
    }

    @Test
    @DisplayName("a key the operator does not keep is refused, not assumed")
    fun aKeyTheOperatorDoesNotKeepIsRefused() {
        val pair = keyPair()

        val decision = PluginTrustDecider.decide(
            facts(signature = signatureOver(pair, measured), publicKey = null),
        )

        assertEquals(PluginTrustFailureKind.PublisherKeyMissing, kindOf(decision))
    }

    @Test
    @DisplayName("a signature that does not verify over the measured bytes is refused")
    fun aSignatureThatDoesNotVerifyIsRefused() {
        val pair = keyPair()

        // The publisher signed the OTHER bytes. The bytes on disk are the ones the runtime
        // measured, and the signature says nothing about them.
        val decision = PluginTrustDecider.decide(
            facts(
                signature = signatureOver(pair, otherBytes),
                publicKey = publicKeyBase64(pair),
            ),
        )

        assertEquals(PluginTrustFailureKind.SignatureInvalid, kindOf(decision))
    }

    @Test
    @DisplayName("the approved bytes, signed by the approved publisher, are trusted")
    fun theApprovedBytesSignedByTheApprovedPublisherAreTrusted() {
        val pair = keyPair()

        val decision = PluginTrustDecider.decide(
            facts(
                signature = signatureOver(pair, measured),
                publicKey = publicKeyBase64(pair),
            ),
        )

        assertEquals(
            PluginTrustDecision.Trusted(
                digest = measured,
                publisher = publisher,
                keyId = "example-key",
            ),
            decision,
        )
    }

    @Test
    @DisplayName("a signature under a different key does not verify, even over the right bytes")
    fun aSignatureUnderAnotherKeyDoesNotVerify() {
        val signer = keyPair()
        val other = keyPair()

        val decision = PluginTrustDecider.decide(
            facts(
                signature = signatureOver(signer, measured),
                publicKey = publicKeyBase64(other),
            ),
        )

        assertEquals(PluginTrustFailureKind.SignatureInvalid, kindOf(decision))
    }
}
