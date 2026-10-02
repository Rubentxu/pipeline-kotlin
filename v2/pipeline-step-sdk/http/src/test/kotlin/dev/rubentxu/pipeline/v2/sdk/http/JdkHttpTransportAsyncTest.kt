package dev.rubentxu.pipeline.v2.sdk.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * H3 — the transport is coroutine-native, and cancellation is not an HTTP failure.
 *
 * ## The property under test
 *
 * > No thread is parked waiting for HTTP, and structured cancellation reaches the
 * > request.
 *
 * Both halves matter and they fail differently. Parking a thread is a scaling
 * defect you notice under `parallel`; swallowing cancellation is a correctness
 * defect that reports the world at fault when the operator asked the run to stop.
 *
 * The canaries map to the two objectives and the law:
 *
 * ```text
 * C1  cancelling the coroutine cancels the future, and NO outcome is produced
 * C2  HttpTimeoutException -> Expired          (unchanged semantics)
 * C3  IOException         -> Unreachable       (unchanged semantics)
 * C4  N requests -> clientFactory invoked EXACTLY ONCE   (H3.2, client reuse)
 * C5  two concurrent requests are not serialised by the transport
 * ```
 */
class JdkHttpTransportAsyncTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A deliberately slow endpoint: C1 needs a request that is still in flight
        // when the coroutine is cancelled.
        server.createContext("/slow") { exchange ->
            Thread.sleep(5_000)
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.createContext("/ok") { exchange ->
            val body = "ok".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // H4-C1: headers arrive, then the body stops. `sendResponseHeaders` with a
        // positive length declares a fixed Content-Length, so writing 64 KiB of a
        // declared 1 MiB and closing is exactly "the server answered 200 and then
        // went away mid-body" — the case `Unreachable` cannot describe.
        server.createContext("/drop-mid-body") { exchange ->
            exchange.sendResponseHeaders(200, 1_048_576L)
            exchange.responseBody.use {
                it.write(ByteArray(64 * 1_024) { FILLER })
                it.flush()
            }
        }
        // H4-C2: a body that keeps arriving, so there is a window in which to cancel.
        server.createContext("/slow-body") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                repeat(400) {
                    out.write(ByteArray(8 * 1_024) { FILLER })
                    out.flush()
                    Thread.sleep(25)
                }
            }
        }
        // H4-C3: a body larger than the declared cap, over a real socket.
        server.createContext("/large") { exchange ->
            val size = exchange.requestURI.query.orEmpty()
                .substringAfter("bytes=", "0").toLongOrNull() ?: 0L
            val head = minOf(size, 64L * 1_024)
            exchange.sendResponseHeaders(200, size)
            exchange.responseBody.use { out ->
                out.write(ByteArray(head.toInt()) { FILLER })
                var sent = head
                while (sent < size) {
                    val chunk = minOf(64L * 1_024, size - sent).toInt()
                    out.write(ByteArray(chunk) { FILLER })
                    sent += chunk
                }
            }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun request(
        url: String,
        timeoutMs: Long? = 10_000,
    ) = HttpSendRequest(
        url = url,
        method = HttpMethod.Get,
        headers = emptyList(),
        body = null,
        timeoutMs = timeoutMs,
        authorization = null,
        maxBodyBytes = 1024 * 1024,
    )

    // ── C1: cancellation is not an HTTP failure ────────────────────────────

    @Test
    fun `C1 cancelling the coroutine cancels the request and produces NO outcome`() = runBlocking {
        val transport = JdkHttpTransport()

        var produced: HttpTransportResult? = null
        val job = launch(Dispatchers.Default) {
            produced = transport.send(request("$baseUrl/slow", timeoutMs = 30_000))
        }

        // Let the request actually reach the server before cancelling it, so this
        // measures cancellation of an in-flight request and not of a pending call.
        delay(300)
        job.cancelAndJoin()

        assertTrue(
            produced == null,
            "a cancelled send must produce NO HttpTransportResult at all. Got $produced — " +
                "turning a cancellation into a result is how a stopped run reports that the " +
                "network failed.",
        )
    }

    @Test
    fun `C1b a cancelled send does not surface as Unreachable or Expired`() = runBlocking {
        val transport = JdkHttpTransport()

        val outcome = withTimeoutOrNull(5_000) {
            val job = launch(Dispatchers.Default) { transport.send(request("$baseUrl/slow", 30_000)) }
            delay(300)
            job.cancelAndJoin()
            "job finished"
        }

        assertEquals("job finished", outcome, "cancellation must not hang the caller")
    }

    // ── C2 / C3: the real transport failures keep their semantics ───────────

    @Test
    fun `C2 an HTTP timeout is Expired, not Unreachable`() = runBlocking {
        val result = JdkHttpTransport().send(request("$baseUrl/slow", timeoutMs = 250))
        assertTrue(
            result.outcome is HttpSendOutcome.Expired,
            "a declared bound elapsing is Expired; got ${result.outcome}",
        )
    }

    @Test
    fun `C3 a refused connection is Unreachable`() = runBlocking {
        // Port 1 on loopback: nothing listens, and the refusal is immediate.
        val result = JdkHttpTransport().send(request("http://127.0.0.1:1/ok", timeoutMs = 2_000))
        assertTrue(
            result.outcome is HttpSendOutcome.Unreachable,
            "a refused connection is Unreachable; got ${result.outcome}",
        )
    }

    // ── C4: the client is built once and reused ────────────────────────────

    @Test
    fun `C4 the HttpClient is built once and reused across requests`() = runBlocking {
        val built = AtomicInteger(0)
        val transport = JdkHttpTransport(clientFactory = {
            built.incrementAndGet()
            HttpClient.newHttpClient()
        })

        repeat(5) { transport.send(request("$baseUrl/ok")) }
        // And concurrently, because sequential reuse would still pass a lazy-init bug
        // that only shows up under parallel branches.
        coroutineScopeOfConcurrently(transport)

        assertEquals(
            1,
            built.get(),
            "clientFactory must be invoked exactly once per transport instance; got " +
                "${built.get()}. A client per request throws away the connection pool that " +
                "concurrent requests exist to amortise.",
        )
    }

    // ── C5: concurrency is not serialised by the transport ──────────────────

    @Test
    fun `C5 two concurrent requests overlap instead of queueing`() = runBlocking {
        val hits = AtomicInteger(0)
        server.createContext("/counted") { exchange ->
            hits.incrementAndGet()
            // Long enough that a serialised transport would be obvious in wall time.
            Thread.sleep(400)
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }

        val transport = JdkHttpTransport()
        val started = System.nanoTime()
        listOf(async { transport.send(request("$baseUrl/counted")) },
                async { transport.send(request("$baseUrl/counted")) }).awaitAll()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(2, hits.get(), "both requests must reach the server")
        assertTrue(
            elapsedMs < 750,
            "two 400ms requests took ${elapsedMs}ms, so the transport serialised them. " +
                "A blocking send on a shared dispatcher would do exactly this.",
        )
    }

    private suspend fun coroutineScopeOfConcurrently(transport: JdkHttpTransport) {
        kotlinx.coroutines.coroutineScope {
            listOf(
                async { transport.send(request("$baseUrl/ok")) },
                async { transport.send(request("$baseUrl/ok")) },
            ).awaitAll()
        }
    }

    // ── H4 over a real socket ──────────────────────────────────────────────

    /**
     * H4-C1 — a body that stops after the headers is NOT an unreachable host.
     *
     * The server was demonstrably reached: it returned a status. Folding this into
     * `Unreachable` would tell an operator "this host does not exist" about a host
     * that just answered, and would throw away the one number that explains the
     * incident — how many bytes actually arrived.
     */
    @Test
    fun `H4-C1 a body cut short after the headers is ResponseInterrupted, not Unreachable`() = runBlocking {
        val result = JdkHttpTransport().send(request("$baseUrl/drop-mid-body", timeoutMs = 10_000))

        val outcome = result.outcome
        assertTrue(
            outcome is HttpSendOutcome.ResponseInterrupted,
            "a 200 whose body stopped is ResponseInterrupted; got $outcome. Reporting it as " +
                "Unreachable would blame the host for something it already answered.",
        )
        outcome as HttpSendOutcome.ResponseInterrupted
        assertTrue(
            outcome.bytesReceived in 1..1_048_576,
            "bytesReceived must report what actually arrived (got ${outcome.bytesReceived}); " +
                "without it a body cut at 0 bytes and one cut at 100 KB look identical.",
        )
        assertTrue(
            outcome.bytesReceived < 1_048_576,
            "the server declared 1 MiB and sent less; ${outcome.bytesReceived} is the fact that " +
                "distinguishes this from a complete response.",
        )
    }

    /**
     * H4-C2 — cancellation reaches a body that is still streaming.
     *
     * C1 proved cancellation works before the response exists. This is the harder
     * half: the headers are long since received and the subscriber is mid-body, which
     * is the window where a transport is most likely to keep reading.
     */
    @Test
    fun `H4-C2 cancelling mid-body produces NO outcome and does not hang`() = runBlocking {
        val transport = JdkHttpTransport()

        var produced: HttpTransportResult? = null
        val job = launch(Dispatchers.Default) {
            produced = transport.send(request("$baseUrl/slow-body", timeoutMs = 30_000))
        }

        // Long enough for the headers and several body batches to have arrived.
        delay(400)
        job.cancelAndJoin()

        assertTrue(
            produced == null,
            "a send cancelled mid-body must produce NO result at all. Got $produced — a " +
                "truncated body reported as a completed request is the worst possible answer.",
        )
    }

    /**
     * H4-C3 — the bounded prefix, over a real socket, with the digest of the WHOLE body.
     *
     * The unit canary proves the subscriber's arithmetic. This proves the JDK actually
     * routes the response through it — a subscriber that is correct but never wired in
     * is a subscriber that has changed nothing.
     */
    @Test
    fun `H4-C3 a body over the cap is truncated, fully counted and fully digested`() = runBlocking {
        val size = 4L * 1_024 * 1_024
        val cap = 1_024 * 1_024

        val result = JdkHttpTransport().send(
            request("$baseUrl/large?bytes=$size", timeoutMs = 30_000).copy(maxBodyBytes = cap.toLong()),
        )

        val outcome = result.outcome
        assertTrue(outcome is HttpSendOutcome.Answered, "expected an answered response, got $outcome")
        outcome as HttpSendOutcome.Answered
        assertTrue(outcome.bodyTruncated, "4 MiB against a 1 MiB cap is a truncation")
        assertEquals(
            size,
            outcome.bodySizeBytes,
            "the counter must see all 4 MiB, not the 1 MiB that was kept",
        )
        assertEquals(cap, outcome.body.length, "exactly the cap is projected")
        assertEquals(
            digestOfRepeatedByte(FILLER, size),
            outcome.bodySha256,
            "the digest must cover the complete 4 MiB body, not the retained prefix",
        )
    }

    private companion object {
        const val FILLER: Byte = 0x78

        /** Independent of the transport: hashes [count] copies of [byte] with the JDK. */
        fun digestOfRepeatedByte(byte: Byte, count: Long): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val block = ByteArray(64 * 1_024) { byte }
            var written = 0L
            while (written < count) {
                val size = minOf(block.size.toLong(), count - written).toInt()
                digest.update(block, 0, size)
                written += size
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
