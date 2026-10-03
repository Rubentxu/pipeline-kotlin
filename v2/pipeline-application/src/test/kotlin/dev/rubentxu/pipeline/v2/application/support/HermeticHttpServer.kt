package dev.rubentxu.pipeline.v2.application.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * WU-093 H8 — a real HTTP server on loopback, for the installed-distribution UAT.
 *
 * ## Why a server and not a mock
 *
 * Every test so far has asserted on a `RecordingTransport` that counts sends. That is
 * the right tool for "did the handler ask once", and the wrong tool for certification.
 * It cannot produce a body cut in half, cannot be slow on demand, cannot return a 500,
 * and cannot tell whether the JDK reused a connection. H8's scenarios are exactly the
 * ones a mock answers by construction, so a mock would certify the mock.
 *
 * ## Hermetic by construction
 *
 * Bound to `127.0.0.1` on an ephemeral port. There is no URL in this file that resolves
 * off-box, and no DNS: a run cannot accidentally depend on the public internet, so a
 * green run cannot be a green run because someone else's server was up.
 *
 * ## The endpoints, and why each one exists
 *
 * ```text
 * /ok                 200, small body                  the allowed baseline
 * /echo               echoes method/body/headers      proves the request that arrived
 * /large?bytes=N      200, N deterministic bytes      the bounded-memory property
 * /slow?ms=N          200 after N ms                   timeouts, and cancellation
 * /drop-mid-body?bytes=N   headers, then N bytes, then a hard close
 *                                                      a body that stops for a reason
 *                                                      that is NOT an unreachable host
 * /status/{code}      that status                      accepted vs refused ranges
 * /basic-auth         401 without the right header    credentials, end to end
 * /redirect           302 to /ok                      redirects must stay unfollowed
 * ```
 *
 * ## It records what it saw
 *
 * [requests] is the other half of the product. Half of H8 is "the pipeline behaved", and
 * half is "the server saw exactly one request" — and only the server can say the second,
 * because the pipeline's own journal cannot see a request that went out and was lost.
 */
