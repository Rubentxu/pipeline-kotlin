package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId

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
    val validResponseCodes: List<StatusRange> = listOf(StatusRange.Span(100, 399)),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val authentication: CredentialsId? = null,
) {
    companion object {
        /**
         * Jenkins defaults `timeout` to `0`, meaning "no timeout", because its client
         * would otherwise impose its own 5-minute default. Inheriting that here would
         * mean a forgotten timeout hangs the run forever, and the common case — a
         * responsive API — would look like a broken runner. Thirty seconds is a
         * declared value, not an inherited one, and an author who really wants no
         * bound says `timeoutSeconds = 0`.
         */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 30
    }
}

/**
 * The HTTP method, as a closed ADT rather than a `String`.
 *
 * A `String` would make `method = "GTE"` a value that reaches the wire. `MKCOL` is
 * deliberately absent: it is a WebDAV method with no known use in a pipeline, and
 * every case of a closed ADT is a branch the engine has to carry forever.
 */
sealed interface HttpMethod {
    val wireName: String

    data object Get : HttpMethod { override val wireName: String get() = "GET" }
    data object Head : HttpMethod { override val wireName: String get() = "HEAD" }
    data object Post : HttpMethod { override val wireName: String get() = "POST" }
    data object Put : HttpMethod { override val wireName: String get() = "PUT" }
    data object Delete : HttpMethod { override val wireName: String get() = "DELETE" }
    data object Options : HttpMethod { override val wireName: String get() = "OPTIONS" }
    data object Patch : HttpMethod { override val wireName: String get() = "PATCH" }

    /**
     * Whether a body may travel with this method.
     *
     * `GET` and `HEAD` do not carry one: sending it is not an error at the transport,
     * but it is a request the server will ignore or reject, and the author almost
     * certainly meant something else. Deciding that here keeps the Step from
     * forwarding a body the server was never going to read.
     */
    val carriesBody: Boolean
        get() = this !is Get && this !is Head && this !is Delete && this !is Options

    companion object {
        fun fromWire(name: String): HttpMethod? = when (name.uppercase()) {
            "GET" -> Get
            "HEAD" -> Head
            "POST" -> Post
            "PUT" -> Put
            "DELETE" -> Delete
            "OPTIONS" -> Options
            "PATCH" -> Patch
            else -> null
        }
    }
}

/**
 * One HTTP header, with its name and value as separate value classes.
 *
 * A `Map<String, String>` is wrong twice over: it cannot represent a header that
 * legitimately repeats (`Set-Cookie` is the canonical case), and it says nothing
 * about which argument is which — `HttpHeader("X-Token", "v1")` would compile with
 * the arguments the wrong way round. The value classes make that unrepresentable.
 */
@JvmInline
value class HttpHeaderName(val value: String) {
    init {
        require(value.isNotBlank()) { "an HTTP header name cannot be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class HttpHeaderValue(val value: String) {
    override fun toString(): String = value
}

data class HttpHeader(val name: HttpHeaderName, val value: HttpHeaderValue) {
    companion object {
        fun of(name: String, value: String): HttpHeader =
            HttpHeader(HttpHeaderName(name), HttpHeaderValue(value))
    }
}

/**
 * A range of acceptable status codes.
 *
 * Jenkins takes this as the string `"100:399,404"` and parses it **after the request
 * has already been sent** (`HttpRequest.java:552-589`), so a typo in a pipeline
 * produces an `IllegalArgumentException` raised against a request that reached the
 * world. Here the shape is validated when the intent is resolved — a pure function
 * applied at decode time — so a bad range is a *declaration* error and never a
 * surprise that arrives after the fact.
 */
sealed interface StatusRange {
    fun contains(status: Int): Boolean

    data class Single(val code: Int) : StatusRange {
        override fun contains(status: Int): Boolean = status == code
    }

    data class Span(val from: Int, val to: Int) : StatusRange {
        init {
            require(from <= to) { "a status span must not run backwards: $from..$to" }
            require(from in 100..599) { "a status span must start within 100..599, got $from" }
            require(to in 100..599) { "a status span must end within 100..599, got $to" }
        }

        override fun contains(status: Int): Boolean = status in from..to
    }

    companion object {
        /**
         * Parses the Jenkins spelling (`"100:399,404"`) into typed ranges, so a
         * pipeline written for Jenkins runs here unedited.
         *
         * Total: anything unparseable returns `null` and the caller turns that into a
         * typed rejection. It never throws and never guesses: an unrecognised token
         * must not silently widen or narrow what the author wrote.
         */
        fun parse(spec: String): List<StatusRange>? {
            if (spec.isBlank()) return null
            val ranges = mutableListOf<StatusRange>()
            for (token in spec.split(',')) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return null
                val parts = trimmed.split(':')
                val range = when (parts.size) {
                    1 -> trimmed.toIntOrNull()?.let { StatusRange.Single(it) }
                    2 -> {
                        val from = parts[0].trim().toIntOrNull()
                        val to = parts[1].trim().toIntOrNull()
                        if (from == null || to == null || from > to) null
                        else StatusRange.Span(from, to)
                    }
                    else -> null
                } ?: return null
                if (range is StatusRange.Single && range.code !in 100..599) return null
                ranges += range
            }
            return ranges.ifEmpty { null }
        }

        /** The Jenkins default: any status below 400 is success. */
        fun jenkinsDefault(): List<StatusRange> = listOf(Span(100, 399))
    }
}

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
