package dev.rubentxu.pipeline.v2.sdk.http

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
     * The author asked for a credential this slice cannot honour yet.
     *
     * Credential resolution is scheduled for G6, where it arrives through a
     * declared capability rather than a service locator. Until then a declared
     * `authentication` is REFUSED, and refusing it is the whole point: silently
     * dropping the credential and sending the request unauthenticated would
     * turn a declared secret into a 401 at best and an audit finding at worst,
     * and the pipeline would look like it had authenticated.
     */
    data class CredentialsUnsupported(val id: String) : HttpRejection {
        override val diagnostic: String get() =
            "credential '$id' cannot be used by http.request yet: credential resolution is not " +
                "available in this build. The request was NOT sent. Remove `authentication` to " +
                "send it unauthenticated on purpose."
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
internal fun HttpIntent.Ready.toSendRequest(): HttpSendRequest = HttpSendRequest(
    url = url,
    method = method,
    headers = headers + buildList {
        contentType?.let { add(HttpHeader.of("Content-Type", resolveContentType(it))) }
        acceptType?.let { add(HttpHeader.of("Accept", resolveContentType(it))) }
    },
    body = body,
    timeoutMs = timeoutMs,
    authorization = null,
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
    // Fail-closed on a credential this build cannot honour. Dropping it and
    // sending anyway would make an unauthenticated request look authenticated.
    input.authentication?.let { id ->
        return HttpIntent.Rejected(HttpRejection.CredentialsUnsupported(id.value))
    }
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
