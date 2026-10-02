package dev.rubentxu.pipeline.v2.domain.step

/**
 * Whether THIS EXECUTION may reach the network at all (LFC-2E3 / WU-093).
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
 * The egress verdict, fail-closed by construction.
 *
 * An ABSENT [NETWORK_EGRESS_CAPABILITY] already means "denied" — that is the
 * path that matters, because fail-closed admission rejects the Step BEFORE its
 * handler runs, and no amount of `if` inside a handler can be as strong as a
 * capability the runtime simply does not hand out. This type is what arrives
 * when the capability IS present, so a consumer can distinguish "allowed" from
 * "known and refused" without re-deriving anything.
 */
sealed interface NetworkEgressPolicy {
    /** The run may open outbound connections. Reachable only via `--allow-network`. */
    data object Allowed : NetworkEgressPolicy

    /**
     * Egress is known and refused.
     *
     * Present for a runtime that expresses the denial explicitly. A run that
     * never receives the capability at all is denied too — one step earlier, at
     * admission, with a better diagnostic.
     */
    data object Denied : NetworkEgressPolicy
}
