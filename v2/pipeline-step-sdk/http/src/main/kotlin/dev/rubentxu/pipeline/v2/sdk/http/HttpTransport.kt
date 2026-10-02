package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The ONE network seam of the `http.request` OFFICIAL_PLUGIN (LFC-2E3 / WU-093).
 *
 * `JdkHttpTransport` is the only class in this module that opens a socket, and it
 * lives HERE, inside the plugin, not in `pipeline-application`. HTTP is a protocol
 * and vendor concern (STEP_ECOSYSTEM_POLICY: `pipeline-plugin-http`; matrix row:
 * `httpRequest | OFFICIAL_PLUGIN candidate`), so the concrete transport belongs to
 * the plugin that owns the protocol. Application composes it; it does not know
 * what HTTP is.
 *
 * What application DOES own is the egress POLICY — whether this run may reach the
 * network at all — and that is not HTTP knowledge, it is a per-execution runtime
 * decision. It arrives as the generic `NETWORK_EGRESS_CAPABILITY` and is declared
 * in `requiredCapabilities`, so its absence is a fail-closed admission rejection
 * before the handler ever runs. A pipeline that reaches for the network without
 * `--allow-network` therefore never gets a transport to use.
 */
val HTTP_TRANSPORT_CAPABILITY: StepCapability = StepCapability("http.transport")

sealed interface NetworkPolicy {
    data object Denied : NetworkPolicy
    data object Allowed : NetworkPolicy
}

data class HttpSendRequest(
    val url: String,
    val method: HttpMethod,
    val headers: List<HttpHeader>,
    val body: String?,
    /** `null` means no bound at all, which only `timeoutSeconds = 0` produces. */
    val timeoutMs: Long?,
    val authorization: HttpAuthorization?,
    val maxBodyBytes: Long,
)

/**
 * A resolved credential header, ready to write — but not yet written.
 *
 * H5: this used to carry a pre-encoded base64 `String`, while the function that
 * produces it claimed the secret "never enters the port". Both cannot be true. It
 * now carries the raw material and the encoding happens inside the transport, in
 * the last place the secret exists, and the bytes can be wiped straight after.
 */
sealed interface HttpAuthorization {
    data object None : HttpAuthorization

    data class Basic(
        val username: String,
        /** Secret material. Wiped by the transport once the header string is built. */
        val password: ByteArray,
    ) : HttpAuthorization
}

/**
 * Everything a send can produce, as a closed set.
 *
 * A dropped connection is not an exception crossing the Step: it is a fact about the
 * world, and the Step has to classify it into a `FailureKind`. Returning `null` or
 * throwing would push that decision out of the type.
 */
sealed interface HttpSendOutcome {
    data class Answered(
        val status: Int,
        val headers: List<HttpHeader>,
        val body: String,
        val bodySha256: String,
        val bodySizeBytes: Long,
        val bodyTruncated: Boolean,
    ) : HttpSendOutcome

    data class Unreachable(val reason: String) : HttpSendOutcome

    data class Expired(val afterMs: Long) : HttpSendOutcome

    /**
     * H4 — the response STARTED and the body stopped.
     *
     * This case exists because `Unreachable` is a lie here. A server that answered
     * `200` and then closed the connection after 37 MiB was, unambiguously, reached;
     * the failure happened later, while the body was being read. Folding both into
     * `Unreachable` loses the two facts an operator actually needs:
     *
     * ```text
     * DNS failure / refused connection  ->  the request never had a chance
     * body cut short at byte N          ->  the request SUCCEEDED, partially
     * ```
     *
     * [bytesReceived] is the whole reason this is worth its own case: a truncated
     * download, a server that ran out of disk, and a proxy that gave up are three
     * different incidents that all report the same string without it.
     */
    data class ResponseInterrupted(
        val reason: String,
        val bytesReceived: Long,
    ) : HttpSendOutcome
}

/**
 * The transport port. Knows about HTTP and about nothing else: no journal, no events,
 * no credentials store, no clock of its own.
 *
 * It measures its own duration because it is the only party that can: the same split
 * `ShExecution` uses for `durationMs`. A handler that timed its own I/O would be
 * measuring its own overhead, and a port that took a `Clock` would be a clock the
 * caller has to keep in step.
 */
interface HttpTransport {
    suspend fun send(request: HttpSendRequest): HttpTransportResult
}

/** What the transport reports back: the outcome plus how long it actually took. */
data class HttpTransportResult(
    val outcome: HttpSendOutcome,
    val durationMs: Long,
)
