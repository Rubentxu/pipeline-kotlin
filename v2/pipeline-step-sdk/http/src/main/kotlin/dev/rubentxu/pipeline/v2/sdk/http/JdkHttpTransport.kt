package dev.rubentxu.pipeline.v2.sdk.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await

/**
 * RP6-C / WU-093 G2 — the ONLY class in this repository that opens a socket.
 *
 * `java.net.http` is not a preference here. `FArch002ApplicationDependsInwardTest`
 * allows `pipeline-application` exactly three third-party artifacts (kotlin-stdlib,
 * kotlin-stdlib-jdk8, kotlinx-coroutines-core) and uses Ktor as its own violation
 * fixture, so OkHttp, Ktor and Apache HttpClient are all out. The JDK 21 toolchain's
 * client is the only door left, and the fitness keeps it that way.
 *
 * Everything policy-shaped lives OUTSIDE this class: which statuses are acceptable,
 * whether a credential may be used, whether the network is allowed at all. Here there
 * is only transport.
 *
 * ## H3.1 — coroutine-native, no parked thread
 *
 * The transport AWAITS a `CompletableFuture`; it never parks a thread waiting
 * for one. The previous shape called the blocking `HttpClient.send` inside
 * `withContext(Dispatchers.IO)`, which parks an IO worker for the whole
 * round-trip and scales linearly with concurrency — the wrong shape for a
 * `parallel` stage running many requests at once.
 *
 * ## H3.2 — ONE client per transport instance
 *
 * `clientFactory()` used to run inside every `send()`, so production built a
 * fresh `HttpClient` per request and threw away exactly the connection pool and
 * keep-alive that concurrent requests exist to amortise. A `HttpClient` is
 * expensive to construct and is designed to be long-lived and shared.
 *
 * ## The law: cancellation is NOT an HTTP failure
 *
 * ```text
 * DNS / refused / reset / TLS  -> Unreachable
 * HTTP timeout                -> Expired
 * coroutine cancellation      -> PROPAGATE
 * ```
 *
 * Catching too widely around `await()` would turn a cancelled run into a
 * reported network failure, and a run that was told to stop would instead
 * report that the world was at fault. `await()` already cancels the future when
 * the awaiting coroutine is cancelled, so the only job here is to NOT catch it.
 *
 * @param clientFactory injectable so the contract can be exercised without a socket;
 *   production leaves it at the default. The seam exists because a test that can only
 *   run against a live host is a test that stops being run. It is invoked ONCE per
 *   transport instance (H3.2), which is what makes client reuse observable.
 */
