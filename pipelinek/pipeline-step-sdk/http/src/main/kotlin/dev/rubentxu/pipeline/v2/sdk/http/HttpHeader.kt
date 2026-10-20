package dev.rubentxu.pipeline.v2.sdk.http

/**
 * One HTTP header, with its name and value as separate value classes
 * (RP6-C / WU-093).
 *
 * A `Map<String, String>` is wrong twice over: it cannot represent a header that
 * legitimately repeats (`Set-Cookie` is the canonical case), and it says nothing
 * about which argument is which — `HttpHeader("X-Token", "v1")` would compile with
 * the arguments the wrong way round. The value classes make that unrepresentable.
 *
 * The `List` is the wire shape too, never an object: a JSON object silently
 * collapses repeated keys, and `Set-Cookie` collapsing is a real bug, not a
 * cosmetic one. Order and duplication are both semantics here.
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
