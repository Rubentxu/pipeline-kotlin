package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire contract of the local service.
 *
 * ## What this is
 *
 * ONE line in, ONE line out, in JSON. The product already speaks JSON on the
 * wire (`pipelinek.capabilities/2`, the `core.*` payloads) and this contract
 * follows the same convention: the version of the document travels in a
 * `schema` field whose value is a string "name/version", and the discriminator
 * of what the line is travels in a `kind`.
 *
 * ## Why the version is a string and not a number
 *
 * `pipelinek capabilities` publishes `pipelinek.capabilities/2`, and the
 * `core.*` payloads publish their own discriminator with the same shape. A
 * number would be a THIRD way of saying the same thing, and the reader that
 * has to learn three conventions is the reader that gets one of them wrong.
 *
 * ## Why there is one version rule
 *
 * The server serves `pipelinek.service/2` and refuses every other schema with
 * `incompatible-protocol`. The rule is an EXACT match, and not "compatible
 * major": with a single version number, "compatible major" admits two opposite
 * readings (a smaller client IS compatible / any different one is NOT), and a
 * contract that contradicts itself in two consecutive sentences is not a
 * contract. Relaxing the rule is a change of the protocol version, not a
 * decision a slice takes.
 *
 * ## Why the version is 2 and not 1
 *
 * `/1` was the protocol of the service that answers and closes: one request per
 * connection, one answer per connection. A SUBSCRIPTION changes that — the
 * connection stays open and the server writes a street of frames on it — so a
 * client has to be able to tell from the schema alone which life the connection
 * will have. The version is the field that tells it.
 *
 * The jump costs no consumer outside this repository, and that is measured
 * rather than assumed: the published release 0.95.0 was cut at
 * 2026-11-09T15:10:34Z, the verb `serve` landed after that cut, and
 * `pipeline-application` is not published. So `pipelinek.service/1` was never
 * in an artifact anybody could hold.
 *
 * ## What the refusal carries
 *
 * Every refusal has a `kind`, and the kinds do not collide: a client tells
 * apart a run nobody knows from a substrate that is not configured from a
 * store that failed, and it does it by reading a word, not by parsing prose.
 * The two vocabularies that meet here — the run's own negatives and the
 * protocol's — are separate, and their names do not overlap: the protocol's
 * unknown operation is `unknown-op` and the run's is `unknown-operation`.
 */
internal object ServiceProtocol {

    /** The schema this build serves. The version travels with every answer. */
    const val SCHEMA: String = "pipelinek.service/2"

    /**
     * The biggest line of request this build reads, in bytes.
     *
     * A line bigger than this is refused before it is parsed: reading it whole
     * would let a client make the service hold whatever it wants in memory.
     */
    const val MAX_LINE_BYTES: Int = 64 * 1024
}

/**
 * What a client asked for.
 *
 * Every case carries the [schema] the client spoke, because the answer says
 * which schema it speaks and a client that cannot compare the two has nothing
 * to compare.
 */
internal sealed interface ServiceRequest {

    /** The schema the client says it speaks. */
    val schema: String

    /** The version of the product and the version of the protocol. */
    data class Version(override val schema: String) : ServiceRequest

    /** The health of the SERVICE and of the substrate it was composed with. */
    data class Health(override val schema: String) : ServiceRequest

    /** The state of one run. */
    data class RunState(override val schema: String, val runId: String) : ServiceRequest
}

/**
 * What a client asked to SUBSCRIBE to.
 *
 * ## Why a second ADT and not two more cases of [ServiceRequest]
 *
 * A request that is answered is a request whose answer is ONE line. A
 * subscription is a request whose answer is a STREET of lines, and the two
 * differ from the first moment: the first is closed with its answer, the
 * second lives until the street ends. One ADT for both would force every
 * reader of [ServiceRequest] to carry a case with no answer, and the compiler
 * could not tell which case is which.
 *
 * ## Why the cursor travels DECODED
 *
 * The cursor is the position the client resumes from, and it is checked
 * before the service does any work: a token of the other plane, a token that
 * does not decode, and a token minted for another run are three DIFFERENT
 * facts, and all three are refusals with a name. A cursor that reached the
 * street undecoded would be a cursor the street would have to distrust, and
 * distrusting a cursor is what this contract exists to prevent.
 */
