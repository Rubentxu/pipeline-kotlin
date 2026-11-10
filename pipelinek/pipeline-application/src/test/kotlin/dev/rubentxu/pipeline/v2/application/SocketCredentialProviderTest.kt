package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.service.CredentialMaterialCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The provider a supervised run uses when its credentials come from the broker
 * of its service.
 *
 * ## The guarantee under trial
 *
 * That the run asks only when a step resolves a credential; that what it asks is
 * its own session, the name and the kind; that a refusal, a silence or an answer
 * that is not the contract becomes a failure made of fixed words and never of
 * what was read; and that no other source is ever consulted.
 *
 * ## The capacity this serves
 *
 * A step that enters `withCredentials` receives the secret only if the broker
 * delivers it. When the broker does not, the body is not entered and the result
 * says why without saying what the secret was.
 *
 * ## Why this instrument
 *
 * The provider is the translation of an answer line into a credential or a
 * failure, so it is tried with a link double that answers the lines the test
 * writes. The socket itself — connecting, giving up, a line that never ends — is
 * tried against a real socket in the integration lane.
 *
 * ## What this does not freeze
 *
 * The wording of the meanings of the refusals, which belongs to
 * [CredentialBrokerRefusal], and the deadline of the real link.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SocketCredentialProviderTest {

    @Test
    fun `building the provider asks nobody and a step that never resolves asks nobody`() {
        val link = LinkDouble { error("nobody was supposed to be asked") }

        val provider = SocketCredentialProvider(link, TOKEN)
        provider.close()

        assertEquals(SocketCredentialProvider.PROVIDER_ID, provider.providerId)
        assertTrue(link.requests.isEmpty(), "a run that never enters a scope with credentials never talks to the broker")
    }

    @Test
    fun `a granted answer becomes a secret text with the name that was asked and the bytes that were delivered`() {
        val link = LinkDouble { grantedText(SECRET) }
        val provider = SocketCredentialProvider(link, TOKEN)

        val credential = provider.resolveToCredential(CredentialsId("deploy-key"))

        val text = assertInstanceOf(SecretText::class.java, credential)
        assertEquals(CredentialsId("deploy-key"), text.id)
        assertArrayEquals(SECRET, text.bytes)
    }

    @Test
    fun `resolve hands the delivered bytes to the handle`() {
        val link = LinkDouble { grantedText(SECRET) }
        val provider = SocketCredentialProvider(link, TOKEN)

        val handle = provider.resolve(CredentialsId("deploy-key"))

        assertArrayEquals(SECRET, handle.bytesView())
    }

    @Test
    fun `the request carries the session of the run and the name asked for`() {
        val link = LinkDouble { grantedText(SECRET) }
        val provider = SocketCredentialProvider(link, TOKEN)

        provider.resolveToCredential(CredentialsId("Deploy-Key_01"))

        val line = link.requests.single()
        val read = assertInstanceOf(
            CredentialBrokerWire.RequestReading.Read::class.java,
            CredentialBrokerWire.decodeRequest(line),
        )
        assertEquals(TOKEN, read.request.token)
        assertEquals("Deploy-Key_01", read.request.credentialId, "the name travels as it was written, case included")
        assertFalse(line.contains('\n'), "a request is one line")
    }

    @Test
    fun `every refusal of the broker reaches the step as its own word and its own credential`() {
        for (refusal in CredentialBrokerRefusal.entries) {
            val link = LinkDouble { CredentialBrokerWire.encodeRefused(refusal) }
            val provider = SocketCredentialProvider(link, TOKEN)

            val failure = assertThrows<CredentialBrokerRefusedException>("the refusal ${refusal.word}") {
                provider.resolveToCredential(CredentialsId("deploy-key"))
            }

            assertEquals(refusal, failure.refusal)
            assertEquals("deploy-key", failure.credentialId)
            assertEquals(
                "credential 'deploy-key' was not delivered: ${refusal.word} (${refusal.meaning})",
                failure.message,
                "the message is the fixed words and the name",
            )
        }
    }

    @Test
    fun `an answer that is not the contract is an answer that is not readable and says nothing of what it held`() {
        val garbage = mapOf(
            "empty" to "",
            "not json" to "the secret is $SECRET_TEXT",
            "an array" to "[\"$SECRET_TEXT\"]",
            "another schema" to """{"schema":"other/1","status":"granted","kind":"string","secret":"$SECRET_TEXT"}""",
            "a granted answer that is not base64" to
                """{"schema":"${CredentialBrokerWire.SCHEMA}","status":"granted","kind":"string","secret":"$SECRET_TEXT %%%"}""",
        )

        for ((what, line) in garbage) {
            val provider = SocketCredentialProvider(LinkDouble { line }, TOKEN)

            val failure = assertThrows<CredentialBrokerRefusedException>(what) {
                provider.resolveToCredential(CredentialsId("deploy-key"))
            }

            assertEquals(CredentialBrokerRefusal.BROKER_ANSWER_NOT_READABLE, failure.refusal, what)
            assertFalse(failure.message.orEmpty().contains(SECRET_TEXT), "$what: the message repeats nothing of the line")
            assertNull(failure.cause, "$what: there is no cause to carry it")
        }
    }

    @Test
    fun `a link that fails with a message made of bytes is an unreachable broker without those bytes`() {
        val link = LinkDouble { throw IllegalStateException("read $SECRET_TEXT from the socket") }
        val provider = SocketCredentialProvider(link, TOKEN)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }

        assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        assertFalse(failure.message.orEmpty().contains(SECRET_TEXT))
        assertNull(failure.cause, "the exception that came first may have been built from the bytes")
    }

    @Test
    fun `a link that gives up keeps its own cause and gains the name of the credential`() {
        val link = LinkDouble { throw CredentialBrokerRefusedException(null, CredentialBrokerRefusal.BROKER_TIMED_OUT) }
        val provider = SocketCredentialProvider(link, TOKEN)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }

        assertEquals(CredentialBrokerRefusal.BROKER_TIMED_OUT, failure.refusal)
        assertEquals("deploy-key", failure.credentialId)
    }

    @Test
    fun `a run without a session asks nobody and is told the broker is unreachable`() {
        val link = LinkDouble { error("a run without a session must not talk to the broker") }
        val provider = SocketCredentialProvider(link, sessionToken = null)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            provider.resolveToCredential(CredentialsId("deploy-key"))
        }

        assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `each resolution is its own exchange and a refusal does not poison the next one`() {
        val answers = CopyOnWriteArrayList(
            listOf(
                CredentialBrokerWire.encodeRefused(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED),
                grantedText(SECRET),
            ),
        )
        val link = LinkDouble { answers.removeAt(0) }
        val provider = SocketCredentialProvider(link, TOKEN)

        assertThrows<CredentialBrokerRefusedException> { provider.resolveToCredential(CredentialsId("first")) }
        val second = provider.resolveToCredential(CredentialsId("second"))

        assertEquals(CredentialsId("second"), (second as SecretText).id)
        assertEquals(2, link.requests.size)
    }

    @Test
    fun `the token file is read once and deleted`(@TempDir directory: Path) {
        val file = directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)
        Files.writeString(file, TOKEN)

        val taken = SocketCredentialProvider.takeSessionToken(file)

        assertEquals(TOKEN, taken)
        assertFalse(Files.exists(file), "a step that looks in the directory later does not find the token")
        assertNull(SocketCredentialProvider.takeSessionToken(file), "the second look finds nothing")
    }

    @Test
    fun `a token file that is too large or not a regular file gives no session and leaves no file`(@TempDir directory: Path) {
        val big = directory.resolve("big")
        Files.writeString(big, "A".repeat(257))
        val exact = directory.resolve("exact")
        Files.writeString(exact, "A".repeat(256))
        val target = directory.resolve("target")
        Files.writeString(target, TOKEN)
        val link = directory.resolve("link")
        Files.createSymbolicLink(link, target)
        val folder = Files.createDirectory(directory.resolve("folder"))

        assertNull(SocketCredentialProvider.takeSessionToken(big), "one byte over the limit")
        assertEquals("A".repeat(256), SocketCredentialProvider.takeSessionToken(exact), "the limit itself is accepted")
        assertNull(SocketCredentialProvider.takeSessionToken(link), "a link is not followed")
        assertNull(SocketCredentialProvider.takeSessionToken(folder), "a directory is not a token")
        assertNull(SocketCredentialProvider.takeSessionToken(directory.resolve("missing")))
        assertFalse(Files.exists(big))
        assertFalse(Files.exists(exact))
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link is deleted")
        assertTrue(Files.exists(target), "what the link pointed at is not touched")
        assertFalse(Files.exists(folder))
    }

    /** A link that answers each request line with what [answer] builds and records the lines. */
    private class LinkDouble(private val answer: (String) -> String) : CredentialBrokerLink {
        val requests: MutableList<String> = CopyOnWriteArrayList()

        override fun exchange(requestLine: String): String {
            requests += requestLine
            return answer(requestLine)
        }
    }

    /**
     * The answer of a broker that hands out the secret [bytes].
     *
     * The answer carries the MATERIAL of the credential, which is the form that says what it is:
     * a run that received bare bytes would have to guess what they are.
     */
    private fun grantedText(bytes: ByteArray): String =
        CredentialBrokerWire.encodeGranted(
            BrokeredCredentialKind.STRING,
            CredentialMaterialCodec.encode(SecretText(CredentialsId("deploy-key"), bytes = bytes)),
        )

    private companion object {
        const val TOKEN = "Wm9uZS10b2tlbi1vZi10aGUtcnVuLTAxMjM0NTY3ODk"
        const val SECRET_TEXT = "s3cr3t-bytes"
        val SECRET: ByteArray = SECRET_TEXT.toByteArray()
    }
}
