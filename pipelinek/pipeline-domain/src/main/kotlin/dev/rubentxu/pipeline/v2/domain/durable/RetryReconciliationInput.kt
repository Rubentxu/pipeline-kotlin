package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Pure input to [RetryReconciler.reconcile].
 *
 * ADR-0075 §4 — the reconciler is a pure function whose only inputs are
 * pure value snapshots read from durable storage. It owns ZERO effects:
 * no journal writes, no clock, no child dispatch.
 *
 * ## Composition
 * The journal adapter assembles this input by reading the canonical
 * durable facts for the retry logical invocation identified by
 * [controlIdentity], filtering by fingerprint to detect contract
 * divergence, and grouping child rows by their attempt ordinal.
 *
 * [preControlChildren] is the bucket of child durable facts that were
 * written before ADR-0075 introduced the retry control row. The
 * reconciler applies the legacy compat policy (ADR-0075 §8) against
 * this bucket when no control row exists.
 */
data class RetryReconciliationInput(
    /**
     * Deterministic retry identity. Used to anchor
     * [RetryReconciliationDecision.RejectDivergence.operationId] when
     * the reconciler must reject an ambiguous or divergent state.
     */
    val controlIdentity: RetryControlIdentity,

    /**
     * Maximum number of attempts allowed by the current retry contract.
     * Final attempt = [maxAttempts]. The reconciler uses this to decide
     * whether to advance or mark the aggregate exhausted.
     */
    val maxAttempts: Int,

    /**
     * Fingerprint of the current retry contract, computed by the
     * emitter/caller. Compared against each [RetryControlRowSnapshot.fingerprint].
     */
    val currentFingerprint: Fingerprint,

    /**
     * All retry control rows for this retry identity, in persisted order.
     * Empty on a fresh retry or on a legacy journal where the control row
     * was never written. The reconciler dedupes by attempt internally.
     */
    val controlRows: List<RetryControlRowSnapshot>,

    /**
     * All retry child rows for this retry identity, already grouped by
     * attempt ordinal (1-based). Empty for a fresh retry.
     */
    val childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>>,

    /**
     * Pre-ADR-0075 child rows that have no associated control row.
     * Used only by the legacy compat branch.
     */
    val preControlChildren: List<RetryChildRowSnapshot>,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    }
}
