package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The answers to the operations of a credential provider session.
 *
 * ## What this owns
 *
 * The meaning of `credential.provider.register`, `.poll`, `.answer` and `.close`: which answer
 * each one produces and which refusal. The state lives in [CredentialProviderSessions]; this
 * class only translates between the wire and that registry.
 *
 * ## Who the session belongs to
 *
 * Every operation is run for the credential that sent the request. The registry matches the
 * session against that owner, so a client that presents another credential cannot register a
 * name another one holds, cannot receive what another one was asked and cannot answer another
 * one's request. A request that arrives without a credential is refused: a session with no
 * owner would be one every anonymous start could reach.
 *
 * ## What it never does
 *
 * It never throws, because an exception would close the connection without saying why. It never
 * writes the value of an answer anywhere: the bytes are decoded, copied by the registry and
 * cleared here, and no refusal quotes them.
 */
internal class CredentialProviderOperations(
    private val sessions: CredentialProviderSessions?,
) {

    /** The answer to a request to REGISTER a credential provider session. */
    fun register(request: ServiceRequest.CredentialRegister, credentialDigest: String?): ServiceResponse =
        withSessions(credentialDigest) { registry, owner ->
            when (val outcome = registry.register(owner, request.provider)) {
                is ProviderRegistrationOutcome.Registered -> answered(
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(outcome.sessionId))
                        put("leaseMillis", JsonPrimitive(outcome.leaseMillis))
                    },
                )

                is ProviderRegistrationOutcome.Refused -> refused(outcome.reason, outcome.detail)
            }
        }

    /** The answer to a POLL: the credential a run is waiting for, or an idle answer. */
    fun poll(request: ServiceRequest.CredentialPoll, credentialDigest: String?): ServiceResponse =
        withSessions(credentialDigest) { registry, owner ->
            when (val outcome = registry.poll(owner, request.sessionId, request.waitMillis)) {
                is CredentialPollOutcome.Asked -> answered(
                    buildJsonObject {
                        put("state", JsonPrimitive(ServiceOperations.POLL_ASKED))
                        put("requestId", JsonPrimitive(outcome.requestId))
                        put("correlationId", JsonPrimitive(outcome.execution))
                        put("credentialId", JsonPrimitive(outcome.credentialId))
                        put("kind", JsonPrimitive(outcome.kind))
                    },
                )

                is CredentialPollOutcome.Idle ->
                    answered(buildJsonObject { put("state", JsonPrimitive(ServiceOperations.POLL_IDLE)) })

                is CredentialPollOutcome.Refused -> refused(outcome.reason, outcome.detail)
            }
        }

    /**
     * The answer to the ANSWER of a provider: the grant of the credential a poll asked for, or the
     * denial of it.
     *
     * A granted value arrives as Base64 text. The text is decoded here, handed to the registry —
     * which keeps its own copy until the run takes it— and the decoded buffer is cleared before
     * this returns, whatever the registry said.
     */
    fun answer(request: ServiceRequest.CredentialAnswer, credentialDigest: String?): ServiceResponse =
        withSessions(credentialDigest) { registry, owner ->
            val decoded = if (request.granted) decodeValue(request.value) else null
            if (request.granted && decoded == null) {
                refused(REASON_VALUE_NOT_BASE64, "a granted credential travels as Base64 text")
            } else {
                try {
                    acknowledged(
                        request.sessionId,
                        registry.answer(owner, request.sessionId, request.requestId, request.granted, decoded),
                        ServiceOperations.OUTCOME_ANSWERED,
                    )
                } finally {
                    decoded?.fill(0)
                }
            }
        }

    /** The answer to the CLOSE of a credential provider session. */
    fun close(request: ServiceRequest.CredentialClose, credentialDigest: String?): ServiceResponse =
        withSessions(credentialDigest) { registry, owner ->
            acknowledged(request.sessionId, registry.close(owner, request.sessionId), ServiceOperations.OUTCOME_CLOSED)
        }

    private fun acknowledged(sessionId: String, outcome: ProviderSessionOutcome, word: String): ServiceResponse =
        when (outcome) {
            is ProviderSessionOutcome.Accepted -> answered(
                buildJsonObject {
                    put("sessionId", JsonPrimitive(sessionId))
                    put("outcome", JsonPrimitive(word))
                },
            )

            is ProviderSessionOutcome.Refused -> refused(outcome.reason, outcome.detail)
        }

    /** The bytes the text spells, or null when it is not Base64 or it is bigger than a credential may be. */
    private fun decodeValue(text: String?): ByteArray? {
        if (text == null || text.length > ServiceProtocol.MAX_CREDENTIAL_VALUE_ENCODED_LENGTH) return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Runs [action] for the credential that sent the request, or refuses.
     *
     * A failure inside the action is a refusal with a fixed text: the message of a failure may
     * name paths or values and it does not travel.
     */
    private fun withSessions(
        credentialDigest: String?,
        action: (CredentialProviderSessions, String) -> ServiceResponse,
    ): ServiceResponse {
        val registry = sessions
            ?: return refused(REASON_NOT_COMPOSED, "this service was not started to serve credential sessions")
        val owner = credentialDigest?.takeIf { it.isNotBlank() }
            ?: return refused(REASON_CREDENTIAL_MISSING, "a credential session belongs to a credential")
        return try {
            action(registry, owner)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            refused(REASON_FAILED, "the operation on the credential session failed")
        } catch (_: Exception) {
            refused(REASON_FAILED, "the operation on the credential session failed")
        }
    }

    private fun refused(reason: String, detail: String): ServiceResponse =
        ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.CredentialProviderRefused(reason, detail))

    private fun answered(result: JsonObject): ServiceResponse = ServiceResponse.Answered(ServiceProtocol.SCHEMA, result)

    internal companion object {
        /** The service was not started to serve credential sessions. */
        const val REASON_NOT_COMPOSED: String = "credential-sessions-not-composed"

        /** An operation arrived without the credential that would own the session. */
        const val REASON_CREDENTIAL_MISSING: String = "credential-session-credential-missing"

        /** A granted value was not Base64 text. */
        const val REASON_VALUE_NOT_BASE64: String = "answer-value-not-base64"

        /** An operation on a credential session failed in a way nobody foresaw. */
        const val REASON_FAILED: String = "credential-session-operation-failed"
    }
}
