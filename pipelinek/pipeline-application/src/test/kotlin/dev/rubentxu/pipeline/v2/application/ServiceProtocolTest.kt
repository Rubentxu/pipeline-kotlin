package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The wire contract of the local service.
 *
 * ## The guarantee under trial
 *
 * A line that arrives on the socket means EXACTLY one thing, and the meaning is
 * a typed value: a request the service can answer, a request for a street it
 * can serve, or a refusal it can render. A line that is not JSON, a line that
 * asks for an operation the service does not have, and a line that speaks
 * another schema are three DIFFERENT facts, and a client that cannot tell them
 * apart cannot decide whether to retry, to upgrade, or to give up.
 *
 * ## The capacity this serves
 *
 * A supervisor that talks to the service over a socket has to be able to read
 * the answer without parsing prose: the version the service speaks, the
 * operation it asked for, and the reason it was refused. The refusal names a
 * cause the client can act on differently — a version to upgrade to, a request
 * to fix, a load to shed — and that difference lives in the `kind`, not in a
 * sentence.
 *
 * ## Why this instrument
 *
 * The codec is a pure function of a line: no socket, no store, no clock, no
 * process. The cheapest instrument that can fail is a line and the value it
 * decodes to. The rows below walk every way a line can be wrong, because a
 * codec that only knows the happy path is a codec whose failures are
 * exceptions.
 *
 * ## What this does not freeze
 *
 * What the service DOES with a request, and the transport that carries it.
 */
class ServiceProtocolTest {

