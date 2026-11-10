package dev.rubentxu.pipeline.v2.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The wire of a configuration provider session, and the typed client that speaks it.
 *
 * ## The guarantee under trial
 *
 * That every operation of a provider session is a NAMED request with NAMED bounds, that a line
 * which breaks a bound is refused as malformed before any rule of the service is asked, that the
 * values an answer carries never reach a diagnostic, and that the client turns an answer into a
 * typed fact or into a named negative and never into a wrong-shaped success.
 *
 * ## The capacity this serves
 *
 * A controller that supplies the configuration of a run does it with five calls —register, poll,
 * answer, close and read back what a run was started with— and has to be able to tell a refusal
 * about its session from one about its run without reading prose.
 *
 * ## Why this instrument
 *
 * The codec is a pure function of a line and the client talks to a faked channel: no socket, no
 * store and no clock. Whether the service honours the rules is observed against the real
 * service, in its own lane.
 *
 * ## What this does not freeze
 *
 * The sentences of a refusal: only the names travel on the wire, and the names are the contract.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServiceConfigurationProviderProtocolTest {

    @Test
    fun `a registration decodes with the keys it declares`() {
        val request = decoded(
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault",""" +
                """"keys":[{"name":"REGION","type":"string","sensitivity":"non-secret"},""" +
                """{"name":"REPLICAS","type":"integer","sensitivity":"non-secret"}],"auth":"token-1"}""",
        )

        assertEquals(
            ServiceRequest.ProviderRegister(
                ServiceProtocol.SCHEMA,
                "vault",
                listOf(
                    ServiceProviderKey("REGION", "string", "non-secret"),
                    ServiceProviderKey("REPLICAS", "integer", "non-secret"),
                ),
            ),
            request,
            "the request carries the name of the provider and the type and the label of each key",
        )
    }

    @Test
    fun `a registration whose shape is wrong is refused as malformed`() {
        val malformed = listOf(
            // no keys at all
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault"}""",
            // keys that are not an array
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault","keys":"A"}""",
            // a key with no label: the label is what keeps a secret out, so it is never defaulted
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault",""" +
                """"keys":[{"name":"REGION","type":"string"}]}""",
            // a key that is not an object
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"vault","keys":["A"]}""",
            // no name for the provider
            """{"schema":"pipelinek.service/2","op":"config.provider.register","keys":[]}""",
            // an empty name
            """{"schema":"pipelinek.service/2","op":"config.provider.register","provider":"","keys":[]}""",
            // a name over the bound
            """{"schema":"pipelinek.service/2","op":"config.provider.register",""" +
                """"provider":"${"p".repeat(ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH + 1)}","keys":[]}""",
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
            ServiceRequest.ProviderPoll(ServiceProtocol.SCHEMA, "session-1", 750L),
            decoded(
                """{"schema":"pipelinek.service/2","op":"config.provider.poll",""" +
                    """"sessionId":"session-1","waitMillis":750}""",
            ),
        )
        assertEquals(
            ServiceRequest.ProviderPoll(ServiceProtocol.SCHEMA, "session-1", 0L),
            decoded(
                """{"schema":"pipelinek.service/2","op":"config.provider.poll","sessionId":"session-1"}""",
            ),
            "an absent wait is a poll that does not wait, and not a poll that waits for ever",
        )
    }

    @Test
    fun `a poll whose wait breaks the bound is refused as malformed`() {
        val waits = listOf("-1", "${ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS + 1}", "\"soon\"", "1.5")

        waits.forEach { wait ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(
                    """{"schema":"pipelinek.service/2","op":"config.provider.poll",""" +
                        """"sessionId":"session-1","waitMillis":$wait}""",
                ),
                "a wait of $wait is not a wait the protocol allows",
            )
        }
        assertEquals(
            ServiceRequest.ProviderPoll(
                ServiceProtocol.SCHEMA,
                "session-1",
                ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS,
            ),
            decoded(
                """{"schema":"pipelinek.service/2","op":"config.provider.poll","sessionId":"session-1",""" +
                    """"waitMillis":${ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS}}""",
            ),
            "the bound itself is allowed",
        )
    }

    @Test
    fun `an answer decodes with the values it carries as text`() {
        val request = decoded(
            """{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"session-1",""" +
                """"requestId":"request-1","revision":"r7","values":{"REGION":"eu","REPLICAS":3,"DEBUG":false}}""",
        )

        assertEquals(
            ServiceRequest.ProviderAnswer(
                ServiceProtocol.SCHEMA,
                "session-1",
                "request-1",
                "r7",
                mapOf("REGION" to "eu", "REPLICAS" to "3", "DEBUG" to "false"),
            ),
            request,
            "each value is read as its text and the service checks it against the declared type",
        )
    }

    @Test
    fun `an answer whose shape is wrong is refused as malformed`() {
        val malformed = listOf(
            // no revision: the provenance of the answer would be a guess
            """{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"s",""" +
                """"requestId":"q","values":{}}""",
            // no request id
            """{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"s",""" +
                """"revision":"r","values":{}}""",
            // values that are not an object
            """{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"s",""" +
                """"requestId":"q","revision":"r","values":["A"]}""",
            // a value that is not a primitive
            """{"schema":"pipelinek.service/2","op":"config.provider.answer","sessionId":"s",""" +
                """"requestId":"q","revision":"r","values":{"A":{"nested":"x"}}}""",
        )

        malformed.forEach { line ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(line),
                "this line is not an answer: $line",
            )
        }
    }

    @Test
    fun `a close and the question about a configuration decode with the one name they need`() {
        assertEquals(
            ServiceRequest.ProviderClose(ServiceProtocol.SCHEMA, "session-1"),
            decoded("""{"schema":"pipelinek.service/2","op":"config.provider.close","sessionId":"session-1"}"""),
        )
        assertEquals(
            ServiceRequest.RunConfigurationQuery(ServiceProtocol.SCHEMA, "run-1"),
            decoded("""{"schema":"pipelinek.service/2","op":"run.configuration","runId":"run-1"}"""),
        )
        listOf(
            """{"schema":"pipelinek.service/2","op":"config.provider.close"}""",
            """{"schema":"pipelinek.service/2","op":"run.configuration"}""",
            """{"schema":"pipelinek.service/2","op":"run.configuration","runId":"../../etc/passwd"}""",
        ).forEach { line ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(line),
                "the name is missing or it is a path and not a run: $line",
            )
        }
    }

    @Test
    fun `a start that names a provider decodes with its keys and one that names it badly is refused`() {
        val request = decoded(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"configurationProvider":{"provider":"vault","keys":["REGION","REPLICAS"]}}""",
        )

        assertEquals(
            ServiceProviderReference("vault", listOf("REGION", "REPLICAS")),
            (request as ServiceRequest.StartRun).configurationProvider,
        )
        assertEquals(
            null,
            (decoded("""{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts"}""")
                as ServiceRequest.StartRun).configurationProvider,
            "a start that names no provider asks nobody for anything",
        )
        listOf(
            """"configurationProvider":"vault"""",
            """"configurationProvider":{"keys":["A"]}""",
            """"configurationProvider":{"provider":"vault"}""",
            """"configurationProvider":{"provider":"vault","keys":[null]}""",
            """"configurationProvider":{"provider":"vault","keys":[["A"]]}""",
            """"configurationProvider":{"provider":"vault","keys":{"A":"B"}}""",
        ).forEach { field ->
            assertEquals(
                ServiceDecoding.Refused(ServiceRefusal.MalformedRequest),
                ServiceCodec.decode(
                    """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",$field}""",
                ),
                "a provider reference that is not one is refused as malformed: $field",
            )
        }
    }

    @Test
    fun `every request of a provider session survives the trip through the wire`() {
        val requests = listOf(
            ServiceRequest.ProviderRegister(
                ServiceProtocol.SCHEMA,
                "vault",
                listOf(ServiceProviderKey("REGION", "string", "non-secret")),
            ),
            ServiceRequest.ProviderPoll(ServiceProtocol.SCHEMA, "session-1", 250L),
            ServiceRequest.ProviderAnswer(ServiceProtocol.SCHEMA, "session-1", "request-1", "r1", mapOf("A" to "1")),
            ServiceRequest.ProviderClose(ServiceProtocol.SCHEMA, "session-1"),
            ServiceRequest.RunConfigurationQuery(ServiceProtocol.SCHEMA, "run-1"),
            ServiceRequest.StartRun(
                schema = ServiceProtocol.SCHEMA,
                script = "build.kts",
                workspace = null,
                idempotencyKey = null,
                configurationProvider = ServiceProviderReference("vault", listOf("A", "B")),
            ),
        )

        requests.forEach { request ->
            val line = ServiceCodec.encode(request, "token-1")
            val back = assertInstanceOf(ServiceDecoding.Decoded::class.java, ServiceCodec.decode(line))
            assertEquals(request, back.request, "what the client wrote is what the service reads: $line")
            assertEquals("token-1", back.credential, "the credential travels with every operation")
        }
    }

    @Test
    fun `the request that carries values never renders them`() {
        val request = ServiceRequest.ProviderAnswer(
            ServiceProtocol.SCHEMA,
            "session-1",
            "request-1",
            "r1",
            mapOf("REGION" to "value-that-must-not-leak"),
        )

        val text = request.toString()

        assertFalse(text.contains("value-that-must-not-leak"), "a diagnostic is not where configuration belongs")
        assertFalse(text.contains("REGION"), "not even the names of what was answered")
        assertTrue(text.contains("session-1") && text.contains("request-1"), "the identity of the answer is shown")
    }

    @Test
    fun `the refusal of a provider session comes back with its name and its reason`() {
        val refused = ServiceCodec.decodeLine(
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"configuration-provider-refused",""" +
                """"reason":"a-secret-is-not-configuration","detail":"the key is labelled secret"}}""",
        )

        val line = assertInstanceOf(ServiceLine.Refused::class.java, refused)
        assertEquals(
            ServiceRefusal.ConfigurationProviderRefused(
                "a-secret-is-not-configuration",
                "the key is labelled secret",
            ),
            line.refusal,
        )
        assertEquals("configuration-provider-refused", line.refusal.kind)
    }

    @Test
    fun `the refusals about a provider and about a run survive the trip through the wire`() {
        val refusals = listOf(
            ServiceRefusal.ConfigurationProviderRefused("provider-gone", "the session was closed"),
            ServiceRefusal.RunConfigurationRefused("provider-timed-out", "nobody answered in time"),
        )

        refusals.forEach { refusal ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            val back = assertInstanceOf(ServiceLine.Refused::class.java, ServiceCodec.decodeLine(line))
            assertEquals(refusal, back.refusal, "the client reads the refusal the service wrote: $line")
        }
    }

    @Test
    fun `a refusal of a provider session that omits its reason or its detail is not a typed refusal`() {
        val lines = listOf(
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"configuration-provider-refused","detail":"d"}}""",
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"configuration-provider-refused","reason":"r"}}""",
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"run-configuration-refused","reason":"r"}}""",
        )

        lines.forEach { line ->
            assertEquals(null, ServiceCodec.decodeLine(line), "a refusal without its facts is not read: $line")
        }
    }

    @Test
    fun `the client registers a provider and reads the session it was given`() {
        val channel = FakeChannel(
            answered("""{"sessionId":"session-1","leaseMillis":15000}"""),
        )

        val answer = client(channel).registerProvider(
            "vault",
            listOf(ServiceProviderKey("REGION", "string", "non-secret")),
        )

        assertEquals(
            ServiceProviderSession("session-1", 15000L),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        assertTrue(channel.written.single().contains("\"op\":\"config.provider.register\""))
    }

    @Test
    fun `a registration answered without its session is not a success`() {
        val withoutLease = client(FakeChannel(answered("""{"sessionId":"session-1"}""")))
            .registerProvider("vault", emptyList())
        val withoutSession = client(FakeChannel(answered("""{"leaseMillis":15000}""")))
            .registerProvider("vault", emptyList())

        assertInstanceOf(ServiceAnswer.Unreadable::class.java, withoutLease)
        assertInstanceOf(ServiceAnswer.Unreadable::class.java, withoutSession)
    }

    @Test
    fun `the client tells an idle poll from a poll that asks for keys`() {
        val idle = client(FakeChannel(answered("""{"state":"idle"}"""))).pollProvider("session-1", 100L)
        val asked = client(
            FakeChannel(answered("""{"state":"asked","requestId":"request-1","keys":["A","B"]}""")),
        ).pollProvider("session-1", 100L)

        assertEquals(ServiceProviderPoll.Idle, assertInstanceOf(ServiceAnswer.Answered::class.java, idle).value)
        assertEquals(
            ServiceProviderPoll.Asked("request-1", listOf("A", "B")),
            assertInstanceOf(ServiceAnswer.Answered::class.java, asked).value,
        )
    }

    @Test
    fun `a poll answered in a shape nobody asked for is not a success`() {
        val shapes = listOf(
            """{"state":"asked","keys":["A"]}""",
            """{"state":"asked","requestId":"request-1"}""",
            """{"state":"asked","requestId":"request-1","keys":[{"x":1}]}""",
            """{"state":"later"}""",
            """{}""",
        )

        shapes.forEach { result ->
            assertInstanceOf(
                ServiceAnswer.Unreadable::class.java,
                client(FakeChannel(answered(result))).pollProvider("session-1", 100L),
                "a poll answered with $result",
            )
        }
    }

    @Test
    fun `the client sends the values of an answer and reads back the acknowledgement`() {
        val channel = FakeChannel(answered("""{"sessionId":"session-1","outcome":"answered"}"""))

        val answer = client(channel).answerProvider("session-1", "request-1", "r7", mapOf("REGION" to "eu"))

        assertEquals(
            ServiceProviderAck("session-1", "answered"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        assertTrue(
            channel.written.single().contains("\"values\":{\"REGION\":\"eu\"}"),
            "the values travel on the wire, which is where they are meant to be",
        )
    }

    @Test
    fun `the client closes a session and an acknowledgement without its outcome is not a success`() {
        val closed = client(FakeChannel(answered("""{"sessionId":"session-1","outcome":"closed"}""")))
            .closeProvider("session-1")
        val bare = client(FakeChannel(answered("""{"sessionId":"session-1"}""")))
            .closeProvider("session-1")

        assertEquals(
            ServiceProviderAck("session-1", "closed"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, closed).value,
        )
        assertInstanceOf(ServiceAnswer.Unreadable::class.java, bare)
    }

    @Test
    fun `the client reads where the configuration of a run came from`() {
        val channel = FakeChannel(
            answered(
                """{"runId":"run-1","digest":"d1","precedence":["request","provider"],""" +
                    """"entries":[{"name":"A","origin":"request","type":"string"},""" +
                    """{"name":"B","origin":"provider","type":"integer"}],""" +
                    """"provider":"vault","revision":"r7","sourceDigest":"d2"}""",
            ),
        )

        val answer = client(channel).runConfiguration("run-1")

        assertEquals(
            ServiceRunConfiguration(
                runId = "run-1",
                digest = "d1",
                precedence = listOf("request", "provider"),
                entries = listOf(
                    ServiceConfigurationEntry("A", "request", "string"),
                    ServiceConfigurationEntry("B", "provider", "integer"),
                ),
                provider = "vault",
                revision = "r7",
                sourceDigest = "d2",
            ),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
    }

    @Test
    fun `a run started without a provider reads back without provider fields`() {
        val answer = client(
            FakeChannel(
                answered(
                    """{"runId":"run-1","digest":"d1","precedence":["request","provider"],""" +
                        """"entries":[{"name":"A","origin":"request","type":"string"}]}""",
                ),
            ),
        ).runConfiguration("run-1")

        val configuration = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
            as ServiceRunConfiguration
        assertEquals(null, configuration.provider)
        assertEquals(null, configuration.revision)
        assertEquals(null, configuration.sourceDigest)
    }

    @Test
    fun `a configuration read back in a shape nobody asked for is not a success`() {
        val shapes = listOf(
            """{"digest":"d1","precedence":[],"entries":[]}""",
            """{"runId":"run-1","precedence":[],"entries":[]}""",
            """{"runId":"run-1","digest":"d1","entries":[]}""",
            """{"runId":"run-1","digest":"d1","precedence":[]}""",
            """{"runId":"run-1","digest":"d1","precedence":[],"entries":[{"name":"A","origin":"request"}]}""",
            """{"runId":"run-1","digest":"d1","precedence":[],"entries":["A"]}""",
        )

        shapes.forEach { result ->
            assertInstanceOf(
                ServiceAnswer.Unreadable::class.java,
                client(FakeChannel(answered(result))).runConfiguration("run-1"),
                "a configuration answered with $result",
            )
        }
    }

    @Test
    fun `the refusal of a provider request reaches the client with its name`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"configuration-provider-refused",""" +
                    """"reason":"provider-name-taken","detail":"a live session holds the name"}}""",
            ),
        )

        val answer = client(channel).registerProvider("vault", emptyList())

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
        assertEquals(
            ServiceRefusal.ConfigurationProviderRefused("provider-name-taken", "a live session holds the name"),
            refusal,
        )
    }

    @Test
    fun `a start that names a provider writes the reference it was given`() {
        val channel = FakeChannel(
            answered(
                """{"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1","startedAtMs":1700}""",
            ),
        )

        client(channel).startRun(
            "build.kts",
            configurationProvider = ServiceProviderReference("vault", listOf("REGION")),
        )

        assertTrue(
            channel.written.single()
                .contains("\"configurationProvider\":{\"provider\":\"vault\",\"keys\":[\"REGION\"]}"),
            "the client wrote the reference it was asked for",
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
