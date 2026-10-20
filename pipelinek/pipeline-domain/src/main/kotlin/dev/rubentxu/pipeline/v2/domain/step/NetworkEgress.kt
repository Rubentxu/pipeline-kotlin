package dev.rubentxu.pipeline.v2.domain.step

/**
 * Whether THIS EXECUTION may reach the network, and to where (LFC-2E3 / WU-093).
 *
 * This is a runtime decision, not a protocol one, and that is exactly why the
 * type lives here, in the innermost seam, rather than beside the HTTP code. A
 * pipeline process that cannot open a socket is a property of the RUN: an
 * `http.request`, a git-over-https fetch, an artefact feed and a registry pull
 * all answer this question identically. Stating it once, generically, is what
 * lets `pipeline-application` hold the permission without ever learning what an
 * HTTP request is.
 *
 * The plugin owns the protocol; the runtime owns the permission. Neither owns
 * both, and neither has to know about the other to get there.
 */
val NETWORK_EGRESS_CAPABILITY: StepCapability = StepCapability("network.egress")

/**
 * A destination, as far as egress is concerned.
 *
 * Scheme, host and port — and nothing else. Deliberately NOT a URL: a query
 * string, a path or a fragment have no bearing on whether a socket may be
 * opened to a host, and modelling them would invite a policy to be written
 * against them, which is how a "this path is fine" rule turns into "this host
 * is fine" the first time a redirect or a rewritten path appears.
 */
data class EgressDestination(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$scheme://$host:$port"
}

/**
 * The runtime's answer about one destination.
 *
 * A refusal always says WHY in closed terms. "Denied" without a reason is the
 * failure mode this type exists to prevent: an operator who cannot tell a
 * policy refusal from a typo will change the wrong thing.
 */
sealed interface EgressDecision {
    data object Allowed : EgressDecision

    data class Refused(val reason: EgressRefusal) : EgressDecision
}

/** Why a destination was refused. Closed, because these reach the journal. */
enum class EgressRefusal {
    /** The run may not reach the network at all. The default. */
    NetworkNotPermitted,

    /** The run may reach the network, but not this destination. */
    DestinationNotPermitted,

    /** The destination could not be understood well enough to judge it. */
    UnjudgeableDestination,
}

/**
 * The gate every outbound socket must pass, BEFORE it is opened.
 *
 * ## Why this is a question and not a verdict
 *
 * A capability carrying `Allowed | Denied` answers "may this run use the
 * network?", which is why it was enough while that was the only question. The
 * moment a destination can be refused too, the answer depends on WHERE — and a
 * static value cannot depend on anything.
 *
 * The tempting shape is a static value carrying the rules:
 *
 * ```kotlin
 * data class Restricted(val destinations: List<EgressRule>) : NetworkEgressPolicy
 * ```
 *
 * and it does not work. Whichever side reads those rules is the side that
 * decides, so "the runtime owns the permission" becomes a comment. A second
 * consumer of the same policy could match the rules differently and reach a
 * different verdict from the same bytes — and a mismatch between "what was
 * allowed" and "what was actually opened" is a security defect, not a
 * refactor. Asking the runtime is the only shape where the decision stays in
 * one place.
 *
 * ## The two trivial gates
 *
 * [AllowAll] and [DenyAll] are the `--allow-network` switch and the default.
 * They are the SAME interface as a restricted gate, which is the point: there
 * is no second code path for "allowed" that could skip the question.
 *
 * ## Fail-closed twice over
 *
 * An ABSENT capability already means "no network" — that is the path that
 * matters, because fail-closed admission rejects the Step BEFORE its handler
 * runs. The gate is the second line, for a runtime that chooses to hand out
 * the capability with a refusing gate. A run with no capability is refused
 * earlier and better.
 *
 * ## Why this is not a `fun interface`
 *
 * It needs an abstract PROPERTY, and Kotlin forbids that in a `fun interface`. The
 * alternatives were a default value or no default at all, and no default wins: a
 * new gate that forgets to say whether it permits anything should fail to compile,
 * not quietly inherit a value. Every implementation therefore states its own
 * entitlement, and the compiler holds them to it.
 */
interface NetworkEgressGate {
    fun decide(destination: EgressDestination): EgressDecision

    /**
     * Whether this run may ASK about egress at all.
     *
     * `false` means the runtime withholds [NETWORK_EGRESS_CAPABILITY] entirely, so
     * admission rejects a Step that needs the network before its handler exists.
     * `true` means the run is entitled to ask, and [decide] has the final word per
     * destination.
     *
     * The distinction is load-bearing, not a convenience. "This run has no network
     * at all" and "this run has a network but not to here" are different operator
     * facts, and collapsing them would mean a one-host allowlist either refused
     * every Step at admission (making the Step unusable) or permitted every Step
     * to reach any host (making the allowlist a comment). Both were the alternative;
     * this is the shape where neither happens.
     *
     * It is a PROPERTY rather than a `when (gate) { DenyAll -> ... }` at the bridge,
     * so the runtime does not have to know which gates exist — only that a gate can
     * say whether the question is on the table.
     */
    val permitsAny: Boolean
}

