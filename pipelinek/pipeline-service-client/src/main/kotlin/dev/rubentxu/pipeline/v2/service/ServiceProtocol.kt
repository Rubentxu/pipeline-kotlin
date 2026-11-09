package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.RunId
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
object ServiceProtocol {

    /** The schema this build serves. The version travels with every answer. */
    const val SCHEMA: String = "pipelinek.service/2"

    /**
     * The biggest line of request this build reads, in bytes.
     *
     * A line bigger than this is refused before it is parsed: reading it whole
     * would let a client make the service hold whatever it wants in memory.
     */
    const val MAX_LINE_BYTES: Int = 64 * 1024

    /**
     * The longest authorization a request may carry.
     *
     * A token is a secret the operator issued, and its length is bounded
     * because the service compares nothing: a token that is longer than this
     * is not a token this service issued, and it is refused as a malformed
     * request instead of being carried around.
     */
    const val MAX_TOKEN_LENGTH: Int = 512

    /**
     * The longest run id a request may carry.
     *
     * A run id is a KEY, not a path and not a text: a client that sends a
     * megabyte of it is not asking about a run. The bound is what makes
     * "zero trust in the client's data" a fact rather than an intention.
     */
    const val MAX_RUN_ID_LENGTH: Int = 256
}

/**
 * What a client asked for.
 *
 * Every case carries the [schema] the client spoke, because the answer says
 * which schema it speaks and a client that cannot compare the two has nothing
 * to compare.
 */
sealed interface ServiceRequest {

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
sealed interface ServiceSubscriptionRequest {

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
enum class ServiceEndReason(val word: String) {

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
sealed interface ServiceRefusal {

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

    /**
     * The client presented no authorization, or one this service does not know.
     *
     * ## Why it says nothing about WHICH
     *
     * A refusal that said "the token is wrong" and a refusal that said "there
     * is no token" would let a client tell apart two things it has no right
     * to tell apart: whether an authorization exists at all. One name covers
     * both, and the client learns the only thing it can act on: that it has
     * to present an authorization this service accepts.
     */
    data object NotAuthorized : ServiceRefusal {
        override val kind: String = "not-authorized"
        override val payload: JsonObject = buildJsonObject { }
    }

    /**
     * The authorization is one this service knows, and its scope does not
     * cover the run that was asked about.
     *
     * ## Why the run travels and not the authorization
     *
     * The run is the fact the client has to act on: it asked about a run it
     * may not ask about. The authorization does NOT travel, because a name
     * that travels can be enumerated by a client that presents tokens until
     * one is accepted.
     *
     * ## What the client cannot learn from this refusal
     *
     * The scope is checked BEFORE the store is read, so this refusal says
     * the same thing about a run that exists and about a run that does not:
     * a client out of scope cannot use the service to find out which runs
     * exist.
     */
    data class RunNotAuthorized(val runId: String) : ServiceRefusal {
        override val kind: String = "run-not-authorized"
        override val payload: JsonObject = buildJsonObject { put("runId", JsonPrimitive(runId)) }
    }

    /**
     * The service already holds as many subscriptions as it admits.
     *
     * ## Why it is not `too-many-connections`
     *
     * Because the remedy is different: the client has to close a
     * subscription and come back, while `too-many-connections` asks it to
     * wait for a free slot. The service keeps a budget for subscriptions
     * SEPARATE from the one for connections precisely so that a client can
     * always ask a question; this refusal is what says which of the two
     * budgets it ran into.
     */
    data object TooManySubscriptions : ServiceRefusal {
        override val kind: String = "too-many-subscriptions"
        override val payload: JsonObject = buildJsonObject { }
    }
}

/**
 * What the service answers.
 *
 * An answer and a refusal are different types, so a client that reads the
 * answer can never read a refusal as one, and the encoding of each is decided
 * in one place.
 */
sealed interface ServiceResponse {

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
sealed interface ServiceDecoding {

    /**
     * The line is a request the service can answer.
     *
     * The [credential] is the authorization the client presented, or null
     * when it presented none. It travels with the request and not INSIDE it:
     * the request says WHAT is asked for, and the credential says WHO is
     * asking, and a request that carried the credential inside would make
     * every operation a different type.
     */
    data class Decoded(val request: ServiceRequest, val credential: String?) : ServiceDecoding

