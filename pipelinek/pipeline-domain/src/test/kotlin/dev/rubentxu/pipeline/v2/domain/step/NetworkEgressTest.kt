package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules every outbound socket passes before it is opened.
 * Two failure modes, opposite in direction and equal in cost: a predicate too loose opens a
 * network the operator closed, and one too tight closes a network they opened. Nothing else in
 * the seam tells the difference — the socket opens or it does not — so the rules live here and
 * are pinned from both sides.
 * The cases below are the ones the KDoc names out loud, because a guarantee nobody wrote down
 * is a guarantee the next edit may treat as a comment.
*/
class NetworkEgressTest {

    private fun dest(
        scheme: String = "https",
        host: String = "api.example.test",
        port: Int = 443,
    ) = EgressDestination(scheme = scheme, host = host, port = port)

    // --- host matching is exact, on purpose -------------------------------------

    @Test
    fun `a host that merely starts with the rule host is not a match`() {
        // Named in the KDoc: a prefix match lets `api.github.com.evil.test` satisfy a rule for
        // `api.github.com`. Both read as "the same host" in a review and are not.
        val rule = EgressRule("https", "api.github.com")

        assertFalse(rule.permits(dest(host = "api.github.com.evil.test")))
        assertTrue(rule.permits(dest(host = "api.github.com")))
    }

    @Test
    fun `a host that merely ends with the rule host is not a match`() {
        // Named in the KDoc: a suffix match lets `evil-github.com` satisfy one for `github.com`.
        val rule = EgressRule("https", "github.com")

        assertFalse(rule.permits(dest(host = "evil-github.com")))
        assertTrue(rule.permits(dest(host = "github.com")))
    }

    @Test
    fun `host matching ignores case, because hostnames are case-insensitive`() {
        val rule = EgressRule("https", "api.example.test")

        assertTrue(rule.permits(dest(host = "API.EXAMPLE.TEST")))
        assertTrue(rule.permits(dest(scheme = "HTTPS")))
    }

    @Test
    fun `a rule for one host does not open another host on the same domain`() {
        // The allowlist has no wildcard. A rule is a host, or it is not that host.
        val rule = EgressRule("https", "api.example.test")

        assertFalse(rule.permits(dest(host = "cdn.example.test")))
        assertFalse(rule.permits(dest(host = "example.test")))
    }

    // --- the port is compared, not ignored --------------------------------------

    @Test
    fun `a rule naming no port opens the scheme default and nothing else`() {
        // Named in the KDoc: "port 443 allowed" that also permits 22 is not a restriction.
        val rule = EgressRule("https", "api.example.test")

        assertTrue(rule.permits(dest(port = 443)))
        assertFalse(rule.permits(dest(port = 8443)))
        assertFalse(rule.permits(dest(port = 22)))
    }

    @Test
    fun `a rule naming a port opens exactly that port`() {
        val rule = EgressRule("https", "api.example.test", port = 8443)

        assertTrue(rule.permits(dest(port = 8443)))
        assertFalse(rule.permits(dest(port = 443)))
    }

    @Test
    fun `a rule with no port and an unrecognised scheme matches nothing`() {
        // Not an error and not a wildcard: there is no default port to mean, so the rule has
        // nothing to stand for and must cover nothing rather than cover everything.
        val rule = EgressRule("gopher", "api.example.test")

        assertFalse(rule.permits(dest(scheme = "gopher", port = 70)))
    }

    @Test
    fun `a rule for http does not open the same host over https`() {
        val rule = EgressRule("http", "api.example.test")

        assertTrue(rule.permits(dest(scheme = "http", port = 80)))
        assertFalse(rule.permits(dest(scheme = "https", port = 443)))
    }

    // --- permits is total --------------------------------------------------------

    @Test
    fun `no spec at all makes permits throw`() {
        val rule = EgressRule("https", "api.example.test")

        val hostile = listOf(
            dest(host = ""),
            dest(host = "   "),
            dest(port = 0),
            dest(port = -1),
            dest(port = -443),
            dest(scheme = ""),
        )

        for (d in hostile) {
            val outcome = try {
                rule.permits(d)
            } catch (e: Throwable) {
                throw AssertionError("permits($d) threw ${e::class.simpleName}: ${e.message}", e)
            }
            assertFalse(outcome, "permits($d) was allowed, and an unjudgeable destination must not be")
        }
    }

