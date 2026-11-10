package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

/**
 * The typed client of the local service.
 *
 * ## The guarantee under trial
 *
 * A client asks a question and gets a TYPED answer or a NAMED negative, and
 * it never gets a wrong-shaped success. The negatives are the ones an
 * operator acts on differently: there is no service, the service speaks
 * another protocol, the service refused, or the deadline went by. A client
 * that collapsed them into one "error" would leave that decision to a string
 * comparison, and two clients would compare it differently.
 *
 * ## The capacity this serves
 *
 * A supervisor that watches many runs over a socket has to be able to tell
 * "the run is not known" from "the service is gone" from "the service is
 * busy" without reading prose — and it has to be able to tell a REFUSAL from
 * an ANSWER, because a refusal that arrived as a state would leave it
 * waiting for a run that is never coming.
 *
 * ## Why the connection is faked here
 *
 * The unit lane is about the CONVERSATION: what the client writes, how it
 * reads what comes back, and what it says when the reading fails. Whether a
 * Unix socket binds and whether a real service answers are facts about a
 * machine, and the integration lane is where they are observed.
 */
class ServiceClientTest {

    @Test
    fun `the client writes the wire's own line and reads the answer`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer",""" +
                    """"result":{"schema":"pipelinek.service/2","productVersion":"1.2.3"}}""",
            ),
        )

        val answer = client(channel).version()

        assertEquals(
            ServiceVersion(ServiceProtocol.SCHEMA, "1.2.3"),
            answer.value(),
        )
        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"version"}""",
            channel.written.single(),
            "the client wrote a line of its own instead of the wire's line",
        )
    }

    @Test
    fun `the health of the service comes back typed`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"schema":"pipelinek.service/2","productVersion":"1.2.3",""" +
                    """"eventStore":"/tmp/store.db","eventStorePresent":true,""" +
                    """"controlRoot":"/tmp/control","controlRootPresent":false}}""",
            ),
        )

        val answer = client(channel).health()

        val health = answer.value<ServiceHealth>()
        assertEquals("/tmp/store.db", health.eventStore)
        assertEquals(true, health.eventStorePresent)
        assertEquals(false, health.controlRootPresent)
    }

    @Test
    fun `the state of a run comes back as the published observation`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"state":"terminal","attempt":1,"at":999,"outcome":"succeeded","terminalAt":999}}""",
            ),
        )

        val answer = client(channel).runState("run-1")

        val observation = answer.value<RuntimeObservation>()
        assertEquals(
            RuntimeObservation.Terminal(AttemptId(1), TerminalObservation.Succeeded(999L), 999L),
            observation,
        )
        assertTrue(channel.written.single().contains("\"runId\":\"run-1\""))
    }

    @Test
    fun `a refusal comes back with its name and not as an answer`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"unknown-run","runId":"run-1"}}""",
            ),
        )

        val answer = client(channel).runState("run-1")

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
        assertEquals(ServiceRefusal.UnknownRun("run-1"), refusal)
    }

    @Test
    fun `there is no service and the client says so`() {
        val answer = client(ThrowingChannel()).runState("run-1")

        val unreachable = assertInstanceOf(ServiceAnswer.Unreachable::class.java, answer)
        assertTrue(unreachable.detail.isNotBlank(), "the negative carries no detail")
    }

    @Test
    fun `a line that is not this protocol is refused and not read as an answer`() {
        val channel = FakeChannel(listOf("""{"kind":"answer","result":{}}"""))

        val answer = client(channel).runState("run-1")

        assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
    }

    @Test
    fun `an answer of another protocol is refused and not read as an answer`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/9","kind":"answer",""" +
                    """"result":{"state":"terminal","attempt":1,"at":1,"outcome":"succeeded","terminalAt":1}}""",
            ),
        )

        val answer = client(channel).runState("run-1")

        val unreadable = assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
        assertTrue(
            unreadable.detail.contains("pipelinek.service/9"),
            "the negative names the schema that came back: ${unreadable.detail}",
        )
    }

    @Test
    fun `a deadline that goes by is its own negative`() {
        val channel = FakeChannel(listOf(), reads = ServiceRead.TimedOut)

        val answer = client(channel).runState("run-1")

        assertEquals(ServiceAnswer.TimedOut, answer)
    }

    @Test
    fun `a street delivers its records and its end`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"record",""" +
                    """"cursor":"evt-cursor-v1:run-1:1","hasMore":true,"record":{"type":"event"}}""",
                """{"schema":"pipelinek.service/2","kind":"end",""" +
                    """"reason":"terminal-observed","cursor":"evt-cursor-v1:run-1:2"}""",
            ),
        )

        val street = client(channel).events("run-1").value<ServiceStreet>()

        val first = assertInstanceOf(ServiceStreetItem.Record::class.java, street.next())
        assertEquals("evt-cursor-v1:run-1:1", first.cursor)
        val end = assertInstanceOf(ServiceStreetItem.End::class.java, street.next())
        assertEquals(ServiceEndReason.TerminalObserved, end.reason)
        assertEquals(null, street.next(), "the street is over after its end")
    }

    @Test
    fun `a street that is refused says so and delivers nothing`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"refused",""" +
                    """"refusal":{"kind":"invalid-cursor","cursor":"x","expected":"evt-cursor-v1"}}""",
            ),
        )

        val street = client(channel).events("run-1").value<ServiceStreet>()

        val refusal = assertInstanceOf(ServiceStreetItem.Refused::class.java, street.next())
        assertEquals(ServiceRefusal.InvalidCursor("x", "evt-cursor-v1"), refusal.refusal)
    }

    @Test
    fun `closing a street closes the connection`() {
        val channel = FakeChannel(listOf())

        val street = client(channel).events("run-1").value<ServiceStreet>()

        street.close()

        assertTrue(channel.closed, "the connection of a closed street is closed")
    }

    @Test
    fun `an answer that carries other parameter values than the ones sent is not a success`() {
        // The echo: a service that does not know the field starts the run WITHOUT the values
        // and answers a well formed question, and a client that cannot tell the two apart
        // believes in values that are not there. The client compares, and a difference is
        // never a successful start.
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700,"configuration":{},"parameters":{"dryRun":"true"}}}""",
            ),
        )

        val answer = client(channel).startRun(
            script = "build.kts",
            parameters = mapOf("dryRun" to "false"),
        )

        val unreadable = assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer)
        assertTrue(
            unreadable.detail.contains("parameter values"),
            "the negative says what did not match",
        )
    }

    @Test
    fun `an answer that carries the parameter values that were sent is a success`() {
        val channel = FakeChannel(
            listOf(
                """{"schema":"pipelinek.service/2","kind":"answer","result":{""" +
                    """"runId":"run-1","correlationId":"corr-1","ownerId":"owner-1",""" +
                    """"startedAtMs":1700,"configuration":{},"parameters":{"dryRun":"false"}}}""",
            ),
        )

        val answer = client(channel).startRun(
            script = "build.kts",
            parameters = mapOf("dryRun" to "false"),
        )

        val started = answer.value<ServiceRunStart>()
        assertEquals(mapOf("dryRun" to "false"), started.parameters)
    }

    /**
     * The value of an answer, when the answer carried one.
     *
     * The cast is the assertion: a refusal that arrived as an answer would
     * fail here instead of being read as a value.
     */
    private inline fun <reified T> ServiceAnswer<*>.value(): T {
        val answered = assertInstanceOf(ServiceAnswer.Answered::class.java, this)
        return assertInstanceOf(T::class.java, answered.value)
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
        private val reads: ServiceRead = ServiceRead.Closed,
    ) : ServiceChannel {

        /** The lines the client wrote. */
        val written = mutableListOf<String>()

        /** Whether the connection was closed. */
        var closed = false
            private set

        private var index = 0

        override fun write(line: String) {
            written += line
        }

        override fun readLine(): ServiceRead {
            if (index < lines.size) {
                return ServiceRead.Line(lines[index++])
            }
            return reads
        }

        override fun close() {
            closed = true
        }
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
