package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Snapshot of a single retry-attempt control row.
 *
 * ADR-0075 §4 — this record is the durable source of truth for the
 * retry aggregate's state at a given attempt. The reconciler reads one
 * snapshot per attempt and reconciles them against child evidence.
 *
 * ## Persistence-before-effects contract (ADR-0075 §6)
 * The reconciler produces a plan; the writer persists the plan BEFORE
 * any child effect is launched. Persisting the [RetryControlRowSnapshot]
 * is how the retry observes its own progress durably.
 *
 * ## Fingerprint field (ADR-0075 §9)
 * [fingerprint] is the retry contract fingerprint at the moment this row
 * was persisted. A different fingerprint on read means the contract has
 * changed; the reconciler MUST reject in that case.
 */
data class RetryControlRowSnapshot(
    /**
     * 1-based attempt ordinal this control row represents.
     * Must be in [1, [dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationInput.maxAttempts]].
     */
    val attempt: Int,

    /**
     * Current aggregate status of this attempt.
     * Terminal states ([OperationStatus.SUCCEEDED], [OperationStatus.FAILED],
     * [OperationStatus.FAILED_TIMEOUT], [OperationStatus.ABORTED],
     * [OperationStatus.DIVERGENT], [OperationStatus.LOST]) mean the
     * attempt is final — the reconciler reuses the outcome rather than
     * scheduling new child execution.
     */
    val status: OperationStatus,

    /**
     * Retry contract fingerprint at the moment this row was persisted.
     * Used to detect contract change across coordinator restarts.
     */
    val fingerprint: Fingerprint,
) {
    init {
        require(attempt >= 1) { "RetryControlRowSnapshot.attempt must be >= 1, got $attempt" }
    }
}