    @Test
    fun `a blank rule matches nothing rather than everything`() {
        assertFalse(EgressRule("https", "").permits(dest()))
        assertFalse(EgressRule("", "api.example.test").permits(dest()))
        assertFalse(EgressRule("  ", "  ").permits(dest()))
    }

    // --- the gate around the rule ------------------------------------------------

    @Test
    fun `an empty allowlist denies everything`() {
        // The KDoc calls this the reading worth being loud about, because it is how a typo in a
        // config file turns into an OPEN network. `any` over an empty list is false; the test
        // pins that outcome rather than the expression that produces it.
        val gate = RestrictedEgressGate(emptyList())

        assertEquals(EgressDecision.Refused(EgressRefusal.DestinationNotPermitted), gate.decide(dest()))
        assertTrue(gate.permitsAny, "an allowlist permits something, so the run may ask")
    }

    @Test
    fun `a destination outside every rule is refused with the destination reason`() {
        val gate = RestrictedEgressGate(listOf(EgressRule("https", "api.example.test")))

        assertEquals(
            EgressDecision.Refused(EgressRefusal.DestinationNotPermitted),
            gate.decide(dest(host = "evil.test")),
        )
    }

    @Test
    fun `a destination covered by any one rule is allowed`() {
        val gate = RestrictedEgressGate(
            listOf(
                EgressRule("https", "a.example.test"),
                EgressRule("https", "b.example.test", port = 8443),
            ),
        )

        assertEquals(EgressDecision.Allowed, gate.decide(dest(host = "a.example.test")))
        assertEquals(EgressDecision.Allowed, gate.decide(dest(host = "b.example.test", port = 8443)))
        assertFalse(gate.decide(dest(host = "c.example.test")) is EgressDecision.Allowed)
    }

    @Test
    fun `AllowAll permits anything and DenyAll permits nothing`() {
        val gate = AllowAll
        assertTrue(gate.permitsAny)
        assertEquals(EgressDecision.Allowed, gate.decide(dest(host = "anything.test", port = 1)))

        val deny = DenyAll
        assertFalse(deny.permitsAny)
        assertEquals(
            EgressDecision.Refused(EgressRefusal.NetworkNotPermitted),
            deny.decide(dest(host = "anything.test")),
        )
    }

    @Test
    fun `DenyAll refuses with NetworkNotPermitted and not the destination reason`() {
        // The two refusals are different operator facts and the journal has to tell them apart:
        // "this run has no network" is fixed by policy, "not to this host" is a judgement about
        // one destination. Collapsing them points an operator at the wrong thing.
        assertEquals(
            EgressRefusal.NetworkNotPermitted,
            (DenyAll.decide(dest()) as EgressDecision.Refused).reason,
        )
        assertEquals(
            EgressRefusal.DestinationNotPermitted,
            (RestrictedEgressGate(emptyList()).decide(dest()) as EgressDecision.Refused).reason,
        )
    }

    @Test
    fun `an unpermitted destination is still a destination the run may ask about`() {
        // The two questions are separate, and conflating them makes a host allowlist refuse
        // every Step at admission -- which makes the allowlist worse than not having one.
        val gate = RestrictedEgressGate(listOf(EgressRule("https", "allowed.test")))

        assertTrue(gate.permitsAny)
        assertEquals(
            EgressDecision.Refused(EgressRefusal.DestinationNotPermitted),
            gate.decide(dest(host = "not-allowed.test")),
        )
    }

    // --- the scheme defaults are one authority ------------------------------------

    @Test
    fun `only the schemes this product recognises have a default port`() {
        assertEquals(80, defaultEgressPortFor("http"))
        assertEquals(443, defaultEgressPortFor("https"))
        assertEquals(443, defaultEgressPortFor("HTTPS"), "scheme matching ignores case")
        assertNull(defaultEgressPortFor("ftp"))
        assertNull(defaultEgressPortFor(""))
        assertNull(defaultEgressPortFor("gopher"))
    }

    @Test
    fun `a destination renders as the operator reads it in the journal`() {
        // This is the text a refusal names, so its shape is part of the contract rather than a
        // toString detail: scheme, host, port, and nothing else that was in the URL.
        assertEquals("https://api.example.test:443", dest().toString())
        assertEquals("http://localhost:8080", dest(scheme = "http", host = "localhost", port = 8080).toString())
    }
}
