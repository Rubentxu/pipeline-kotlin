package dev.rubentxu.pipeline.v2.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The configuration of a run, on the wire and in the typed client.
 *
 * ## The guarantee under trial
 *
 * That a client learns what configuration its run WILL HAVE, and that a
 * service which did not understand the configuration says so instead of
 * letting the client believe in one that is not there.
 *
 * ## Why the ECHO is the guarantee and not a convenience
 *
 * Because the schema of the wire does not change when an operation gains a
 * field, a service that does not know the field IGNORES it in silence: it
 * starts the run without the configuration, and the client —which sent it
 * and saw a perfectly well formed answer— believes in a configuration that
 * is not there. The echo is what turns that silence into a fact, and the
 * client that compares the echo with what it sent is what turns the fact
 * into a NAMED negative.
 *
 * ## The two halves and why they are in one file
 *
 * The wire half is the SHAPE (what a line may carry) and the client half is
 * the COMPARISON (what the client does with the answer). They are one
 * guarantee split in two places, and a test of only one of them would leave
 * the other untested: a codec that reads a configuration nobody compares is
 * a field that travels and is ignored.
 */
class ServiceRunConfigurationProtocolTest {

    @Test
    fun `a request to start a run carries the configuration it was given`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"configuration":{"BUILD_PROFILE":"release","TARGET":"api"}}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(
            mapOf("BUILD_PROFILE" to "release", "TARGET" to "api"),
            start.configuration,
            "the configuration travels with the request",
        )
    }

    @Test
    fun `a request without a configuration carries none`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(emptyMap<String, String>(), start.configuration)
    }

    @Test
    fun `a configuration that is not an object is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"configuration":5}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a configuration entry whose value is not a primitive is a malformed line`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"configuration":{"K":{"nested":true}}}""",
        )

        val refused = assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
        assertEquals(ServiceRefusal.MalformedRequest, refused.refusal)
    }

    @Test
    fun `a number is read as its text, like every other primitive of the wire`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts",""" +
                """"configuration":{"PORT":8080,"DEBUG":true}}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        val start = assertInstanceOf(ServiceRequest.StartRun::class.java, request)
        assertEquals(mapOf("PORT" to "8080", "DEBUG" to "true"), start.configuration)
    }

    @Test
    fun `the refusal of a configuration comes back with its name and its reason`() {
        val refused = ServiceCodec.decodeLine(
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"run-configuration-refused",""" +
                """"reason":"the-configuration-name-is-reserved",""" +
                """"detail":"the name may not begin with PIPELINE_"}}""",
        )

        val line = assertInstanceOf(ServiceLine.Refused::class.java, refused)
        assertEquals(
            ServiceRefusal.RunConfigurationRefused(
                "the-configuration-name-is-reserved",
                "the name may not begin with PIPELINE_",
            ),
            line.refusal,
        )
    }

    @Test
    fun `a client that sent a configuration and got it back is told the run started`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700,"configuration":{"BUILD_PROFILE":"release"}}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts", configuration = mapOf("BUILD_PROFILE" to "release"))

        val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value as ServiceRunStart
        assertEquals("run-1", started.runId)
        assertEquals(mapOf("BUILD_PROFILE" to "release"), started.configuration)
        assertTrue(
            channel.written.single().contains("\"configuration\":{\"BUILD_PROFILE\":\"release\"}"),
            "the client wrote the configuration it was asked for",
        )
    }

    @Test
    fun `a service that ignored the configuration is NOT a success`() {
        // The answer carries no configuration at all: that is what a service
        // that does not know the field says. The client sent one, so the
        // difference is a fact and the client has to report it.
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts", configuration = mapOf("BUILD_PROFILE" to "release"))

        val unreadable = assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
        assertTrue(
            unreadable.detail.contains("run-1"),
            "the detail names the run, because the run EXISTS and the operator has to cancel it",
        )
    }

    @Test
    fun `a service that echoed ANOTHER configuration is NOT a success`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700,"configuration":{"BUILD_PROFILE":"debug"}}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts", configuration = mapOf("BUILD_PROFILE" to "release"))

        assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
    }

    @Test
    fun `a client that sent no configuration is not troubled by an answer without one`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts")

        val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value as ServiceRunStart
        assertEquals("run-1", started.runId)
        assertEquals(emptyMap<String, String>(), started.configuration)
    }

    @Test
    fun `the refusal of a configuration reaches the client with its name`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"run-configuration-refused",""" +
                    """"reason":"the-configuration-name-is-denied",""" +
                    """"detail":"the sandbox removes this name"}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts", configuration = mapOf("LD_PRELOAD" to "/tmp/x.so"))

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
        assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusal)
    }

    private fun client(channel: ServiceChannel): ServiceClient = ServiceClient(
        endpoint = Path.of("/tmp/service.sock"),
        credential = "token-1",
        deadline = null,
        schema = ServiceProtocol.SCHEMA,
        channels = { _, _ -> channel },
    )

    /**
     * A connection that answers with the lines it was given.
     */
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
