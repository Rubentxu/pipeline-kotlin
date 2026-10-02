package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.EgressDecision
import dev.rubentxu.pipeline.v2.domain.step.EgressDestination
import dev.rubentxu.pipeline.v2.domain.step.EgressRefusal
import dev.rubentxu.pipeline.v2.domain.step.EgressRule
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.RestrictedEgressGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H6 — the destination is derived, and the gate is ASKED.
 *
 * ## What these canaries are actually for
 *
 * They look like tests of a small pure function, and they are not the point. The
 * point is that the FUNCTION DOES NOT EXIST ON THE OTHER SIDE: the moment the plugin
 * held rules and matched them itself, "the runtime owns the permission" would be a
 * comment, and a second consumer reading the same bytes could reach a different
 * verdict. Every case below is a case where the naive implementation is wrong in a
 * way a review would not catch.
 *
 * ```text
 * E1  https + omitted port  → 443, not 0 and not "unknown"
 * E2  explicit port         → that port, overriding the scheme default
 * E3  DenyAll               → judged refusal naming NetworkNotPermitted
 * E4  a matching rule       → permitted, with the destination spelled out
 * E5  a rule without a port does NOT open another port on the same host
 * E6  a suffix host is not the rule's host        (prefix match would pass)
 * E7  a superdomain host is not the rule's host   (suffix match would pass)
 * E8  an EMPTY allowlist denies everything — it is not "no rules, so all allowed"
 * E9  scheme and host compare case-insensitively
 * E10 an unparseable URL is a refusal, and the gate is never consulted
 * E11 userinfo in the URL never reaches the destination
 * E12 a gate contradicting its own argument is corrected, not propagated
 * ```
 */
class EgressAdmissionTest {

    private fun gateFor(vararg rules: EgressRule) = RestrictedEgressGate(rules.toList())

    private fun destination(
        scheme: String,
        host: String,
        port: Int,
    ) = EgressDestination(scheme = scheme, host = host, port = port)

    // ── E1 / E2: the URL becomes a destination ──────────────────────────────

    @Test
    fun `E1 an omitted port becomes the scheme default`() {
        val parsed = egressDestinationOf("https://api.example.test/v1/things")

        assertEquals(
            destination("https", "api.example.test", 443),
            parsed,
            "a URL without a port names the scheme's default. Leaving it unknown would make " +
                "every allowlist rule unmatchable and would defer the question to the socket.",
        )
        assertEquals(
            destination("http", "internal.example.test", 80),
            egressDestinationOf("http://internal.example.test/"),
            "http is not 443",
        )
    }

    @Test
    fun `E2 an explicit port overrides the scheme default`() {
        assertEquals(
            destination("https", "api.example.test", 8443),
            egressDestinationOf("https://api.example.test:8443/v1/things"),
            "an explicit port is the port, and it is the port an allowlist compares",
        )
    }

    @Test
    fun `E2b a scheme with no known default and no explicit port cannot be judged`() {
        assertEquals(
            null,
            egressDestinationOf("ftp://files.example.test/pub"),
            "there is no port to judge and no rule that could name one. Inventing 21 here " +
                "would be a product decision made in a parser.",
        )
    }

    // ── E3 / E4: the gate is asked, and its answer is kept ──────────────────

    @Test
    fun `E3 a run with no network at all is refused with that reason`() {
        val admission = egressAdmissionOf("https://api.example.test/v1", DenyAll)

        assertTrue(
            admission is EgressAdmission.RefusedJudged,
            "the destination was perfectly judgeable, so this is a JUDGED refusal; got $admission",
        )
        assertEquals(
            EgressRefusal.NetworkNotPermitted,
            (admission as EgressAdmission.RefusedJudged).reason,
        )
        assertEquals(
            destination("https", "api.example.test", 443),
            admission.destination,
            "the refusal must name the destination, so an operator is not left guessing which host",
        )
    }

    @Test
    fun `E4 a matching rule permits, and the destination travels with the permission`() {
        val admission = egressAdmissionOf(
            "https://api.example.test/v1/things?page=2",
            gateFor(EgressRule("https", "api.example.test")),
        )

        assertEquals(
            EgressAdmission.Permitted(destination("https", "api.example.test", 443)),
            admission,
            "query string and path are not part of a destination — a policy written against " +
                "them becomes 'this path is fine', which is not a network permission",
        )
    }

