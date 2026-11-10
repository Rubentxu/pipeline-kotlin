package dev.rubentxu.pipeline.v2.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

/**
 * The wire of a request to START a run, and of the refusals it answers with.
 *
 * ## The guarantee under trial
 *
 * That a start request is a NAMED operation with NAMED bounds, and that every
 * way it can be refused arrives with a name of its own. A client that has to
 * read a sentence to learn why its run did not start is a client that has to
 * be changed every time the sentence is reworded, and a client that cannot
 * tell "you are not allowed to start" from "that script is not admitted" is a
 * client that cannot decide whether to ask for another authorization or to
 * ask for a different script.
 *
 * ## The capacity this serves
 *
 * A supervisor that starts runs over a socket has to know WHEN it may try
 * again: a refusal about the credential is not retryable, a refusal about the
 * budget is retryable later, and a refusal about the names is retryable with
 * other names. The three live in the wire's vocabulary for exactly that
 * reason.
 *
 * ## Why this instrument
 *
 * The codec is a pure function of a line: no socket, no store, no clock and
 * no process. The cheapest instrument that can fail is a line and the value
 * it decodes to, and every bound of the request is a bound the codec can
 * refuse without touching the disk.
 *
 * ## What this does not freeze
 *
 * Whether the service admits the script, whether the run succeeds, and the
 * words of the sentences the service prints: only the NAMES travel on the
 * wire, and the names are the contract.
 */
class ServiceStartProtocolTest {

    @Test
    fun `a request to start a run decodes with the names it carries`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start",""" +
                """"script":"build.kts","workspace":"scratch","idempotencyKey":"key-1"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.StartRun(ServiceProtocol.SCHEMA, "build.kts", "scratch", "key-1"),
            request,
            "the request carries the script, the workspace and the key",
        )
    }

    @Test
    fun `a request to start a run may leave the workspace and the key out`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.StartRun(ServiceProtocol.SCHEMA, "build.kts", null, null),
            request,
            "the workspace and the key are optional, and an absent one is not an empty one",
        )
    }

    @Test
    fun `a request to start a run without naming the script is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":""}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a script name longer than the bound is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"${"s".repeat(257)}"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a workspace name longer than the bound is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"a.kts",""" +
                """"workspace":"${"w".repeat(257)}"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `an idempotency key longer than the bound is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.start","script":"a.kts",""" +
                """"idempotencyKey":"${"k".repeat(129)}"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a request to start a run is written with its names and without the absent ones`() {
        val line = ServiceCodec.encode(
            ServiceRequest.StartRun(ServiceProtocol.SCHEMA, "build.kts", null, "key-1"),
            "token-1",
        )

        val document = Json.parseToJsonElement(line).jsonObject
        assertEquals("run.start", document["op"]?.jsonPrimitive?.content)
        assertEquals("build.kts", document["script"]?.jsonPrimitive?.content)
        assertEquals("key-1", document["idempotencyKey"]?.jsonPrimitive?.content)
        assertNull(document["workspace"], "an absent workspace is not written as an empty one")
    }

    @Test
    fun `every refusal of a start carries a kind of its own and no two kinds collide`() {
        val refusals: List<ServiceRefusal> = listOf(
            ServiceRefusal.StartNotAuthorized,
            ServiceRefusal.ScriptNotAdmitted("build.kts"),
            ServiceRefusal.WorkspaceNotAdmitted("scratch"),
            ServiceRefusal.IdempotencyConflict("key-1"),
            ServiceRefusal.TooManySupervisedRuns,
            ServiceRefusal.RunStartRefused("no-ownership-in-time", "the run did not own itself"),
        )

        val kinds = refusals.map { it.kind }

        assertEquals(
            refusals.size,
            kinds.toSet().size,
            "two refusals of a start share a kind, so a client cannot tell them apart: $kinds",
        )
        refusals.forEach { refusal ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            val decoded = Json.parseToJsonElement(line).jsonObject["refusal"]?.jsonObject
            assertEquals(
                refusal.kind,
                decoded?.get("kind")?.jsonPrimitive?.content,
                "the wire carries the kind of $refusal",
            )
        }
    }

    @Test
    fun `a refusal of a start keeps the fact it carried`() {
        val rows: List<Pair<ServiceRefusal, String>> = listOf(
            ServiceRefusal.StartNotAuthorized to """{"kind":"start-not-authorized"}""",
            ServiceRefusal.ScriptNotAdmitted("build.kts") to
                """{"kind":"script-not-admitted","script":"build.kts"}""",
            ServiceRefusal.WorkspaceNotAdmitted("scratch") to
                """{"kind":"workspace-not-admitted","workspace":"scratch"}""",
            ServiceRefusal.IdempotencyConflict("key-1") to
                """{"kind":"idempotency-conflict","key":"key-1"}""",
            ServiceRefusal.TooManySupervisedRuns to """{"kind":"too-many-supervised-runs"}""",
            ServiceRefusal.RunStartRefused("the-child-died", "the run ended") to
                """{"kind":"run-start-refused","reason":"the-child-died","detail":"the run ended"}""",
        )

        rows.forEach { (refusal, expected) ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            val decoded = Json.parseToJsonElement(line).jsonObject["refusal"]?.jsonObject

            assertEquals(
                expected,
                decoded?.toString(),
                "the refusal of a start keeps the fact it carried",
            )
        }
    }

    @Test
    fun `the client writes the start operation and reads the run it started`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts", "scratch", "key-1")

        val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
        assertEquals(
            ServiceRunStart("run-1", "corr-1", "owner-1", 1700L),
            started,
            "the answer of a start is the identity of the run that started",
        )
        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"run.start",""" +
                """"script":"build.kts","workspace":"scratch","idempotencyKey":"key-1"}""",
            channel.written.single(),
            "the client wrote the wire's own line",
        )
    }

    @Test
    fun `a refusal of a start comes back with its name and not as an answer`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"too-many-supervised-runs"}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts")

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
        assertEquals(ServiceRefusal.TooManySupervisedRuns, refusal)
    }

    @Test
    fun `an answer of a start without the run it names is not read as a success`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"run-1"}}""",
            ),
        )

        val answer = client(channel).startRun("build.kts")

        assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
    }

    @Test
    fun `there is no service and the client says so when a run is asked for`() {
        val answer = client(ThrowingChannel()).startRun("build.kts")

        val unreachable = assertInstanceOf(ServiceAnswer.Unreachable::class.java, answer)
        assertTrue(unreachable.detail.isNotBlank(), "the negative carries no detail")
    }

    /**
     * A client that talks to [channel].
     */
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
    private class FakeChannel(
        private val lines: List<String>,
    ) : ServiceChannel {

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

    /**
     * A connection that cannot be opened: there is no service at the endpoint.
     */
    private class ThrowingChannel : ServiceChannel {
        override fun write(line: String) = throw IOException("no service at the endpoint")
        override fun readLine(): ServiceRead = ServiceRead.Closed
        override fun close() = Unit
    }
}
