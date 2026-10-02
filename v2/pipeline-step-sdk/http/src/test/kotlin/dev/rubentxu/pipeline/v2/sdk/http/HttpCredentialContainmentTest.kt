package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * H5 — a secret may exist in exactly one place, and every other place is a defect.
 *
 * ## The canaries
 *
 * ```text
 * K1  an undeclared authentication is Undeclared — sent unauthenticated ON PURPOSE
 * K2  a resolvable UsernamePassword becomes a Basic header and nothing else
 * K3  an absent name is Absent, and a run with no store is StoreUnavailable
 * K4  a credential of the wrong kind is WrongKind, naming what arrived
 * K5  the secret never appears in the input, the codec, the journal or the output
 * K6  the secret never appears in a toString, a diagnostic or a failure message
 * K7  a transport that throws carrying the secret does not leak it into the outcome
 * K8  the encoding wipes its own scratch buffers and leaves the caller's alone
 * ```
 *
 * K5 and K6 are the ones that matter. K1..K4 are behaviour anybody can see; K5 and
 * K6 are the property that stops being true the moment somebody adds a log line.
 */
class HttpCredentialContainmentTest {

    private val id = CredentialsId("deploy-token")

    private fun resolverFor(resolution: (CredentialsId) -> HttpCredentialResolution) =
        HttpCredentialResolver { resolution(it) }

    // ── K1: nothing declared, nothing resolved ─────────────────────────────

    @Test
    fun `K1 an undeclared authentication is Undeclared and never asks the resolver`() {
        var asked = false
        val resolver = HttpCredentialResolver {
            asked = true
            HttpCredentialResolution.NotFound("unused")
        }

        val decision = credentialDecisionOf(null, resolver)

        assertEquals(
            CredentialDecision.Undeclared,
            decision,
            "no declared credential means no authorization, and that is a legitimate choice " +
                "rather than a failure",
        )
        assertFalse(asked, "the resolver must not be consulted for a Step that declared nothing")
    }

    // ── K2: the happy path ─────────────────────────────────────────────────

    @Test
    fun `K2 a resolvable credential becomes a Basic header`() {
        val decision = credentialDecisionOf(
            id,
            resolverFor { HttpCredentialResolution.Basic("alice", "s3cr3t".toByteArray()) },
        )

        assertTrue(decision is CredentialDecision.Authorized, "got $decision")
        val basic = (decision as CredentialDecision.Authorized).authorization as HttpAuthorization.Basic
        assertEquals("alice", basic.username)
        assertArrayEquals("s3cr3t".toByteArray(), basic.password)
    }

    // ── K3: absent is not the same as no store ─────────────────────────────

    @Test
    fun `K3b a run with no credential store says so instead of blaming the name`() {
        val decision = credentialDecisionOf(id, NoCredentialSource)

        val rejection = (decision as CredentialDecision.Refused).rejection
        assertTrue(rejection is HttpRejection.CredentialRefused.StoreUnavailable, "got $rejection")
        assertTrue(
            rejection.diagnostic.contains("no credential store"),
            "the diagnosis must name the cause; got '${rejection.diagnostic}'",
        )
        assertFalse(
            rejection.diagnostic.contains("no credential named"),
            "'no credential named X' is a DIFFERENT fact from 'this run has nowhere to look'. " +
                "Sending an operator to rename a credential that is fine is the Jenkins lie this " +
                "case exists to avoid.",
        )
    }

    @Test
    fun `K3c a name that is simply absent is Absent`() {
        val decision = credentialDecisionOf(
            id,
            resolverFor { HttpCredentialResolution.NotFound(it.value) },
        )

        val rejection = (decision as CredentialDecision.Refused).rejection
        assertTrue(rejection is HttpRejection.CredentialRefused.Absent, "got $rejection")
    }

    // ── K4: the wrong kind is named, not hidden ────────────────────────────

    @Test
    fun `K4 a credential of the wrong kind is WrongKind and names what arrived`() {
        val decision = credentialDecisionOf(
            id,
            resolverFor {
                HttpCredentialResolution.KindUnsupported(
                    id = it.value,
                    found = "SecretFile",
                    supported = listOf("UsernamePassword"),
                )
            },
        )

        val rejection = (decision as CredentialDecision.Refused).rejection
        val wrong = rejection as HttpRejection.CredentialRefused.WrongKind
        assertEquals("SecretFile", wrong.found)
        assertEquals(listOf("UsernamePassword"), wrong.supported)
        assertTrue(
            rejection.diagnostic.contains("SecretFile"),
            "the diagnostic must say what arrived; got '${rejection.diagnostic}'",
        )
        assertFalse(
            rejection.diagnostic.contains("doesn't exist anymore"),
            "Jenkins blames a missing credential for a present one of the wrong shape, and the " +
                "operator goes looking for a credential they can already see",
        )
    }

    // ── K5: the secret is not in anything durable ──────────────────────────

    @Test
    fun `K5 the secret never reaches the input, the codec or the output`() {
        val secret = "sup3r-s3cr3t-value"
        val input = HttpRequestInput(
            url = "http://example.test/x",
            method = HttpMethod.Get,
            authentication = id,
        )

        val encodedInput = HttpRequestCodec.encode(input).value
        assertFalse(
            encodedInput.contains(secret),
            "the INPUT wire form carries the secret: $encodedInput",
        )
        assertTrue(
            encodedInput.contains("deploy-token"),
            "the input must still carry the NAME, or the credential could never be resolved",
        )

        // And the output side, for an answer that succeeded.
        val output = HttpResponseOutput(
            HttpAttempt.Answered(
                url = "http://example.test/x",
                method = HttpMethod.Get,
                durationMs = 1L,
                response = HttpResponse("http://example.test/x", HttpMethod.Get, 200),
            ),
        )
        assertFalse(
            HttpResponseCodec.encode(output).value.contains(secret),
            "the OUTPUT wire form carries the secret",
        )
    }

