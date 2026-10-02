package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.EgressRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H4-Q — the contract must describe the bytes that are actually written.
 *
 * Three separate lies were corrected in this block, and each one is a different way
 * of failing the same way: the durable record says something other than what happened.
 *
 * ```text
 * Q1  the OUTPUT schema declared kind "httpResponse" while the encoder wrote
 *     "httpAttempt", and required a "status" that does not exist for the cases a
 *     reader most needs to recognise
 * Q2  the INPUT schema declared validResponseCodes as an object while the encoder
 *     writes an array
 * Q3  decode rebuilt EVERY failure as Unreachable, so a timeout was durably
 *     recorded as a network fact and an unauthorized credential as a failed request
 * ```
 *
 * Q3 is the one that mattered. `ReplayPolicy.NEVER` means a replayed run reads this
 * record back; if the case is not on the wire, the record can only be read as the
 * crudest reading available, and the diagnostic string is not a case.
 */
class HttpCodecTruthTest {

    private fun decodeAttempt(output: HttpResponseOutput): HttpAttempt =
        HttpResponseCodec.decode(HttpResponseCodec.encode(output)).attempt

    private fun outputOf(attempt: HttpAttempt) = HttpResponseOutput(attempt)

    private val answered = HttpResponse(
        url = "http://example.test/ok",
        method = HttpMethod.Get,
        status = 200,
        body = "hello",
        bodySha256 = "abc",
        bodySizeBytes = 5L,
    )

    // ── Q1: the output schema describes the output ─────────────────────────