    @Test
    fun `E4b AllowAll permits every destination without inspecting it`() {
        val admission = egressAdmissionOf("http://anything.example.test:9999/x", AllowAll)

        assertTrue(admission is EgressAdmission.Permitted, "got $admission")
    }

    // ── E5: the port is compared, not ignored ───────────────────────────────

    @Test
    fun `E5 a rule that names no port opens that host's default port and nothing else`() {
        val gate = gateFor(EgressRule("https", "api.example.test"))

        assertTrue(
            admission("https://api.example.test/x", gate) is EgressAdmission.Permitted,
            "the default port is permitted",
        )
        assertTrue(
            admission("https://api.example.test:8443/x", gate) is EgressAdmission.RefusedJudged,
            "\"port 443 allowed\" that also permits 8443 is not a restriction. A rule that does " +
                "not name a port must name the scheme default and nothing else.",
        )
        assertTrue(
            admission("https://api.example.test:22/x", gate) is EgressAdmission.RefusedJudged,
            "and certainly not a second service on the same host",
        )
    }

    @Test
    fun `E5b an explicit port in the rule must match exactly too`() {
        val gate = gateFor(EgressRule("https", "api.example.test", 8443))

        assertTrue(admission("https://api.example.test:8443/x", gate) is EgressAdmission.Permitted)
        assertTrue(admission("https://api.example.test/x", gate) is EgressAdmission.RefusedJudged)
    }

    // ── E6 / E7: the host is EQUAL, never a prefix or a suffix ──────────────

    @Test
    fun `E6 a host that merely starts with the rule's host is not that host`() {
        val admission = admission(
            "https://api.example.test.evil.test/x",
            gateFor(EgressRule("https", "api.example.test")),
        )

        assertTrue(
            admission is EgressAdmission.RefusedJudged,
            "a prefix match lets an attacker append a domain they own to a permitted one. " +
                "Both spellings read as 'the same host' in a review, and neither is.",
        )
    }

    @Test
    fun `E7 a host that merely ends with the rule's host is not that host`() {
        assertTrue(
            admission(
                "https://evil-api.example.test/x",
                gateFor(EgressRule("https", "api.example.test")),
            ) is EgressAdmission.RefusedJudged,
            "a suffix match lets evil-api.example.test satisfy a rule for api.example.test",
        )
        assertTrue(
            admission(
                "https://example.test/x",
                gateFor(EgressRule("https", "api.example.test")),
            ) is EgressAdmission.RefusedJudged,
            "and a bare parent domain is not the sub-domain that was permitted",
        )
    }

    // ── E8: an empty allowlist is closed, not open ───────────────────────────

    @Test
    fun `E8 an empty allowlist denies everything`() {
        val admission = admission("https://api.example.test/x", RestrictedEgressGate(emptyList()))

        assertTrue(
            admission is EgressAdmission.RefusedJudged,
            "an empty allowlist is a policy that permits nothing. Reading it as 'no rules, so " +
                "everything' is how a typo in a config file turns into an open network.",
        )
    }

    // ── E9: case is noise, not identity ─────────────────────────────────────

    @Test
    fun `E9 scheme and host compare case-insensitively, and the destination is normalised`() {
        val gate = gateFor(EgressRule("https", "API.Example.Test"))

        assertTrue(
            admission("HTTPS://api.example.test/x", gate) is EgressAdmission.Permitted,
            "DNS is case-insensitive; an operator who typed a rule in upper case meant the host",
        )
        assertEquals(
            destination("https", "api.example.test", 443),
            (egressAdmissionOf("HTTPS://API.EXAMPLE.TEST/x", gate) as EgressAdmission.Permitted)
                .destination,
            "the destination the gate is asked about is normalised, so two spellings of one URL " +
                "cannot produce two different recorded destinations",
        )
    }

    // ── E10: what cannot be parsed is not quietly permitted ─────────────────

    @Test
    fun `E10 an unjudgeable URL is refused and the gate is never consulted`() {
        var consulted = false
        val spy = object : NetworkEgressGate {
            override val permitsAny: Boolean get() = true
            override fun decide(destination: EgressDestination): EgressDecision {
                consulted = true
                return EgressDecision.Allowed
            }
        }

        for (unusable in listOf("not a url", "://missing-scheme", "/just/a/path", "https://")) {
            assertEquals(
                EgressAdmission.RefusedUnjudgeable,
                egressAdmissionOf(unusable, spy),
                "'$unusable' has no scheme/host/port, so there was nothing to permit. Asking " +
                    "anyway — or permitting because the question could not be formed — is the " +
                    "one failure mode with no safe default.",
            )
        }
        assertFalse(consulted, "an unjudgeable destination must never reach the gate")
    }