/** `--allow-network`. Decides nothing and forbids nothing. */
object AllowAll : NetworkEgressGate {
    override val permitsAny: Boolean get() = true
    override fun decide(destination: EgressDestination): EgressDecision = EgressDecision.Allowed
}

/** The default. Refuses every destination without inspecting it. */
object DenyAll : NetworkEgressGate {
    override val permitsAny: Boolean get() = false
    override fun decide(destination: EgressDestination): EgressDecision =
        EgressDecision.Refused(EgressRefusal.NetworkNotPermitted)
}

/**
 * The port a scheme names when a URL does not, or `null` for a scheme with no
 * default this product recognises.
 *
 * Domain knowledge, shared deliberately: the gate needs it to know that a rule for
 * `https://api.example.test` means port 443, and the caller needs it to describe a
 * destination whose URL omitted the port. Two private copies would be two
 * opinions, and a disagreement here is an allowlist that quietly means something
 * else on one side of the socket.
 */
fun defaultEgressPortFor(scheme: String): Int? = when (scheme.lowercase()) {
    "http" -> 80
    "https" -> 443
    else -> null
}

/**
 * H6 — egress allowed only to destinations that match a rule.
 *
 * Deliberately the smallest useful allowlist: scheme, host, port. Not CIDR
 * ranges, not DNS pinning, not proxies, not wildcards in the host. Each of
 * those is a real feature and each is also a way to be wrong in a way that is
 * hard to notice — a CIDR that looks right and is not, a resolved address that
 * changed after the check, a proxy that is really the destination. The value
 * of an allowlist is that an operator can read it and know what it means, and
 * that is the whole value at this point.
 *
 * ## Host matching is EXACT, on purpose
 *
 * A prefix match would let `api.github.com.evil.test` satisfy a rule for
 * `api.github.com`, and a suffix match would let `evil-github.com` satisfy
 * one for `github.com`. Both read as "the same host" in a review and are not.
 * The only host pattern here is the host itself.
 *
 * ## The port is compared, not ignored
 *
 * "Port 443 allowed" that also permits 22 is not a restriction. When a rule
 * does not name a port it names a scheme's default and nothing else, so a
 * rule for `https://api.example.test` does not open `https://api.example.test:8443`.
 */
class RestrictedEgressGate(private val rules: List<EgressRule>) : NetworkEgressGate {

    // An allowlist permits something, so the run may ask. Whether THIS
    // destination survives is [decide]'s job and not admission's.
    override val permitsAny: Boolean get() = true

    override fun decide(destination: EgressDestination): EgressDecision {
        // `any` over an empty list is `false`, so an empty allowlist denies
        // everything — it is not "no rules, therefore everything". That reading is
        // the one worth being loud about, because it is how a typo in a config file
        // turns into an open network, so it is spelled out here even though the
        // expression below already enforces it.
        //
        // An explicit `if (rules.isEmpty()) return Refused(...)` used to stand in
        // this spot. Mutation M-http-29 proved it unreachable by behaviour — it
        // produced byte-identical decisions — so it was removed rather than kept as
        // a comment nobody maintains. The property survives as a canary (E8), and a
        // guard that cannot change an outcome is not a guard.
        val permitted = rules.any { it.permits(destination) }
        return if (permitted) {
            EgressDecision.Allowed
        } else {
            EgressDecision.Refused(EgressRefusal.DestinationNotPermitted)
        }
    }
}

/**
 * One permitted destination.
 *
 * [port] is nullable and means "the scheme's default port, and only that" —
 * see [RestrictedEgressGate]. It is not a wildcard, because a wildcard is the
 * shape a reviewer stops seeing after the third one.
 */
data class EgressRule(
    val scheme: String,
    val host: String,
    val port: Int? = null,
) {
    /**
     * Whether this rule covers [destination].
     *
     * PURE and TOTAL. A destination the rule cannot judge — a blank host, a
     * negative port — is NOT a match. Deciding it cannot happen must not
     * become deciding it is fine.
     */
    fun permits(destination: EgressDestination): Boolean {
        if (scheme.isBlank() || host.isBlank()) return false
        if (destination.host.isBlank() || destination.port <= 0) return false
        if (!scheme.equals(destination.scheme, ignoreCase = true)) return false
        if (!host.equals(destination.host, ignoreCase = true)) return false
        val expectedPort = port ?: defaultEgressPortFor(scheme) ?: return false
        return expectedPort == destination.port
    }
}