internal sealed interface ServiceSubscriptionRequest {

    /** The schema the client says it speaks. */
    val schema: String

    /** The run whose street the client wants. */
    val runId: String

    /**
     * The events of [runId], from [after] onwards.
     *
     * [after] is `null` when the client named no cursor, and then the street
     * starts at the first row the run ever wrote.
     */
    data class EventsFollow(
        override val schema: String,
        override val runId: String,
        val after: EventCursor?,
    ) : ServiceSubscriptionRequest

    /**
     * The console of [runId], from [after] onwards.
     *
     * [after] is the frame ordinal the street starts after, and `-1` is the
     * position before the first frame of the run.
     */
    data class OutputFollow(
        override val schema: String,
        override val runId: String,
        val after: Long,
    ) : ServiceSubscriptionRequest
}

/**
 * Why a street ended.
 *
 * ## The vocabulary is one word, and that is the claim
 *
 * The end of a street says WHY the street ended, and there is exactly one
 * reason a street of this service can end: the run's terminal fact was
 * observed. A street that cannot say that does NOT end — it refuses, with the
 * name of the fact that stopped it.
 *
 * ## Why there is no word for "the run's outcome"
 *
 * Because the outcome is not a fact about the street. A street ending says
 * that the run is OVER; it says nothing about whether the run SUCCEEDED, and
 * a client that reads it as a success is reading a word the wire never said.
 * The outcome is another authority's answer — `run.state`, or the `outcome` of
 * the terminal event — and this vocabulary has no room for a second opinion
 * about it.
 *
 * ## Why the other outcome of the command line is not here
 *
 * The command line's follow has two terminal outcomes: the run finished, or
 * the output was sealed while the run's fate stayed unknown. The second one
 * needs an execution authority that cannot be reached, and this service's
 * substrate always has one — the store is what it was composed with, and the
 * verb refuses to start without it. So the street cannot produce that fact,
 * and a vocabulary that named it would be promising a case no client of this
 * service could ever see.
 */
internal enum class ServiceEndReason(val word: String) {

    /** The run's terminal fact was observed. The only way a street ends. */
    TerminalObserved("terminal-observed"),
}

/**
 * Why the service did not answer.
 *
 * ## What the kinds are for
 *
 * A client acts differently on each one: a version to upgrade to, a request to
 * fix, a load to shed, a run to give up on. Collapsing them into one "error"
 * would leave that decision to a string comparison, and two clients would
 * compare it differently.
 *
 * ## Why the payload travels with the case
 *
 * Each case carries its own facts, and [ServiceCodec] writes them as they
 * are. A projection written apart from the cases would be a second copy of
 * every refusal, free to disagree with the one the code decides.
 */
internal sealed interface ServiceRefusal {

    /** The word the wire carries for this refusal. */
    val kind: String

    /** The facts the refusal carries, besides its kind. */
    val payload: JsonObject

    /** The run is not known to this instance. */
    data class UnknownRun(val runId: String) : ServiceRefusal {
        override val kind: String = "unknown-run"
        override val payload: JsonObject = buildJsonObject { put("runId", JsonPrimitive(runId)) }
    }

    /** The substrate has no control root, so there is no lease to consult. */
    data object NotConfiguredControlRoot : ServiceRefusal {
        override val kind: String = "not-configured-control-root"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The substrate has no event plane configured for this instance. */
    data object NotConfiguredEventPlane : ServiceRefusal {
        override val kind: String = "not-configured-event-plane"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The substrate has no output plane configured for this instance. */
    data object NotConfiguredOutputPlane : ServiceRefusal {
        override val kind: String = "not-configured-output-plane"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The storage failed while answering. */
    data class StorageFailed(val cause: String) : ServiceRefusal {
        override val kind: String = "storage-failed"
        override val payload: JsonObject = buildJsonObject { put("cause", JsonPrimitive(cause)) }
    }