    @Test
    fun `E10b a malformed URL is a value, not an exception`() {
        // An author-supplied URL is external input. `URI` throws on malformed text, and
        // an exception escaping here would become normal control flow on the way to a
        // socket rather than a typed outcome in the journal.
        val hostile = "http://exa mple.test/\u0000not-a-url"

        assertEquals(EgressAdmission.RefusedUnjudgeable, egressAdmissionOf(hostile, AllowAll))
    }

    // ── E11: a secret in the URL is not a destination ───────────────────────

    @Test
    fun `E11 userinfo never reaches the destination or its rendering`() {
        val admission = egressAdmissionOf(
            "https://alice:hunter2@api.example.test/v1",
            DenyAll,
        )

        val rendered = admission.toString()
        assertFalse(
            rendered.contains("hunter2") || rendered.contains("alice"),
            "the refusal travels to the journal and the CLI. Reading userinfo into the " +
                "destination — or even echoing the raw URL into a diagnostic — puts a " +
                "password in a durable record. Got: $rendered",
        )
        assertEquals(
            destination("https", "api.example.test", 443),
            (admission as EgressAdmission.RefusedJudged).destination,
            "the host is still the destination; only the secret part is dropped",
        )
    }

    // ── E12: the verdict's wording is corrected by the bytes ─────────────────

    @Test
    fun `E12 a gate that calls a judged destination unjudgeable is corrected, not believed`() {
        val confused = object : NetworkEgressGate {
            override val permitsAny: Boolean get() = true
            override fun decide(destination: EgressDestination): EgressDecision =
                EgressDecision.Refused(EgressRefusal.UnjudgeableDestination)
        }

        val admission = egressAdmissionOf("https://api.example.test/x", confused)

        assertTrue(admission is EgressAdmission.RefusedJudged, "got $admission")
        assertEquals(
            EgressRefusal.DestinationNotPermitted,
            (admission as EgressAdmission.RefusedJudged).reason,
            "we just parsed the destination, so 'it could not be judged' contradicts the " +
                "argument this function passed in. The refusal stands; the reason is corrected.",
        )
    }

    @Test
    fun `E12b the refusal survives as a refusal, whatever the gate claims`() {
        // The half of E12 that matters: correcting a reason must never be a way to
        // upgrade a denial into a permission.
        val confused = object : NetworkEgressGate {
            override val permitsAny: Boolean get() = true
            override fun decide(destination: EgressDestination): EgressDecision =
                EgressDecision.Refused(EgressRefusal.UnjudgeableDestination)
        }

        val attempt = HttpAttempt.Failed(
            url = "https://api.example.test/x",
            method = HttpMethod.Get,
            durationMs = 0L,
            failure = egressAdmissionOf("https://api.example.test/x", confused).toEgressFailure(),
        )

        assertTrue(
            attempt.failure is HttpFailure.EgressDenied,
            "got ${attempt.failure} — a refusal must never come back as anything a retry would fix",
        )
        val outcome = HttpResponseOutput(attempt).outcome
        assertTrue(outcome is StepOutcome.Failure, "a refused egress must FAIL the step; got $outcome")
        assertEquals(
            FailureKind.USER,
            (outcome as StepOutcome.Failure).failure.kind,
            "a refusal is the operator's problem, not a NETWORK incident — and not a TIMEOUT, " +
                "which is what would make an outer retry block repeat it forever",
        )
    }

    // ── the two ends still differ, and that difference is the whole design ───

    @Test
    fun `E13 the default refuses and --allow-network permits, through the SAME interface`() {
        assertFalse(DenyAll.permitsAny, "the default run must not even be able to ask")
        assertTrue(AllowAll.permitsAny)
        assertTrue(
            RestrictedEgressGate(listOf(EgressRule("https", "api.example.test"))).permitsAny,
            "a run with a one-host allowlist HAS a network. Withholding the capability from it " +
                "would make http.request unusable, and granting it unconditionally would make " +
                "the allowlist a comment. This property is what keeps those apart.",
        )
    }

    private fun admission(url: String, gate: NetworkEgressGate): EgressAdmission =
        egressAdmissionOf(url, gate)
}
