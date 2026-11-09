package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceDecoding
import dev.rubentxu.pipeline.v2.service.ServiceEndReason
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest

/**
 * The service that answers the connections.
 *
 * ## The guarantee under trial
 *
 * A connection is a QUESTION and the service owes it an ANSWER, and the two
 * rules that make that true are the ones a client cannot see and cannot work
 * around: ONE question per connection, and a client that misbehaves is told
 * what it did instead of being left with a closed socket and no idea why.
 *
 * ## The capacity this serves
 *
 * A supervisor that watches a service has to survive a client that sends
 * nonsense, a client that sends too much, a client that says nothing at all,
 * and more clients than the service admits. Each of those four has a NAME in
 * the contract, and a client that can name the failure can act on it: retry,
 * fix the request, wait, or shed load. A service that answers all four the
 * same way answers none of them.
 *
 * ## Why this instrument
 *
 * The transport is a seam, and this is the lane where the seam pays: the
 * behaviour of the service is exercised over scripted connections, with no
 * socket, no store and no clock. What the socket itself does — the
 * permissions, the leftover, the deletion — is measured in the integration
 * lane, where a real socket exists.
 *
 * ## What this does not freeze
 *
 * The transport itself, and the bytes on the wire.
 */
class ServiceServerTest {

    @Test
    fun `a request is answered and the connection is closed`() {
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))

        serve(connection)

        val answer = answerOf(connection)
        assertEquals(
            "answer",
            answer["kind"]?.jsonPrimitive?.content,
            "the service answered the question: ${connection.written}",
        )
        assertEquals(
            ServiceProtocol.SCHEMA,
            answer["schema"]?.jsonPrimitive?.content,
            "the answer says which schema it speaks",
        )
        assertTrue(connection.closed, "one question per connection: the connection is closed")
    }

    @Test
    fun `a line that is not a request is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line("esto no es una peticion"))

        serve(connection)

        assertEquals("malformed-request", refusalKindOf(connection))
    }

    @Test
    fun `a line that is too big is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.TooLarge)

        serve(connection)

        assertEquals(
            "request-too-large",
            refusalKindOf(connection),
            "a client that sends too much is told so, and not left guessing",
        )
    }

    @Test
    fun `a client that goes quiet is closed without an answer`() {
        val connection = ScriptedConnection(ServiceRead.TimedOut)

        serve(connection)

        assertTrue(
            connection.written.isEmpty(),
            "a client that says nothing gets no answer, only a closed connection",
        )
        assertTrue(connection.closed, "the connection that went quiet is closed")
    }

    @Test
    fun `a client that closes before speaking is closed without an answer`() {
        val connection = ScriptedConnection(ServiceRead.Closed)

        serve(connection)

        assertTrue(connection.written.isEmpty(), "a client that left gets no answer")
        assertTrue(connection.closed, "the connection of the client that left is closed")
    }

    @Test
    fun `a client that arrives when the service is full is refused with a name`() {
        val holding = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST), gate = CountDownLatch(1))
        val arriving = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))
        val transport = ScriptedTransport(listOf(holding, arriving))

        val server = ServiceServer(transport, operations(), streets(), ServiceLimits(maxConnections = 1))
        try {
            server.serve()

            assertEquals(
                "too-many-connections",
                refusalKindOf(arriving),
                "the client that arrived when the service was full is told so",
            )
        } finally {
            holding.release()
            server.close()
        }
    }

    @Test
    fun `the connection that was admitted is answered even when another one is refused`() {
        val holding = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST), gate = CountDownLatch(1))
        val arriving = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))
        val transport = ScriptedTransport(listOf(holding, arriving))

        val server = ServiceServer(transport, operations(), streets(), ServiceLimits(maxConnections = 1))
        try {
            server.serve()
            holding.release()
            holding.awaitAnswered()

            assertTrue(
                holding.written.isNotEmpty(),
                "the connection that was admitted is served, and the refusal of the other does not stop it",
            )
            assertEquals("too-many-connections", refusalKindOf(arriving))
        } finally {
            holding.release()
            server.close()
        }
    }

    @Test
    fun `a connection that arrives when the service is stopping is closed`() {
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))
        val server = ServiceServer(ScriptedTransport(listOf(connection)), operations(), streets())
        server.close()

        server.serve()

        assertTrue(
            connection.closed,
            "a connection that nobody can serve is closed, not left holding a socket",
        )
        assertTrue(
            connection.written.isEmpty(),
            "a connection that nobody can serve gets no answer",
        )
    }

    @Test
    fun `a subscription is served by the street and the connection closes when it ends`() {
        val connection = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val served = mutableListOf<ServiceSubscriptionRequest>()
        val server = ServiceServer(ScriptedTransport(listOf(connection)), operations(), streets(served))
        try {
            server.serve()
            connection.awaitAnswered()

            assertEquals(1, served.size, "the street was asked to serve the subscription")
            assertEquals("run-1", served.first().runId)
            assertTrue(
                connection.written.isNotEmpty(),
                "the street wrote the frames of the street on the connection",
            )
            assertTrue(
                connection.closed,
                "the connection is closed when the street ends, and not before",
            )
        } finally {
            server.close()
        }
    }

    /**
     * Serves the scripted connections and waits for the answers.
     */
    private fun serve(vararg connections: ScriptedConnection) {
        val server = ServiceServer(ScriptedTransport(connections.toList()), operations(), streets())
        try {
            server.serve()
            connections.forEach { it.awaitAnswered() }
        } finally {
            server.close()
        }
    }

    /**
     * The answer written in [connection], read as a document.
     */
    private fun answerOf(connection: ScriptedConnection) =
        Json.parseToJsonElement(connection.written.first()).jsonObject

    /**
     * The kind of the refusal written in [connection].
     */
    private fun refusalKindOf(connection: ScriptedConnection): String? =
        answerOf(connection)["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content

    /**
     * The operations the service answers with.
     *
     * The questions of these rows are about the SERVICE — the version, the
     * health, and the shape of a refusal — and not about a run, so the port
     * behind them is never asked.
     */
    private fun operations(): ServiceOperations = ServiceOperations(
        introspection = UnusedPort(),
        substrate = {
            ServiceSubstrate(
                eventStore = "/tmp/state/pipelinek.db",
                controlRoot = "/tmp/state/ctl",
                eventStorePresent = true,
                controlRootPresent = true,
            )
        },
    )

    /**
     * The streets, faked.
     *
     * These rows are about the SERVICE and not about a street, so the fake
     * writes one line and ends: the row that is about a street asserts on
     * what the street was asked to serve.
     */
    private fun streets(served: MutableList<ServiceSubscriptionRequest> = mutableListOf()): ServiceStreets =
        object : ServiceStreets {
            override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
                served.add(request)
                connection.writeLine(
                    ServiceCodec.encodeRecord(
                        cursor = "evt-cursor-v1:${request.runId}:0",
                        record = Json.parseToJsonElement("""{"type":"event"}""").jsonObject,
                        hasMore = false,
                    ),
                )
            }
        }

    /**
     * A transport that hands out the connections it was given, and then none.
     */
    private class ScriptedTransport(connections: List<ScriptedConnection>) : ServiceTransport {

        private val pending = ArrayDeque(connections)

        override fun accept(): ServiceConnection? = pending.removeFirstOrNull()

        override fun close() {
            // The queue survives the close on purpose: a transport that is
            // closed may still hand out a connection that arrived before, and
            // the service has to know what to do with it.
        }
    }

    /**
     * A connection that reads what it was told to read.
     */
    private class ScriptedConnection(
        private val read: ServiceRead,
        private val gate: CountDownLatch? = null,
    ) : ServiceConnection {

        /** The lines the service wrote. */
        val written: MutableList<String> = mutableListOf()

        /** Whether the connection was closed. */
        var closed: Boolean = false
            private set

        private val answered = CountDownLatch(1)

        override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead {
            gate?.await()
            return read
        }

        override fun writeLine(line: String) {
            written.add(line)
            answered.countDown()
        }

        override fun close() {
            closed = true
            answered.countDown()
        }

        /** Lets the connection finish its read. */
        fun release() {
            gate?.countDown()
        }

        /** Waits for the answer, or for the connection to end. */
        fun awaitAnswered() {
            answered.await(5, TimeUnit.SECONDS)
        }
    }

    /**
     * A port that is never asked, because these rows are about the service.
     */
    private class UnusedPort : RuntimeIntrospectionPort {

        override fun inspect(runId: String): RuntimeIntrospectionResult = throw UnsupportedOperationException(
            "these rows do not ask about a run",
        )

        override fun runStructure(
            runId: String,
            options: StructureRunOptions,
        ): RunStructureResult = throw UnsupportedOperationException(
            "these rows do not ask about a run",
        )

        override fun inspectOperation(
            operationId: String,
            attempt: Int,
        ): OperationInspectionResult = throw UnsupportedOperationException(
            "these rows do not ask about a run",
        )
    }

    private companion object {
        /** A question about the version of the product. */
        private const val VERSION_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"version"}"""

        /** A request for the events of a run. */
        private const val EVENTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1"}"""
    }
}
