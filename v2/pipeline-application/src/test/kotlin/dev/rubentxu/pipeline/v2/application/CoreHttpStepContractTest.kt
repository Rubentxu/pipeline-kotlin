package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.domain.step.http.HttpHeader
import dev.rubentxu.pipeline.v2.domain.step.http.HttpMethod
import dev.rubentxu.pipeline.v2.domain.step.http.StatusRange

/**
 * Contract of `core.httpRequest` (RP6-C / WU-093 G1), exercised WITHOUT a socket:
 * the network arrives in G2 behind `HttpOperations`, and the contract has to hold
 * before any transport exists.
 *
 * The mutation rows M-http-1 and M-http-2 (WU093_G1_G4_IMPLEMENTATION_PLAN.md) exist to
 * break exactly two laws stated here, and both were verified RED before this suite was
 * accepted as green.
 */
class CoreHttpStepContractTest {

    // ── identity and surface ────────────────────────────────────────────────

    @Test
    fun `the default surface is a bounded GET, not an unbounded one`() {
        // Jenkins defaults `timeout` to 0 = no timeout. Inheriting it would mean a
        // forgotten bound hangs the run forever, so the default is declared instead.
        val input = CoreHttpInput(url = "https://example.test/api")
        assertEquals(HttpMethod.Get, input.method)
        assertEquals(30, input.timeoutSeconds)
        assertEquals(listOf(StatusRange.Span(100, 399)), input.validResponseCodes)
        assertNull(input.body)
        assertNull(input.authentication)
    }

    @Test
    fun `timeout zero means no bound and survives as null`() {
        // Jenkins-compatible: 0 is how you ask for "no timeout". It must mean exactly
        // that and nothing else — not a zero-length deadline that fires immediately.
        val intent = httpIntentOf(CoreHttpInput(url = "https://example.test", timeoutSeconds = 0))
        assertTrue(intent is HttpIntent.Ready)
        assertNull((intent as HttpIntent.Ready).timeoutMs)
    }

    // ── the method ADT ──────────────────────────────────────────────────────

    @Test
    fun `every declared method round-trips through the wire`() {
        val methods = listOf(
            HttpMethod.Get, HttpMethod.Head, HttpMethod.Post,
            HttpMethod.Put, HttpMethod.Delete, HttpMethod.Options, HttpMethod.Patch,
        )
        for (method in methods) {
            val input = CoreHttpInput(url = "https://example.test", method = method)
            val decoded = CoreHttpWireCodec.decode(CoreHttpWireCodec.encode(input))
            assertEquals(method, decoded.method, "method $method did not survive the wire")
        }
    }