class HermeticHttpServer private constructor(
    private val server: HttpServer,
    val port: Int,
) : AutoCloseable {

    private val seen = java.util.concurrent.ConcurrentLinkedQueue<RecordedRequest>()

    /** Everything this server was asked, in order. The evidence the pipeline cannot produce. */
    val requests: List<RecordedRequest> get() = seen.toList()

    fun countOf(path: String): Int = requests.count { it.path == path }

    /**
     * How many distinct client sockets served these requests.
     *
     * Connection reuse is not observable from the client: `HttpClient` reusing a
     * keep-alive connection and opening a fresh one look identical to every assertion
     * that only counts requests. The server sees the peer port, so it is the only
     * witness — and reuse is worth measuring, because a per-request connection is a
     * per-request TLS handshake and a per-request round trip in a pipeline that calls
     * a remote service in a loop.
     */
    fun distinctClientSockets(): Int = requests.mapNotNull { it.peerPort }.distinct().size

    val authority: String get() = "127.0.0.1:$port"

    /**
     * The URL prefix every scenario must use.
     *
     * NOT the same as [authority]. A pipeline that writes `127.0.0.1:8080/ok` is a
     * URL with no scheme, and the H6 egress gate correctly refuses it as
     * `UnjudgeableDestination` — which is the gate working, caught the first draft
     * of this harness for writing a bare authority where a URL belonged.
     */
    val baseUrl: String get() = "http://$authority"

    override fun close() {
        server.stop(0)
    }

    /**
     * A request as the SERVER saw it.
     *
     * Deliberately holds the `Authorization` header as a boolean ("was one sent") and not
     * as its value: a recorder that keeps credentials is a recorder that eventually
     * prints them, and this file ends up in a test failure message.
     */
    data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val bodyLength: Int,
        val carriedAuthorization: Boolean,
        val userAgent: String?,
        /** The client's ephemeral port, which is a socket identity. */
        val peerPort: Int?,
    )

    companion object {
        /**
         * Starts on an ephemeral loopback port. [credentials] is the only pair
         * `/basic-auth` will accept; anything else gets a 401.
         */
        fun start(
            credentials: Pair<String, String> = "stage" to "s3cr3t",
        ): HermeticHttpServer {
            val server = HttpServer.create(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0,
            )
            val instance = HermeticHttpServer(server, server.address.port)
            val expected = Base64.getEncoder()
                .encodeToString("${credentials.first}:${credentials.second}".toByteArray(StandardCharsets.UTF_8))

            // Cached, not per-request: /large can serve hundreds of MiB and re-encoding
            // the block every time would make the memory test measure the server.
            val cache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

            server.createContext("/") { exchange -> instance.route(exchange, expected, cache) }
            // Daemon threads, or a forked UAT that ends first leaves the JVM unable to exit.
            server.executor = Executors.newFixedThreadPool(8) { r ->
                Thread(r, "h8-http-uat").apply { isDaemon = true }
            }
            server.start()
            return instance
        }

        private fun HermeticHttpServer.route(
            exchange: HttpExchange,
            expectedBasic: String,
            cache: java.util.concurrent.ConcurrentHashMap<Int, ByteArray>,
        ) {
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query
            seen += RecordedRequest(
                method = exchange.requestMethod,
                path = path,
                query = query,
                bodyLength = exchange.requestBody.readAllBytes().size,
                carriedAuthorization = exchange.requestHeaders.containsKey("Authorization"),
                userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                peerPort = exchange.remoteAddress?.port,
            )

            try {
                when {
                    path == "/ok" -> respond(exchange, 200, "ok\n".toByteArray())

                    path == "/echo" -> respond(
                        exchange,
                        200,
                        echo(exchange),
                    )

                    path == "/large" -> serveLarge(exchange, query, cache)

                    path == "/slow" -> {
                        Thread.sleep(query?.longParam("ms") ?: 1_000L)
                        respond(exchange, 200, "slow-ok\n".toByteArray())
                    }

                    path == "/drop-mid-body" -> dropMidBody(
                        exchange,
                        (query?.longParam("bytes") ?: 65_536L).toInt(),
                    )

                    path == "/basic-auth" -> {
                        val offered = exchange.requestHeaders.getFirst("Authorization")
                        if (offered == "Basic $expectedBasic") {
                            respond(exchange, 200, "authenticated\n".toByteArray())
                        } else {
                            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"h8\"")
                            respond(exchange, 401, "unauthorized\n".toByteArray())
                        }
                    }

                    path == "/redirect" -> {
                        exchange.responseHeaders.add("Location", "/ok")
                        respond(exchange, 302, ByteArray(0))
                    }

                    path.startsWith("/status/") -> {
                        val code = path.removePrefix("/status/").toIntOrNull() ?: 500
                        respond(exchange, code, "status-$code\n".toByteArray())
                    }

                    else -> respond(exchange, 404, "no such endpoint\n".toByteArray())
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                runCatching { exchange.close() }
            }
        }

        /** Deterministic content, so a hash in a receipt can be recomputed by a reader. */
        private fun blockFor(size: Int): ByteArray {
            val out = ByteArray(size)
            var i = 0
            var seed = 0x5A
            while (i < size) {
                seed = (seed * 31 + 17) and 0xFF
                out[i] = seed.toByte()
                i++
            }
            return out
        }

        private fun serveLarge(
            exchange: HttpExchange,
            query: String?,
            cache: java.util.concurrent.ConcurrentHashMap<Int, ByteArray>,
        ) {
            val total = (query?.longParam("bytes") ?: 65_536L).toInt()
            val chunk = cache.computeIfAbsent(64 * 1024) { blockFor(it) }

            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, total.toLong())
            exchange.responseBody.use { out ->
                var written = 0
                while (written < total) {
                    val n = minOf(chunk.size, total - written)
                    out.write(chunk, 0, n)
                    out.flush()
                    written += n
                }
            }
        }

        /**
         * Announces [bytes], writes [bytes], then closes without finishing the exchange.
         *
         * The client has status 200 and is mid-body when the stream dies. That is the
         * case `ResponseInterrupted` exists for, and the case a `Unreachable` would
         * have lied about: the host was reachable and answered.
         */
        private fun dropMidBody(exchange: HttpExchange, bytes: Int) {
            val half = (bytes / 2).coerceAtLeast(1)
            exchange.sendResponseHeaders(200, bytes.toLong())
            exchange.responseBody.use { out ->
                out.write(blockFor(half))
                out.flush()
            }
            exchange.close()
        }

        private fun echo(exchange: HttpExchange): ByteArray {
            val body = exchange.requestBody.readAllBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it) }
            val text = buildString {
                append("method=").append(exchange.requestMethod).append('\n')
                append("path=").append(exchange.requestURI.path).append('\n')
                append("bodySha256=").append(digest).append('\n')
                append("bodyBytes=").append(body.size).append('\n')
                append("authorizationPresent=").append(exchange.requestHeaders.containsKey("Authorization")).append('\n')
            }
            return text.toByteArray(StandardCharsets.UTF_8)
        }

        private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        private fun String?.longParam(name: String): Long? =
            this?.split("&")
                ?.mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
                ?.firstOrNull { it[0] == name }
                ?.get(1)
                ?.toLongOrNull()
    }
}
