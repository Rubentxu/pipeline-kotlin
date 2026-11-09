package dev.rubentxu.pipeline.v2.application

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
 * The server serves `pipelinek.service/1` and refuses every other schema with
 * `incompatible-protocol`. The rule is an EXACT match, and not "compatible
 * major": with a single version number, "compatible major" admits two opposite
 * readings (a smaller client IS compatible / any different one is NOT), and a
 * contract that contradicts itself in two consecutive sentences is not a
 * contract. Relaxing the rule is a change of the protocol version, not a
 * decision this slice takes.
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
    const val SCHEMA: String = "pipelinek.service/1"

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
            else -> ServiceDecoding.Refused(ServiceRefusal.UnknownOp)
        }
    }

    /**
     * The line [response] travels as.
     *
     * The schema travels in every line, and the refusal carries its own facts
     * besides its kind: a client that reads the kind knows what to do, and a
     * client that reads the facts knows what it was refused about.
     */
    fun encode(response: ServiceResponse): String {
        val document = when (response) {
            is ServiceResponse.Answered -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("ok", JsonPrimitive(true))
                put("result", response.result)
            }
            is ServiceResponse.Refused -> buildJsonObject {
                put("schema", JsonPrimitive(response.schema))
                put("ok", JsonPrimitive(false))
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