    /** Another process is publishing the run, and it holds the publishing authority. */
    data class HeldByAnother(val heldBy: String, val fencingToken: Long) : ServiceRefusal {
        override val kind: String = "held-by-another"
        override val payload: JsonObject = buildJsonObject {
            put("heldBy", JsonPrimitive(heldBy))
            put("fencingToken", JsonPrimitive(fencingToken))
        }
    }

    /** The substrate disagrees with itself about the run. */
    data class Inconsistent(val details: String) : ServiceRefusal {
        override val kind: String = "inconsistent-lease-state"
        override val payload: JsonObject = buildJsonObject { put("details", JsonPrimitive(details)) }
    }

    /** The journal holds no row for the (operation, attempt) pair that was asked for. */
    data class UnknownRunOperation(val operationId: String, val attempt: Int) : ServiceRefusal {
        override val kind: String = "unknown-operation"
        override val payload: JsonObject = buildJsonObject {
            put("operationId", JsonPrimitive(operationId))
            put("attempt", JsonPrimitive(attempt))
        }
    }

    /** The client speaks a schema this build does not serve. */
    data object IncompatibleProtocol : ServiceRefusal {
        override val kind: String = "incompatible-protocol"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The line is not a request this build can read. */
    data object MalformedRequest : ServiceRefusal {
        override val kind: String = "malformed-request"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The line is bigger than the service reads. */
    data object RequestTooLarge : ServiceRefusal {
        override val kind: String = "request-too-large"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The service already has as many connections as it admits. */
    data object TooManyConnections : ServiceRefusal {
        override val kind: String = "too-many-connections"
        override val payload: JsonObject = buildJsonObject { }
    }

    /** The client asked for an operation this build does not have. */
    data object UnknownOp : ServiceRefusal {
        override val kind: String = "unknown-op"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The cursor the client sent is not a cursor this street can resume from.
     *
     * Three facts land here, and the client acts on them differently, so all
     * three are told apart by the facts this case carries:
     *  - the token does not decode at all;
     *  - the token belongs to the OTHER plane (a byte cursor in the events
     *    street, a frame ordinal in the output street), and resuming from it
     *    would advance the client to a position in the wrong bytes;
     *  - the token names a DIFFERENT run.
     *
     * A cursor that is refused is a cursor the client has to rebuild, and
     * rebuilding it from the frames the street already sent is the client's
     * own job: the service keeps no state per client, so it has no other
     * cursor to offer.
     */
    data class InvalidCursor(val cursor: String, val expected: String) : ServiceRefusal {
        override val kind: String = "invalid-cursor"
        override val payload: JsonObject = buildJsonObject {
            put("cursor", JsonPrimitive(cursor))
            put("expected", JsonPrimitive(expected))
        }
    }

    /**
     * The cursor the client sent is past the end of the history that is there.
     *
     * The run EXISTS and the position does not: the run wrote fewer rows than
     * the cursor names. A client that is told this can reset the cursor to the
     * tail and continue, which is a different action from giving up on the run.
     */
    data class CursorBeyondTail(
        val runId: String,
        val requestedSequence: Long,
        val tailSequence: Long,
    ) : ServiceRefusal {
        override val kind: String = "cursor-beyond-tail"
        override val payload: JsonObject = buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("requestedSequence", JsonPrimitive(requestedSequence))
            put("tailSequence", JsonPrimitive(tailSequence))
        }
    }

