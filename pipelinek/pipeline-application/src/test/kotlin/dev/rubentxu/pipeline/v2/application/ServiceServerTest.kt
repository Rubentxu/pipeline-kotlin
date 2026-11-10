package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.InputAnswerCodec
import dev.rubentxu.pipeline.v2.runtime.InputDecision
import dev.rubentxu.pipeline.v2.runtime.InputQuestionCodec
import dev.rubentxu.pipeline.v2.runtime.InputQuestionLayout
import dev.rubentxu.pipeline.v2.runtime.PublishedInputQuestion
import dev.rubentxu.pipeline.v2.runtime.control.CancelOutcome
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
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

    @TempDir
    lateinit var inputs: Path

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

        val server = ServiceServer(
            transport,
            operations(),
            streets(),
            authorizations(),
            ServiceLimits(maxConnections = 1),
        )
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

        val server = ServiceServer(
            transport,
            operations(),
            streets(),
            authorizations(),
            ServiceLimits(maxConnections = 1),
        )
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
        val server = ServiceServer(ScriptedTransport(listOf(connection)), operations(), streets(), authorizations())
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
        val server = ServiceServer(ScriptedTransport(listOf(connection)), operations(), streets(served), authorizations())
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
     * A client that presents no credential is refused with a name.
     *
     * The refusal is NAMED and not a closed socket, because a client that
     * cannot tell "you are not allowed" from "the service is broken" cannot
     * act on it: the first is fixed with another token, the second with a
     * restart.
     */
    @Test
    fun `a question with no credential is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_VERSION_REQUEST))

        serve(connection)

        assertEquals(
            "not-authorized",
            refusalKindOf(connection),
            "a client that presents nothing is told so, and not left guessing",
        )
    }

    @Test
    fun `a question with a token the service does not know is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(UNKNOWN_VERSION_REQUEST))

        serve(connection)

        assertEquals(
            "not-authorized",
            refusalKindOf(connection),
            "a token nobody knows is the same fact as no token at all",
        )
    }

    @Test
    fun `a question about a run the scope does not cover is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(RUN_STATE_REQUEST))

        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            streets(),
            authorizations(ServiceScope.Runs(setOf("run-2"))),
        )
        try {
            server.serve()
            connection.awaitAnswered()

            assertEquals(
                "run-not-authorized",
                refusalKindOf(connection),
                "a client that may not ask about THAT run is told so",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a subscription with no credential is refused and the street is not served`() {
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_EVENTS_REQUEST))
        val served = mutableListOf<ServiceSubscriptionRequest>()

        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            streets(served),
            authorizations(),
        )
        try {
            server.serve()
            connection.awaitAnswered()

            assertEquals(
                "not-authorized",
                refusalKindOf(connection),
                "a subscription without a credential is refused by name",
            )
            assertEquals(
                0,
                served.size,
                "the refusal happens BEFORE the subscription takes a slot, so no street is served",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a subscription over the budget is refused and the street is not served`() {
        val streetStarted = CountDownLatch(1)
        val releaseStreet = CountDownLatch(1)
        val admitted = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val arriving = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST), gate = streetStarted)
        val served = mutableListOf<ServiceSubscriptionRequest>()

        val server = ServiceServer(
            ScriptedTransport(listOf(admitted, arriving)),
            operations(),
            blockingStreets(served, streetStarted, releaseStreet),
            authorizations(),
            ServiceLimits(maxSubscriptions = 1),
        )
        try {
            server.serve()
            arriving.awaitAnswered()

            assertEquals(
                "too-many-subscriptions",
                refusalKindOf(arriving),
                "a subscription over the budget is refused by name, and not queued",
            )
            assertEquals(
                1,
                served.size,
                "only the subscription that took a slot reached the street",
            )
        } finally {
            releaseStreet.countDown()
            server.close()
        }
    }

    /**
     * A run id that looks like a path is refused with a name.
     *
     * The refusal happens in the CODEC, before the request reaches the store
     * and before the street opens: a request that named a path would
     * otherwise travel until the plane refused it, and the refusal would
     * arrive as a closed connection instead of as a word the client can read.
     */
    @Test
    fun `a question about a run whose id looks like a path is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(SPOOFED_RUN_STATE_REQUEST))

        serve(connection)

        assertEquals(
            "malformed-request",
            refusalKindOf(connection),
            "a run id that is not a run id is refused by name, and not by a closed connection",
        )
    }

    @Test
    fun `a subscription for a run whose id looks like a path is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(SPOOFED_OUTPUT_REQUEST))
        val served = mutableListOf<ServiceSubscriptionRequest>()

        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            streets(served),
            authorizations(),
        )
        try {
            server.serve()
            connection.awaitAnswered()

            assertEquals(
                "malformed-request",
                refusalKindOf(connection),
                "the street is not opened for a run id that is not a run id",
            )
            assertEquals(
                0,
                served.size,
                "no street was served, because the request never became one",
            )
        } finally {
            server.close()
        }
    }

    /**
     * The slot of a subscription is given back when the street ends.
     *
     * A budget that is only taken and never given back is a budget that runs
     * out after eight streets in the whole life of the service: the counter
     * has to be decremented in the same place the street is left.
     */
    @Test
    fun `the slot of a subscription is given back when the street ends`() {
        val streetEnded = CountDownLatch(1)
        val first = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val second = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val served = mutableListOf<ServiceSubscriptionRequest>()
        val streets = object : ServiceStreets {
            override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
                served.add(request)
                connection.writeLine(
                    ServiceCodec.encodeRecord(
                        cursor = "evt-cursor-v1:${request.runId}:0",
                        record = Json.parseToJsonElement("""{"type":"event"}""").jsonObject,
                        hasMore = false,
                    ),
                )
                streetEnded.countDown()
            }
        }
        // The second connection is handed out only after the first street
        // ended, so the row measures the budget and not the order in which
        // two workers happened to run.
        val transport = object : ServiceTransport {
            private val pending = ArrayDeque(listOf(first, second))

            override fun accept(): ServiceConnection? {
                val next = pending.removeFirstOrNull() ?: return null
                if (next === second) streetEnded.await(5, TimeUnit.SECONDS)
                return next
            }

            override fun close() = Unit
        }

        val server = ServiceServer(transport, operations(), streets, authorizations(), ServiceLimits(maxSubscriptions = 1))
        try {
            server.serve()
            second.awaitAnswered()

            assertEquals(
                2,
                served.size,
                "the second subscription took the slot the first one gave back",
            )
        } finally {
            server.close()
        }
    }

    /**
     * The slot of a subscription is given back when the street REFUSES.
     *
     * A street that refuses ends the same way a street that delivered ends,
     * and a budget that only counts the streets that delivered is a budget
     * that runs out after eight refusals: the counter has to be decremented
     * where the street is left, whatever the street wrote on its way out.
     */
    @Test
    fun `the slot of a subscription is given back when the street refuses`() {
        val streetEnded = CountDownLatch(1)
        val first = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val second = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val served = mutableListOf<ServiceSubscriptionRequest>()
        val streets = object : ServiceStreets {
            override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
                served.add(request)
                connection.writeLine(
                    ServiceCodec.encode(
                        ServiceResponse.Refused(
                            ServiceProtocol.SCHEMA,
                            ServiceRefusal.UnknownRun(request.runId),
                        ),
                    ),
                )
                streetEnded.countDown()
            }
        }
        // The second connection is handed out only after the first street
        // ended, so the row measures the budget and not the order in which
        // two workers happened to run.
        val transport = object : ServiceTransport {
            private val pending = ArrayDeque(listOf(first, second))

            override fun accept(): ServiceConnection? {
                val next = pending.removeFirstOrNull() ?: return null
                if (next === second) streetEnded.await(5, TimeUnit.SECONDS)
                return next
            }

            override fun close() = Unit
        }

        val server = ServiceServer(transport, operations(), streets, authorizations(), ServiceLimits(maxSubscriptions = 1))
        try {
            server.serve()
            second.awaitAnswered()

            assertEquals(
                2,
                served.size,
                "the second subscription took the slot the first one gave back",
            )
        } finally {
            server.close()
        }
    }

    /**
     * A client that may not start runs is told so by name.
     *
     * The refusal is NOT `not-authorized`: the token was accepted, and what
     * it lacks is the right to start. A client that could not tell the two
     * apart would learn which authorizations exist by presenting tokens
     * until one is accepted.
     */
    @Test
    fun `a client that may not start runs is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(START_REQUEST))

        serve(connection)

        assertEquals(
            "start-not-authorized",
            refusalKindOf(connection),
            "a client that may read but not start is told which right it lacks",
        )
    }

    @Test
    fun `a client that may start runs reaches the door of the starts`() {
        val connection = ScriptedConnection(ServiceRead.Line(START_REQUEST))
        val starter = ServiceTestStarter(
            ServiceStartOutcome.Accepted("run-1", "corr-1", "owner-1", 1700L),
        )

        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(starter),
            streets(),
            authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        )
        try {
            server.serve()
            connection.awaitAnswered()
        } finally {
            server.close()
        }

        val answer = answerOf(connection)
        assertEquals("answer", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "run-1",
            answer["result"]?.jsonObject?.get("runId")?.jsonPrimitive?.content,
            "the answer of a start is the identity of the run that started",
        )
        assertEquals(1, starter.asked.size, "the door of the starts was opened once")
        assertEquals("build.kts", starter.asked.single().script)
    }

    @Test
    fun `a client that may not cancel runs is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(CANCEL_REQUEST))

        serve(connection)

        assertEquals(
            "cancel-not-authorized",
            refusalKindOf(connection),
            "a client that may read but not cancel is told which right it lacks",
        )
    }

    @Test
    fun `a client that presents nothing may not cancel runs`() {
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_CANCEL_REQUEST))

        serve(connection)

        assertEquals(
            "not-authorized",
            refusalKindOf(connection),
            "no token is no authorization",
        )
    }

    @Test
    fun `a client that may cancel a run it does not cover is refused with a name`() {
        val connection = ScriptedConnection(ServiceRead.Line(CANCEL_REQUEST))
        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            streets(),
            authorizations(
                ServiceScope.WithRights(
                    ServiceScope.Runs(setOf("another-run")),
                    setOf(ServiceScope.Right.Cancel),
                ),
            ),
        )
        try {
            server.serve()
            connection.awaitAnswered()
        } finally {
            server.close()
        }

        assertEquals(
            "run-not-authorized",
            refusalKindOf(connection),
            "a run the scope does not cover may not be cancelled",
        )
    }

    @Test
    fun `a client that may cancel the runs it covers reaches the door of the cancellations`() {
        val connection = ScriptedConnection(ServiceRead.Line(CANCEL_REQUEST))
        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(cancellations = cancellationOf(CancelOutcome.Cancelled(AttemptId(1), 1700L, 0))),
            streets(),
            authorizations(
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
            ),
        )
        try {
            server.serve()
            connection.awaitAnswered()
        } finally {
            server.close()
        }

        val answer = answerOf(connection)
        assertEquals("answer", answer["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "run-1",
            answer["result"]?.jsonObject?.get("runId")?.jsonPrimitive?.content,
            "the answer of a cancellation is the run that was closed",
        )
    }

    @Test
    fun `a client that may not answer questions is refused with a name for both operations`() {
        listOf(LIST_INPUTS_REQUEST, ANSWER_INPUT_REQUEST).forEach { request ->
            val connection = ScriptedConnection(ServiceRead.Line(request))

            serve(connection)

            assertEquals(
                "answer-not-authorized",
                refusalKindOf(connection),
                "a client that may read but not answer is told which right it lacks: $request",
            )
        }
    }

    @Test
    fun `a client that presents nothing may not list or answer questions`() {
        listOf(LIST_INPUTS_REQUEST, ANSWER_INPUT_REQUEST).forEach { request ->
            val connection = ScriptedConnection(ServiceRead.Line(request.replace(",\"auth\":\"token-1\"", "")))

            serve(connection)

            assertEquals("not-authorized", refusalKindOf(connection), "no token is no authorization: $request")
        }
    }

    @Test
    fun `a client that may answer a run it does not cover is refused with a name`() {
        listOf(LIST_INPUTS_REQUEST, ANSWER_INPUT_REQUEST).forEach { request ->
            val connection = ScriptedConnection(ServiceRead.Line(request))
            val server = ServiceServer(
                ScriptedTransport(listOf(connection)),
                operations(),
                streets(),
                authorizations(
                    ServiceScope.WithRights(
                        ServiceScope.Runs(setOf("another-run")),
                        setOf(ServiceScope.Right.Answer),
                    ),
                ),
            )
            try {
                server.serve()
                connection.awaitAnswered()
            } finally {
                server.close()
            }

            assertEquals(
                "run-not-authorized",
                refusalKindOf(connection),
                "the questions of a run the scope does not cover are neither listed nor answered: $request",
            )
        }
    }

    @Test
    fun `a client that may answer lists the questions and its label is the one that answers`() {
        val opId = "run-1-s0-0"
        val dir = inputs.resolve(InputQuestionLayout.directoryName(opId))
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve(InputQuestionLayout.REQUEST_FILE),
            InputQuestionCodec.encode(
                PublishedInputQuestion(
                    runId = "run-1",
                    opId = opId,
                    message = "Deploy?",
                    ok = "Proceed",
                    submitter = "alice",
                    id = null,
                    publishedAtMs = 1L,
                    expiresAtMs = null,
                ),
            ),
        )
        val authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer)))
        val answers = ServiceInputAnswers(
            inputsRoot = inputs,
            runState = {
                RunStateAnswer.Observed(RuntimeObservation.LiveButEmpty(AttemptId(1), "the run has not written yet"))
            },
            responderOf = authorizations::labelOfDigest,
        )
        val listing = ScriptedConnection(ServiceRead.Line(LIST_INPUTS_REQUEST))
        val answering = ScriptedConnection(ServiceRead.Line(ANSWER_INPUT_REQUEST))
        val server = ServiceServer(
            ScriptedTransport(listOf(listing, answering)),
            operations(inputs = answers),
            streets(),
            authorizations,
        )
        try {
            server.serve()
            listing.awaitAnswered()
            answering.awaitAnswered()
        } finally {
            server.close()
        }

        val listed = answerOf(listing)["result"]?.jsonObject?.get("inputs").toString()
        assertTrue("Deploy?" in listed && opId in listed, "the question of the run is in the list: $listed")
        assertEquals(
            "answered",
            answerOf(answering)["result"]?.jsonObject?.get("outcome")?.jsonPrimitive?.content,
            "the client that holds the right reached the door of the answers",
        )
        assertEquals(
            InputDecision.Proceed("operator", null),
            InputAnswerCodec.decode(Files.readString(dir.resolve(InputQuestionLayout.RESPONSE_FILE))),
            "the responder written is the label of the token the client presented, and alice is only what the pipeline declared",
        )
    }

    /**
     * Serves the scripted connections and waits for the answers.
     */
    private fun serve(vararg connections: ScriptedConnection) {
        val server = ServiceServer(ScriptedTransport(connections.toList()), operations(), streets(), authorizations())
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
     * The authorizations this service knows.
     *
     * One token, with the scope the row needs: the rows about the service
     * ask about anything, and the row about the SCOPE names a run this token
     * may not ask about.
     */
    private fun authorizations(scope: ServiceScope = ServiceScope.All): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", scope)))

    /**
     * The operations the service answers with.
     *
     * The questions of these rows are about the SERVICE — the version, the
     * health, and the shape of a refusal — and not about a run, so the port
     * behind them is never asked.
     */
    private fun operations(
        starter: ServiceRunStarter = ServiceTestStarter(),
        cancellations: ServiceRunCancellation? = null,
        inputs: ServiceInputAnswers? = null,
    ): ServiceOperations =
        ServiceOperations(
            introspection = UnusedPort(),
            substrate = {
                ServiceSubstrate(
                    eventStore = "/tmp/state/pipelinek.db",
                    controlRoot = "/tmp/state/ctl",
                    eventStorePresent = true,
                    controlRootPresent = true,
                )
            },
            starts = starter,
            cancellations = cancellations,
            inputs = inputs,
        )

    /**
     * A cancellation whose port answers [outcome] to every request.
     */
    private fun cancellationOf(outcome: CancelOutcome): ServiceRunCancellation =
        ServiceRunCancellation(
            ender = object : ServiceRunEnder {
                override fun end(runId: String): Boolean = true
            },
            control = { _, _ -> outcome },
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
     * The streets, faked, and one of them BLOCKS.
     *
     * The street counts down [started] when it begins to serve and waits for
     * [release]: the row that measures the budget of the subscriptions needs
     * a subscription that is ALIVE while another one arrives, and a street
     * that ended at once would give the slot back before the second client
     * asked for it.
     */
    private fun blockingStreets(
        served: MutableList<ServiceSubscriptionRequest>,
        started: CountDownLatch,
        release: CountDownLatch,
    ): ServiceStreets = object : ServiceStreets {
        override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
            served.add(request)
            started.countDown()
            release.await()
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
        /** The token this service knows. */
        private const val TOKEN: String = "token-1"

        /** A question about the version of the product, with the credential. */
        private const val VERSION_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"version","auth":"token-1"}"""

        /** A question about the version of the product, with NO credential. */
        private const val ANONYMOUS_VERSION_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"version"}"""

        /** A question about the version of the product, with a token nobody knows. */
        private const val UNKNOWN_VERSION_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"version","auth":"token-of-another"}"""

        /** A request for the events of a run, with the credential. */
        private const val EVENTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1","auth":"token-1"}"""

        /** A request for the events of a run, with NO credential. */
        private const val ANONYMOUS_EVENTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1"}"""

        /** A question about the state of a run, with the credential. */
        private const val RUN_STATE_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"run-1","auth":"token-1"}"""

        /** A question whose run id is a path and not a run id. */
        private const val SPOOFED_RUN_STATE_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"../../etc/passwd","auth":"token-1"}"""

        /** A subscription whose run id is a path and not a run id. */
        private const val SPOOFED_OUTPUT_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"../../etc/passwd","auth":"token-1"}"""

        /** A request to start a run, with the credential. */
        private const val START_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","auth":"token-1"}"""

        /** A request to cancel a run, with the credential. */
        private const val CANCEL_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1","auth":"token-1"}"""

        /** A request to cancel a run, with NO credential. */
        private const val ANONYMOUS_CANCEL_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1"}"""

        /** A request to list the questions of a run, with the credential. */
        private const val LIST_INPUTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"inputs.list","runId":"run-1","auth":"token-1"}"""

        /** A request to answer a question of a run, with the credential. */
        private const val ANSWER_INPUT_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"run-1-s0-0","decision":"proceed","auth":"token-1"}"""
    }
}
