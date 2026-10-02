package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.EgressDecision
import dev.rubentxu.pipeline.v2.domain.step.EgressDestination
import dev.rubentxu.pipeline.v2.domain.step.EgressRefusal
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.defaultEgressPortFor
import java.net.URI

/**
 * H6 — asking the runtime whether one socket may be opened.
 *
 * ## The plugin decides nothing here
 *
 * This file parses a URL and hands a typed question to [NetworkEgressGate]. It does
 * not hold a rule, match a pattern, or carry a "should we allow this" flag. The
 * moment it did, "the runtime owns the permission" would be a comment, and two
 * consumers reading the same bytes could reach two different verdicts — which is a
 * security defect wearing a refactor's clothes.
 *
 * So the split is strict:
 *
 * ```text
 * plugin   "here is scheme://host:port; may I?"     ← this file
 * runtime  "no" / "yes" / "no, and here is why"      ← NetworkEgressGate
 * ```
 *
 * ## Pure and total
 *
 * Both functions here are PURE and TOTAL. A URL that cannot be reduced to a
 * destination is a VALUE ([EgressAdmission.RefusedUnjudgeable]), not an exception,
 * and it is refused rather than waved through: a destination nobody could parse is
 * a destination nobody could check, and "we could not judge it" must never become
 * "it was fine".
 *
 * Nothing here opens anything. The caller's contract is that this runs before a
 * transport exists to be called.
 */
internal sealed interface EgressAdmission {

    /** The socket may be opened, to exactly this destination. */
    data class Permitted(val destination: EgressDestination) : EgressAdmission

    /**
     * The destination was determined and refused.
     *
     * The destination is carried because an operator reading "not permitted" needs
     * to know WHICH one, and the URL alone is a string they have to re-read.
     */
    data class RefusedJudged(
        val destination: EgressDestination,
        val reason: EgressRefusal,
    ) : EgressAdmission

    /**
     * The URL could not be reduced to a destination at all, so there was nothing to
     * ask about — and the gate was never consulted.
     */
    data object RefusedUnjudgeable : EgressAdmission
}

/**
 * The admission answer for [url], or `null` when the URL is not a destination this
 * product can describe.
 *
 * `null` here is narrow and deliberate: it means "scheme/host/port could not be
 * determined", never "the gate said no". Callers map `null` onto
 * [EgressAdmission.RefusedUnjudgeable] themselves, so the two facts stay
 * distinguishable instead of collapsing into one boolean.
 */
internal fun egressDestinationOf(url: String): EgressDestination? {
    // An author-supplied URL is EXTERNAL input: `URI` throws on malformed text, and
    // a thrown syntax error must not become normal control flow on the way to a
    // socket. Caught here, converted to a value by the caller.
    val uri = try {
        URI(url.trim())
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    // `URI.host` is null for an authority it will not parse — a registry-based
    // authority, a host with a character the RFC disallows. That is exactly the
    // "cannot be judged" case, and it fails closed rather than being guessed at.
    //
    // Lowercased, because hostnames are case-insensitive and matching already is.
    // Leaving the author's casing here would mean `API.EXAMPLE.TEST` and
    // `api.example.test` matched each other as rules while producing two different
    // destinations — and a destination is what the refusal names in the journal.
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val port = when {
        uri.port in 1..65535 -> uri.port
        else -> defaultEgressPortFor(scheme) ?: return null
    }
    // Userinfo is deliberately not read. `https://user:secret@host/` is a real URL
    // shape, and copying any part of the authority other than the host is how a
    // credential ends up in a refusal message that goes to the journal.
    return EgressDestination(scheme = scheme, host = host, port = port)
}

/**
 * Asks [gate] about [url] and interprets the answer.
 *
 * Pure: no I/O, no clock, no ambient state. The gate is the only thing that knows
 * the answer, and this function's whole job is to turn that answer into a shape the
 * handler can act on without re-deriving it.
 *
 * ## The one normalisation
 *
 * A gate that reports [EgressRefusal.UnjudgeableDestination] for a destination we
 * have just determined is contradicted by the argument it was handed — we parsed it,
 * so it was judgeable. The verdict's wording is corrected to
 * [EgressRefusal.DestinationNotPermitted] rather than propagated, because carrying
 * it would make [EgressAdmission.RefusedJudged] claim a destination it does not
 * have. The refusal is unchanged; only the reason is made truthful. Doing it here
 * rather than in an `init { require(...) }` is deliberate: this is domain control
 * flow, and an exception is not a way to report a policy answer.
 */
internal fun egressAdmissionOf(url: String, gate: NetworkEgressGate): EgressAdmission {
    val destination = egressDestinationOf(url)
        ?: return EgressAdmission.RefusedUnjudgeable
    return when (val decision = gate.decide(destination)) {
        is EgressDecision.Allowed -> EgressAdmission.Permitted(destination)
        is EgressDecision.Refused -> EgressAdmission.RefusedJudged(
            destination = destination,
            reason = if (decision.reason == EgressRefusal.UnjudgeableDestination) {
                EgressRefusal.DestinationNotPermitted
            } else {
                decision.reason
            },
        )
    }
}

/** How an admission answer becomes the Step's typed failure. */
internal fun EgressAdmission.toEgressFailure(): HttpFailure = when (this) {
    is EgressAdmission.Permitted -> throw IllegalStateException(
        "a permitted admission has no failure to report",
    )

    is EgressAdmission.RefusedJudged -> HttpFailure.EgressDenied(reason)
    is EgressAdmission.RefusedUnjudgeable ->
        HttpFailure.EgressDenied(EgressRefusal.UnjudgeableDestination)
}