    /**
     * The Output Plane refused to answer, and the refusal says which fact stopped it.
     *
     * ## Why the plane's own word travels
     *
     * The plane has a closed vocabulary of nine refusals and no wire names for
     * them. The service does not INVENT nine names of its own: it carries the
     * plane's word in [reason] and the facts that word came with in [detail],
     * so a client reads the plane's own diagnosis instead of a paraphrase of
     * it. The translation is a total `when`, one case per plane refusal, so
     * no refusal of the plane can lose its way on the wire.
     */
    data class OutputPlaneRefused(val reason: String, val detail: String) : ServiceRefusal {
        override val kind: String = "output-plane-refused"
        override val payload: JsonObject = buildJsonObject {
            put("reason", JsonPrimitive(reason))
            put("detail", JsonPrimitive(detail))
        }
    }
}

/**
 * What the service answers.
 *
 * An answer and a refusal are different types, so a client that reads the
 * answer can never read a refusal as one, and the encoding of each is decided
 * in one place.
 */
internal sealed interface ServiceResponse {

    /** The schema this build speaks. It travels in every answer. */
    val schema: String

    /** The service answered. */
    data class Answered(override val schema: String, val result: JsonObject) : ServiceResponse

    /** The service refused. */
    data class Refused(override val schema: String, val refusal: ServiceRefusal) : ServiceResponse
}

/**
 * What a line turned out to be.
 *
 * A line is a request or a refusal, and never an exception: a client that
 * sends nonsense gets a refusal it can read, not a stack trace and a closed
 * connection.
 */
internal sealed interface ServiceDecoding {

    /** The line is a request the service can answer. */
    data class Decoded(val request: ServiceRequest) : ServiceDecoding

    /** The line is a request for a street, and the street's request is known. */
    data class Subscribed(val request: ServiceSubscriptionRequest) : ServiceDecoding

    /** The line is not a request this build can answer. */
    data class Refused(val refusal: ServiceRefusal) : ServiceDecoding
}

/**
 * The codec of the service's wire.
 *
 * ## What it owns
 *
 * The two directions of the contract: a line becomes a [ServiceRequest] or a
 * [ServiceRefusal], and a [ServiceResponse] becomes a line. It is a pure
 * function of its input — no socket, no store, no clock — so the contract can
 * be read and tested without a service running.
 *
 * ## What it does not own
 *
 * What the service DOES with a request, and how the line travelled.
 */
internal object ServiceCodec {

    /** The family of the cursor the events street resumes from. */
    private const val EVENT_CURSOR_FAMILY: String = "evt-cursor-v1"

    /** The family of the cursor the output street resumes from. */
    private const val FRAME_ORDINAL_FAMILY: String = ConsoleFramePlane.FRAME_ORDINAL_TOKEN_PREFIX

    /** The position before the first frame of a run. */
    private const val FIRST_FRAME_ORDINAL: Long = -1L

    /**
     * What [line] is.
     *
     * The order of the checks is the contract: first whether the line is JSON
     * at all, then which schema it speaks, and only then what it asks for. A
     * line that is not JSON has no schema, so asking it for one would answer a
     * question the line never asked.
     */
    fun decode(line: String): ServiceDecoding {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        val request = parsed as? JsonObject
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        val schema = request["schema"]?.jsonPrimitive?.contentOrNull
            ?: return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        if (schema != ServiceProtocol.SCHEMA) {
            return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        }

        val op = request["op"]?.jsonPrimitive?.contentOrNull
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        return when (op) {
            "version" -> ServiceDecoding.Decoded(ServiceRequest.Version(schema))
            "health" -> ServiceDecoding.Decoded(ServiceRequest.Health(schema))
            "run.state" -> {
                val runId = request["runId"]?.jsonPrimitive?.contentOrNull
                    ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
                ServiceDecoding.Decoded(ServiceRequest.RunState(schema, runId))
            }
            "events.follow" -> decodeEventsFollow(request, schema)
            "output.follow" -> decodeOutputFollow(request, schema)
            else -> ServiceDecoding.Refused(ServiceRefusal.UnknownOp)
        }
    }