    @Test
    fun `a request for the version decodes`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"version"}""")

        assertEquals(
            ServiceRequest.Version(ServiceProtocol.SCHEMA),
            assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request,
            "the request names the version operation and the schema it speaks",
        )
    }

    @Test
    fun `a request for the health decodes`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"health"}""")

        assertEquals(
            ServiceRequest.Health(ServiceProtocol.SCHEMA),
            assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request,
            "the request names the health operation",
        )
    }

    @Test
    fun `a request for the state of a run decodes with its run`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"run-1"}""",
        )

        assertEquals(
            ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"),
            assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request,
            "the request carries the run it asks about",
        )
    }

    @Test
    fun `a request that speaks another schema is refused as an incompatible protocol`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/9","op":"version"}""")

        assertEquals(
            ServiceRefusal.IncompatibleProtocol,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a client that speaks another protocol is refused, not answered",
        )
    }

    @Test
    fun `the protocol this build served before the subscriptions is refused`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1","op":"version"}""")

        assertEquals(
            ServiceRefusal.IncompatibleProtocol,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a client of the previous protocol is refused, because the life of a connection changed",
        )
    }

    @Test
    fun `a request that does not say which protocol it speaks is refused`() {
        val decoded = ServiceCodec.decode("""{"op":"version"}""")

        assertEquals(
            ServiceRefusal.IncompatibleProtocol,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a request without a schema is a request whose protocol is unknown",
        )
    }

    @Test
    fun `a line that is not json is refused as a malformed request`() {
        val decoded = ServiceCodec.decode("this is not a request")

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a line that is not a request is refused, and refused with a reason",
        )
    }

    @Test
    fun `a request without an operation is refused as a malformed request`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2"}""")

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a request without an operation says nothing the service can answer",
        )
    }

    @Test
    fun `a request for an operation the service does not have is refused as an unknown op`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"run.cancel"}""")

        assertEquals(
            ServiceRefusal.UnknownOp,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "an operation the service does not have is refused by name",
        )
    }

    @Test
    fun `a request for the state of a run without naming the run is refused`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/2","op":"run.state"}""")

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a question about a run that does not name the run has no answer",
        )
    }

    @Test
    fun `a request for the events of a run decodes as a subscription`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1"}""",
        )

        assertEquals(
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
            assertInstanceOf(ServiceDecoding.Subscribed::class.java, decoded).request,
            "a subscription is not a question: it is a request for a street",
        )
    }

    @Test
    fun `a request for the events of a run decodes with the cursor it names`() {
        val cursor = EventCursor("run-1", 7L)

        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1",""" +
                """"after":"${cursor.encode()}"}""",
        )

        assertEquals(
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", cursor),
            assertInstanceOf(ServiceDecoding.Subscribed::class.java, decoded).request,
            "the cursor travels DECODED, so the street never has to distrust it",
        )
    }

    @Test
    fun `a request for the console of a run decodes as a subscription`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"run-1"}""",
        )

        assertEquals(
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
            assertInstanceOf(ServiceDecoding.Subscribed::class.java, decoded).request,
            "without a cursor the street starts before the first frame of the run",
        )
    }

    @Test
    fun `a request for the console of a run decodes with the ordinal it names`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"run-1",""" +
                """"after":"out-frame-ord-v1:12"}""",
        )

        assertEquals(
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", 12L),
            assertInstanceOf(ServiceDecoding.Subscribed::class.java, decoded).request,
            "the ordinal travels decoded, and it is the position the street resumes from",
        )
    }

    @Test
    fun `a subscription that does not name the run is refused`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow"}""",
        )

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a street without a run has no history to serve",
        )
    }

    @Test
    fun `an events cursor of ANOTHER run is refused by name`() {
        val foreign = EventCursor("run-other", 7L)

        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1",""" +
                """"after":"${foreign.encode()}"}""",
        )

        assertEquals(
            ServiceRefusal.InvalidCursor(foreign.encode(), "evt-cursor-v1"),
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "resuming from another run's cursor would skip the history of the run that WAS asked for",
        )
    }

    @Test
    fun `a cursor of the OTHER plane is refused by name in the events street`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1",""" +
                """"after":"out-cursor-v1:run-1/op-1:40"}""",
        )

        assertEquals(
            ServiceRefusal.InvalidCursor("out-cursor-v1:run-1/op-1:40", "evt-cursor-v1"),
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a byte cursor is a position in bytes, and resuming from it would advance the client " +
                "to a position in the wrong plane",
        )
    }

    @Test
    fun `a cursor of the OTHER plane is refused by name in the output street`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"output.follow","runId":"run-1",""" +
                """"after":"evt-cursor-v1:run-1:7"}""",
        )

        assertEquals(
            ServiceRefusal.InvalidCursor("evt-cursor-v1:run-1:7", "out-frame-ord-v1"),
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "an event cursor is a position in a sequence, and the console street reads ordinals",
        )
    }

    @Test
    fun `a cursor that does not decode at all is refused by name`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1",""" +
                """"after":"not-a-cursor"}""",
        )

        assertEquals(
            ServiceRefusal.InvalidCursor("not-a-cursor", "evt-cursor-v1"),
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a token that does not decode is not a position, and the client is told which " +
                "family of positions it should have offered",
        )
    }

    @Test
    fun `an answer says the schema it speaks and that it answered`() {
        val line = ServiceCodec.encode(
            ServiceResponse.Answered(
                ServiceProtocol.SCHEMA,
                Json.parseToJsonElement("""{"productVersion":"0.95.0"}""").jsonObject,
            ),
        )

        val decoded = Json.parseToJsonElement(line).jsonObject

        assertEquals(ServiceProtocol.SCHEMA, decoded["schema"]?.jsonPrimitive?.content)
        assertEquals("answer", decoded["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "0.95.0",
            decoded["result"]?.jsonObject?.get("productVersion")?.jsonPrimitive?.content,
            "the result travels whole",
        )
    }

    @Test
    fun `a refusal says the schema it speaks and the reason it refused`() {
        val line = ServiceCodec.encode(
            ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.UnknownRun("run-7")),
        )

        val decoded = Json.parseToJsonElement(line).jsonObject
        val refusal = decoded["refusal"]?.jsonObject

        assertEquals(ServiceProtocol.SCHEMA, decoded["schema"]?.jsonPrimitive?.content)
        assertEquals(
            "refused",
            decoded["kind"]?.jsonPrimitive?.content,
            "a refusal is not an answer, and it says so",
        )
        assertEquals("unknown-run", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals(
            "run-7",
            refusal?.get("runId")?.jsonPrimitive?.content,
            "the refusal keeps the fact it carried",
        )
    }

    @Test
    fun `a record carries its cursor, its document and whether more follows`() {
        val line = ServiceCodec.encodeRecord(
            cursor = "evt-cursor-v1:run-1:7",
            record = Json.parseToJsonElement("""{"type":"event","kind":"RunStarted"}""").jsonObject,
            hasMore = true,
        )

        val decoded = Json.parseToJsonElement(line).jsonObject

        assertEquals(ServiceProtocol.SCHEMA, decoded["schema"]?.jsonPrimitive?.content)
        assertEquals("record", decoded["kind"]?.jsonPrimitive?.content)
        assertEquals("evt-cursor-v1:run-1:7", decoded["cursor"]?.jsonPrimitive?.content)
        assertEquals(
            "true",
            decoded["hasMore"]?.jsonPrimitive?.content,
            "the record says whether more of this lane follows it",
        )
        assertEquals(
            "RunStarted",
            decoded["record"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            "the document travels whole",
        )
    }

    @Test
    fun `the end of a street carries its reason and its final position`() {
        val line = ServiceCodec.encodeEnd(ServiceEndReason.TerminalObserved, "evt-cursor-v1:run-1:9")

        val decoded = Json.parseToJsonElement(line).jsonObject

        assertEquals(ServiceProtocol.SCHEMA, decoded["schema"]?.jsonPrimitive?.content)
        assertEquals("end", decoded["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", decoded["reason"]?.jsonPrimitive?.content)
        assertEquals(
            "evt-cursor-v1:run-1:9",
            decoded["cursor"]?.jsonPrimitive?.content,
            "the end carries the position the street stood at",
        )
    }

    @Test
    fun `the vocabulary of the end of a street is ONE word`() {
        val words = ServiceEndReason.entries.map { it.word }

        assertEquals(
            listOf("terminal-observed"),
            words,
            "the vocabulary names one way a street can end, and a word for the outcome of the " +
                "run would be a second opinion about another authority's answer",
        )
    }

    @Test
    fun `every refusal carries a kind of its own and no two kinds collide`() {
        val refusals: List<ServiceRefusal> = listOf(
            ServiceRefusal.UnknownRun("run-1"),
            ServiceRefusal.NotConfiguredControlRoot,
            ServiceRefusal.NotConfiguredEventPlane,
            ServiceRefusal.NotConfiguredOutputPlane,
            ServiceRefusal.StorageFailed("the disk is gone"),
            ServiceRefusal.HeldByAnother("owner-1", 7L),
            ServiceRefusal.Inconsistent("the journal and the lease disagree"),
            ServiceRefusal.UnknownRunOperation("op-1", 2),
            ServiceRefusal.IncompatibleProtocol,
            ServiceRefusal.MalformedRequest,
            ServiceRefusal.RequestTooLarge,
            ServiceRefusal.TooManyConnections,
            ServiceRefusal.UnknownOp,
            ServiceRefusal.InvalidCursor("not-a-cursor", "evt-cursor-v1"),
            ServiceRefusal.CursorBeyondTail("run-1", 40L, 12L),
            ServiceRefusal.OutputPlaneRefused("retention-gap", "the bytes are gone"),
        )

        val kinds = refusals.map { it.kind }

        assertEquals(
            refusals.size,
            kinds.toSet().size,
            "two refusals share a kind, so a client cannot tell them apart: $kinds",
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
    fun `the refusal of the run state keeps the fact it carried`() {
        val rows: List<Pair<ServiceRefusal, JsonObject>> = listOf(
            ServiceRefusal.UnknownRun("run-1") to Json.parseToJsonElement(
                """{"kind":"unknown-run","runId":"run-1"}""",
            ).jsonObject,
            ServiceRefusal.NotConfiguredControlRoot to Json.parseToJsonElement(
                """{"kind":"not-configured-control-root"}""",
            ).jsonObject,
            ServiceRefusal.NotConfiguredEventPlane to Json.parseToJsonElement(
                """{"kind":"not-configured-event-plane"}""",
            ).jsonObject,
            ServiceRefusal.NotConfiguredOutputPlane to Json.parseToJsonElement(
                """{"kind":"not-configured-output-plane"}""",
            ).jsonObject,
            ServiceRefusal.StorageFailed("the disk is gone") to Json.parseToJsonElement(
                """{"kind":"storage-failed","cause":"the disk is gone"}""",
            ).jsonObject,
            ServiceRefusal.HeldByAnother("owner-1", 7L) to Json.parseToJsonElement(
                """{"kind":"held-by-another","heldBy":"owner-1","fencingToken":7}""",
            ).jsonObject,
            ServiceRefusal.Inconsistent("the journal and the lease disagree") to
                Json.parseToJsonElement(
                    """{"kind":"inconsistent-lease-state","details":"the journal and the lease disagree"}""",
                ).jsonObject,
            ServiceRefusal.UnknownRunOperation("op-1", 2) to Json.parseToJsonElement(
                """{"kind":"unknown-operation","operationId":"op-1","attempt":2}""",
            ).jsonObject,
            ServiceRefusal.InvalidCursor("nonsense", "evt-cursor-v1") to Json.parseToJsonElement(
                """{"kind":"invalid-cursor","cursor":"nonsense","expected":"evt-cursor-v1"}""",
            ).jsonObject,
            ServiceRefusal.CursorBeyondTail("run-1", 40L, 12L) to Json.parseToJsonElement(
                """{"kind":"cursor-beyond-tail","runId":"run-1","requestedSequence":40,""" +
                    """"tailSequence":12}""",
            ).jsonObject,
            ServiceRefusal.OutputPlaneRefused("retention-gap", "the bytes are gone") to
                Json.parseToJsonElement(
                    """{"kind":"output-plane-refused","reason":"retention-gap",""" +
                        """"detail":"the bytes are gone"}""",
                ).jsonObject,
        )

        rows.forEach { (refusal, expected) ->
            val line = ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))
            assertEquals(
                expected,
                Json.parseToJsonElement(line).jsonObject["refusal"]?.jsonObject,
                "the wire lost the fact of $refusal",
            )
        }
    }
}