    @Test
    fun `an unknown method is rejected at decode, never guessed`() {
        val payload = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            """{"kind":"httpRequest","url":"https://example.test","httpMode":"GTE"}""",
        )
        val failure = runCatching { CoreHttpWireCodec.decode(payload) }.exceptionOrNull()
        // Decoding must fail loudly rather than defaulting to GET: silently downgrading
        // a PATCH into a GET is the worst possible answer to a typo.
        assertTrue(failure != null, "an unknown httpMode must not decode to a default")
    }

    // ── headers (D1) ────────────────────────────────────────────────────────

    @Test
    fun `a header cannot be built with its name and value the wrong way round`() {
        // The value classes are what makes this unrepresentable rather than merely
        // discouraged: `HttpHeader("X-Token", "v1")` does not compile.
        val header = HttpHeader.of("X-Token", "v1")
        assertEquals("X-Token", header.name.value)
        assertEquals("v1", header.value.value)
    }

    @Test
    fun `a repeated header keeps every value instead of collapsing`() {
        // `Set-Cookie` legitimately repeats. A Map<String,String> would keep the last
        // one and quietly lose a session.
        val input = CoreHttpInput(
            url = "https://example.test",
            customHeaders = listOf(
                HttpHeader.of("Set-Cookie", "a=1"),
                HttpHeader.of("Set-Cookie", "b=2"),
            ),
        )
        val decoded = CoreHttpWireCodec.decode(CoreHttpWireCodec.encode(input))
        assertEquals(2, decoded.customHeaders.size, "both Set-Cookie values must survive")
        assertEquals(listOf("a=1", "b=2"), decoded.customHeaders.map { it.value.value })
    }

    // ── status ranges (D3) ──────────────────────────────────────────────────

    @Test
    fun `the Jenkins spelling of a status range parses into typed ranges`() {
        assertEquals(
            listOf(StatusRange.Span(100, 399), StatusRange.Single(404)),
            StatusRange.parse("100:399,404"),
        )
        assertEquals(listOf(StatusRange.Single(200)), StatusRange.parse(" 200 "))
    }

    @Test
    fun `a range that cannot mean anything is refused by the parser`() {
        assertNull(StatusRange.parse("abc"), "not a number")
        assertNull(StatusRange.parse("500:404"), "backwards span")
        assertNull(StatusRange.parse("1:2:3"), "three parts")
        assertNull(StatusRange.parse("99"), "outside 100..599")
        assertNull(StatusRange.parse(""), "empty")
    }

    @Test
    fun `a backwards span is unrepresentable rather than merely unlikely`() {
        // M-http-2 target. The invariant lives in the constructor, so no codec, no
        // handler and no test can build one.
        val failure = runCatching { StatusRange.Span(500, 404) }.exceptionOrNull()
        assertTrue(failure != null, "Span(500, 404) must not exist")
    }

    @Test
    fun `acceptance is inclusive at both ends and is a property of the response`() {
        val accepted = listOf(StatusRange.Span(200, 299), StatusRange.Single(404))
        assertTrue(CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 200).isAccepted(accepted))
        assertTrue(CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 299).isAccepted(accepted))
        assertTrue(CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 404).isAccepted(accepted))
        assertTrue(!CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 300).isAccepted(accepted))
        assertTrue(!CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 199).isAccepted(accepted))
    }

    // ── rejections are a closed set, each distinguishable (M-http-1) ──────────

    @Test
    fun `every rejection is its own case and round-trips distinctly`() {
        // M-http-1 target. A single "InvalidHttpRequest" catch-all would make all of
        // these indistinguishable in the journal and in the failure message.
        val rejections = listOf(
            HttpRejection.BlankUrl("   "),
            HttpRejection.UnsupportedMethod("GTE"),
            HttpRejection.BodyWithoutMethod("GET"),
            HttpRejection.UnparseableStatusRange("nope"),
            HttpRejection.NonPositiveTimeout(-5),
            HttpRejection.BlankHeaderName(2),
        )
        assertEquals(
            rejections.size,
            rejections.map { it.diagnostic }.distinct().size,
            "each rejection must produce its own diagnostic",
        )
        assertEquals(
            rejections.size,
            rejections.distinct().size,
            "each rejection must be a distinct value",
        )
        assertNotEquals(rejections[0], rejections[1])
    }

    @Test
    fun `each way a declaration is wrong produces its own rejection`() {
        assertEquals(
            HttpIntent.Rejected(HttpRejection.BlankUrl(" ")),
            httpIntentOf(CoreHttpInput(url = " ")),
        )
        assertEquals(
            HttpIntent.Rejected(HttpRejection.BodyWithoutMethod("GET")),
            httpIntentOf(CoreHttpInput(url = "https://x.test", body = "a=1")),
        )
        assertEquals(
            HttpIntent.Rejected(HttpRejection.NonPositiveTimeout(-1)),
            httpIntentOf(CoreHttpInput(url = "https://x.test", timeoutSeconds = -1)),
        )
    }

    @Test
    fun `a body with a method that carries one is accepted`() {
        val intent = httpIntentOf(
            CoreHttpInput(url = "https://x.test", method = HttpMethod.Post, body = "a=1"),
        )
        assertTrue(intent is HttpIntent.Ready)
        assertEquals("a=1", (intent as HttpIntent.Ready).body)
    }

    // ── output carrier ──────────────────────────────────────────────────────

    @Test
    fun `the output is a typed carrier so a refused request cannot read as success`() {
        // Same law WU-092 had to add: without TypedStepOutput the boundary defaults to
        // Success. httpRequest has five distinct failure shapes, all of which would
        // have been reported green.
        assertTrue(
            CoreHttpResponse(url = "u", method = HttpMethod.Get, status = 0) is
                dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput,
            "CoreHttpResponse MUST implement TypedStepOutput",
        )
    }

    @Test
    fun `the output codec round-trips a full response`() {
        val response = CoreHttpResponse(
            url = "https://example.test/api",
            method = HttpMethod.Post,
            status = 201,
            headers = listOf(HttpHeader.of("Content-Type", "application/json")),
            body = """{"ok":true}""",
            bodySha256 = "deadbeef",
            bodySizeBytes = 11L,
            bodyTruncated = false,
        )
        assertEquals(response, CoreHttpOutputCodec.decode(CoreHttpOutputCodec.encode(response)))
    }

    @Test
    fun `a truncated response survives the wire with its digest intact`() {
        // The digest is of the COMPLETE body. Losing that would make a truncated
        // response indistinguishable from a genuinely short one.
        val response = CoreHttpResponse(
            url = "https://example.test/big",
            method = HttpMethod.Get,
            status = 200,
            body = "first-1024-bytes...",
            bodySha256 = "sha-of-the-whole",
            bodySizeBytes = 9_999_999L,
            bodyTruncated = true,
        )
        val decoded = CoreHttpOutputCodec.decode(CoreHttpOutputCodec.encode(response))
        assertTrue(decoded.bodyTruncated)
        assertEquals(9_999_999L, decoded.bodySizeBytes)
        assertEquals("sha-of-the-whole", decoded.bodySha256)
    }

    // ── outcome classification ──────────────────────────────────────────────

    @Test
    fun `a surprising status is the author's policy, not a network failure`() {
        val refused = HttpAttempt.Refused(
            url = "https://example.test",
            method = HttpMethod.Get,
            durationMs = 12,
            status = 503,
            accepted = listOf(StatusRange.Span(200, 299)),
        )
        val outcome = refused.toStepOutcome()
        assertTrue(outcome is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
            (outcome as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure.kind,
            "the network worked; the author asked for something else",
        )
    }

    @Test
    fun `an unreachable host is NETWORK and an elapsed bound is TIMEOUT`() {
        val unreachable = HttpAttempt.Failed(
            "https://nowhere.invalid", HttpMethod.Get, 5,
            HttpFailure.Unreachable("no such host"),
        ).toStepOutcome()
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.NETWORK,
            (unreachable as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure.kind,
        )
        val expired = HttpAttempt.Failed(
            "https://slow.test", HttpMethod.Get, 30_000,
            HttpFailure.Expired(30_000),
        ).toStepOutcome()
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
            (expired as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure.kind,
        )
    }

    @Test
    fun `a credential of the wrong kind names itself instead of claiming to be missing`() {
        // Jenkins' message here is "doesn't exist anymore", which sends the operator to
        // look for a credential they can already see.
        val rejection = CredentialRejection.KindUnsupported(
            id = "api-token",
            found = "SecretText",
            supported = listOf("UsernamePassword"),
        )
        val message = rejection.diagnostic
        assertTrue(message.contains("api-token"), message)
        assertTrue(message.contains("SecretText"), message)
        assertTrue(message.contains("UsernamePassword"), message)
        assertTrue(!message.contains("doesn't exist"), "must not blame a missing credential")
    }

    // ── content type resolution (D9) ────────────────────────────────────────

    @Test
    fun `a Jenkins MIME name resolves and a literal MIME type passes through`() {
        assertEquals("application/json", resolveContentType("APPLICATION_JSON"))
        assertEquals("text/plain", resolveContentType("TEXT_PLAIN"))
        assertEquals("application/vnd.custom+json", resolveContentType("application/vnd.custom+json"))
    }

}
