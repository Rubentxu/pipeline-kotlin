package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Snapshot of a single retry child effect row.
 *
 * ADR-0075 §4 + §5 — the durable evidence that a particular child effect
 * (canonical dispatch output) reached a particular terminal (or in-flight)
 * state at a particular retry attempt. The reconciler reads a multiset of
 * these per attempt and uses them to decide between
 * [RetryReconciliationDecision.CloseSuccessFromChild],
 * [RetryReconciliationDecision.AdvanceAfterFailure], and
 * [RetryReconciliationDecision.RejectDivergence].
 *
 * ## Why a snapshot, not a live reference
 * The reconciler is pure: it MUST NOT open a journal, talk to a journal
 * sink, or call into a coordinator. The journal adapter reads the rows
 * on its own thread and produces a [RetryChildRowSnapshot] per row in
 * the form the reconciler expects.
 *
 * ## Fingerprint contract
 * When present, [fingerprint] matches the child's own input fingerprint
 * under the current retry contract. A divergence detected via the control
 * row's [RetryControlRowSnapshot.fingerprint] is enough to reject; this
 * field is currently reserved for future child-level divergence checks
 * (it is part of the durable journal schema).
 */
data class RetryChildRowSnapshot(
    /**
     * 1-based attempt ordinal this child belongs to.
     */
    val attempt: Int,

    /**
     * 0-based position of the child within the retry body. For Block
     * Steps with a sequential body this is always 0; for parallel blocks
     * the index within the body's parallel branches would also appear
     * here, but RETRY-D only governs sequential retry bodies in this
     * scope.
     */
    val childIndex: Int,

    /**
     * Current status of the child operation as projected by the
     * journal. May be terminal or in-flight.
     */
    val status: OperationStatus,

    /**
     * Optional fingerprint of the child effect's own input. When present,
     * the journal adapter guarantees it matches the child journal row's
     * fingerprint at the moment of snapshot. Null when the journal row
     * has not yet been fully captured (very rare transient state).
     */
    val fingerprint: Fingerprint? = null,
) {
    init {
        require(attempt >= 1) { "RetryChildRowSnapshot.attempt must be >= 1, got $attempt" }
        require(childIndex >= 0) { "RetryChildRowSnapshot.childIndex must be >= 0, got $childIndex" }
    }

    /** True if this child has reached a terminal state. */
    val isTerminal: Boolean get() = status.isTerminal

    /** True if this child terminated with success. */
    val isSuccess: Boolean get() = status == OperationStatus.SUCCEEDED

    /**
     * True if this child terminated with a non-success terminal status.
     * For retry purposes, FAILED, FAILED_TIMEOUT and LOST all count as
     * failures because the body must run again on the next attempt.
     * DIVERGENT and ABORTED are treated as failures too — the reconciler
     * cannot trust the outcome and must surface the failure.
     */
    val isFailure: Boolean
        get() = status == OperationStatus.FAILED ||
            status == OperationStatus.FAILED_TIMEOUT ||
            status == OperationStatus.LOST ||
            status == OperationStatus.DIVERGENT ||
            status == OperationStatus.ABORTED
}