    /**
     * The line is a request for a street, and the street's request is known.
     *
     * The [credential] is the authorization the client presented, or null
     * when it presented none — the same contract as [Decoded], because a
     * street is asked for by a client like any other question.
     */
    data class Subscribed(val request: ServiceSubscriptionRequest, val credential: String?) : ServiceDecoding

    /** The line is not a request this build can answer. */
    data class Refused(val refusal: ServiceRefusal) : ServiceDecoding
}

/**
 * What a line of the wire turned out to be, read by a CLIENT.
 *
 * ## Why the client needs its own reading
 *
 * The service's [ServiceDecoding] reads a line as a REQUEST, because that is
 * what arrives on the service's side. The client reads the other direction:
 * an answer, a refusal, a record of a street, or the end of a street. The two
 * readings are of the same lines and they are not the same function, so they
 * are two types and not one type with a flag.
 *
 * ## Why there is no "anything else"
 *
 * A line that is none of the four is not a line of this protocol. The decoder
 * answers `null` and the client names the fact, because a client that read a
 * foreign line as an answer would report a success that never happened.
 */
sealed interface ServiceLine {

    /** The service answered. */
    data class Answered(val schema: String, val result: JsonObject) : ServiceLine

    /** The service refused. */
    data class Refused(val schema: String, val refusal: ServiceRefusal) : ServiceLine

    /** A record of a street. */
    data class Record(
        val cursor: String,
        val record: JsonObject,
        val hasMore: Boolean,
    ) : ServiceLine

    /** The end of a street. */
    data class End(val reason: ServiceEndReason, val cursor: String) : ServiceLine
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
 * The client's direction lives here too, because the wire has ONE codec: a
 * client that encoded requests by hand would be a second authority about the
 * shape of a request, and the service would answer a line the client did not
 * quite mean.
 *
 * ## What it does not own
 *
 * What the service DOES with a request, and how the line travelled.
 */
object ServiceCodec {

    /** The family of the cursor the events street resumes from. */
    private const val EVENT_CURSOR_FAMILY: String = "evt-cursor-v1"

    /** The family of the cursor the output street resumes from. */
    private val FRAME_ORDINAL_FAMILY: String = FrameOrdinalToken.PREFIX

    /** The position before the first frame of a run. */
    private const val FIRST_FRAME_ORDINAL: Long = -1L

    /**
     * What [line] is.
     *
     * The order of the checks is the contract: first whether the line is JSON
     * at all, then which schema it speaks, then the authorization it carries,
     * and only then what it asks for. A line that is not JSON has no schema,
     * so asking it for one would answer a question the line never asked.
     *
     * ## Why nothing here throws
     *
     * Every field is read as a TEXT or not at all: a client that sends
     * `"schema": {}` is answered with a refusal and not with an exception,
     * because a line is not a request or a refusal, and NEVER an exception.
     */
    fun decode(line: String): ServiceDecoding {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        val request = parsed as? JsonObject
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        val schema = request.text("schema")
            ?: return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        if (schema != ServiceProtocol.SCHEMA) {
            return ServiceDecoding.Refused(ServiceRefusal.IncompatibleProtocol)
        }

        // The authorization is read BEFORE the operation, because it belongs
        // to every operation: a line that carries a malformed authorization
        // is a malformed request whatever it asks for.
        val credential = request.text("auth")
        if (credential != null && (credential.isEmpty() || credential.length > ServiceProtocol.MAX_TOKEN_LENGTH)) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }

        val op = request.text("op")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)

        return when (op) {
            "version" -> ServiceDecoding.Decoded(ServiceRequest.Version(schema), credential)
            "health" -> ServiceDecoding.Decoded(ServiceRequest.Health(schema), credential)
            "run.state" -> {
                val runId = request.runId() ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
                ServiceDecoding.Decoded(ServiceRequest.RunState(schema, runId), credential)
            }
            "events.follow" -> decodeEventsFollow(request, schema, credential)
            "output.follow" -> decodeOutputFollow(request, schema, credential)
            else -> ServiceDecoding.Refused(ServiceRefusal.UnknownOp)
        }
    }

