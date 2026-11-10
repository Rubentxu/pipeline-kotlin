package dev.rubentxu.pipeline.v2.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The decoding and encoding of the operations of a credential provider session.
 *
 * Only the SHAPE of a line is decided here: a name, an id, a decision of exactly two words and a
 * secret that is text within a bound. Whether the name is legal, whether the session is alive and
 * whether the text is Base64 are rules, and the service refuses them by name.
 */
internal object CredentialProviderWire {

    private const val OP_REGISTER = "credential.provider.register"
    private const val OP_POLL = "credential.provider.poll"
    private const val OP_ANSWER = "credential.provider.answer"
    private const val OP_CLOSE = "credential.provider.close"

    private const val DECISION_GRANT = "grant"
    private const val DECISION_DENY = "deny"

    /**
     * Decodes the operations of a credential provider session, or refuses [op] as unknown when
     * it is none of them.
     */
    fun decode(op: String, request: JsonObject, schema: String, credential: String?): ServiceDecoding =
        when (op) {
            OP_REGISTER -> decodeRegister(request, schema, credential)
            OP_POLL -> decodePoll(request, schema, credential)
            OP_ANSWER -> decodeAnswer(request, schema, credential)
            OP_CLOSE -> {
                val sessionId = request.label("sessionId")
                if (sessionId == null) {
                    ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
                } else {
                    ServiceDecoding.Decoded(ServiceRequest.CredentialClose(schema, sessionId), credential)
                }
            }
            else -> ServiceDecoding.Refused(ServiceRefusal.UnknownOp)
        }

    /**
     * The name of the provider a start names, or null when the field is absent.
     *
     * A field that is present and is not a name is reported by [Named.Malformed] and not read as
     * absent: a start that asked for a controller and named it badly must not run with the
     * service's own credentials.
     */
    fun startProvider(request: JsonObject): Named {
        val element = request["credentialProvider"] ?: return Named.Absent
        val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name.isNullOrEmpty() || name.length > ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH) return Named.Malformed
        return Named.Present(name)
    }

    /** What the `credentialProvider` field of a start came to. */
    sealed interface Named {
        /** The start names no provider. */
        data object Absent : Named

        /** The start names the provider [name]. */
        data class Present(val name: String) : Named

        /** The field is there and is not a name. */
        data object Malformed : Named
    }

    /** Writes the `credential.provider.register` fields of [request] into this document. */
    fun JsonObjectBuilder.putRegister(request: ServiceRequest.CredentialRegister) {
        put("op", JsonPrimitive(OP_REGISTER))
        put("provider", JsonPrimitive(request.provider))
    }

    /** Writes the `credential.provider.poll` fields of [request] into this document. */
    fun JsonObjectBuilder.putPoll(request: ServiceRequest.CredentialPoll) {
        put("op", JsonPrimitive(OP_POLL))
        put("sessionId", JsonPrimitive(request.sessionId))
        put("waitMillis", JsonPrimitive(request.waitMillis))
    }

    /** Writes the `credential.provider.answer` fields of [request] into this document. */
    fun JsonObjectBuilder.putAnswer(request: ServiceRequest.CredentialAnswer) {
        put("op", JsonPrimitive(OP_ANSWER))
        put("sessionId", JsonPrimitive(request.sessionId))
        put("requestId", JsonPrimitive(request.requestId))
        put("decision", JsonPrimitive(if (request.granted) DECISION_GRANT else DECISION_DENY))
        request.value?.let { put("value", JsonPrimitive(it)) }
    }

    /** Writes the `credential.provider.close` fields of [request] into this document. */
    fun JsonObjectBuilder.putClose(request: ServiceRequest.CredentialClose) {
        put("op", JsonPrimitive(OP_CLOSE))
        put("sessionId", JsonPrimitive(request.sessionId))
    }

    private fun decodeRegister(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val provider = request.label("provider")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        return ServiceDecoding.Decoded(ServiceRequest.CredentialRegister(schema, provider), credential)
    }

    /**
     * The poll this line asks for.
     *
     * The wait is a non-negative number of milliseconds no bigger than the protocol's bound; an
     * absent wait is a poll that does not wait. The service caps it again with its own limit.
     */
    private fun decodePoll(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val sessionId = request.label("sessionId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val waitMillis = if (request["waitMillis"] == null) {
            0L
        } else {
            (request["waitMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        if (waitMillis < 0L || waitMillis > ServiceProtocol.MAX_PROVIDER_POLL_WAIT_MILLIS) {
            return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        return ServiceDecoding.Decoded(ServiceRequest.CredentialPoll(schema, sessionId, waitMillis), credential)
    }

    /**
     * The answer this line carries.
     *
     * The decision is one of two words. A grant carries a non-empty text within the bound and a
     * denial carries none, because a line that hands a value over and refuses at the same time
     * is not a decision to be interpreted. The value must be a JSON STRING: a number would be
     * read as its digits and a secret is never a number.
     */
    private fun decodeAnswer(request: JsonObject, schema: String, credential: String?): ServiceDecoding {
        val sessionId = request.label("sessionId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val requestId = request.label("requestId")
            ?: return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        val granted = when (request.string("decision")) {
            DECISION_GRANT -> true
            DECISION_DENY -> false
            else -> return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
        }
        val value = if (granted) {
            val text = request.string("value")
            if (text.isNullOrEmpty() || text.length > ServiceProtocol.MAX_CREDENTIAL_VALUE_ENCODED_LENGTH) {
                return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
            }
            text
        } else {
            if (request.containsKey("value")) return ServiceDecoding.Refused(ServiceRefusal.MalformedRequest)
            null
        }
        return ServiceDecoding.Decoded(
            ServiceRequest.CredentialAnswer(schema, sessionId, requestId, granted, value),
            credential,
        )
    }

    /** The label [key] holds as a string, or null when it holds none, is empty or is over the bound. */
    private fun JsonObject.label(key: String): String? {
        val value = string(key) ?: return null
        if (value.isEmpty() || value.length > ServiceProtocol.MAX_PROVIDER_FIELD_LENGTH) return null
        return value
    }

    /** The text [key] holds when it is a JSON string, and null for anything else, a number included. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
