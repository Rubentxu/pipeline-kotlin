package dev.rubentxu.pipeline.v2.application

/**
 * PK-0-57-RC1 / ADR-PK1-OUTPUT-FRAMING — opt-in versioned output
 * protocol.
 *
 * The default [Published] preserves the R0.3 channel split: the
 * [LaunchReceipt] (`pipelinek.launch/1`) is line 1 of stdout, and the
 * event array is line 2 of stdout. This is the contract
 * [ADR-PK1-IDENTITY] declared and that the R0.3 brief locked in.
 *
 * The opt-in [SeparatedV2] moves the event array to stderr line 1 so
 * stdout carries only the receipt. The receipt's schema and channel
 * are unchanged — the bump is on the *channel of the event array*,
 * not on the receipt. The token `pipelinek.output/2` is the wire id
 * clients and servers exchange in `--output-protocol` negotiation.
 *
 * **Default is preserved.** A process invoked without
 * `--output-protocol` behaves exactly as the R0.3 release did. No
 * silent migration, no surprise reroute.
 *
 * **Negotiation is fail-closed.** An unknown id in `--output-protocol`
 * is rejected at startup with [CliExit.InvalidArgument] (exit 2) and
 * a typed envelope listing [SUPPORTED_IDS]. The server never falls
 * back to [Published] on an unknown request — that would be silent
 * degradation, the very defect ADR-PK1-OUTPUT-FRAMING was written to
 * prevent.
 */
enum class OutputProtocol(val id: String) {
    /** `output/v1` — R0.3 channel split (receipt + events on stdout). */
    Published("output/v1"),

    /** `output/v2` — events move to stderr; receipt stays on stdout. */
    SeparatedV2("output/v2"),
    ;

    companion object {
        /** The default protocol; used when `--output-protocol` is absent. */
        val DEFAULT: OutputProtocol = Published

        /** Every protocol id this binary accepts, in declaration order. */
        val SUPPORTED_IDS: List<String> = values().map { it.id }

        /**
         * Resolves a wire id (as supplied by `--output-protocol`) to a
         * known [OutputProtocol], or `null` if the id is unknown. The
         * caller is responsible for emitting the fail-closed error.
         */
        fun fromId(id: String): OutputProtocol? = values().firstOrNull { it.id == id }
    }
}
