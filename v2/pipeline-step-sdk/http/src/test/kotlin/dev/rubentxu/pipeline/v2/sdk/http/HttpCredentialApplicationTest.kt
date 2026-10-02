package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialResolution
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.credentials.api.CredentialStoreUnavailability
import dev.rubentxu.pipeline.v2.credentials.api.NoBasicCredentialSource
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H5 — the decision is pure; these canaries prove it is actually APPLIED.
 *
 * ## Why this file exists at all
 *
 * `HttpCredentialContainmentTest` exercises `credentialDecisionOf` and passes
 * perfectly whether or not the handler ever looks at its answer. Mutation M-http-21
 * replaced the handler's call with `credentialDecisionOf(null, credentials)` — so a
 * refused credential quietly became an anonymous request — and **every test stayed
 * green**.
 *
 * That is the gap worth naming: testing the selector and not the selection is how a
 * security property ends up looking covered while nothing enforces it. The property
 * is not "the resolver can refuse". It is:
 *
 * > A declared credential that cannot be used produces NO request at all.
 *
 * ## The canaries
 *
 * ```text
 * K9  a refused credential NEVER reaches the transport
 * K10 the refusal is a declaration failure with durationMs == 0
 * K11 a resolvable credential DOES reach the transport, with the header
 * K12 an undeclared credential reaches the transport with no header
 * K13 a denied egress run is refused before the credential is even looked up
 * ```
 */
class HttpCredentialApplicationTest {

    private val id = CredentialsId("deploy-token")
    private val url = "http://example.test/x"

    /** A transport that records whether it was touched. Never opens anything. */
    private class RecordingTransport : HttpTransport {
        var calls = 0
        var lastAuthorization: HttpAuthorization? = null

        override suspend fun send(request: HttpSendRequest): HttpTransportResult {
            calls++
            lastAuthorization = request.authorization
            return HttpTransportResult(
                outcome = HttpSendOutcome.Answered(
                    status = 200,
                    headers = emptyList(),
                    body = "",
                    bodySha256 = "",
                    bodySizeBytes = 0L,
                    bodyTruncated = false,
                ),
                durationMs = 1L,
            )
        }
    }

    private fun contextFor(
        transport: HttpTransport,
        credentials: BasicCredentialSource,
        egress: NetworkEgressGate = AllowAll,
    ): StepHandlerContext {
        val available = mapOf<StepCapability, Any>(
            HTTP_TRANSPORT_CAPABILITY to transport,
            BASIC_CREDENTIALS_CAPABILITY to credentials,
            NETWORK_EGRESS_CAPABILITY to egress,
        )
        return StepHandlerContext(
            runId = RunId("h5"),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available(): Set<StepCapability> = available.keys
                override fun <T : Any> get(key: StepCapability): T {
                    @Suppress("UNCHECKED_CAST")
                    return available.getValue(key) as T
                }
            },
        )
    }

    private fun input(authentication: CredentialsId? = id) =
        HttpRequestInput(url = url, method = HttpMethod.Get, authentication = authentication)

    // ── K9 / K10: a refusal is a refusal, not an anonymous request ──────────

    @Test
    fun `K9 a refused credential never reaches the transport`() {
        val transport = RecordingTransport()
        val output = runBlocking {
            HttpRequestStep.definition.handler.execute(
                input(),
                contextFor(transport, NoBasicCredentialSource),
            )
        }

        assertEquals(
            0,
            transport.calls,
            "the transport was called for a credential that could not be resolved. Sending it " +
                "unauthenticated is precisely the failure H5 exists to prevent: the pipeline " +
                "would look like it had authenticated.",
        )
    }

    @Test
    fun `K10 the refusal is a declaration failure with nothing sent`() = runBlocking {
        val output = HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(RecordingTransport(), NoBasicCredentialSource),
        )

        val attempt = output.attempt
        assertTrue(attempt is HttpAttempt.Failed, "got $attempt")
        val failure = (attempt as HttpAttempt.Failed).failure
        assertTrue(failure is HttpFailure.Rejected, "got $failure")
        assertEquals(
            0L,
            attempt.durationMs,
            "zero means no socket was opened, so a reader of the journal can tell 'we decided " +
                "not to send' from 'we sent and it failed'",
        )
        assertTrue(
            (failure as HttpFailure.Rejected).rejection is HttpRejection.CredentialRefused,
            "got ${failure.rejection}",
        )
    }

    @Test
    fun `K10b an absent credential is refused, and a missing one is not an anonymous send`() =
        runBlocking {
            val transport = RecordingTransport()
            val output = HttpRequestStep.definition.handler.execute(
                input(),
                contextFor(
                    transport,
                    BasicCredentialSource { BasicCredentialResolution.NotFound(it.value) },
                ),
            )

            assertEquals(0, transport.calls, "an absent credential must not become an open request")
            val rejection =
                ((output.attempt as HttpAttempt.Failed).failure as HttpFailure.Rejected).rejection
            assertTrue(
                rejection is HttpRejection.CredentialRefused.Absent,
                "got $rejection",
            )
        }

    // ── K11 / K12: the happy paths still send ──────────────────────────────

    @Test
    fun `K11 a resolvable credential reaches the transport as a Basic header`() = runBlocking {
        val transport = RecordingTransport()

        HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(
                transport,
                BasicCredentialSource { BasicCredentialResolution.Resolved("alice", "s3cr3t".toByteArray()) },
            ),
        )

        assertEquals(1, transport.calls, "a resolvable credential must actually be sent")
        val authorization = transport.lastAuthorization
        assertTrue(authorization is HttpAuthorization.Basic, "got $authorization")
        assertEquals("alice", (authorization as HttpAuthorization.Basic).username)
    }

    @Test
    fun `K12 an undeclared credential sends with no authorization at all`() = runBlocking {
        val transport = RecordingTransport()

        HttpRequestStep.definition.handler.execute(
            input(authentication = null),
            contextFor(transport, NoBasicCredentialSource),
        )

        assertEquals(
            1,
            transport.calls,
            "declaring no credential is a legitimate choice, not a failure: refusing it would " +
                "make every unauthenticated request impossible",
        )
        assertEquals(null, transport.lastAuthorization, "nothing was declared, so nothing is sent")
    }

    // ── K13: ordering of the gates ──────────────────────────────────────────

    @Test
    fun `K13 a denied egress run is refused before the credential is consulted`() = runBlocking {
        val transport = RecordingTransport()
        var asked = false

        HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(
                transport,
                BasicCredentialSource {
                    asked = true
                    BasicCredentialResolution.Resolved("alice", "s3cr3t".toByteArray())
                },
                egress = DenyAll,
            ),
        )

        assertEquals(0, transport.calls, "a denied run must not reach the network")
        assertFalse(
            asked,
            "the credential must not be resolved for a run that may not reach the network. " +
                "Resolving it anyway would decrypt a secret nobody was authorised to send.",
        )
    }
}
