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
 * **Wire ids follow the `pipelinek.output/N` versioned family.** [Published] is
 * `pipelinek.output/1`; [SeparatedV2] is `pipelinek.output/2`. This
 * matches the `pipelinek.launch/1` schema family declared by
 * ADR-PK1-IDENTITY and the bump rule of ADR-PK1-OUTPUT-FRAMING
 * ("alineado con el patrón `pipelinek.<family>/N`. Bump sobre el canal, no
 * sobre el recibo").
 *
 * **Negotiation is fail-closed.** [negotiate] returns `null` when
 * no id in the supplied preference list intersects with
 * [SUPPORTED_IDS]. The caller (the CLI entrypoint) emits a typed
 * `CliExit.InvalidArgument` envelope and exits with code 2. The
 * server NEVER falls back to [Published] on an unknown request —
 * that would be silent degradation, the very defect
 * ADR-PK1-OUTPUT-FRAMING was written to prevent.
 */
enum class OutputProtocol(val id: String) {
    /** `pipelinek.output/1` — R0.3 channel split (receipt + events on stdout). */
    Published("pipelinek.output/1"),

    /** `pipelinek.output/2` — events move to stderr; receipt stays on stdout. */
    SeparatedV2("pipelinek.output/2"),
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

        /**
         * Intersection-based negotiation per ADR-PK1-OUTPUT-FRAMING.
         *
         * The client passes a list of protocol ids it accepts, in
         * priority order (leftmost is most preferred). The server
         * picks the FIRST id in that order that intersects with
         * [SUPPORTED_IDS]; everything else is irrelevant for the
         * selection. Unknown ids in the list are SKIPPED, not rejected
         * — a client may declare more ids than the server supports as
         * long as at least one of them intersects.
         *
         * Returns:
         *  - `null` when [tokens] is non-empty AND no id in it
         *    matches any supported id. The CLI exits with
         *    [CliExit.InvalidArgument] (code 2) and an envelope
         *    listing [SUPPORTED_IDS]. NEVER falls back to [DEFAULT].
         *  - [DEFAULT] when [tokens] is empty. No opt-in was supplied;
         *    behaviour is identical to the R0.3 release.
         *  - The chosen [OutputProtocol] otherwise.
         *
         * Duplicates are tolerated: the first occurrence defines the
         * priority position. Empty strings and blank tokens inside
         * the list are treated as unknown (no match).
         */
        fun negotiate(tokens: List<String>): OutputProtocol? {
            if (tokens.isEmpty()) return DEFAULT
            val supported = SUPPORTED_IDS.toSet()
            // firstOrNull preserves client order. A blank token is
            // never a supported id, so it does not match.
            val chosen = tokens.firstOrNull { token -> token.isNotBlank() && token in supported }
                ?: return null
            // `chosen` is in SUPPORTED_IDS so fromId is non-null;
            // the `?: error(...)` is the type-system bridge and is
            // unreachable.
            return fromId(chosen) ?: error(
                "negotiate invariant violated: '$chosen' is in SUPPORTED_IDS but fromId returned null",
            )
        }
    }
}