package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire contract of the local service.
 *
 * ## The guarantee under trial
 *
 * A line that arrives on the socket means EXACTLY one thing, and the meaning is
 * a typed value: a request the service can answer, or a refusal it can render.
 * A line that is not JSON, a line that asks for an operation the service does
 * not have, and a line that speaks another schema are three DIFFERENT facts,
 * and a client that cannot tell them apart cannot decide whether to retry, to
 * upgrade, or to give up.
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
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1","op":"version"}""")

        assertEquals(
            ServiceRequest.Version(ServiceProtocol.SCHEMA),
            assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request,
            "the request names the version operation and the schema it speaks",
        )
    }

    @Test
    fun `a request for the health decodes`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1","op":"health"}""")

        assertEquals(
            ServiceRequest.Health(ServiceProtocol.SCHEMA),
            assertInstanceOf(ServiceDecoding.Decoded::class.java, decoded).request,
            "the request names the health operation",
        )
    }

    @Test
    fun `a request for the state of a run decodes with its run`() {
        val decoded = ServiceCodec.decode(
            """{"schema":"pipelinek.service/1","op":"run.state","runId":"run-1"}""",
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
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1"}""")

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a request without an operation says nothing the service can answer",
        )
    }

    @Test
    fun `a request for an operation the service does not have is refused as an unknown op`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1","op":"run.cancel"}""")

        assertEquals(
            ServiceRefusal.UnknownOp,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "an operation the service does not have is refused by name",
        )
    }

    @Test
    fun `a request for the state of a run without naming the run is refused`() {
        val decoded = ServiceCodec.decode("""{"schema":"pipelinek.service/1","op":"run.state"}""")

        assertEquals(
            ServiceRefusal.MalformedRequest,
            assertInstanceOf(ServiceDecoding.Refused::class.java, decoded).refusal,
            "a question about a run that does not name the run has no answer",
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
        assertTrue(decoded["ok"]?.jsonPrimitive?.boolean ?: false, "the answer says that it answered")
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
            false,
            decoded["ok"]?.jsonPrimitive?.boolean,
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
