package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialResolution
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.credentials.api.NoBasicCredentialSource
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.EgressDecision
import dev.rubentxu.pipeline.v2.domain.step.EgressDestination
import dev.rubentxu.pipeline.v2.domain.step.EgressRefusal
import dev.rubentxu.pipeline.v2.domain.step.EgressRule
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.RestrictedEgressGate
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H6 — the destination question is asked BEFORE anything opens.
 *
 * ## Why this file exists separately from [EgressAdmissionTest]
 *
 * `EgressAdmissionTest` exercises the pure function and would pass whether or not the
 * handler ever called it. That is not hypothetical: mutation M-http-25 replaced the
 * handler's call with a constant `Permitted`, and every one of those tests stayed
 * green while the entire egress gate was decorative.
 *
 * The property that matters is not "the gate can refuse". It is:
 *
 * > A destination the runtime refused produces NO socket at all.
 *
 * and, underneath it, the one that would be easy to get wrong:
 *
 * > A destination the runtime refused NEVER decrypts a credential.
 *
 * ```text
 * E14 a permitted destination reaches the transport
 * E15 a refused destination never reaches the transport
 * E16 the refusal names WHICH refusal, not just "denied"
 * E17 the gate is asked BEFORE the credential is resolved
 * E18 an unjudgeable URL opens nothing either
 * E19 durationMs is zero: nothing was sent, nothing was spent
 * ```
 */
class HttpEgressApplicationTest {

    private val url = "https://api.example.test/v1/things"

    /** A transport that records whether it was touched. Never opens anything. */
    private class RecordingTransport : HttpTransport {
        var calls = 0

