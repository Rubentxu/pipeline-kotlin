package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.AdmissionRefusal
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import java.util.Base64

/**
 * The facts the operator's trust decision is made over.
 *
 * ## Why these facts and not the artifact
 * Everything here is a value somebody else produced: the manifest's declaration of who published
 * the plugin, the bytes the runtime measured, the row the operator wrote, the signature the
 * publisher detached, the public key the operator keeps. None of them is a file, a classloader or
 * a clock, so the decision they feed can be asked about without touching a disk — which is what
 * makes it worth pinning down separately from the reading.
 *
 * ## Why the measured digest is a fact and not an argument
 * [measuredDigest] is the digest the runtime computed over the artifact's bytes. It is the same
 * magnitude the operator pinned in their row, so the two can be compared. The manifest's own
 * `releaseDigest` is a DIFFERENT magnitude — a digest of the plugin's payload — and it is not
 * here on purpose: comparing it with the artifact's bytes accuses every plugin of lying.
 */
data class PluginTrustFacts(
    /** The canonical plugin text, the key the operator's row is filed under. */
    val pluginId: String,
    /** The release the operator pinned. A downgrade is a different row, not the same one. */
    val releaseVersion: SemVer,
    /** The publisher the artifact's own manifest declares. */
    val declaredPublisher: String,
    /** The digest the runtime measured over the artifact's bytes. */
    val measuredDigest: Digest,
    /** The operator's row for `(pluginId, releaseVersion)`, or `null` when there is none. */
    val operatorRow: OperatorTrustDatabase.Row?,
    /** The publisher's detached signature, or `null` when the artifact carries none. */
    val signature: PluginArtifactSignature?,
    /** The publisher's public key, base64 X.509 SubjectPublicKeyInfo, or `null` when unknown. */
    val publisherPublicKey: String?,
)

/**
 * The outcome of the operator's trust decision.
 *
 * There is no third state: an artifact is either anchored in the operator's policy or it is
 * refused with a reason that names what did not hold. A warning path would recreate exactly the
 * fail-open behaviour the whole trust layer exists to end.
 */
sealed interface PluginTrustDecision {

    /** The artifact is the one the operator approved, signed by the publisher they named. */
    data class Trusted(val digest: Digest, val publisher: String, val keyId: String) : PluginTrustDecision

    /** The artifact is refused. [failure] says which anchor did not hold. */
    data class Refused(val failure: PluginTrustFailure) : PluginTrustDecision
}

/**
 * Why the operator's trust decision refused an artifact.
 *
 * @property kind which anchor did not hold. Closed, because a refusal a caller has to read as
 *   prose is a refusal it cannot switch on.
 * @property detail the human sentence the operator reads. Diagnostics: the decision itself is
 *   carried by [kind] and by [refusal].
 * @property refusal the refusal the product returns, in the published vocabulary.
 */
data class PluginTrustFailure(
    val kind: PluginTrustFailureKind,
    val detail: String,
    val refusal: AdmissionRefusal,
)

/**
 * The anchors an artifact has to satisfy to be trusted.
 *
 * Each case is a distinct operational fact with a distinct remedy, which is why they are separate
 * cases rather than one "not trusted" with a message: "you have not approved this release" and
 * "the bytes are not the ones you approved" are answered by different actions.
 */
enum class PluginTrustFailureKind {
    /** The operator has no row for this `(plugin, release)`. */
    NoOperatorRow,

    /** The bytes the runtime measured are not the bytes the operator pinned. */
    DigestMismatch,

    /** The artifact declares a publisher the operator did not approve for it. */
    PublisherMismatch,

    /** The operator's row records no signed publisher for this release. */
    PublisherNotSigned,

    /** The artifact carries no detached signature. */
    SignatureMissing,

    /** The signature was made by a key the operator's row does not name. */
    SignatureKeyMismatch,

    /** The operator's keyring does not hold the key the row names. */
    PublisherKeyMissing,

    /** The signature does not verify over the measured bytes. */
    SignatureInvalid,
}

/**
 * THE decision on whether an artifact is the one the operator approved.
 *
 * ## What it decides
 * Two anchors, in this order:
 * 1. **The bytes.** The digest the operator pinned for `(plugin, release)` must equal the digest
 *    the runtime measured over the artifact's file. Same magnitude, so the comparison is
 *    meaningful: this is the anchor that makes a swapped JAR a refusal rather than a surprise.
 * 2. **The publisher.** The artifact's manifest must name the publisher the operator pinned, the
 *    operator's row must record that the release is signed, and a detached signature over the
 *    measured bytes must verify under the key the row names. A publisher identity that is only a
 *    string in the plugin's own manifest is not verifiable; a string the operator's file pins,
 *    bound to bytes the publisher signed, is.
 *
 * ## Why the byte anchor comes first
 * A mismatch in the bytes is the most specific failure and the most actionable one: it names two
 * digests and the artifact they were measured from. Checking the publisher first would answer a
 * question about the manifest while the bytes — the thing that will actually be loaded — are
 * still unverified.
 *
 * ## Why it is pure
 * Every input is a value, and the answer is a value. Nothing here reads a file, loads a class or
 * asks a clock. The I/O lives in [PluginTrustPolicy], which gathers the facts and calls this.
 */