    /**
     * The events street this line asks for.
     *
     * The cursor is decoded HERE and not in the street, because a cursor that
     * does not belong is a fact about the REQUEST, and the client that sent
     * it has to be told before the street opens.
     */
    private fun decodeEventsFollow(request: JsonObject, schema: String): ServiceDecoding {
        val runId = request["runId"]?.jsonPrimitive?.contentOrNull
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val token = request["after"]?.jsonPrimitive?.contentOrNull ?: return subscribed(
            ServiceSubscriptionRequest.EventsFollow(schema, runId, null),
        )
        val cursor = EventCursor.decode(token)
            ?: return ServiceDecoding.Refused(
                ServiceRefusal.InvalidCursor(token, EVENT_CURSOR_FAMILY),
            )
        if (cursor.runId != runId) {
            // The cursor names a run this street is not. Resuming from it would
            // skip the history of the run that WAS asked for, silently.
            return ServiceDecoding.Refused(
                ServiceRefusal.InvalidCursor(token, EVENT_CURSOR_FAMILY),
            )
        }
        return subscribed(ServiceSubscriptionRequest.EventsFollow(schema, runId, cursor))
    }

    /**
     * The output street this line asks for.
     */
    private fun decodeOutputFollow(request: JsonObject, schema: String): ServiceDecoding {
        val runId = request["runId"]?.jsonPrimitive?.contentOrNull
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val token = request["after"]?.jsonPrimitive?.contentOrNull
            ?: return subscribed(
                ServiceSubscriptionRequest.OutputFollow(schema, runId, FIRST_FRAME_ORDINAL),
            )
        val ordinal = ConsoleFramePlane.decodeFrameOrdinal(token)
            ?: return ServiceDecoding.Refused(
                ServiceRefusal.InvalidCursor(token, FRAME_ORDINAL_FAMILY),
            )
        return subscribed(ServiceSubscriptionRequest.OutputFollow(schema, runId, ordinal))
    }

    /**
     * The decoding of a street's request.
     */
    private fun subscribed(request: ServiceSubscriptionRequest): ServiceDecoding =
        ServiceDecoding.Subscribed(request)

    /**
     * The line a record travels as.
     *
     * The cursor is the position the client resumes from, and it travels with
     * the record because a record without its position is a fact the client
     * cannot place. [hasMore] says whether MORE of this lane follows this
     * record — the consumer's continuation, which for a subscription that
     * delivers everything it reads is the store's own flag.
     */
    fun encodeRecord(cursor: String, record: JsonObject, hasMore: Boolean): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
            put("kind", JsonPrimitive("record"))
            put("cursor", JsonPrimitive(cursor))
            put("hasMore", JsonPrimitive(hasMore))
            put("record", record)
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line the end of a street travels as.
     */
    fun encodeEnd(reason: ServiceEndReason, cursor: String): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
            put("kind", JsonPrimitive("end"))
            put("reason", JsonPrimitive(reason.word))
            put("cursor", JsonPrimitive(cursor))
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line [response] travels as.
     *
     * The schema travels in every line, and the `kind` says what the line IS:
     * an answer, a refusal, a record of a street, or the end of a street. The
     * discriminator is a WORD and not a boolean, because a protocol with a
     * boolean and a word is a protocol with two rules, and because a client
     * reads a word without having to know which of two shapes it is reading.
     *
     * The refusal carries its own facts besides its kind: a client that reads
     * the kind knows what to do, and a client that reads the facts knows what
     * it was refused about.
     */
    fun encode(response: ServiceResponse): String {
        val document = when (response) {
            is ServiceResponse.Answered -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("kind", JsonPrimitive("answer"))
                put("result", response.result)
            }
            is ServiceResponse.Refused -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("kind", JsonPrimitive("refused"))
                put("refusal", refusalDocument(response.refusal))
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The document a refusal travels as: its kind and the facts it carries.
     */
    private fun refusalDocument(refusal: ServiceRefusal): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(refusal.kind))
        refusal.payload.forEach { (name, value) -> put(name, value) }
    }
}
