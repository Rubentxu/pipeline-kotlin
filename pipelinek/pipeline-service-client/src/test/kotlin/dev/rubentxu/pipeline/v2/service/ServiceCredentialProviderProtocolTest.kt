package dev.rubentxu.pipeline.v2.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The wire of a credential provider session, and the typed client that speaks it.
 *
 * ## The guarantee under trial
 *
 * That every operation of a credential provider session is a NAMED request with NAMED bounds,
 * that a line which breaks a bound is refused as malformed before any rule of the service is
 * asked, that the secret an answer carries never reaches a diagnostic, and that the client turns
 * an answer into a typed fact or into a named negative and never into a wrong-shaped success.
 *
 * ## The capacity this serves
 *
 * A controller that keeps its secrets answers the credential a run asks for, when it asks, with
 * four calls: register, poll, answer and close. It must be able to tell a refusal about its
 * session from a refusal about a run without reading prose.
 *
 * ## Why this instrument
 *
 * The codec is a pure function of a line and the client talks to a faked channel: no socket, no
 * store and no clock. Whether the service honours the rules is observed against the real service.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServiceCredentialProviderProtocolTest {

    @Test
    fun `a registration decodes with the name of the provider and nothing else`() {
        val request = decoded(
            """{"schema":"pipelinek.service/2","op":"credential.provider.register","provider":"vault","auth":"token-1"}""",
        )

        assertEquals(ServiceRequest.CredentialRegister(ServiceProtocol.SCHEMA, "vault"), request)
    }

    @Test
    fun `a registration whose name is missing, empty or over the bound is refused as malformed`() {
        val malformed = listOf(
            """{"schema":"pipelinek.service/2","op":"credential.provider.register"}""",
            """{"schema":"pipelinek.service/2","op":"credential.provider.register","provider":""}""",
            """{"schema":"pipelinek.service/2","op":"credential.provider.register","provider":null}""",
            """{"schema":"pipelinek.service/2","op":"credential.provider.register",""" +
                """"provider":"${"p".repeat(ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH + 1)}"}""",
        )

        malformed.forEach { line ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(line),
                "this line is not a registration: $line",
            )
        }
    }

    @Test
    fun `a poll decodes with its wait and a poll without a wait does not wait`() {
        assertEquals(
            ServiceRequest.CredentialPoll(ServiceProtocol.SCHEMA, "session-1", 750L),
            decoded(
                """{"schema":"pipelinek.service/2","op":"credential.provider.poll","sessionId":"session-1","waitMillis":750}""",
            ),
        )
        assertEquals(
            ServiceRequest.CredentialPoll(ServiceProtocol.SCHEMA, "session-1", 0L),
            decoded("""{"schema":"pipelinek.service/2","op":"credential.provider.poll","sessionId":"session-1"}"""),
            "an absent wait is a poll that does not wait, and not a poll that waits for ever",
        )
    }

    @Test
    fun `a poll whose wait or session breaks the bound is refused as malformed`() {
        listOf("-1", "${ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS + 1}", "\"soon\"", "1.5").forEach { wait ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(
                    """{"schema":"pipelinek.service/2","op":"credential.provider.poll",""" +
                        """"sessionId":"session-1","waitMillis":$wait}""",
                ),
                "a wait of $wait is not a wait the protocol allows",
            )
        }
        assertEquals(
            ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
            ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"credential.provider.poll","waitMillis":10}"""),
            "a poll names the session it polls",
        )
    }

    @Test
    fun `a grant decodes with the value as the text it travels as and a denial decodes without one`() {
        val granted = decoded(
            """{"schema":"pipelinek.service/2","op":"credential.provider.answer","sessionId":"session-1",""" +
                """"requestId":"request-1","decision":"grant","value":"c2VjcmV0"}""",
        )
        val denied = decoded(
            """{"schema":"pipelinek.service/2","op":"credential.provider.answer","sessionId":"session-1",""" +
                """"requestId":"request-1","decision":"deny"}""",
        )

        assertEquals(
            ServiceRequest.CredentialAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", true, "c2VjcmV0"),
            granted,
        )
        assertEquals(
            ServiceRequest.CredentialAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", false, null),
            denied,
        )
    }

    @Test
    fun `an answer whose shape is wrong is refused as malformed`() {
        val head = """{"schema":"pipelinek.service/2","op":"credential.provider.answer","sessionId":"s","requestId":"q","""
        val malformed = listOf(
            // a third word: the run proceeds or it aborts, nothing in between
            """$head"decision":"maybe"}""",
            // no decision at all
            """$head"value":"c2VjcmV0"}""",
            // a grant that hands nothing over
            """$head"decision":"grant"}""",
            """$head"decision":"grant","value":""}""",
            // a denial that carries a secret: nothing hands a value over and refuses at the same time
            """$head"decision":"deny","value":"c2VjcmV0"}""",
            // a value that is not text: a number would be read as its digits
            """$head"decision":"grant","value":12345}""",
            """$head"decision":"grant","value":null}""",
            """$head"decision":"grant","value":["c2VjcmV0"]}""",
            // a value over the bound
            """$head"decision":"grant","value":"${"A".repeat(ServiceProtocol.MAX_CREDENTIAL_VALUE_ENCODED_LENGTH + 4)}"}""",
            // the identities of the answer
            """{"schema":"pipelinek.service/2","op":"credential.provider.answer","requestId":"q","decision":"deny"}""",
            """{"schema":"pipelinek.service/2","op":"credential.provider.answer","sessionId":"s","decision":"deny"}""",
        )

        malformed.forEach { line ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(line),
                "this line is not an answer: ${line.take(180)}",
            )
        }
    }

    @Test
    fun `a value of exactly the bound travels and its line fits in the line the service reads`() {
        val value = Base64.getEncoder().encodeToString(ByteArray(ServiceProtocol.MAX_CREDENTIAL_VALUE_BYTES) { 'x'.code.toByte() })
        val request = ServiceRequest.CredentialAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", true, value)

        val line = ServiceCodec.encode(request, "t".repeat(ServiceProtocol.MAX_TOKEN_LENGTH))

        assertEquals(ServiceProtocol.MAX_CREDENTIAL_VALUE_ENCODED_LENGTH, value.length)
        assertTrue(
            line.toByteArray(Charsets.UTF_8).size < ServiceProtocol.MAX_LINE_BYTES,
            "the biggest secret the protocol admits has to fit in the biggest line the service reads",
        )
        assertEquals(request, decoded(line))
    }

    @Test
    fun `a close decodes with the one name it needs`() {
        assertEquals(
            ServiceRequest.CredentialClose(ServiceProtocol.SCHEMA, "session-1"),
            decoded("""{"schema":"pipelinek.service/2","op":"credential.provider.close","sessionId":"session-1"}"""),
        )
        assertEquals(
            ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
            ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"credential.provider.close"}"""),
        )
    }

    @Test
    fun `a start names the credential provider as a plain name and one that names it badly is refused`() {
        val named = decoded(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","credentialProvider":"vault"}""",
        ) as ServiceRequest.StartRun

        assertEquals("vault", named.credentialProvider)
        assertEquals(
            null,
            (decoded("""{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts"}""")
                as ServiceRequest.StartRun).credentialProvider,
            "a start that names no provider asks nobody for any credential",
        )
        listOf(
            "\"credentialProvider\":\"\"",
            "\"credentialProvider\":7",
            "\"credentialProvider\":null",
            "\"credentialProvider\":{\"provider\":\"vault\"}",
            "\"credentialProvider\":[\"vault\"]",
            "\"credentialProvider\":\"${"p".repeat(ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH + 1)}\"",
        ).forEach { field ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(
                    """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",$field}""",
                ),
                "a credential provider that is not a name is refused as malformed: ${field.take(80)}",
            )
        }
    }

    @Test
    fun `every request of a credential provider session survives the trip through the wire`() {
        val requests = listOf(
            ServiceRequest.CredentialRegister(ServiceProtocol.SCHEMA, "vault"),
            ServiceRequest.CredentialPoll(ServiceProtocol.SCHEMA, "session-1", 250L),
            ServiceRequest.CredentialAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", true, "c2VjcmV0"),
            ServiceRequest.CredentialAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", false, null),
            ServiceRequest.CredentialClose(ServiceProtocol.SCHEMA, "session-1"),
            ServiceRequest.StartRun(
                schema = ServiceProtocol.SCHEMA,
                script = "build.kts",
                workspace = null,
                idempotencyKey = null,
                credentialProvider = "vault",
            ),
        )

        requests.forEach { request ->
            val line = ServiceCodec.encode(request, "token-1")
            val back = assertInstanceOf(ServiceDecoding.Decoded::class.java, ServiceCodec.decode(line))
            assertEquals(request, back.request, "what the client wrote is what the service reads: ${line.take(200)}")
            assertEquals("token-1", back.credential, "the credential travels with every operation")
        }
    }

    @Test
    fun `the request that carries a secret never renders it`() {
        val request = ServiceRequest.CredentialAnswer(
            ServiceProtocol.SCHEMA,
            "session-1",
            "request-1",
            true,
            "dmFsdWUtdGhhdC1tdXN0LW5vdC1sZWFr",
        )

        val text = request.toString()

        assertFalse(text.contains("dmFsdWUtdGhhdC1tdXN0LW5vdC1sZWFr"), "a diagnostic is not where a secret belongs: $text")
        assertTrue(text.contains("session-1") && text.contains("request-1"), "the identity of the answer is shown: $text")
        assertTrue(text.contains("grant"), "the decision is shown: $text")
    }

    @Test
    fun `the start request names the provider and nothing it could be confused with a secret`() {
        val text = ServiceRequest.StartRun(
            schema = ServiceProtocol.SCHEMA,
            script = "build.kts",
            workspace = null,
            idempotencyKey = null,
            credentialProvider = "vault",
        ).toString()

        assertTrue(text.contains("credentialProvider=vault"), text)
    }

    @Test
    fun `the refusal of a credential provider request comes back with its name and its reason`() {
        val refused = ServiceCodec.decodeLine(
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"credential-provider-refused",""" +
                """"reason":"provider-name-taken","detail":"a live session holds the name"}}""",
        )

        val line = assertInstanceOf(ServiceLine.Refused::class.java, refused)
        assertEquals(ServiceRefusal.CredentialProviderRefused("provider-name-taken", "a live session holds the name"), line.refusal)
        assertEquals("credential-provider-refused", line.refusal.kind)
    }

    @Test
    fun `the refusal survives the trip through the wire and one that omits a fact is not read`() {
        val refusal = ServiceRefusal.CredentialProviderRefused("request-unknown", "no request of this session waits")

        val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
        val back = assertInstanceOf(ServiceLine.Refused::class.java, ServiceCodec.decodeLine(line))

        assertEquals(refusal, back.refusal, "the client reads the refusal the service wrote: $line")
        listOf(
            """{"schema":"pipelinek.service/2","kind":"refused","refusal":{"kind":"credential-provider-refused","detail":"d"}}""",
            """{"schema":"pipelinek.service/2","kind":"refused","refusal":{"kind":"credential-provider-refused","reason":"r"}}""",
        ).forEach { incomplete ->
            assertEquals(null, ServiceCodec.decodeLine(incomplete), "a refusal without its facts is not read: $incomplete")
        }
    }

    @Test
    fun `the client registers a provider and reads the session it was given`() {
        val channel = FakeChannel(answered("""{"sessionId":"session-1","leaseMillis":15000}"""))

        val answer = client(channel).registerCredentialProvider("vault")

        assertEquals(
            ServiceProviderSession("session-1", 15000L),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        assertTrue(channel.written.single().contains("\"op\":\"credential.provider.register\""))
    }

    @Test
    fun `a registration answered without its session or its lease is not a success`() {
        listOf("""{"sessionId":"session-1"}""", """{"leaseMillis":15000}""", "{}").forEach { result ->
            assertInstanceOf(
                ServiceAnswer.Unreadable::class.java,
                client(FakeChannel(answered(result))).registerCredentialProvider("vault"),
                "a registration answered with $result",
            )
        }
    }

    @Test
    fun `the client tells an idle poll from a poll that carries a request`() {
        val idle = client(FakeChannel(answered("""{"state":"idle"}"""))).pollCredentialProvider("session-1", 100L)
        val asked = client(
            FakeChannel(
                answered(
                    """{"state":"asked","requestId":"request-1","correlationId":"corr-1",""" +
                        """"credentialId":"deploy-token","kind":"string"}""",
                ),
            ),
        ).pollCredentialProvider("session-1", 100L)

        assertEquals(ServiceCredentialPoll.Idle, assertInstanceOf(ServiceAnswer.Answered::class.java, idle).value)
        assertEquals(
            ServiceCredentialPoll.Asked("request-1", "corr-1", "deploy-token", "string"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, asked).value,
        )
    }

    @Test
    fun `a poll answered in a shape nobody asked for is not a success`() {
        val shapes = listOf(
            """{"state":"asked","correlationId":"c","credentialId":"d","kind":"string"}""",
            """{"state":"asked","requestId":"r","credentialId":"d","kind":"string"}""",
            """{"state":"asked","requestId":"r","correlationId":"c","kind":"string"}""",
            """{"state":"asked","requestId":"r","correlationId":"c","credentialId":"d"}""",
            """{"state":"later"}""",
            """{}""",
        )

        shapes.forEach { result ->
            assertInstanceOf(
                ServiceAnswer.Unreadable::class.java,
                client(FakeChannel(answered(result))).pollCredentialProvider("session-1", 100L),
                "a poll answered with $result",
            )
        }
    }

    @Test
    fun `the client hands a credential over as the text of the bytes and reads back the acknowledgement`() {
        val channel = FakeChannel(answered("""{"sessionId":"session-1","outcome":"answered"}"""))
        val secret = "the-secret-value".toByteArray(Charsets.UTF_8)

        val answer = client(channel).grantCredential("session-1", "request-1", secret)

        assertEquals(
            ServiceProviderAck("session-1", "answered"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        val line = channel.written.single()
        assertTrue(line.contains("\"decision\":\"grant\""), line)
        val decodedLine = decoded(line) as ServiceRequest.CredentialAnswer
        assertArrayEquals(secret, Base64.getDecoder().decode(decodedLine.value), "the bytes arrive as they left")
    }

    @Test
    fun `the client denies a credential without a value on the line`() {
        val channel = FakeChannel(answered("""{"sessionId":"session-1","outcome":"answered"}"""))

        client(channel).denyCredential("session-1", "request-1")

        val line = channel.written.single()
        assertTrue(line.contains("\"decision\":\"deny\""), line)
        assertFalse(line.contains("\"value\""), "a denial carries no value: $line")
    }

    @Test
    fun `the client closes a session and an acknowledgement without its outcome is not a success`() {
        val closed = client(FakeChannel(answered("""{"sessionId":"session-1","outcome":"closed"}""")))
            .closeCredentialProvider("session-1")
        val bare = client(FakeChannel(answered("""{"sessionId":"session-1"}""")))
            .closeCredentialProvider("session-1")

        assertEquals(
            ServiceProviderAck("session-1", "closed"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, closed).value,
        )
        assertInstanceOf(ServiceAnswer.Unreadable::class.java, bare)
    }

    @Test
    fun `the refusal of a credential provider request reaches the client with its name`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"credential-provider-refused",""" +
                    """"reason":"provider-name-taken","detail":"a live session holds the name"}}""",
            ),
        )

        val answer = client(channel).registerCredentialProvider("vault")

        assertEquals(
            ServiceRefusal.CredentialProviderRefused("provider-name-taken", "a live session holds the name"),
            assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal,
        )
    }

    @Test
    fun `a start that names a credential provider writes the name it was given`() {
        val channel = FakeChannel(
            answered("""{"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1","startedAtMs":1700}"""),
        )

        client(channel).startRun("build.kts", credentialProvider = "vault")

        assertTrue(
            channel.written.single().contains("\"credentialProvider\":\"vault\""),
            "the client wrote the name it was asked for",
        )
    }

    private fun decoded(line: String): ServiceRequest =
        assertInstanceOf(ServiceDecoding.Decoded::class.java, ServiceCodec.decode(line)).request

    /** The line of an answer whose result is [result]. */
    private fun answered(result: String): List<String> =
        listOf("""{"schema":"pipelinek.service/2","kind":"answer","result":$result}""")

    private fun client(channel: ServiceChannel): ServiceClient = ServiceClient(
        endpoint = Path.of("/tmp/service.sock"),
        credential = "token-1",
        deadline = null,
        schema = ServiceProtocol.SCHEMA,
        channels = { _, _ -> channel },
    )

    /** A connection that answers with the lines it was given. */
    private class FakeChannel(private val lines: List<String>) : ServiceChannel {

        /** The lines the client wrote. */
        val written = mutableListOf<String>()

        private var index = 0

        override fun write(line: String) {
            written += line
        }

        override fun readLine(): ServiceRead =
            if (index < lines.size) ServiceRead.Line(lines[index++]) else ServiceRead.Closed

        override fun close() = Unit
    }
}
