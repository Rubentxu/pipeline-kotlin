package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.CredentialStoreUnavailability
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind

/**
 * Typed input of `http.request` (RP6-C / WU-093), owned by the HTTP
 * OFFICIAL_PLUGIN rather than by core.
 *
 * Derived from the official `httpRequest` reference
 * (<https://plugins.jenkins.io/http_request/>) — which is NOT `durable-task-step`:
 * the step was extracted to its own plugin, and the parameter list that used to
 * circulate for it (`failOnStatusCode`, `customBands`, `responseCode`, `sslVerify`,
 * `retry`, `retryableStatusCodes`) belongs to a surface that no longer exists.
 *
 * Everything here is DECLARATION. Nothing in this file performs I/O, resolves a
 * credential or decides whether the network is allowed — those are the handler's
 * job, behind declared capabilities.
 *
 * The WHOLE payload is encoded by [HttpRequestCodec]; the engine does not probe
 * individual fields. Not annotated `@Serializable` on purpose:
 * `pipeline-application` does not apply the kotlinx-serialization compiler plugin,
 * so the codec is written out explicitly, exactly as `CoreLockInput` and
 * `CoreInputInput` are.
 */
data class HttpRequestInput(
    val url: String,
    val method: HttpMethod = HttpMethod.Get,
    val customHeaders: List<HttpHeader> = emptyList(),
    val body: String? = null,
    val contentType: String? = null,
    val acceptType: String? = null,
    val validResponseCodes: List<StatusRange> = StatusRange.jenkinsDefault(),
    val timeoutSeconds: Int = HttpDefaults.DEFAULT_TIMEOUT_SECONDS,
    val authentication: CredentialsId? = null,
)

/** Everything that can make an `httpRequest` declaration unusable, as a closed set. */
sealed interface HttpRejection {
    val diagnostic: String

    /**
     * How this declaration error is classified when it reaches a run.
     *
     * Every rejection is a [FailureKind.USER] fact: the author wrote something the
     * Step cannot honour, and no transport, network or server had any part in
     * it. Deciding that here rather than at the boundary means the classification
     * is a property of the rejection, not a string somebody re-reads downstream.
     */
    val failureKind: FailureKind get() = FailureKind.USER

    data class BlankUrl(val attempted: String) : HttpRejection {
        override val diagnostic: String get() = "the URL is blank"
    }

    data class UnsupportedMethod(val attempted: String) : HttpRejection {
        override val diagnostic: String get() = "'$attempted' is not an HTTP method this Step speaks"
    }

    data class BodyWithoutMethod(val method: String) : HttpRejection {
        override val diagnostic: String get() =
            "a request body was supplied for $method, which does not carry one"
    }

    data class UnparseableStatusRange(val attempted: String) : HttpRejection {
        override val diagnostic: String get() =
            "'$attempted' is not a status range; use 'N' or 'from:to', separated by commas"
    }

    data class NonPositiveTimeout(val attempted: Int) : HttpRejection {
        override val diagnostic: String get() =
            "timeoutSeconds must be 0 (no timeout) or positive, got $attempted"
    }

    data class BlankHeaderName(val position: Int) : HttpRejection {
        override val diagnostic: String get() = "the header at position $position has a blank name"
    }

    /**
     * H5 — a named credential could not be turned into an `Authorization` header.
     *
     * A DECLARATION error, not a transport one: no socket is opened and
     * `durationMs` is zero, so a reader of the journal can tell "we decided not to
     * send" from "we sent and it failed".
     *
     * `NoSource` is its own case rather than folded into [CredentialsUnsupported]
     * because the two send an operator to different places: one is usually an
     * author typo, the other means the run was never given a store to look in.
     */
    sealed interface CredentialRefused : HttpRejection {
        val id: String

        data class Absent(override val id: String) : CredentialRefused {
            override val diagnostic: String get() = "no credential named '$id'"
        }

        /**
         * Jenkins reports this as *"Authentication 'X' doesn't exist anymore"*, which
         * is a lie: it blames a missing credential for a present one of the wrong
         * shape, and the operator goes looking for a credential they can already
         * see. This says what arrived and what would have worked.
         */
        data class WrongKind(
            override val id: String,
            val found: String,
            val supported: List<String>,
        ) : CredentialRefused {
            override val diagnostic: String get() =
                "credential '$id' is a $found; http.request supports " +
                    supported.joinToString(" and ")
        }

        data class StoreUnavailable(override val id: String, val reason: CredentialStoreUnavailability) :
            CredentialRefused {
            override val diagnostic: String get() = when (reason) {
                CredentialStoreUnavailability.NotConfigured ->
                    "this run has no credential store configured, so '$id' cannot be looked up"

                CredentialStoreUnavailability.Unreadable ->
                    "the credential store could not be opened, so '$id' cannot be looked up"

                CredentialStoreUnavailability.Unavailable ->
                    "the credential store could not answer the lookup for '$id'"
            }
        }
    }

    /**
     * H5: retired. A declared `authentication` used to be REFUSED outright, on the
     * sound grounds that silently dropping a credential and sending the request
     * unauthenticated would turn a declared secret into a 401 at best and an audit
     * finding at worst.
     *
     * The reasoning was right and the mechanism was wrong: refusing meant
     * `http.request` could never authenticate at all, and the refusal text had to
     * explain a missing feature. The credential is now resolved through
     * [HTTP_CREDENTIALS_CAPABILITY] before any socket exists, and every way that
     * can fail is still a typed rejection with the request NOT sent. The property
     * this case protected — a declared secret must never degrade into an
     * anonymous request — is now enforced by the resolution being MANDATORY
     * rather than by the declaration being forbidden.
     *
     * Kept, not deleted, so a durable record written while it was the only outcome
     * still decodes into something truthful.
     */
    data class CredentialsUnsupported(val id: String) : HttpRejection {
        override val diagnostic: String get() =
            "credential '$id' cannot be used by http.request yet: credential resolution is not " +
                "available in this build. The request was NOT sent. Remove `authentication` to " +
                "send it unauthenticated on purpose."
    }