object PluginTrustDecider {

    /**
     * Decide whether [facts] describes the artifact the operator approved.
     */
    fun decide(facts: PluginTrustFacts): PluginTrustDecision {
        val row = facts.operatorRow
            ?: return refuse(
                kind = PluginTrustFailureKind.NoOperatorRow,
                detail = "the operator has no trust row for ${facts.pluginId}@${facts.releaseVersion}, " +
                    "so these bytes are not approved by anybody",
                refusal = AdmissionRefusal.RefusedUntrustedIdentity(
                    identity = facts.pluginId,
                    reason = TrustMetadata.UnverifiedReason.NoTrustedDigestAvailable,
                ),
            )

        if (!row.digest.value.equals(facts.measuredDigest.value, ignoreCase = true)) {
            return refuse(
                kind = PluginTrustFailureKind.DigestMismatch,
                detail = "the operator approved ${row.digest.value} for ${facts.pluginId}@${facts.releaseVersion}, " +
                    "and these bytes measure ${facts.measuredDigest.value}",
                refusal = AdmissionRefusal.RefusedDigestMismatch(
                    identity = facts.pluginId,
                    declared = row.digest,
                    measured = facts.measuredDigest,
                ),
            )
        }

        if (!row.publisher.equals(facts.declaredPublisher, ignoreCase = true)) {
            return refuse(
                kind = PluginTrustFailureKind.PublisherMismatch,
                detail = "the operator approved publisher '${row.publisher}' for " +
                    "${facts.pluginId}@${facts.releaseVersion}, and this artifact declares '${facts.declaredPublisher}'",
                refusal = outsidePolicy(facts.pluginId),
            )
        }

        if (!row.signed || row.signedBy.isNullOrBlank()) {
            return refuse(
                kind = PluginTrustFailureKind.PublisherNotSigned,
                detail = "the operator's row for ${facts.pluginId}@${facts.releaseVersion} records no signed publisher, " +
                    "so the publisher's identity is not verifiable",
                refusal = outsidePolicy(facts.pluginId),
            )
        }

        val signature = facts.signature
            ?: return refuse(
                kind = PluginTrustFailureKind.SignatureMissing,
                detail = "the artifact carries no detached signature, so the publisher's endorsement of these " +
                    "bytes cannot be checked",
                refusal = outsidePolicy(facts.pluginId),
            )

        if (signature.keyId != row.signedBy) {
            return refuse(
                kind = PluginTrustFailureKind.SignatureKeyMismatch,
                detail = "the artifact was signed by '${signature.keyId}' and the operator approved the publisher " +
                    "key '${row.signedBy}'",
                refusal = outsidePolicy(facts.pluginId),
            )
        }

        val publicKey = facts.publisherPublicKey?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        }
            ?: return refuse(
                kind = PluginTrustFailureKind.PublisherKeyMissing,
                detail = "the operator's keyring holds no usable public key for '${row.signedBy}'",
                refusal = outsidePolicy(facts.pluginId),
            )

        val message = facts.measuredDigest.rawBytes()
            ?: return refuse(
                kind = PluginTrustFailureKind.SignatureInvalid,
                detail = "the measured digest ${facts.measuredDigest.value} is not a SHA-256 this runtime can sign over",
                refusal = outsidePolicy(facts.pluginId),
            )

        val signatureBytes = runCatching { Base64.getDecoder().decode(signature.signature) }.getOrNull()
        val verified = signatureBytes != null &&
            Ed25519SignatureVerifier.verify(publicKey, message, signatureBytes)

        if (!verified) {
            return refuse(
                kind = PluginTrustFailureKind.SignatureInvalid,
                detail = "the signature by '${signature.keyId}' does not verify over the measured bytes " +
                    "(${facts.measuredDigest.value}) under the operator's key",
                refusal = outsidePolicy(facts.pluginId),
            )
        }

        return PluginTrustDecision.Trusted(
            digest = facts.measuredDigest,
            publisher = row.publisher,
            keyId = row.signedBy,
        )
    }

    private fun outsidePolicy(identity: String): AdmissionRefusal =
        AdmissionRefusal.RefusedUntrustedIdentity(
            identity = identity,
            reason = TrustMetadata.UnverifiedReason.OutsideOperatorTrustPolicy,
        )

    private fun refuse(
        kind: PluginTrustFailureKind,
        detail: String,
        refusal: AdmissionRefusal,
    ): PluginTrustDecision.Refused = PluginTrustDecision.Refused(
        PluginTrustFailure(kind = kind, detail = detail, refusal = refusal),
    )
}
