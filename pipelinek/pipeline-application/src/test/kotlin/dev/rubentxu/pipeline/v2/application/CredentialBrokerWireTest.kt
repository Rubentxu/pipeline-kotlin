package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The words a run and its credential broker say to each other.
 *
 * ## The guarantee under trial
 *
 * That the line a run sends and the line the broker answers are read back as
 * what was written, and that anything else is read as NOTHING: a line that is
 * not the contract is never half-understood, and the refusal to understand it
 * never repeats what it was given.
 *
 * ## The capacity this serves
 *
 * The broker hands a secret to a process through a private socket. The wire is
 * the only place where that secret is a line of text, so it is the place where
 * the secret could be printed by accident: in a message that explains a
 * refusal, in the `toString` of a value, in the reading of a line that was
 * almost right.
 *
 * ## Why this instrument
 *
 * The wire is PURE — text in, value out — so the cheapest thing that can fail
 * is to encode a line and decode it, and to decode lines that are wrong.
 *
 * ## What this does not freeze
 *
 * The exact bytes of a line beyond what a reader must find in it, and the order
 * of its fields.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialBrokerWireTest {

    @Test
    fun `a request is read back as it was written`() {
        val line = CredentialBrokerWire.encodeRequest(TOKEN, "deploy-key", BrokeredCredentialKind.STRING)

        val reading = CredentialBrokerWire.decodeRequest(line)

        val read = assertInstanceOf(CredentialBrokerWire.RequestReading.Read::class.java, reading)
        assertEquals(TOKEN, read.request.token)
        assertEquals("deploy-key", read.request.credentialId)
        assertEquals("string", read.request.kindWord)
    }

    @Test
    fun `a request names its schema and is one line`() {
        val line = CredentialBrokerWire.encodeRequest(TOKEN, "deploy-key", BrokeredCredentialKind.STRING)

        assertTrue(line.contains(CredentialBrokerWire.SCHEMA), "the line says which contract it speaks: $line")
        assertFalse(line.contains('\n'), "a request is one line: the reader stops at the first newline")
    }

    @Test
    fun `a request for a kind the broker does not carry is still read, so the broker can say why it refuses`() {
        val line = """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","credentialId":"a","kind":"file"}"""

        val reading = CredentialBrokerWire.decodeRequest(line)

        val read = assertInstanceOf(CredentialBrokerWire.RequestReading.Read::class.java, reading)
        assertEquals("file", read.request.kindWord, "the kind travels as the word it was written with")
    }

    @Test
    fun `a line that is not the request is read as nothing`() {
        val lines = listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"schema":"pipelinek.credentials/2","token":"$TOKEN","op":"resolve","credentialId":"a","kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"list","credentialId":"a","kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","op":"resolve","credentialId":"a","kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","credentialId":"a"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":7,"op":"resolve","credentialId":"a","kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","token":"$TOKEN","op":"resolve","credentialId":"a","kind":"string""",
        )

        for (line in lines) {
            assertEquals(
                CredentialBrokerWire.RequestReading.Unreadable,
                CredentialBrokerWire.decodeRequest(line),
                "'$line' is not a request of this contract",
            )
        }
    }

    @Test
    fun `a granted answer carries the bytes of the secret and they come back identical`() {
        // Bytes that a text encoding would damage: a NUL, a byte above 0x7f and a newline.
        val secret = byteArrayOf(0x73, 0x00, 0x0A, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x20)

        val line = CredentialBrokerWire.encodeGranted(BrokeredCredentialKind.STRING, secret)
        val reading = CredentialBrokerWire.decodeResponse(line)

        assertFalse(line.contains('\n'), "an answer is one line, whatever the secret holds")
        val granted = assertInstanceOf(CredentialBrokerWire.ResponseReading.Granted::class.java, reading)
        assertEquals(BrokeredCredentialKind.STRING, granted.kind)
        assertArrayEquals(secret, granted.secret, "the bytes arrive as they left")
    }

    @Test
    fun `every refusal is read back as the refusal that was written`() {
        for (refusal in CredentialBrokerRefusal.entries) {
            val line = CredentialBrokerWire.encodeRefused(refusal)

            val reading = CredentialBrokerWire.decodeResponse(line)

            val refused = assertInstanceOf(CredentialBrokerWire.ResponseReading.Refused::class.java, reading)
            assertEquals(refusal, refused.refusal, "'${refusal.word}' survives the wire")
        }
    }

    @Test
    fun `a refusal word is a fixed word and nothing else can be said`() {
        val words = CredentialBrokerRefusal.entries.map { it.word }

        assertEquals(words.size, words.toSet().size, "two refusals would be one word")
        for (word in words) {
            assertTrue(Regex("[a-z]+(-[a-z]+)*").matches(word), "'$word' is a word an operator can search for")
            assertEquals(CredentialBrokerRefusal.ofWord(word)?.word, word)
        }
        assertEquals(null, CredentialBrokerRefusal.ofWord("deploy-key"), "a word the broker does not say is not a refusal")
    }

    @Test
    fun `an answer that is not the contract is read as nothing and says nothing of what it held`() {
        val secretLooking = "s3cr3t-value-that-must-not-travel"
        val lines = listOf(
            "",
            "not json",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"string","value":"$secretLooking"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"string"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"file","value":"AAAA"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":false,"refusal":"$secretLooking"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":false}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"string","value":"AAAA","refusal":"broker-failed"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":false,"refusal":"broker-failed","value":"AAAA"}""",
            """{"schema":"pipelinek.credentials/9","ok":false,"refusal":"broker-failed"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":"true","kind":"string","value":"AAAA"}""",
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"string","value":"not base64 !!"}""",
            CredentialBrokerWire.encodeGranted(BrokeredCredentialKind.STRING, ByteArray(CredentialBrokerWire.MAX_SECRET_BYTES + 1)),
            """{"schema":"${CredentialBrokerWire.SCHEMA}","ok":true,"kind":"string","value":"$secretLooking""",
        )

        for (line in lines) {
            val reading = CredentialBrokerWire.decodeResponse(line)

            assertEquals(CredentialBrokerWire.ResponseReading.Unreadable, reading, "'$line' is not an answer of this contract")
            assertFalse(reading.toString().contains(secretLooking), "the reading never repeats what it was given")
        }
    }

    @Test
    fun `no value prints the token or the secret it holds`() {
        val secret = "plain-secret-text".toByteArray()

        val request = (CredentialBrokerWire.decodeRequest(
            CredentialBrokerWire.encodeRequest(TOKEN, "deploy-key", BrokeredCredentialKind.STRING),
        ) as CredentialBrokerWire.RequestReading.Read).request
        val granted = CredentialBrokerWire.decodeResponse(
            CredentialBrokerWire.encodeGranted(BrokeredCredentialKind.STRING, secret),
        )

        assertFalse(request.toString().contains(TOKEN), "the session token is a credential of the run: ${request}")
        assertFalse(granted.toString().contains("plain-secret-text"), "the secret is never in a string: $granted")
        assertFalse(
            granted.toString().contains(java.util.Base64.getEncoder().encodeToString(secret)),
            "the encoded secret is the secret: $granted",
        )
    }

    @Test
    fun `the largest secret fits in the answer the reader accepts`() {
        val secret = ByteArray(CredentialBrokerWire.MAX_SECRET_BYTES) { 0xFF.toByte() }

        val line = CredentialBrokerWire.encodeGranted(BrokeredCredentialKind.STRING, secret)

        assertTrue(
            line.toByteArray(Charsets.UTF_8).size <= CredentialBrokerWire.MAX_RESPONSE_LINE_BYTES,
            "a secret the broker may send is an answer the run may read",
        )
    }

    @Test
    fun `the longest request the broker grants fits in the line it reads`() {
        val id = "k".repeat(CredentialGrant.MAX_CREDENTIAL_ID_LENGTH)
        val line = CredentialBrokerWire.encodeRequest(CredentialBrokerWire.newSessionToken(), id, BrokeredCredentialKind.STRING)

        assertTrue(
            line.toByteArray(Charsets.UTF_8).size <= CredentialBrokerWire.MAX_REQUEST_LINE_BYTES,
            "a credential the operator may grant is a request the broker may read",
        )
    }

    @Test
    fun `a session token is long, urlsafe and never the same twice`() {
        val first = CredentialBrokerWire.newSessionToken()
        val second = CredentialBrokerWire.newSessionToken()

        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first), "32 random bytes, urlsafe, no padding: $first")
        assertTrue(first != second, "two runs do not share a session")
    }

    private companion object {
        /** A token as the broker would issue one. */
        private const val TOKEN: String = "dG9rZW4tZGUtcHJ1ZWJhLTAxMjM0NTY3ODlhYmNkZWY"
    }
}
