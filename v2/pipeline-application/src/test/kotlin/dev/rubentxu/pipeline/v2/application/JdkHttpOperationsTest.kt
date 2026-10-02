package dev.rubentxu.pipeline.v2.application

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import dev.rubentxu.pipeline.v2.domain.step.http.HttpHeader
import dev.rubentxu.pipeline.v2.domain.step.http.HttpMethod

/**
 * RP6-C / WU-093 G2 — the transport, against a REAL local HTTP server.
 *
 * A fake `HttpClient` would prove the code compiles and nothing else: timeouts,
 * truncation, multi-valued headers and "unreachable host" are all properties of the
 * transport, and a fake has to be configured to exhibit each of them, which means the
 * test would be asserting against its own imagination. A loopback server does not need
 * the internet and cannot flake on it.
 *
 * The complementary direction — the Step's contract over a substituted
 * [HttpOperations] — is in `CoreHttpStepContractTest`, which is why this file does not
 * test the ADTs again.
 */
@Timeout(120)
class JdkHttpOperationsTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val ops = JdkHttpOperations()
    private val counterHits = AtomicInteger()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            val body = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            // Two values for one name: the repeated-header case the wire must preserve.
            exchange.responseHeaders.add("Set-Cookie", "a=1")
            exchange.responseHeaders.add("Set-Cookie", "b=2")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/created") { exchange ->
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.createContext("/boom") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/big") { exchange ->
            val body = ByteArray(4096) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(3_000)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/echo") { exchange ->
            val received = exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            val body = """{"method":"${exchange.requestMethod}","auth":"${exchange.requestHeaders.getFirst("Authorization") ?: ""}","body":"$received","ct":"${exchange.requestHeaders.getFirst("Content-Type") ?: ""}"}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/count") { exchange ->
            counterHits.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun send(
        url: String = "$baseUrl/ok",
        method: HttpMethod = HttpMethod.Get,
        headers: List<HttpHeader> = emptyList(),
        body: String? = null,
        timeoutMs: Long? = 5_000,
        maxBodyBytes: Long = 1024 * 1024,
        authorization: HttpAuthorization? = null,
    ) = runBlocking {
        ops.send(
            HttpSendRequest(
                url = url,
                method = method,
                headers = headers,
                body = body,
                timeoutMs = timeoutMs,
                authorization = authorization,
                maxBodyBytes = maxBodyBytes,
            ),
        )
    }

    private fun answered(result: HttpTransportResult): HttpSendOutcome.Answered =
        result.outcome as HttpSendOutcome.Answered

    @Test
    fun `a GET returns the status, the body and every value of a repeated header`() {
        val result = send()
        val outcome = answered(result)
        assertEquals(200, outcome.status)
        assertEquals("""{"ok":true}""", outcome.body)
        // The multi-valued map must be flattened, not collapsed to the last value.
        assertEquals(
            listOf("a=1", "b=2"),
            outcome.headers.filter { it.name.value.equals("Set-Cookie", ignoreCase = true) }
                .map { it.value.value },
            "a repeated response header must survive as repeated",
        )
        assertTrue(outcome.bodySha256.isNotEmpty())
        assertEquals(11L, outcome.bodySizeBytes)
        assertTrue(!outcome.bodyTruncated)
    }

    @Test
    fun `a status the server chose is reported, never interpreted`() {
        // Transport does not decide acceptability: 503 travels back as a fact and the
        // Step decides. Putting the policy here would mean the port knows the policy.
        assertEquals(503, answered(send(url = "$baseUrl/boom")).status)
        assertEquals(201, answered(send(url = "$baseUrl/created")).status)
    }

    @Test
    fun `a body over the cap is truncated but its digest stays whole`() {
        val outcome = answered(send(url = "$baseUrl/big", maxBodyBytes = 100))
        assertTrue(outcome.bodyTruncated, "a 4096-byte body under a 100-byte cap must be marked")
        assertEquals(100, outcome.body.length)
        assertEquals(4096L, outcome.bodySizeBytes, "the real size is what the server sent")
        val whole = answered(send(url = "$baseUrl/big", maxBodyBytes = 1024 * 1024))
        assertEquals(
            whole.bodySha256,
            outcome.bodySha256,
            "the digest is of the complete body, so a truncated read still identifies it",
        )
    }

    @Test
    fun `the declared bound actually bounds the wait`() {
        // M-http-3 target. Without this row the bound is a field that is parsed,
        // resolved and then never applied.
        val startedAt = System.currentTimeMillis()
        val result = send(url = "$baseUrl/slow", timeoutMs = 400)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            result.outcome is HttpSendOutcome.Expired,
            "a request that outlives its bound must report Expired, got ${result.outcome}",
        )
        assertTrue(
            elapsed < 2_500,
            "the bound must cut the wait short of the server's 3s; took ${elapsed}ms",
        )
    }

    @Test
    fun `no bound means no bound`() {
        // The other half of the same law: `timeoutSeconds = 0` must NOT be turned
        // into a zero-length deadline that fires immediately.
        val startedAt = System.currentTimeMillis()
        val result = send(url = "$baseUrl/ok", timeoutMs = null)
        assertTrue(result.outcome is HttpSendOutcome.Answered, "got ${result.outcome}")
        assertTrue(System.currentTimeMillis() - startedAt < 2_000)
    }

    @Test
    fun `a host that does not resolve is Unreachable, not an exception`() {
        val result = send(url = "http://this-host-does-not-exist.invalid/x", timeoutMs = 5_000)
        assertTrue(
            result.outcome is HttpSendOutcome.Unreachable,
            "an unresolvable host must be a typed outcome, got ${result.outcome}",
        )
    }

    @Test
    fun `a refused connection is Unreachable too`() {
        // Port 1 on loopback: nothing listens there.
        val result = send(url = "http://127.0.0.1:1/x", timeoutMs = 3_000)
        assertTrue(result.outcome is HttpSendOutcome.Unreachable, "got ${result.outcome}")
    }

    @Test
    fun `a body and its content type reach the server`() {
        val outcome = answered(
            send(
                url = "$baseUrl/echo",
                method = HttpMethod.Post,
                body = """{"hello":"world"}""",
                headers = listOf(HttpHeader.of("Content-Type", "application/json")),
            ),
        )
        assertTrue(outcome.body.contains(""""method":"POST""""), outcome.body)
        assertTrue(outcome.body.contains("application/json"), outcome.body)
        assertTrue(outcome.body.contains("hello"), outcome.body)
    }

    @Test
    fun `an already-resolved credential is sent as a Basic header`() {
        val value = basicAuthorizationValue("ana", "s3cret")
        val outcome = answered(
            send(
                url = "$baseUrl/echo",
                headers = listOf(HttpHeader.of("Authorization", "Basic $value")),
            ),
        )
        assertTrue(outcome.body.contains("Basic "), outcome.body)
    }

    @Test
    fun `redirects are not followed so a credential cannot travel to another host`() {
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val outcome = answered(
            send(
                url = "$baseUrl/redirect",
                headers = listOf(HttpHeader.of("Authorization", "Basic leaked-candidate")),
            ),
        )
        assertEquals(
            302,
            outcome.status,
            "the 302 must be reported as it arrived, not followed",
        )
        assertTrue(!outcome.body.contains("leaked-candidate"))
    }

    @Test
    fun `one send is exactly one request`() {
        // Jenkins calls disableAutomaticRetries() explicitly because a silent retry of
        // a POST charges twice. The JDK client does not retry on its own, and this row
        // is what keeps it true if a future client is swapped in.
        val before = counterHits.get()
        answered(send(url = "$baseUrl/count"))
        answered(send(url = "$baseUrl/count"))
        assertEquals(
            before + 2,
            counterHits.get(),
            "two sends must reach the server exactly twice",
        )
    }
}