    /**
     * The text [key] holds, or null when it holds none.
     *
     * A field that is not a string is a field that holds no text: the
     * element is read as a primitive or not at all, so a client cannot make
     * the decoder raise by sending an object where a string belongs.
     */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The run this request is about, or null when it names none.
     *
     * A run id has to be a run id: it has to NAME a durable location, in the
     * alphabet the product uses for that, and it has to be short enough to
     * be one. The alphabet is [RunId]'s and not a second copy of it: the
     * Output Plane builds a path out of the run id, and the one authority
     * about which run ids may name a location is the identity itself.
     *
     * A run id that does not qualify is refused HERE, before anything is
     * opened: a request that named a path would otherwise travel until the
     * plane refused it, and the refusal would arrive as a closed connection
     * instead of as a word the client can read.
     */
    private fun JsonObject.runId(): String? {
        val runId = text("runId") ?: return null
        if (runId.length > ServiceProtocol.MAX_RUN_ID_LENGTH) return null
        if (!RunId.isNameable(runId)) return null
        return runId
    }

    /**
     * The events street this line asks for.
     *
     * The cursor is decoded HERE and not in the street, because a cursor that
     * does not belong is a fact about the REQUEST, and the client that sent
     * it has to be told before the street opens.
     */
    private fun decodeEventsFollow(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val runId = request.runId()
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val token = request.text("after") ?: return subscribed(
            ServiceSubscriptionRequest.EventsFollow(schema, runId, null),
            credential,
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
        return subscribed(ServiceSubscriptionRequest.EventsFollow(schema, runId, cursor), credential)
    }

    /**
     * The output street this line asks for.
     */
    private fun decodeOutputFollow(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val runId = request.runId()
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val token = request.text("after")
            ?: return subscribed(
                ServiceSubscriptionRequest.OutputFollow(schema, runId, FIRST_FRAME_ORDINAL),
                credential,
            )
        val ordinal = FrameOrdinalToken.decode(token)
            ?: return ServiceDecoding.Refused(
                ServiceRefusal.InvalidCursor(token, FRAME_ORDINAL_FAMILY),
            )
        return subscribed(ServiceSubscriptionRequest.OutputFollow(schema, runId, ordinal), credential)
    }

    /**
     * The decoding of a street's request.
     */
    private fun subscribed(request: ServiceSubscriptionRequest, credential: String?): ServiceDecoding =
        ServiceDecoding.Subscribed(request, credential)

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
     * The line [request] travels as.
     *
     * The `op` word says what is asked for, and the schema travels with every
     * line because a service that serves another schema has to be able to
     * say so before it refuses.
     *
     * ## Why a request without an authorization exists at all
     *
     * Because the service asks EVERY question for an authorization, and a
     * line without one is answered with `not-authorized`. This overload is
     * the way to write that line on purpose: a test that checks the refusal,
     * and a client that wants to see it.
     */
    fun encode(request: ServiceRequest): String = encode(request, null)

    /**
     * The line [request] travels as, presented with [credential].
     *
     * The authorization travels with the request and not in a second line:
     * one question per connection is the wire's rule, and a handshake before
     * the question would make every client two round trips for one fact.
     */
    fun encode(request: ServiceRequest, credential: String?): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(request.schema))
            credential?.let { put("auth", JsonPrimitive(it)) }
            when (request) {
                is ServiceRequest.Version -> put("op", JsonPrimitive("version"))
                is ServiceRequest.Health -> put("op", JsonPrimitive("health"))
                is ServiceRequest.RunState -> {
                    put("op", JsonPrimitive("run.state"))
                    put("runId", JsonPrimitive(request.runId))
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The line [request] travels as.
     *
     * The cursor travels ENCODED, and only when there is one: a street that
     * was asked for from the beginning names no cursor, and a street that was
     * asked to resume names the position it resumes from.
     */
    fun encode(request: ServiceSubscriptionRequest): String = encode(request, null)

    /**
     * The line [request] travels as, presented with [credential].
     *
     * A street is asked for by a client like any other question, so it is
     * authorized the same way.
     */
    fun encode(request: ServiceSubscriptionRequest, credential: String?): String {
        val document = buildJsonObject {
            put("schema", JsonPrimitive(request.schema))
            credential?.let { put("auth", JsonPrimitive(it)) }
            when (request) {
                is ServiceSubscriptionRequest.EventsFollow -> {
                    put("op", JsonPrimitive("events.follow"))
                    put("runId", JsonPrimitive(request.runId))
                    request.after?.let { put("after", JsonPrimitive(it.encode())) }
                }
                is ServiceSubscriptionRequest.OutputFollow -> {
                    put("op", JsonPrimitive("output.follow"))
                    put("runId", JsonPrimitive(request.runId))
                    put("after", JsonPrimitive(FrameOrdinalToken.encode(request.after)))
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * What [line] is, read by a client.
     *
     * `null` when the line is not one of this protocol's four kinds. The
     * client turns that into a named negative: a client that read a foreign
     * line as an answer would report a success that never happened.
     */
    fun decodeLine(line: String): ServiceLine? {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (_: Exception) {
            return null
        }
        val document = parsed as? JsonObject ?: return null
        return when (document["kind"]?.jsonPrimitive?.contentOrNull) {
            "answer" -> {
                val schema = document["schema"]?.jsonPrimitive?.contentOrNull ?: return null
                val result = document["result"] as? JsonObject ?: return null
                ServiceLine.Answered(schema, result)
            }
            "refused" -> {
                val schema = document["schema"]?.jsonPrimitive?.contentOrNull ?: return null
                val refusal = (document["refusal"] as? JsonObject)?.let { decodeRefusal(it) } ?: return null
                ServiceLine.Refused(schema, refusal)
            }
            "record" -> {
                val cursor = document["cursor"]?.jsonPrimitive?.contentOrNull ?: return null
                val record = document["record"] as? JsonObject ?: return null
                val hasMore = document["hasMore"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                ServiceLine.Record(cursor, record, hasMore)
            }
            "end" -> {
                val word = document["reason"]?.jsonPrimitive?.contentOrNull ?: return null
                val reason = ServiceEndReason.entries.firstOrNull { it.word == word } ?: return null
                val cursor = document["cursor"]?.jsonPrimitive?.contentOrNull ?: return null
                ServiceLine.End(reason, cursor)
            }
            else -> null
        }
    }

    /**
     * The refusal [document] carries.
     *
     * A total reading: the kinds of [ServiceRefusal] are closed, and a
     * document whose kind is none of them is not a refusal of this protocol.
     * The facts each kind carries are read here and nowhere else, so a
     * refusal cannot lose a fact on the way in.
     */
    private fun decodeRefusal(document: JsonObject): ServiceRefusal? {
        val kind = document["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (kind) {
            "unknown-run" -> ServiceRefusal.UnknownRun(document.str("runId") ?: return null)
            "not-configured-control-root" -> ServiceRefusal.NotConfiguredControlRoot
            "not-configured-event-plane" -> ServiceRefusal.NotConfiguredEventPlane
            "not-configured-output-plane" -> ServiceRefusal.NotConfiguredOutputPlane
            "storage-failed" -> ServiceRefusal.StorageFailed(document.str("cause") ?: return null)
            "held-by-another" -> ServiceRefusal.HeldByAnother(
                document.str("heldBy") ?: return null,
                document.long("fencingToken") ?: return null,
            )
            "inconsistent-lease-state" -> ServiceRefusal.Inconsistent(document.str("details") ?: return null)
            "unknown-operation" -> ServiceRefusal.UnknownRunOperation(
                document.str("operationId") ?: return null,
                document.long("attempt")?.toInt() ?: return null,
            )
            "incompatible-protocol" -> ServiceRefusal.IncompatibleProtocol
            "malformed-request" -> ServiceRefusal.MalformedRequest
            "request-too-large" -> ServiceRefusal.RequestTooLarge
            "too-many-connections" -> ServiceRefusal.TooManyConnections
            "unknown-op" -> ServiceRefusal.UnknownOp
            "invalid-cursor" -> ServiceRefusal.InvalidCursor(
                document.str("cursor") ?: return null,
                document.str("expected") ?: return null,
            )
            "cursor-beyond-tail" -> ServiceRefusal.CursorBeyondTail(
                document.str("runId") ?: return null,
                document.long("requestedSequence") ?: return null,
                document.long("tailSequence") ?: return null,
            )
            "output-plane-refused" -> ServiceRefusal.OutputPlaneRefused(
                document.str("reason") ?: return null,
                document.str("detail") ?: return null,
            )
            "not-authorized" -> ServiceRefusal.NotAuthorized
            "run-not-authorized" -> ServiceRefusal.RunNotAuthorized(document.str("runId") ?: return null)
            "too-many-subscriptions" -> ServiceRefusal.TooManySubscriptions
            else -> null
        }
    }

    /** The string [key] holds, or null when it does not hold a string. */
    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** The long [key] holds, or null when it does not hold one. */
    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    /**
     * The document a refusal travels as: its kind and the facts it carries.
     */
    private fun refusalDocument(refusal: ServiceRefusal): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(refusal.kind))
        refusal.payload.forEach { (name, value) -> put(name, value) }
    }
}
