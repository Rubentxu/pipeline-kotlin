package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The publisher's detached signature over an artifact's bytes.
 *
 * ## Why a detached signature, and not a signature inside the artifact
 * The artifact's digest is what the operator pins, and the artifact is a file. A signature stored
 * INSIDE that file would change the file, so the digest would have to cover bytes that cannot
 * exist yet: the signature would have to sign the file without the signature, and the file
 * without the signature is not the file that gets verified. The circle closes only by weakening
 * the claim. A detached signature breaks it: the publisher signs the digest of the artifact as
 * shipped, the signature travels beside it, and the bytes the operator pinned are the bytes that
 * were signed.
 *
 * ## What the signature covers
 * The RAW bytes of the SHA-256 digest the runtime measured — thirty-two bytes, not the hex text.
 * Signing the hex text would work too and would be less convenient to produce with standard
 * tooling; signing the digest is what a checksum file does, and it is what [openssl] does with
 * `pkeyutl -sign -rawin` over the output of `dgst -sha256 -binary`.
 *
 * ## What this file does NOT establish
 * A valid signature proves that whoever holds the private key signed these bytes. It says nothing
 * about whether that key belongs to the publisher the operator had in mind — that is what the
 * operator's own file says, and what the trust row binds to the key id. The two halves together
 * are the anchor: the operator's file names the publisher and the key, and the signature proves
 * the bytes carry that key's endorsement.
 */
data class PluginArtifactSignature(
    /** The key that signed. Matched against the key id the operator's row names. */
    val keyId: String,
    /** The signature algorithm. Only Ed25519 is accepted; see [PluginArtifactSignatureCodec]. */
    val algorithm: String,
    /** The signature bytes, base64. */
    val signature: String,
    /** The digest the publisher claims to have signed. Diagnostics; the runtime verifies against its own measurement. */
    val digest: Digest?,
)

/**
 * Reads the publisher's detached signature.
 *
 * ## The sidecar's place
 * The signature of an artifact lives at `<artifact>.sig`, beside the artifact. Nothing else is
 * looked for: a signature inside a JAR would be a signature over bytes that do not exist yet, and
 * a signature at a configured path would let the operator point the runtime at a signature that
 * belongs to a different artifact.
 */
object PluginArtifactSignatureCodec {

    /** The suffix that turns an artifact path into its signature path. */
    const val SIDECAR_SUFFIX: String = ".sig"

    /** The algorithm this runtime verifies. A signature naming another one is not read. */
    const val ALGORITHM: String = "Ed25519"

    /**
     * The signature path that belongs to [artifact].
     */
    fun sidecarOf(artifact: Path): Path = artifact.resolveSibling(artifact.fileName.toString() + SIDECAR_SUFFIX)

    /**
     * Read a signature document.
     *
     * @return the signature, or `null` when the document is not one this runtime reads: not an
     *   object, missing a key id, missing the signature, naming an algorithm this runtime does not
     *   verify, or carrying bytes that are not base64. A document that cannot be read is a
     *   MISSING signature, which the trust decision refuses; it is never a signature that verifies.
     */
    fun decode(text: String): PluginArtifactSignature? {
        val obj = MinimalJson.parseObject(text) ?: return null
        val keyId = obj["keyId"] as? String ?: return null
        if (keyId.isBlank()) return null
        val algorithm = obj["algorithm"] as? String ?: return null
        if (!algorithm.equals(ALGORITHM, ignoreCase = true)) return null
        val signature = obj["signature"] as? String ?: return null
        if (decodeBase64(signature) == null) return null
        val digest = (obj["digest"] as? String)?.let { raw ->
            runCatching { Digest(raw) }.getOrNull()
        }
        return PluginArtifactSignature(
            keyId = keyId,
            algorithm = algorithm,
            signature = signature,
            digest = digest,
        )
    }

    private fun decodeBase64(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/**
 * Verifies an Ed25519 signature over a message.
 *
 * ## Why this is a separate, tiny, pure thing
 * The verification is the only cryptographic claim this runtime makes about a publisher, and it
 * is the part worth pinning down: it takes bytes in and answers a question, with no file, no
 * clock, no network and no classloader. A test can therefore ask it the only question that
 * matters — does this signature verify under this key over these bytes, and does it stop
 * verifying when any of the three changes — without building an artifact, a keystore or a
 * process.
 *
 * ## The key format
 * X.509 SubjectPublicKeyInfo, DER, base64 in the operator's keyring file. That is what
 * `openssl pkey -pubout -outform DER` produces and what the JDK's `X509EncodedKeySpec` reads, so
 * the operator needs no tool this runtime does not already have.
 */
object Ed25519SignatureVerifier {

    /**
     * @param publicKeySpki the signer's public key, X.509 SubjectPublicKeyInfo, DER.
     * @param message the bytes that were signed — here, the raw digest bytes.
     * @param signature the signature bytes.
     * @return true when the signature verifies under the key over the message. A key this
     *   runtime cannot read, a signature that is not Ed25519, or a signature that simply does not
     *   match all answer false: an unverifiable signature is not a verified one.
     */
    fun verify(publicKeySpki: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val key = keyFactory.generatePublic(X509EncodedKeySpec(publicKeySpki))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(signature)
    } catch (e: java.security.GeneralSecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * The raw bytes behind a digest.
 *
 * A digest is carried as `sha256:<hex>`; a signature is made over the thirty-two bytes those
 * hex characters stand for. Decoding is pure, and a digest that is not a SHA-256 of the right
 * length has no bytes to sign — which is a refusal, not a crash.
 */
fun Digest.rawBytes(): ByteArray? {
    val hex = value.removePrefix("sha256:")
    if (hex.length != 64) return null
    val out = ByteArray(32)
    for (i in 0 until 32) {
        val high = hex[i * 2].digitToIntOrNull(16) ?: return null
        val low = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
