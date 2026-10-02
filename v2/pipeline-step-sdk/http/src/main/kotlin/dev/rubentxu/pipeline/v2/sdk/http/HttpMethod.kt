package dev.rubentxu.pipeline.v2.sdk.http

/**
 * The HTTP method, as a closed ADT rather than a `String` (RP6-C / WU-093).
 *
 * It lives in `pipeline-domain`, not in the application layer, because it is
 * vocabulary shared by the DSL surface (`StepSpec.HttpRequest`, declared in
 * `pipeline-scripting-api`) and the runtime contract. `pipeline-scripting-api`
 * must not depend on `pipeline-application` — `FArchRP030HexagonalDependencyDirectionTest`
 * forbids it — so a type declared there could only reach the DSL by duplication.
 * One truth, one module: `domain` is the innermost seam both sides are allowed
 * to name, exactly as `domain.scm.Scm` and `domain.CredentialsId` already are.
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
