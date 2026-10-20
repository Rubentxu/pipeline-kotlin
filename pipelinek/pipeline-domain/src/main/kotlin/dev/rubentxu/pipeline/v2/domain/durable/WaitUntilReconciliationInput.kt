package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Pure input to [WaitUntilReconciler.reconcile].
 *
 * The reconciler is a pure function whose only inputs are pure value snapshots
 * read from durable storage. It owns ZERO effects: no journal writes, no clock,
 * no child dispatch.
 *
 * ## Composition
 * The journal adapter assembles this input by reading the persisted control rows
 * for the waitUntil logical invocation identified by [controlIdentity].
 */
data class WaitUntilReconciliationInput(
    /**
     * Deterministic waitUntil aggregate identity. Used to anchor
     * [WaitUntilReconciliationDecision.RejectDivergence.operationId] when
     * the reconciler must reject an ambiguous or divergent state.
     */
    val controlIdentity: WaitUntilControlIdentity,

    /**
     * Initial recurrence period in milliseconds before the first poll.
     */
    val initialRecurrencePeriodMs: Long,

    /**
     * Maximum backoff interval in milliseconds. The reconciler uses this to
     * decide whether to advance to the next poll or terminate with
     * [WaitUntilReconciliationDecision.DeadlineExceeded].
     */
    val maxBackoffMs: Long,

    /**
     * Fingerprint of the current waitUntil contract, computed by the
     * emitter/caller. Compared against each [WaitUntilControlRowSnapshot.fingerprint].
     */
    val currentFingerprint: Fingerprint,

    /**
     * All waitUntil control rows for this waitUntil logical invocation,
     * in persisted order. Empty on a fresh waitUntil.
     */
    val controlRows: List<WaitUntilControlRowSnapshot>,
) {
    init {
        require(initialRecurrencePeriodMs >= 0) {
            "initialRecurrencePeriodMs must be >= 0, got $initialRecurrencePeriodMs"
        }
        require(maxBackoffMs >= 0) {
            "maxBackoffMs must be >= 0, got $maxBackoffMs"
        }
    }
}