    @Test
    fun `Q1 the output schema declares the kind and cases the encoder actually writes`() {
        val schema = Json.parseToJsonElement(HttpResponseCodec.schema()).jsonObject
        val properties = schema["properties"]!!.jsonObject
        val required = (schema["required"] as kotlinx.serialization.json.JsonArray)
            .map { it.jsonPrimitive.content }.toSet()

        assertEquals(
            "httpAttempt",
            properties["kind"]!!.jsonObject["const"]!!.jsonPrimitive.content,
            "the schema must declare the kind the encoder writes",
        )
        assertTrue(
            required.contains("attempt"),
            "'attempt' is the case discriminator and must be required; got $required",
        )
        assertTrue(
            !required.contains("status"),
            "'status' must NOT be required: a failed or unauthorized attempt has no " +
                "status, and requiring it makes the schema reject the records a reader " +
                "most needs to identify. Required set was $required",
        )
        assertEquals(
            listOf("answered", "refused", "failed", "unauthorized"),
            properties["attempt"]!!.jsonObject["enum"]!!
                .let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonPrimitive.content } },
            "the schema must enumerate every attempt case the closed ADT can produce",
        )
    }

    @Test
    fun `Q1b every attempt case validates against the declared schema`() {
        val schema = Json.parseToJsonElement(HttpResponseCodec.schema()).jsonObject
        val properties = schema["properties"]!!.jsonObject
        val declared = properties.keys.map { it }.toSet()

        val attempts = listOf(
            HttpAttempt.Answered("u", HttpMethod.Get, 1L, answered),
            HttpAttempt.Refused("u", HttpMethod.Get, 1L, 500, listOf(StatusRange.Single(200))),
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.Unreachable("no route")),
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.Expired(30L)),
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.EgressDenied(EgressRefusal.NetworkNotPermitted)),
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.ResponseInterrupted("cut", 42L)),
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.Rejected(HttpRejection.BlankUrl(""))),
            HttpAttempt.Unauthorized("u", HttpMethod.Get, 1L, CredentialRejection.NotFound("creds")),
        )

        for (attempt in attempts) {
            val encoded = Json.parseToJsonElement(HttpResponseCodec.encode(outputOf(attempt)).value).jsonObject
            for (key in encoded.keys) {
                assertTrue(
                    key in declared,
                    "the encoder writes '$key' for ${attempt::class.simpleName} but the schema " +
                        "does not declare it. An undeclared property is a second contract.",
                )
            }
        }
    }

    // ── Q2: the input schema describes the input ───────────────────────────

    @Test
    fun `Q2 the input schema declares validResponseCodes as the array the encoder writes`() {
        val schema = Json.parseToJsonElement(HttpRequestCodec.schema()).jsonObject
        val codes = schema["properties"]!!.jsonObject["validResponseCodes"]!!.jsonObject

        assertEquals(
            "array",
            codes["type"]!!.jsonPrimitive.content,
            "validResponseCodes is encoded as an array of strings. It used to be declared as " +
                "an object with a 'spec' property, which no encoder has ever produced.",
        )

        // And the declared shape must accept what the encoder really writes.
        val encoded = Json.parseToJsonElement(
            HttpRequestCodec.encode(
                HttpRequestInput(
                    url = "http://example.test",
                    validResponseCodes = listOf(StatusRange.Single(200), StatusRange.Span(300, 399)),
                ),
            ).value,
        ).jsonObject
        val values = encoded["validResponseCodes"]!!
        assertTrue(
            values is kotlinx.serialization.json.JsonArray,
            "the encoder must write an array, got $values",
        )
    }

    // ── Q3: the case survives the wire ─────────────────────────────────────

    @Test
    fun `Q3 a timeout comes back as a timeout, not as Unreachable`() {
        val original = HttpAttempt.Failed("u", HttpMethod.Get, 7L, HttpFailure.Expired(30_000L))

        val decoded = decodeAttempt(outputOf(original))

        assertEquals(
            original,
            decoded,
            "a timeout decoded as anything else is a durable record that lies about why the " +
                "run failed, and it is read back on every replay.",
        )
    }

    @Test
    fun `Q3b every failure CASE and diagnostic survives a round trip`() {
        // The contract promises the HttpFailure case and its diagnostic survive. It
        // does NOT promise the inner HttpRejection case — see Q3h for exactly why,
        // which is why this asserts the failure and the text rather than deep equality
        // on a value that contains a declaration projection.
        val failures = listOf(
            HttpFailure.Unreachable("ConnectException: no route to host"),
            HttpFailure.Expired(30_000L),
            HttpFailure.ResponseInterrupted("fixed content-length", 65_536L),
            HttpFailure.EgressDenied(EgressRefusal.DestinationNotPermitted),
            HttpFailure.Rejected(HttpRejection.BlankUrl("   ")),
        )

        for (failure in failures) {
            val decoded = decodeAttempt(
                outputOf(HttpAttempt.Failed("u", HttpMethod.Post, 3L, failure)),
            )
            val decodedFailure = (decoded as HttpAttempt.Failed).failure

            assertEquals(
                failure::class,
                decodedFailure::class,
                "the failure CASE must survive; ${failure::class.simpleName} came back as " +
                    "${decodedFailure::class.simpleName}. A case that cannot be rebuilt from " +
                    "the record can only ever be guessed.",
            )
            assertEquals(
                failure.diagnostic,
                decodedFailure.diagnostic,
                "the diagnostic must survive verbatim for ${failure::class.simpleName}",
            )
        }
    }

    @Test
    fun `Q3h a declaration rejection keeps its reason and admits what it loses`() {
        // Stated rather than hidden: the record is a PROJECTION of the declaration. It
        // keeps WHY and not WHICH, so `BlankUrl("   ")` comes back as a refusal whose
        // reason is the sentence. Everything a run consumes — FailureKind.USER and the
        // diagnostic — round-trips; reconstructing the author's input does not.
        val decoded = decodeAttempt(
            outputOf(
                HttpAttempt.Failed("u", HttpMethod.Get, 0L, HttpFailure.Rejected(HttpRejection.BlankUrl("   "))),
            ),
        )

        val rejection = ((decoded as HttpAttempt.Failed).failure as HttpFailure.Rejected).rejection
        assertEquals("the URL is blank", rejection.diagnostic, "the reason must survive")
        assertTrue(
            rejection is HttpRejection.DeclarationRefused,
            "expected the documented projection case, got $rejection",
        )
        assertEquals(
            FailureKind.USER,
            HttpResponseOutput(decoded).outcome.let {
                (it as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure.kind
            },
            "a declaration refusal stays a USER failure after the round trip; nothing about " +
                "the world was wrong and no retry would fix it",
        )
    }

    @Test
    fun `Q3c a mid-body interruption keeps its byte count`() {
        val decoded = decodeAttempt(
            outputOf(HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.ResponseInterrupted("cut", 4_194_304L))),
        )

        val failure = (decoded as HttpAttempt.Failed).failure
        assertTrue(failure is HttpFailure.ResponseInterrupted, "got $failure")
        assertEquals(
            4_194_304L,
            (failure as HttpFailure.ResponseInterrupted).bytesReceived,
            "how much arrived is the whole reason this case exists; dropping it on the wire " +
                "makes it a slower Unreachable",
        )
    }

    @Test
    fun `Q3d an unauthorized credential comes back as Unauthorized, not as a failed request`() {
        val original = HttpAttempt.Unauthorized(
            "u",
            HttpMethod.Get,
            0L,
            CredentialRejection.NotFound("deploy-token"),
        )

        val decoded = decodeAttempt(outputOf(original))

        assertEquals(
            original,
            decoded,
            "an unauthorized credential is a declaration refusal with nothing sent. Decoding " +
                "it as a failed request reports a network incident that never happened.",
        )
    }

    @Test
    fun `Q3e a legacy record without failureKind still decodes`() {
        // Exactly what the previous encoder wrote: a case-less diagnostic string.
        val legacy = """
            {"kind":"httpAttempt","url":"http://example.test/x","httpMode":"GET",
             "durationMs":12,"attempt":"failed","failure":"ConnectException: no route to host"}
        """.trimIndent()

        val decoded = HttpResponseCodec.decode(
            dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(legacy),
        ).attempt

        assertTrue(
            decoded is HttpAttempt.Failed,
            "a record written by an older build must still decode, got $decoded",
        )
        assertEquals(
            "ConnectException: no route to host",
            (decoded as HttpAttempt.Failed).failure.diagnostic,
            "the diagnostic must be preserved verbatim; the case is unknowable and is not invented",
        )
    }

    @Test
    fun `Q3f an unknown attempt kind is refused rather than read as a failure`() {
        val record = """
            {"kind":"httpAttempt","url":"http://example.test/x","attempt":"teleported"}
        """.trimIndent()

        val thrown = runCatching {
            HttpResponseCodec.decode(dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(record))
        }.exceptionOrNull()

        assertTrue(
            thrown is HttpCodecException,
            "a record this build cannot interpret must be refused, not read as a failed " +
                "request. A journal from a newer build would otherwise look like a real incident. Got $thrown",
        )
    }

    @Test
    fun `Q3g the failure classification still reaches the run`() {
        val interrupted = HttpResponseOutput(
            HttpAttempt.Failed("u", HttpMethod.Get, 1L, HttpFailure.ResponseInterrupted("cut", 99L)),
        ).outcome

        assertEquals(
            FailureKind.NETWORK,
            (interrupted as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure.kind,
        )
        assertTrue(
            interrupted.failure.message.contains("99"),
            "the message must carry the byte count; got ${interrupted.failure.message}",
        )
    }

    /**
     * H6 — the refusal gained a reason, and an older record must not be damaged by it.
     *
     * A pre-H6 record is not missing information that used to be there: the build that
     * wrote it could only EVER say "the run may not use the network", because that was
     * the entire vocabulary the egress seam had. Decoding it as
     * [EgressRefusal.NetworkNotPermitted] is therefore not a fallback — it is the one
     * thing that record can truthfully mean.
     */
    @Test
    fun `Q3h a pre-H6 egressDenied record decodes to the only refusal it could mean`() {
        val legacy = """
            {"kind":"httpAttempt","url":"http://example.test/x","httpMode":"GET",
             "durationMs":0,"attempt":"failed","failureKind":"egressDenied",
             "failure":"this run may not reach the network; start it with --allow-network to permit egress"}
        """.trimIndent()

        val decoded = HttpResponseCodec.decode(
            dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(legacy),
        ).attempt

        val failure = (decoded as HttpAttempt.Failed).failure
        assertEquals(
            EgressRefusal.NetworkNotPermitted,
            (failure as HttpFailure.EgressDenied).reason,
            "a record written when the only egress verdict was Denied must not acquire a " +
                "reason it never carried — and it must not lose the one it can honestly state.",
        )
    }

    @Test
    fun `Q3i an unknown egress reason is refused as no-network, never invented`() {
        // A record from a build with a reason this one has never heard of. Refusing is
        // the safe reading: every one of these reasons is a refusal, so collapsing an
        // unknown one to NetworkNotPermitted can never turn a denial into a permission.
        val record = """
            {"kind":"httpAttempt","url":"http://example.test/x","httpMode":"GET",
             "durationMs":0,"attempt":"failed","failureKind":"egressDenied",
             "failureEgressReason":"ApprovedByTheVibes"}
        """.trimIndent()

        val failure = (
            HttpResponseCodec.decode(dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(record)).attempt
                as HttpAttempt.Failed
            ).failure

        assertEquals(
            EgressRefusal.NetworkNotPermitted,
            (failure as HttpFailure.EgressDenied).reason,
            "an unrecognised reason must not be decoded into a fact this build cannot support",
        )
    }
}
