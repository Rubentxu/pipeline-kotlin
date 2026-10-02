package dev.rubentxu.pipeline.v2.sdk.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * @param clientFactory injectable so the contract can be exercised without a socket;
 *   production leaves it at the default. The seam exists because a test that can only
 *   run against a live host is a test that stops being run.
 */
class JdkHttpTransport(
    private val clientFactory: () -> HttpClient = { defaultClient() },
) : HttpTransport {

    override suspend fun send(request: HttpSendRequest): HttpTransportResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val outcome: HttpSendOutcome = try {
                execute(request)
            } catch (e: HttpTimeoutException) {
                HttpSendOutcome.Expired(elapsedMs(startedAt))
            } catch (e: IOException) {
                // DNS failure, refused connection, reset, TLS handshake failure. They
                // are one fact to a caller — the host could not be reached — and the
                // cause is kept as the diagnostic rather than as a case, because none
                // of them is something the caller can act on differently.
                HttpSendOutcome.Unreachable(e.javaClass.simpleName + ": " + (e.message ?: "no detail"))
            }
            HttpTransportResult(outcome = outcome, durationMs = elapsedMs(startedAt))
        }

    private fun execute(request: HttpSendRequest): HttpSendOutcome {
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

        val response: HttpResponse<ByteArray> = clientFactory()
            .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val raw = response.body()

        // The digest is of the COMPLETE body, even when the materialised prefix is
        // shorter. A truncated response whose digest is of the prefix cannot be told
        // apart from a genuinely short one, and the identity of what the server
        // actually said is exactly the thing worth keeping.
        val digest = sha256Hex(raw)
        val prefix = if (raw.size > request.maxBodyBytes) {
            raw.copyOf(request.maxBodyBytes.toInt())
        } else {
            raw
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
            body = String(prefix, StandardCharsets.UTF_8),
            bodySha256 = digest,
            bodySizeBytes = raw.size.toLong(),
            bodyTruncated = raw.size > request.maxBodyBytes,
        )
    }

    /**
     * Deliberately no `redirect` policy: `HttpClient.Redirect.NORMAL` follows a 302
     * and the `Authorization` header goes with it. A credential minted for one host
     * leaking to another is a worse failure than a 302 the author has to notice, so
     * redirects are not followed and the status is reported as it arrived.
     */
    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun sha256Hex(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is required by the platform", e)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000

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
