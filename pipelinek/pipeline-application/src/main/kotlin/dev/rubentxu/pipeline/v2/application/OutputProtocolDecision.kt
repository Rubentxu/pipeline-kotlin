package dev.rubentxu.pipeline.v2.application

/**
 * What the `--output-protocol` negotiation decided.
 *
 * ## Why the negotiation does not end the process
 *
 * Negotiation answers a question: which protocol does this run speak? The
 * answer has two shapes — a protocol, or a refusal — and the refusal is a
 * [CliExit], the same typed decision every other refusal in this CLI is.
 *
 * The negotiation used to write the envelope and end the JVM itself, from
 * inside a helper. The helper took a decision that was not its own, and it
 * took the process with it, before the entry point had decided anything.
 */
internal sealed interface OutputProtocolDecision {

    /** The negotiation accepted a protocol. */
    data class Accepted(val protocol: OutputProtocol) : OutputProtocolDecision

    /** The negotiation refused, and this is the decision it refuses with. */
    data class Refused(val exit: CliExit) : OutputProtocolDecision
}

/**
 * Resolve the operator-supplied `--output-protocol` tokens to a concrete
 * [OutputProtocol], or refuse with [CliExit.InvalidArgument] (exit 2).
 *
 * Negotiation rules:
 * - Empty list → [OutputProtocol.DEFAULT] (Published). No change
 *   in behaviour for callers that did not opt in.
 * - The token list is the CLIENT'S PREFERENCE ORDER. The first id
 *   in the list that intersects [OutputProtocol.SUPPORTED_IDS] wins.
 *   Unknown ids in the list are SKIPPED, not rejected — clients
 *   may declare more ids than this binary supports, as long as at
 *   least one matches.
 * - When NO id in the list intersects the supported set, the decision is
 *   [OutputProtocolDecision.Refused], carrying a [CliExit.InvalidArgument]
 *   (code 2) and a message listing every supported id. The server NEVER
 *   falls back to Published on an unknown request — silent degradation is the
 *   exact defect this negotiation exists to prevent.
 *
 * Resolution lives in [OutputProtocol.negotiate]; this only translates "no
 * intersection" into the typed decision the entry point acts on. The words
 * are the same ones the refusal carried before, byte for byte: the envelope
 * goes to stderr, where a consumer reads it.
 */
internal fun negotiateOutputProtocol(tokens: List<String>): OutputProtocolDecision {
    val resolved = OutputProtocol.negotiate(tokens)
    if (resolved != null) return OutputProtocolDecision.Accepted(resolved)
    val supported = OutputProtocol.SUPPORTED_IDS.joinToString(", ")
    val requested = tokens.joinToString(", ")
    return OutputProtocolDecision.Refused(
        CliExit.InvalidArgument(
            message = "no acceptable --output-protocol in [$requested]. Supported: $supported",
            hint = "see ADR-PK1-OUTPUT-FRAMING for the negotiation contract",
        ),
    )
}
