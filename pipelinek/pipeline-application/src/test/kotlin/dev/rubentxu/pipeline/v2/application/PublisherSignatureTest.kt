package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * The publisher's signature, observed where it is read and verified.
 *
 * Two things have to be true for a publisher's endorsement to mean anything: the document that
 * carries it has to be read as the operator expects, and the signature has to stop verifying the
 * moment the bytes change. Both are checked here over values in memory — no artifact, no keyring
 * file, no process.
 */
class PublisherSignatureTest {

    private val digest = Digest("sha256:" + "a".repeat(64))

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(message)
        return signer.sign()
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    @DisplayName("the signature of an artifact lives beside the artifact")
    fun theSignatureLivesBesideTheArtifact() {
        assertEquals(
            Path.of("/plugins/uppercase.jar.sig"),
            PluginArtifactSignatureCodec.sidecarOf(Path.of("/plugins/uppercase.jar")),
        )
    }

    @Test
    @DisplayName("a well-formed signature document is read")
    fun aWellFormedSignatureDocumentIsRead() {
        val text = """
            {
              "schemaVersion": 1,
              "keyId": "example-key",
              "algorithm": "Ed25519",
              "signature": "${base64(ByteArray(64))}",
              "digest": "sha256:${"a".repeat(64)}"
            }
        """.trimIndent()

        val signature = PluginArtifactSignatureCodec.decode(text)

        assertNotNull(signature)
        assertEquals("example-key", signature!!.keyId)
        assertEquals("Ed25519", signature.algorithm)
        assertEquals(digest, signature.digest)
    }

    @Test
    @DisplayName("a document that is not a signature is not read as one")
    fun aDocumentThatIsNotASignatureIsNotReadAsOne() {
        // Each of these is a document a publisher might plausibly write, and none of them is a
        // signature this runtime can verify. A reader that accepted them would turn "the
        // publisher did not sign this" into "the publisher signed this, and here is the proof".
        val unreadable = listOf(
            "",
            "not json at all",
            "{}",
            """{"keyId":"example-key","algorithm":"RSA","signature":"AAAA"}""",
            """{"keyId":"example-key","algorithm":"Ed25519"}""",
            """{"algorithm":"Ed25519","signature":"AAAA"}""",
            """{"keyId":"","algorithm":"Ed25519","signature":"AAAA"}""",
            """{"keyId":"example-key","algorithm":"Ed25519","signature":"not base64!!"}""",
        )

        for (text in unreadable) {
            assertNull(PluginArtifactSignatureCodec.decode(text), "should not have read: $text")
        }
    }

    @Test
    @DisplayName("a signature verifies over the bytes it signed, and over nothing else")
    fun aSignatureVerifiesOverTheBytesItSigned() {
        val pair = keyPair()
        val message = digest.rawBytes()
        assertNotNull(message)
        val signature = sign(pair.private, message!!)

        assertTrue(
            Ed25519SignatureVerifier.verify(pair.public.encoded, message, signature),
            "the signature must verify over the bytes it signed",
        )
        assertFalse(
            Ed25519SignatureVerifier.verify(pair.public.encoded, ByteArray(32), signature),
            "the same signature must not verify over other bytes",
        )
    }

    @Test
    @DisplayName("a signature does not verify under a key that did not make it")
    fun aSignatureDoesNotVerifyUnderAnotherKey() {
        val signer = keyPair()
        val other = keyPair()
        val message = digest.rawBytes()!!
        val signature = sign(signer.private, message)

        assertFalse(
            Ed25519SignatureVerifier.verify(other.public.encoded, message, signature),
            "a signature must not verify under a key that did not make it",
        )
    }

    @Test
    @DisplayName("a key this runtime cannot read does not verify anything")
    fun anUnreadableKeyVerifiesNothing() {
        val pair = keyPair()
        val message = digest.rawBytes()!!
        val signature = sign(pair.private, message)

        assertFalse(
            Ed25519SignatureVerifier.verify(byteArrayOf(1, 2, 3), message, signature),
            "a key that is not a public key must not verify a signature",
        )
        assertFalse(
            Ed25519SignatureVerifier.verify(pair.public.encoded, message, byteArrayOf(9)),
            "a signature that is not a signature must not verify",
        )
    }

    @Test
    @DisplayName("a digest carries the thirty-two bytes a signature is made over")
    fun aDigestCarriesTheBytesASignatureIsMadeOver() {
        val raw = digest.rawBytes()

        assertNotNull(raw)
        assertEquals(32, raw!!.size)
        // The bytes a signature covers are the digest's bytes, not the hex text: signing the text
        // would be a second convention for the same quantity.
        assertEquals(0xaa.toByte(), raw[0])
        assertEquals(0xaa.toByte(), raw[31])
    }

    @Test
    @DisplayName("a digest that is not a SHA-256 never becomes one")
    fun aDigestThatIsNotASha256NeverBecomesOne() {
        // The digest type refuses a malformed value at construction, so the bytes a signature is
        // made over always exist and always are thirty-two of them. A digest that could be
        // malformed would be a digest whose signature covered the wrong thing.
        assertThrows(IllegalArgumentException::class.java) { Digest("sha256:zzzz") }
        assertThrows(IllegalArgumentException::class.java) { Digest("sha256:abcd") }
        assertThrows(IllegalArgumentException::class.java) { Digest("md5:${"a".repeat(64)}") }
    }
}