    /**
     * A declaration this build refused, read back from a durable record.
     *
     * Only ever produced by `HttpResponseCodec.decode`, and that is the whole point of
     * its existence. The record is a PROJECTION of the original declaration: it kept
     * the reason and not the case. Reusing one of the specific cases to carry that text
     * — `UnsupportedMethod` for a blank URL, say — would put a fact on the wire that was
     * never true, and the next reader would believe it.
     *
     * What round-trips exactly is the diagnostic, which is the only thing a
     * declaration rejection is consumed for. What is LOST is which of the five
     * declaration cases produced it, and a decode therefore cannot be used to
     * reconstruct the author's input — only to report why the request was not sent.
     */
    data class DeclarationRefused(val reason: String) : HttpRejection {
        override val diagnostic: String get() = reason
    }
}

/**
 * The resolved, validated form of an [HttpRequestInput]: what the handler will actually
 * do.
 *
 * `httpIntentOf` is PURE and TOTAL. Every way a declaration can be wrong is
 * enumerated here as a [HttpRejection] case, so "invalid input" is a value the
 * engine can decide rather than an exception it has to catch. It is applied once,
 * at the Step, and never in the compiler — the compiler transcribes.
 */
sealed interface HttpIntent {
    data class Ready(
        val url: String,
        val method: HttpMethod,
        val headers: List<HttpHeader>,
        val body: String?,
        val contentType: String?,
        val acceptType: String?,
        val validResponseCodes: List<StatusRange>,
        /** `null` means "no bound at all", which only `timeoutSeconds = 0` produces. */
        val timeoutMs: Long?,
        val authentication: CredentialsId?,
    ) : HttpIntent

    data class Rejected(val rejection: HttpRejection) : HttpIntent
}

/**
 * Projects a READY intent onto the transport's request shape.
 *
 * Content negotiation is resolved HERE, not in the transport and not in the
 * façade, so the headers that travel are the ones the decision produced. The
 * transport sends what it is given and never invents a `Content-Type`.
 */
internal fun HttpIntent.Ready.toSendRequest(authorization: HttpAuthorization?): HttpSendRequest =
    HttpSendRequest(
        url = url,
        method = method,
        headers = headers + buildList {
            contentType?.let { add(HttpHeader.of("Content-Type", resolveContentType(it))) }
            acceptType?.let { add(HttpHeader.of("Accept", resolveContentType(it))) }
        },
        body = body,
        timeoutMs = timeoutMs,
        authorization = authorization,
        maxBodyBytes = HttpDefaults.MAX_RESPONSE_BYTES,
    )

internal fun httpIntentOf(input: HttpRequestInput): HttpIntent {
    if (input.url.isBlank()) {
        return HttpIntent.Rejected(HttpRejection.BlankUrl(input.url))
    }
    val headers = input.customHeaders.mapIndexed { index, header ->
        if (header.name.value.isBlank()) {
            return HttpIntent.Rejected(HttpRejection.BlankHeaderName(index))
        }
        header
    }
    if (input.body != null && !input.method.carriesBody) {
        return HttpIntent.Rejected(HttpRejection.BodyWithoutMethod(input.method.wireName))
    }
    if (input.timeoutSeconds < 0) {
        return HttpIntent.Rejected(HttpRejection.NonPositiveTimeout(input.timeoutSeconds))
    }
    // H5: a declared `authentication` is no longer refused HERE. It is resolved
    // through the credentials capability before any socket exists, and a failure
    // there is still a typed rejection with nothing sent. Refusing at this stage
    // would have meant the Step could never authenticate at all.
    return HttpIntent.Ready(
        url = input.url,
        method = input.method,
        headers = headers,
        body = input.body,
        contentType = input.contentType,
        acceptType = input.acceptType,
        validResponseCodes = input.validResponseCodes,
        // 0 is Jenkins' "no timeout" and survives as null. Anything else is a real bound.
        timeoutMs = input.timeoutSeconds.takeIf { it > 0 }?.let { it * 1000L },
        authentication = input.authentication,
    )
}

/**
 * The MIME names Jenkins accepts as a `MimeType` enum, mapped to their real values.
 *
 * A pipeline written for Jenkins writes `contentType: "APPLICATION_JSON"`, so that
 * spelling has to work. It does not mean the enum has to be the only way in: a
 * literal MIME type is passed through untouched. Accepting the legacy names costs
 * one table and keeps every existing pipeline runnable; forbidding every other MIME
 * type would have been a limitation with no upside.
 */
internal fun resolveContentType(declared: String): String =
    when (declared.trim()) {
        "NOT_SET" -> ""
        "TEXT_HTML" -> "text/html"
        "TEXT_PLAIN" -> "text/plain"
        "APPLICATION_FORM" -> "application/x-www-form-urlencoded"
        "APPLICATION_FORM_DATA" -> "multipart/form-data"
        "APPLICATION_JSON" -> "application/json"
        "APPLICATION_JSON_UTF8" -> "application/json; charset=utf-8"
        "APPLICATION_JSON_MERGE_PATCH" -> "application/merge-patch+json"
        "APPLICATION_TAR" -> "application/x-tar"
        "APPLICATION_ZIP" -> "application/zip"
        "APPLICATION_OCTETSTREAM" -> "application/octet-stream"
        else -> declared
    }
