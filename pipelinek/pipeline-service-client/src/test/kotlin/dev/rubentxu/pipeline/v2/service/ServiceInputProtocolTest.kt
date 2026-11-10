package dev.rubentxu.pipeline.v2.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The wire of the requests that LIST and ANSWER the questions of a run, and of the refusals they
 * are answered with.
 *
 * ## The guarantee under trial
 *
 * That an answer is a NAMED operation whose decision is one of two words and whose every bound is
 * refused before the service looks at any question; that the request carries no name of the
 * responder, so nobody can be attributed an answer by what a line says; and that the two refusals
 * arrive with a name of their own, so a client can tell "you may not answer" from "that question
 * cannot be answered".
 *
 * ## The capacity this serves
 *
 * A run that reaches an `input` step waits for a decision from somebody who is not the run. The
 * vocabulary of the wire is what lets a client list what is waiting, answer it once, and retry an
 * answer whose reply was lost without reading the retry as a conflict.
 *
 * ## Why this instrument
 *
 * The codec is a pure function of a line, so the cheapest instrument that can fail is a line and
 * the value it decodes to. The client is tried against a double of the connection, because what it
 * decides —what it writes and what it refuses to read— is a property of the lines.
 *
 * ## What this does not freeze
 *
 * Which questions a service holds, whether the credential may answer them, and the sentences of
 * the refusals: only the names travel on the wire, and the names are the contract.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServiceInputProtocolTest {

    // --- listing ---

    @Test
    fun `a request to list the questions of a run decodes with the run it names`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"inputs.list","runId":"run-42"}""")

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(ServiceRequest.ListInputs(ServiceProtocol.SCHEMA, "run-42"), request)
    }

    @Test
    fun `a request to list the questions without a run that can be named is refused`() {
        val lines = listOf(
            """{"schema":"pipelinek.service/2","op":"inputs.list"}""",
            """{"schema":"pipelinek.service/2","op":"inputs.list","runId":"../escape"}""",
            """{"schema":"pipelinek.service/2","op":"inputs.list","runId":"${"r".repeat(ServiceProtocol.MAX_RUN_ID_LENGTH + 1)}"}""",
        )

        lines.forEach { line ->
            assertInstanceOf(ServiceDecoding.Refused::class.java, ServiceCodec.decode(line), line)
        }
    }

    // --- answering ---

    @Test
    fun `a request to answer decodes with the run, the operation, the decision and the message`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"run-1-s0-2",""" +
                """"decision":"proceed","message":"go"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", "run-1-s0-2", ServiceInputDecision.PROCEED, "go"),
            request,
        )
    }

    @Test
    fun `an abort decodes as an abort and a missing message as no message`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"run-1-s0-2",""" +
                """"decision":"abort"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", "run-1-s0-2", ServiceInputDecision.ABORT, null),
            request,
        )
    }

    @Test
    fun `a request to answer that breaks a rule of the wire is refused before any question is read`() {
        val head = """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"run-1-s0-2","""
        val rows: List<Pair<String, String>> = listOf(
            "no run" to """{"schema":"pipelinek.service/2","op":"input.answer","opId":"x","decision":"proceed"}""",
            "a run that names a path" to
                """{"schema":"pipelinek.service/2","op":"input.answer","runId":"../x","opId":"x","decision":"proceed"}""",
            "no operation" to """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","decision":"proceed"}""",
            "an empty operation" to
                """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"","decision":"proceed"}""",
            "an operation past the bound" to
                """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1",""" +
                """"opId":"${"o".repeat(ServiceProtocol.MAX_INPUT_OP_ID_LENGTH + 1)}","decision":"proceed"}""",
            "no decision" to """$head"message":"go"}""",
            "a decision in other words" to """$head"decision":"approve"}""",
            "a decision in capitals" to """$head"decision":"PROCEED"}""",
            "an empty decision" to """$head"decision":""}""",
            "a decision that is not text" to """$head"decision":{"word":"proceed"}}""",
            "a message past the bound" to
                """$head"decision":"proceed","message":"${"m".repeat(ServiceProtocol.MAX_INPUT_ANSWER_MESSAGE_LENGTH + 1)}"}""",
            "a message with a line break" to """$head"decision":"proceed","message":"first\nsecond"}""",
        )

        rows.forEach { (name, line) ->
            val decoded = ServiceCodec.decode(line)
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded, name)
            assertEquals(ServiceRefusal.MalformedRequest, (decoded as ServiceDecoding.Refused).refusal, name)
        }
    }

    @Test
    fun `the longest operation and the longest message the wire allows are accepted`() {
        val opId = "o".repeat(ServiceProtocol.MAX_INPUT_OP_ID_LENGTH)
        val message = "m".repeat(ServiceProtocol.MAX_INPUT_ANSWER_MESSAGE_LENGTH)

        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"$opId",""" +
                """"decision":"proceed","message":"$message"}""",
        )

        val request = assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request
        assertEquals(
            ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", opId, ServiceInputDecision.PROCEED, message),
            request,
            "the bound is a limit and its last value is legal",
        )
    }

    @Test
    fun `every decision the client can write is read back as the same decision`() {
        ServiceInputDecision.entries.forEach { decision ->
            val request = ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", "run-1-s0-2", decision, "why")

            val decoded = ServiceCodec.decode(ServiceCodec.encode(request, "token-1"))

            assertEquals(request, assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request)
        }
        assertEquals(
            listOf("proceed", "abort"),
            ServiceInputDecision.entries.map { it.word },
            "the closed vocabulary of a question is two words and the wire has the same two",
        )
    }

    @Test
    fun `a request to answer is written with the names it carries and with no name of the responder`() {
        val line = ServiceCodec.encode(
            ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", "run-1-s0-2", ServiceInputDecision.ABORT, "no"),
            "token-1",
        )

        val document = Json.parseToJsonElement(line).jsonObject
        assertEquals("input.answer", document["op"]?.jsonPrimitive?.content)
        assertEquals("run-1", document["runId"]?.jsonPrimitive?.content)
        assertEquals("run-1-s0-2", document["opId"]?.jsonPrimitive?.content)
        assertEquals("abort", document["decision"]?.jsonPrimitive?.content)
        assertEquals("no", document["message"]?.jsonPrimitive?.content)
        assertEquals(
            setOf("schema", "auth", "op", "runId", "opId", "decision", "message"),
            document.keys,
            "the line says who is asking only through the credential",
        )
    }

    @Test
    fun `a request to answer without a message writes no message`() {
        val line = ServiceCodec.encode(
            ServiceRequest.AnswerInput(ServiceProtocol.SCHEMA, "run-1", "run-1-s0-2", ServiceInputDecision.PROCEED, null),
            "token-1",
        )

        assertNull(Json.parseToJsonElement(line).jsonObject["message"], "an absent message is not written as an empty one")
    }

    @Test
    fun `a request to list is written with the names it carries`() {
        val line = ServiceCodec.encode(ServiceRequest.ListInputs(ServiceProtocol.SCHEMA, "run-1"), "token-1")

        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"inputs.list","runId":"run-1"}""",
            line,
        )
    }

    // --- refusals ---

    @Test
    fun `the refusals about the questions carry a kind of their own and keep their facts`() {
        val rows: List<Pair<ServiceRefusal, String>> = listOf(
            ServiceRefusal.AnswerNotAuthorized to """{"kind":"answer-not-authorized"}""",
            ServiceRefusal.InputAnswerRefused("already-answered", "another answer was recorded first") to
                """{"kind":"input-answer-refused","reason":"already-answered",""" +
                """"detail":"another answer was recorded first"}""",
        )

        assertEquals(rows.size, rows.map { it.first.kind }.toSet().size, "two refusals share a kind")
        rows.forEach { (refusal, expected) ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            assertEquals(expected, Json.parseToJsonElement(line).jsonObject["refusal"]?.jsonObject?.toString())
            val back = assertInstanceOf(ServiceLine.Refused::class.java, ServiceCodec.decodeLine(line)).refusal
            assertEquals(refusal, back, "the refusal is read back with its name and its facts")
        }
    }

    @Test
    fun `a refusal of an answer that lost its reason or its detail is not read as a line`() {
        listOf(
            """{"kind":"input-answer-refused"}""",
            """{"kind":"input-answer-refused","detail":"another answer was recorded first"}""",
            """{"kind":"input-answer-refused","reason":"already-answered"}""",
        ).forEach { refusal ->
            val line = """{"schema":"pipelinek.service/2","kind":"refused","refusal":$refusal}"""

            assertNull(ServiceCodec.decodeLine(line), "a refusal that lost a fact cannot be shown as whole: $refusal")
        }
    }

    // --- the client ---

    @Test
    fun `the client lists the questions and reads each fact of them`() {
        val channel = FakeChannel(
            """{"schema":"pipelinek.service/2","kind":"answer","result":{"inputs":[""" +
                """{"opId":"run-1-s0-2","message":"Deploy?","ok":"Proceed","id":"gate",""" +
                """"declaredSubmitter":"ops","publishedAtMs":1000,"expiresAtMs":6000},""" +
                """{"opId":"run-1-s1-0","message":"Again?","ok":"Yes","id":null,""" +
                """"declaredSubmitter":null,"publishedAtMs":2000,"expiresAtMs":null}]}}""",
        )

        val answer = client(channel).pendingInputs("run-1")

        val questions = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
        assertEquals(
            listOf(
                ServiceInputQuestion("run-1-s0-2", "Deploy?", "Proceed", "gate", "ops", 1000L, 6000L),
                ServiceInputQuestion("run-1-s1-0", "Again?", "Yes", null, null, 2000L, null),
            ),
            questions,
        )
        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"inputs.list","runId":"run-1"}""",
            channel.written.single(),
        )
    }

    @Test
    fun `a run that asks nothing is an empty list and not a failure`() {
        val channel = FakeChannel("""{"schema":"pipelinek.service/2","kind":"answer","result":{"inputs":[]}}""")

        val answer = client(channel).pendingInputs("run-1")

        assertEquals(emptyList<ServiceInputQuestion>(), assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value)
    }

    @Test
    fun `an answer that lists questions it cannot read is not read as a list`() {
        val noOperation = """{"message":"Deploy?","ok":"Proceed","publishedAtMs":1000}"""
        val noPublication = """{"opId":"run-1-s0-2","message":"Deploy?","ok":"Proceed"}"""
        val notAnObject = "\"run-1-s0-2\""
        listOf(
            """{"inputs":[$noOperation]}""",
            """{"inputs":[$noPublication]}""",
            """{"inputs":[$notAnObject]}""",
            """{"inputs":"none"}""",
            """{}""",
        ).forEach { result ->
            val channel = FakeChannel("""{"schema":"pipelinek.service/2","kind":"answer","result":$result}""")

            val answer = client(channel).pendingInputs("run-1")

            assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer, result)
        }
    }

    @Test
    fun `the client answers a question and reads what was recorded`() {
        val channel = FakeChannel(
            """{"schema":"pipelinek.service/2","kind":"answer","result":{"runId":"run-1",""" +
                """"opId":"run-1-s0-2","decision":"proceed","outcome":"answered"}}""",
        )

        val answer = client(channel).answerInput("run-1", "run-1-s0-2", ServiceInputDecision.PROCEED, "go")

        assertEquals(
            ServiceInputAnswer("run-1", "run-1-s0-2", ServiceInputDecision.PROCEED, "answered"),
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
        assertEquals(
            """{"schema":"pipelinek.service/2","auth":"token-1","op":"input.answer","runId":"run-1",""" +
                """"opId":"run-1-s0-2","decision":"proceed","message":"go"}""",
            channel.written.single(),
        )
    }

    @Test
    fun `a refusal of an answer comes back with its name and not as an answer`() {
        listOf(
            """{"kind":"answer-not-authorized"}""" to ServiceRefusal.AnswerNotAuthorized,
            """{"kind":"input-answer-refused","reason":"expired","detail":"the run stopped waiting"}""" to
                ServiceRefusal.InputAnswerRefused("expired", "the run stopped waiting"),
        ).forEach { (refusal, expected) ->
            val channel = FakeChannel("""{"schema":"pipelinek.service/2","kind":"refused","refusal":$refusal}""")

            val answer = client(channel).answerInput("run-1", "run-1-s0-2", ServiceInputDecision.ABORT)

            assertEquals(expected, assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal)
        }
    }

    @Test
    fun `an answer of the service that lacks a fact or speaks another word is not read as a success`() {
        listOf(
            """{"opId":"o","decision":"proceed","outcome":"answered"}""",
            """{"runId":"run-1","decision":"proceed","outcome":"answered"}""",
            """{"runId":"run-1","opId":"o","outcome":"answered"}""",
            """{"runId":"run-1","opId":"o","decision":"approve","outcome":"answered"}""",
            """{"runId":"run-1","opId":"o","decision":"proceed"}""",
        ).forEach { result ->
            val channel = FakeChannel("""{"schema":"pipelinek.service/2","kind":"answer","result":$result}""")

            val answer = client(channel).answerInput("run-1", "o", ServiceInputDecision.PROCEED)

            assertInstanceOf(ServiceAnswer.Unreadable::class.java, answer, result)
        }
    }

    @Test
    fun `there is no service and the client says so when it lists or answers`() {
        val listed = client(ThrowingChannel()).pendingInputs("run-1")
        val answered = client(ThrowingChannel()).answerInput("run-1", "o", ServiceInputDecision.PROCEED)

        assertTrue(assertInstanceOf(ServiceAnswer.Unreachable::class.java, listed).detail.isNotBlank())
        assertTrue(assertInstanceOf(ServiceAnswer.Unreachable::class.java, answered).detail.isNotBlank())
    }

    // --- fixtures ---

    private fun client(channel: ServiceChannel): ServiceClient = ServiceClient(
        endpoint = Path.of("/tmp/service.sock"),
        credential = "token-1",
        deadline = null,
        schema = ServiceProtocol.SCHEMA,
        channels = { _, _ -> channel },
    )

    /** A connection that answers with the line it was given. */
    private class FakeChannel(private val line: String) : ServiceChannel {

        /** The lines the client wrote. */
        val written = mutableListOf<String>()

        private var read = false

        override fun write(line: String) {
            written += line
        }

        override fun readLine(): ServiceRead =
            if (!read) {
                read = true
                ServiceRead.Line(line)
            } else {
                ServiceRead.Closed
            }

        override fun close() = Unit
    }

    /** A connection that cannot be opened: there is no service at the endpoint. */
    private class ThrowingChannel : ServiceChannel {
        override fun write(line: String) = throw IOException("no service at the endpoint")
        override fun readLine(): ServiceRead = ServiceRead.Closed
        override fun close() = Unit
    }
}
