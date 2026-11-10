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
 * The wire of a request to CANCEL a run, and of the refusals it answers with.
 *
 * ## The guarantee under trial
 *
 * That a cancellation is a NAMED operation with a NAMED bound, and that the
 * two refusals it can be answered with arrive with a name of their own. A
 * client that has to read a sentence to learn why its run was not cancelled
 * is a client that has to be changed every time the sentence is reworded,
 * and a client that cannot tell "you are not allowed to cancel" from "that
 * run could not be cancelled" is a client that cannot decide whether to ask
 * for another authorization or to leave the run alone.
 *
 * ## The capacity this serves
 *
 * A cancellation that is retried —because the answer was lost, because the
 * connection broke — has to be answered with the SAME terminal both times,
 * and never with a second terminal. The vocabulary of the wire is what says
 * that: `cancelled` and `already-cancelled` are two answers to the same
 * question, and `run-cancel-refused` carries the word that says WHICH fact
 * stopped it.
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
 * Whether the service holds the run, whether the run's own facts record the
 * terminal, and the words of the sentences the service prints: only the
 * NAMES travel on the wire, and the names are the contract.
 */
class ServiceCancelProtocolTest {

    @Test
    fun `a request to cancel a run decodes with the run it names`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", null),
            request,
            "the request carries the run and no reason",
        )
    }

    @Test
    fun `a request to cancel a run carries the reason it names`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1",""" +
                """"reason":"the operator asked for it"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", "the operator asked for it"),
            request,
            "the reason travels verbatim, because it is recorded in the run's own history",
        )
    }

    @Test
    fun `a request to cancel a run without naming the run is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a reason longer than the bound is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1",""" +
                """"reason":"${"r".repeat(ServiceProtocol.MAX_CANCEL_REASON_LENGTH + 1)}"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a reason with a line break in it is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1",""" +
                """"reason":"first line\nsecond line"}""",
        )

        assertInstanceOf(ServiceDecoding.Refused::class.java, decoded)
    }

    @Test
    fun `a reason of the longest allowed length is accepted`() {
        val reason = "r".repeat(ServiceProtocol.MAX_CANCEL_REASON_LENGTH)
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1",""" +
                """"reason":"$reason"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", reason),
            request,
            "the bound is the length the product accepts, and the last one of them is legal",
        )
    }

    @Test
    fun `a cancellation with nothing to say is a cancellation`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1","reason":""}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", ""),
            request,
            "an empty reason is legal, because the product accepts it",
        )
    }

    @Test
    fun `a request to cancel a run is written with the names it carries`() {
        val line = ServiceCodec.encode(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", "because"),
            "token-1",
        )

        val document = Json.parseToJsonElement(line).jsonObject
        assertEquals("run.cancel", document["op"]?.jsonPrimitive?.content)
        assertEquals("run-1", document["runId"]?.jsonPrimitive?.content)
        assertEquals("because", document["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a request to cancel a run without a reason writes no reason`() {
        val line = ServiceCodec.encode(
            ServiceRequest.CancelRun(ServiceProtocol.SCHEMA, "run-1", null),
            "token-1",
        )

        val document = Json.parseToJsonElement(line).jsonObject
        assertNull(document["reason"], "an absent reason is not written as an empty one")
    }

    @Test
    fun `every refusal of a cancellation carries a kind of its own`() {
        val refusals: List<ServiceRefusal> = listOf(
            ServiceRefusal.CancelNotAuthorized,
            ServiceRefusal.RunCancelRefused("the-run-is-unknown", "the service does not know it"),
        )

        val kinds = refusals.map { it.kind }

        assertEquals(
            refusals.size,
            kinds.toSet().size,
            "two refusals of a cancellation share a kind, so a client cannot tell them apart: $kinds",
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
    fun `a refusal of a cancellation keeps the fact it carried`() {
        val rows: List<Pair<ServiceRefusal, String>> = listOf(
            ServiceRefusal.CancelNotAuthorized to """{"kind":"cancel-not-authorized"}""",
            ServiceRefusal.RunCancelRefused("the-run-is-unknown", "the service does not know it") to
                """{"kind":"run-cancel-refused","reason":"the-run-is-unknown",""" +
                """"detail":"the service does not know it"}""",
        )

        rows.forEach { (refusal, expected) ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            val decoded = Json.parseToJsonElement(line).jsonObject["refusal"]?.jsonObject

            assertEquals(
                expected,
                decoded?.toString(),
                "the refusal of a cancellation keeps the fact it carried",
            )
        }
    }

    @Test
    fun `a refusal of a cancellation is read back with its name and its facts`() {
        val decoded = ServiceCodec.decodeLine(
            """{"schema":"pipelinek.service/2","kind":"refused",""" +
                """"refusal":{"kind":"run-cancel-refused","reason":"the-run-is-unknown",""" +
                """"detail":"the service does not know it"}}""",
        )

        val refusal = assertInstanceOf(ServiceLine.Refused::class.java, decoded).refusal
        assertEquals(
            ServiceRefusal.RunCancelRefused("the-run-is-unknown", "the service does not know it"),
            refusal,
            "the refusal arrives with the word that says which fact stopped it",
        )
    }

    @Test
    fun `the client writes the cancel operation and reads the terminal`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","outcome":"cancelled","terminalAtMs":1700}}""",
            ),
        )

        val answer = client(channel).cancelRun("run-1", "because")

        val cancelled = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
        assertEquals(
            ServiceRunCancel("run-1", "cancelled", 1700L),
            cancelled,
            "the answer of a cancellation is the terminal that was recorded",
        )
        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"run.cancel",""" +
                """"runId":"run-1","reason":"because"}""",
            channel.written.single(),
            "the client wrote the wire's own line",
        )
    }

    @Test
    fun `a repeated cancellation is answered with the terminal it already has`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","outcome":"already-cancelled","terminalAtMs":1700}}""",
            ),
        )

        val answer = client(channel).cancelRun("run-1")

        val cancelled = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
        assertEquals(
            ServiceRunCancel("run-1", "already-cancelled", 1700L),
            cancelled,
            "a cancellation repeated is an answer and not a failure",
        )
    }

    @Test
    fun `a refusal of a cancellation comes back with its name and not as an answer`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"cancel-not-authorized"}}""",
            ),
        )

        val answer = client(channel).cancelRun("run-1")

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
        assertEquals(ServiceRefusal.CancelNotAuthorized, refusal)
    }

    @Test
    fun `an answer of a cancellation without the terminal is not read as a success`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer",""" +
                    """"result":{"runId":"run-1","outcome":"cancelled"}}""",
            ),
        )

        val answer = client(channel).cancelRun("run-1")

        assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
    }

    @Test
    fun `there is no service and the client says so when a run is cancelled`() {
        val answer = client(ThrowingChannel()).cancelRun("run-1")

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