        override suspend fun send(request: HttpSendRequest): HttpTransportResult {
            calls++
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
        egress: NetworkEgressGate,
    ): StepHandlerContext {
        val available = mapOf<StepCapability, Any>(
            HTTP_TRANSPORT_CAPABILITY to transport,
            BASIC_CREDENTIALS_CAPABILITY to credentials,
            NETWORK_EGRESS_CAPABILITY to egress,
        )
        return StepHandlerContext(
            runId = RunId("h6"),
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

    private fun input(target: String = url, authentication: CredentialsId? = null) =
        HttpRequestInput(
            url = target,
            method = HttpMethod.Get,
            authentication = authentication,
        )

    private fun allowingOnly(vararg rules: EgressRule) = RestrictedEgressGate(rules.toList())

    // ── E14: the permitted path still sends ─────────────────────────────────

    @Test
    fun `E14 a destination on the allowlist is reached`() = runBlocking {
        val transport = RecordingTransport()

        val output = HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(transport, NoBasicCredentialSource, allowingOnly(EgressRule("https", "api.example.test"))),
        )

        assertEquals(1, transport.calls, "a permitted destination must actually be sent")
        assertTrue(output.attempt is HttpAttempt.Answered, "got ${output.attempt}")
    }

    @Test
    fun `E14b --allow-network reaches every destination`() = runBlocking {
        val transport = RecordingTransport()

        HttpRequestStep.definition.handler.execute(
            input(target = "http://anything.example.test:9999/x"),
            contextFor(transport, NoBasicCredentialSource, AllowAll),
        )

        assertEquals(1, transport.calls, "AllowAll is the --allow-network switch and must send")
    }

    // ── E15: the refused path opens nothing ─────────────────────────────────

    @Test
    fun `E15 a destination off the allowlist never reaches the transport`() = runBlocking {
        val transport = RecordingTransport()

        val output = HttpRequestStep.definition.handler.execute(
            input(target = "https://api.example.test.evil.test/v1"),
            contextFor(transport, NoBasicCredentialSource, allowingOnly(EgressRule("https", "api.example.test"))),
        )

        assertEquals(
            0,
            transport.calls,
            "the socket was opened for a host the runtime refused. This is the whole point of " +
                "the gate and the one thing no other test in this file would catch.",
        )
        assertEquals(
            EgressRefusal.DestinationNotPermitted,
            ((output.attempt as HttpAttempt.Failed).failure as HttpFailure.EgressDenied).reason,
        )
    }

    @Test
    fun `E15b a port the rule does not name is refused like any other host`() = runBlocking {
        val transport = RecordingTransport()

        HttpRequestStep.definition.handler.execute(
            input(target = "https://api.example.test:8443/v1"),
            contextFor(transport, NoBasicCredentialSource, allowingOnly(EgressRule("https", "api.example.test"))),
        )

        assertEquals(
            0,
            transport.calls,
            "a rule for https://api.example.test names port 443. Opening 8443 as well would " +
                "make the allowlist cover a service nobody looked at.",
        )
    }

    @Test
    fun `E15c an empty allowlist refuses everything, and says so as a destination refusal`() =
        runBlocking {
            val transport = RecordingTransport()

            val output = HttpRequestStep.definition.handler.execute(
                input(),
                contextFor(transport, NoBasicCredentialSource, RestrictedEgressGate(emptyList())),
            )

            assertEquals(0, transport.calls, "an empty allowlist is not an open network")
            assertEquals(
                EgressRefusal.DestinationNotPermitted,
                ((output.attempt as HttpAttempt.Failed).failure as HttpFailure.EgressDenied).reason,
                "the run HAS a network; this particular destination is not on it. Those are " +
                    "different operator problems and the operator has to be told which.",
            )
        }

    // ── E16: the refusal names itself ───────────────────────────────────────

    @Test
    fun `E16 the run-level denial and the destination-level refusal read differently`() =
        runBlocking {
            val denied = HttpRequestStep.definition.handler.execute(
                input(),
                contextFor(RecordingTransport(), NoBasicCredentialSource, DenyAll),
            )
            val refused = HttpRequestStep.definition.handler.execute(
                input(),
                contextFor(
                    RecordingTransport(),
                    NoBasicCredentialSource,
                    allowingOnly(EgressRule("https", "other.example.test")),
                ),
            )

            val deniedMessage =
                ((denied.attempt as HttpAttempt.Failed).failure).diagnostic
            val refusedMessage =
                ((refused.attempt as HttpAttempt.Failed).failure).diagnostic

            assertTrue(
                deniedMessage.contains("--allow-network"),
                "a run with no network must be told about the flag; got '$deniedMessage'",
            )
            assertTrue(
                refusedMessage.contains("allowlist"),
                "a run whose destination is not permitted must NOT be sent to --allow-network — " +
                    "the flag would open every host, and the operator would trade one policy for " +
                    "a wider one to fix a narrow problem. Got '$refusedMessage'",
            )
        }

    // ── E17: the ordering, which is the security property ───────────────────

    @Test
    fun `E17 a refused destination is refused BEFORE the credential is resolved`() = runBlocking {
        val transport = RecordingTransport()
        var asked = false
        val credentials = BasicCredentialSource {
            asked = true
            BasicCredentialResolution.Resolved("alice", "s3cr3t".toByteArray())
        }

        HttpRequestStep.definition.handler.execute(
            input(authentication = CredentialsId("deploy-token")),
            contextFor(
                transport,
                credentials,
                allowingOnly(EgressRule("https", "other.example.test")),
            ),
        )

        assertEquals(0, transport.calls, "no socket")
        assertFalse(
            asked,
            "a credential must not be decrypted for a destination the run may not reach. The " +
                "seam returns live bytes; asking it first would decrypt material nobody was " +
                "authorised to send, on a request that was never going to leave.",
        )
    }

    @Test
    fun `E17b the gate is asked about the destination, not about the run`() = runBlocking {
        val askedAbout = mutableListOf<EgressDestination>()
        val spy = object : NetworkEgressGate {
            override val permitsAny: Boolean get() = true
            override fun decide(destination: EgressDestination): EgressDecision {
                askedAbout += destination
                return EgressDecision.Allowed
            }
        }

        HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(RecordingTransport(), NoBasicCredentialSource, spy),
        )

        assertEquals(
            listOf(EgressDestination("https", "api.example.test", 443)),
            askedAbout,
            "the gate received exactly the socket's destination, derived from the URL. A gate " +
                "asked about something else cannot do its job, and a gate asked twice cannot be " +
                "audited against a single socket.",
        )
    }

    // ── E18: an unjudgeable URL opens nothing ───────────────────────────────

    @Test
    fun `E18 a URL with no destination is refused even when the gate allows everything`() =
        runBlocking {
            val transport = RecordingTransport()

            val output = HttpRequestStep.definition.handler.execute(
                input(target = "not a url"),
                contextFor(transport, NoBasicCredentialSource, AllowAll),
            )

            assertEquals(
                0,
                transport.calls,
                "a destination nobody could parse is a destination nobody could check. " +
                    "--allow-network permitting everything does not permit the uncheckable.",
            )
            assertEquals(
                EgressRefusal.UnjudgeableDestination,
                ((output.attempt as HttpAttempt.Failed).failure as HttpFailure.EgressDenied).reason,
                "and it is its own reason: telling an operator to widen a policy would be " +
                    "advice for a problem they do not have",
            )
        }

    // ── E19: nothing sent, nothing spent ────────────────────────────────────

    @Test
    fun `E19 a refused egress is recorded with durationMs zero`() = runBlocking {
        val output = HttpRequestStep.definition.handler.execute(
            input(),
            contextFor(RecordingTransport(), NoBasicCredentialSource, DenyAll),
        )

        assertEquals(
            0L,
            output.attempt.durationMs,
            "zero is what lets a reader of the journal tell 'we decided not to send' from " +
                "'we sent and it failed' — two completely different facts about a pipeline",
        )
    }
}
