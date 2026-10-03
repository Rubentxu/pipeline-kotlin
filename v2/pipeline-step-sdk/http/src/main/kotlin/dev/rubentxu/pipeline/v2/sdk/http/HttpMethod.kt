package dev.rubentxu.pipeline.v2.sdk.http

/**
 * The HTTP method, as a closed ADT rather than a `String` (RP6-C / WU-093).
 *
 * It lives in the plugin module, next to the DSL surface that offers it
 * ([httpRequest]) and to the contract that carries it, so one type serves the
 * author and the runtime with no duplication and no second declaration.
 *
 * It deliberately does NOT live in `pipeline-domain`. Before the OFFICIAL_PLUGIN
 * pivot it did, justified by a `StepSpec.HttpRequest` in `pipeline-scripting-api`
 * that needed the vocabulary and could not reach the application layer
 * (`FArchRP030HexagonalDependencyDirectionTest` forbids that direction). The pivot
 * deleted `StepSpec.HttpRequest` along with the compiler branch, so that
 * justification described an architecture that no longer exists. Keeping the type
 * in `pipeline-domain` afterwards would have been the actual defect: it would name
 * HTTP from the innermost seam, which is precisely what FIT-2 and FIT-4 assert that
 * this plugin does not do. Vocabulary that only a plugin speaks belongs to the plugin.
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
