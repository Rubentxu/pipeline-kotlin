package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Snapshot of a single waitUntil-poll control row.
 *
 * ADR-RETROSPECTIVE §RETROSPECTIVE-D — this mirrors [RetryControlRowSnapshot] but
 * carries backoff state: each control row records the backoff interval at the
 * time it was persisted so that a second binary invocation can resume from the
 * correct backoff value.
 *
 * ## Persistence-before-effects contract
 * The reconciler produces a plan; the writer persists the plan BEFORE any
 * child effect is launched. Persisting the [WaitUntilControlRowSnapshot] is how
 * the waitUntil aggregate observes its own progress durably.
 *
 * ## Fingerprint field
 * [fingerprint] is the waitUntil contract fingerprint at the moment this row
 * was persisted. A different fingerprint on read means the contract has
 * changed; the reconciler MUST reject in that case.
 *
 * ## Backoff field
 * [currentBackoffMs] is the backoff interval in milliseconds at the moment
 * this row was persisted. Used to compute [nextBackoffMs] on advance.
 */
data class WaitUntilControlRowSnapshot(
    /**
     * 1-based poll attempt ordinal.
     */
    val attempt: Int,

    /**
     * Current aggregate status of this poll attempt.
     * Terminal states ([OperationStatus.SUCCEEDED], [OperationStatus.FAILED],
     * [OperationStatus.ABORTED], [OperationStatus.DIVERGENT], [OperationStatus.LOST])
     * mean the attempt is final.
     */
    val status: OperationStatus,

    /**
     * Backoff interval in milliseconds at the moment this row was persisted.
     * Used to compute the next backoff on advance.
     */
    val currentBackoffMs: Long,

    /**
     * WaitUntil contract fingerprint at the moment this row was persisted.
     * Used to detect contract change across coordinator restarts.
     */
    val fingerprint: Fingerprint,
) {
    init {
        require(attempt >= 1) { "WaitUntilControlRowSnapshot.attempt must be >= 1, got $attempt" }
        require(currentBackoffMs >= 0) { "currentBackoffMs must be >= 0, got $currentBackoffMs" }
    }
}
