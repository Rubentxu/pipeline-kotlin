package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.http.HttpDefaults
import dev.rubentxu.pipeline.v2.domain.step.http.HttpHeader
import dev.rubentxu.pipeline.v2.domain.step.http.HttpMethod
import dev.rubentxu.pipeline.v2.domain.step.http.StatusRange

/**
 * Typed input of `core.httpRequest` (RP6-C / WU-093).
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
 * The WHOLE payload is encoded by [CoreHttpWireCodec]; the engine does not probe
 * individual fields. Not annotated `@Serializable` on purpose:
 * `pipeline-application` does not apply the kotlinx-serialization compiler plugin,
 * so the codec is written out explicitly, exactly as `CoreLockInput` and
 * `CoreInputInput` are.
 */
data class CoreHttpInput(
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
}

/**
 * The resolved, validated form of an [CoreHttpInput]: what the handler will actually
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

internal fun httpIntentOf(input: CoreHttpInput): HttpIntent {
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