class JdkHttpTransport(
    private val clientFactory: () -> HttpClient = { defaultClient() },
) : HttpTransport {

    // H3.2: built once, reused for the life of the transport. `lazy`, not eager,
    // so constructing a transport never opens anything on its own.
    private val client: HttpClient by lazy(clientFactory)

    override suspend fun send(request: HttpSendRequest): HttpTransportResult {
        val startedAt = System.nanoTime()
        // H4: the body is consumed by a subscriber that keeps a bounded prefix and
        // digests everything, so `maxBodyBytes` bounds MEMORY rather than arriving
        // after the fact to truncate a body that was already fully in the heap.
        val subscriber = BoundedBodySubscriber(materializationLimitOf(request.maxBodyBytes))
        // Only the two REAL transport failures are caught. CancellationException is
        // deliberately NOT in scope: it is the runtime's decision, not a fact about
        // the network, and swallowing it here is how a stopped run reports that the
        // world was at fault.
        val outcome: HttpSendOutcome = try {
            executeAsync(request, subscriber)
        } catch (e: HttpTimeoutException) {
            HttpSendOutcome.Expired(elapsedMs(startedAt))
        } catch (e: IOException) {
            // ONE classification point, asked a FACT rather than guessed from which
            // call threw. The JDK may surface a mid-body failure through the OUTER
            // response future, so "it came from the body await" is not a reliable
            // discriminator — but "the body subscription was established" is, and it
            // is the difference between a host that never answered and a host that
            // answered and then stopped.
            if (subscriber.bodyStarted) {
                HttpSendOutcome.ResponseInterrupted(
                    reason = describe(e),
                    bytesReceived = subscriber.bytesReceived,
                )
            } else {
                // DNS failure, refused connection, reset, TLS handshake failure. They
                // are one fact to a caller — the host could not be reached — and the
                // cause is kept as the diagnostic rather than as a case, because none
                // of them is something the caller can act on differently.
                HttpSendOutcome.Unreachable(describe(e))
            }
        }
        return HttpTransportResult(outcome = outcome, durationMs = elapsedMs(startedAt))
    }

    private fun describe(e: IOException): String =
        e.javaClass.simpleName + ": " + (e.message ?: "no detail")

    private suspend fun executeAsync(
        request: HttpSendRequest,
        subscriber: BoundedBodySubscriber,
    ): HttpSendOutcome {
        val builder = HttpRequest.newBuilder(URI.create(request.url))
        request.timeoutMs?.let { builder.timeout(Duration.ofMillis(it)) }
        request.headers.forEach { builder.header(it.name.value, it.value.value) }
        when (val authorization = request.authorization) {
            null, HttpAuthorization.None -> Unit
            is HttpAuthorization.Basic -> builder.header(
                "Authorization",
                "Basic " + authorization.base64UserPassword,
            )
        }
        val javaMethod = when (request.method) {
            HttpMethod.Get -> "GET"
            HttpMethod.Head -> "HEAD"
            HttpMethod.Post -> "POST"
            HttpMethod.Put -> "PUT"
            HttpMethod.Delete -> "DELETE"
            HttpMethod.Options -> "OPTIONS"
            HttpMethod.Patch -> "PATCH"
        }
        val bodyBytes = request.body?.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes != null) {
            builder.method(javaMethod, HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        } else {
            builder.method(javaMethod, HttpRequest.BodyPublishers.noBody())
        }

        // H3.1: the suspension point covering CONNECT, SEND and HEADERS. `await()`
        // parks THIS coroutine, not a thread, and cancels the underlying future if
        // the coroutine is cancelled.
        //
        // `fromSubscriber` (there is no `ofSubscriber`) yields a `BodyHandler<Void>`:
        // the returned response carries NO body and the body arrives on the
        // subscriber's own completion stage. The `Void` is not a missing feature —
        // it is the JDK saying the body belongs to the subscriber, which is the
        // whole reason the response can be bounded in memory.
        val response: HttpResponse<Void> = client
            .sendAsync(builder.build(), HttpResponse.BodyHandlers.fromSubscriber(subscriber))
            .await()

        // The response object is complete at the HEADERS; the body follows on the
        // subscriber's stage. A mid-body failure surfaces HERE or through the outer
        // future — the JDK chooses — and `send` classifies it from `bodyStarted`
        // rather than from which of the two threw.
        val body: BoundedBody = try {
            subscriber.getBody().toCompletableFuture().await()
        } catch (e: CancellationException) {
            // Not an HTTP fact. Cancel the exchange explicitly rather than trusting
            // that cancelling the stage reached the socket, then let it propagate:
            // a stopped run must never be reported as a broken network.
            subscriber.cancel()
            throw e
        }

        // `map()` flattens the multi-valued header map, which is what a header LIST
        // means: HTTP allows a name to repeat, and `Set-Cookie` relies on it. Iterating
        // the entrySet instead would keep only the last value of each name.
        val headers = response.headers().map().flatMap { (name, values) ->
            values.map { value -> HttpHeader.of(name, value) }
        }
        return HttpSendOutcome.Answered(
            status = response.statusCode(),
            headers = headers,
            body = String(body.prefix, StandardCharsets.UTF_8),
            bodySha256 = body.sha256,
            bodySizeBytes = body.totalBytes,
            bodyTruncated = body.truncated,
        )
    }

    /**
     * Narrows the declared cap to what a JVM array can actually be.
     *
     * [HttpSendRequest.maxBodyBytes] is `Long` because that is the honest type for a
     * body size, and a `ByteArray` is not. Narrowing with `.toInt()` would turn a cap
     * above `Int.MAX_VALUE` into a negative one and truncate every response to
     * nothing — a silent lie about a limit, which is worse than a loud failure.
     *
     * `require` rather than a typed rejection because this is a PROGRAMMER defect
     * today: `toSendRequest()` is the only producer and it supplies
     * [HttpDefaults.MAX_RESPONSE_BYTES]. The moment an author can set the cap, the
     * check belongs in `httpIntentOf` as an [HttpRejection], where a wrong value is
     * a USER fact instead of a crash.
     */
    private fun materializationLimitOf(maxBodyBytes: Long): Int {
        require(maxBodyBytes in 0..MAX_MATERIALIZED_BYTES) {
            "maxBodyBytes must be between 0 and $MAX_MATERIALIZED_BYTES, got $maxBodyBytes. " +
                "A cap a JVM array cannot hold would silently truncate every response."
        }
        return maxBodyBytes.toInt()
    }

    /**
     * Wall time of the whole exchange, body included.
     *
     * Measured here because the transport is the only party that can: a handler
     * timing its own I/O would be timing its own overhead, and a port that took a
     * `Clock` would be a clock the caller has to keep in step. This is the same split
     * `ShExecution` uses for its `durationMs`.
     */
    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000

        /**
         * The largest prefix a JVM array can hold. `ByteArray` is `Int`-indexed, so
         * this is a property of the platform rather than a limit anybody chose.
         */
        private const val MAX_MATERIALIZED_BYTES: Long = Int.MAX_VALUE.toLong()

        /**
         * Deliberately `NEVER` redirects: `NORMAL` would follow a 302 and take the
         * `Authorization` header with it. A credential minted for one host leaking to
         * another is a worse failure than a 302 the author has to notice, so the status
         * is reported as it arrived.
         */
        fun defaultClient(): HttpClient =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS))
                .build()
    }
}

/** Encodes user/password into a Basic header value. Lives here so the secret never enters the port. */
fun basicAuthorizationValue(user: String, password: String): String =
    Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