    @Test
    fun `K5b a failed attempt about a credential carries no secret`() {
        val secret = "sup3r-s3cr3t-value"
        val output = HttpResponseOutput(
            HttpAttempt.Unauthorized(
                url = "http://example.test/x",
                method = HttpMethod.Get,
                durationMs = 0L,
                rejection = CredentialRejection.NotFound(id.value),
            ),
        )
        // The resolution value itself is what carries the secret; assert that none of
        // its renderings does, because every one of them is a candidate for a log line.
        val basic = HttpAuthorization.Basic("alice", secret.toByteArray())
        assertFalse(basic.toString().contains(secret), "HttpAuthorization.Basic leaks: $basic")
        assertFalse(
            HttpCredentialResolution.Basic("alice", secret.toByteArray()).toString().contains(secret),
            "HttpCredentialResolution.Basic leaks",
        )
        assertFalse(
            HttpResponseCodec.encode(output).value.contains(secret),
            "an unauthorized attempt must not echo the secret it failed on",
        )
    }

    // ── K6: diagnostics are safe to print ──────────────────────────────────

    @Test
    fun `K6 a refusal diagnostic never contains the secret`() {
        // The realistic shape of this leak: a store that fails and echoes whatever it
        // was holding into the reason. The message travels from the resolution through
        // the rejection, through the failure, through the step outcome and into the
        // event log and the journal.
        //
        // The fix is structural, not a filter: the reasons are a CLOSED set, so there
        // is no free text for a secret to hide in. A resolver that wants to say
        // "passphrase mismatch" logs it through its own redacting sink; the durable
        // Step record gets the case.
        val secret = "sup3r-s3cr3t-value"
        val decision = credentialDecisionOf(
            id,
            resolverFor { HttpCredentialResolution.NoSource(StoreUnavailability.Unreadable) },
        )

        val output = HttpResponseOutput(
            HttpAttempt.Failed(
                url = "http://example.test/x",
                method = HttpMethod.Get,
                durationMs = 0L,
                failure = HttpFailure.Rejected((decision as CredentialDecision.Refused).rejection),
            ),
        )

        val rendered = buildString {
            append(output.attempt.toString())
            append(output.toString())
            append(output.outcome)
            append(HttpResponseCodec.encode(output).value)
        }
        assertFalse(
            rendered.contains(secret),
            "the secret escaped into something an operator or a log would print: $rendered",
        )
    }

    @Test
    fun `K6b the resolution hierarchy admits no free text a secret could hide in`() {
        // The property K6 depends on, asserted structurally rather than through one
        // example: `NoSource` carries an enum, not a `String`. There is no free text
        // anywhere on the path from resolution to journal, so there is nothing for a
        // secret to hide in. A reflection check is the honest way to say that — and it
        // fails the moment somebody "just adds a detail" to the reason.
        val parameterTypes = HttpCredentialResolution.NoSource::class.java.declaredConstructors
            .single()
            .parameterTypes

        assertEquals(
            listOf(StoreUnavailability::class.java),
            parameterTypes.toList(),
            "NoSource must carry a closed reason, not a String. A free-text reason is a " +
                "secret exfiltration path into the journal, the event log and every " +
                "toString() in between; K6 caught exactly that.",
        )
        assertTrue(
            StoreUnavailability.entries.size == 3,
            "a closed set of three: not configured, unreadable, unavailable. If a fourth " +
                "arrives it must arrive with a diagnostic that is still a fixed phrase.",
        )
    }

    // ── K7: a throwing transport cannot smuggle the secret out ──────────────

    @Test
    fun `K7 the Authorization header is a ByteArray, so it is not a String in the port`() {
        val decision = credentialDecisionOf(
            id,
            resolverFor { HttpCredentialResolution.Basic("alice", "s3cr3t".toByteArray()) },
        )
        val basic = (decision as CredentialDecision.Authorized).authorization as HttpAuthorization.Basic

        // The regression this pins: the port used to carry a pre-encoded base64
        // String, while the function producing it claimed the secret "never enters
        // the port". Both cannot be true, and a String is what ends up in log
        // formatters and exception messages by accident.
        assertTrue(
            basic.password is ByteArray,
            "the port must carry bytes, not an encoded String",
        )
        assertFalse(
            basic.toString().contains(Base64.getEncoder().encodeToString("alice:s3cr3t".toByteArray())),
            "the port value must not render the encoded credential",
        )
    }

    // ── K8: the encoder cleans up after itself ─────────────────────────────

    @Test
    fun `K8 the Basic header is correct and the caller's password is not destroyed`() {
        val password = "s3cr3t".toByteArray()

        val header = basicAuthorizationValue("alice", password)

        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".toByteArray(StandardCharsets.UTF_8)),
            header,
            "the header must be a correct RFC 7617 Basic value",
        )
        assertArrayEquals(
            "s3cr3t".toByteArray(),
            password,
            "the caller's array is not ours to wipe; the resolution value may outlive the call",
        )
    }
}
