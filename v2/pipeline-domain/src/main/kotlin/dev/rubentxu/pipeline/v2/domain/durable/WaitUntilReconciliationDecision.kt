package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Sealed ADT returned by [WaitUntilReconciler.reconcile].
 *
 * Design §14.3 — every reconciliation outcome is one of these variants, each
 * carrying the minimum evidence a downstream caller needs to act. There are
 * NO nullable booleans, no `attempt: Int?` flag bags. The variant IS the state.
 *
 * ## Variants
 * - [ScheduleAttempt] — execute the body as a fresh poll attempt at [attempt].
 *   The journal MUST persist the control row BEFORE any child effect is launched.
 * - [ResumeAttempt] — re-attach to an in-flight attempt [attempt] whose child
 *   journal evidence is mid-run. NO new child is launched.
 * - [AdvanceAfterPredicateSatisfied] — predicate was true; close the aggregate
 *   as success without scheduling any new child execution.
 * - [AdvanceAfterPredicateUnsatisfied] — predicate was false; advance to the
 *   next poll with [nextBackoffMs] backoff. The journal persists the transition
 *   BEFORE the delay is entered.
 * - [DeadlineExceeded] — the backoff ceiling was reached; terminate with failure.
 * - [Aborted] — waitUntil was explicitly aborted by the caller.
 * - [RejectDivergence] — durable state is ambiguous or contract-divergent.
 *   ZERO child execution. The caller MUST surface this as a step failure.
 *
 * ## Backoff discipline
 * Unlike the retry aggregate (which counts attempts), waitUntil counts polls
 * with exponential backoff toward a [maxBackoffMs] ceiling. The reconciler
 * computes the next backoff from the persisted control rows so a second binary
 * invocation can resume from the correct backoff state.
 */
sealed interface WaitUntilReconciliationDecision {

    /** Execute the body as a fresh poll attempt at [attempt]. */
    data class ScheduleAttempt(val attempt: Int) : WaitUntilReconciliationDecision

    /** Re-attach to an in-flight attempt [attempt] without launching a new child. */
    data class ResumeAttempt(val attempt: Int) : WaitUntilReconciliationDecision

    /**
     * Predicate was true at attempt [attempt]; close the aggregate as success
     * without scheduling any new child execution.
     */
    data class AdvanceAfterPredicateSatisfied(val attempt: Int) : WaitUntilReconciliationDecision

    /**
     * Predicate was false at attempt [attempt]; advance to the next poll with
     * [nextBackoffMs] backoff. The journal persists this transition BEFORE
     * the delay is entered.
     */
    data class AdvanceAfterPredicateUnsatisfied(
        val attempt: Int,
        val nextBackoffMs: Long,
    ) : WaitUntilReconciliationDecision

    /** The backoff ceiling was reached at attempt [attempt]; terminate with failure. */
    data class DeadlineExceeded(val attempt: Int) : WaitUntilReconciliationDecision

    /**
     * WaitUntil was explicitly aborted by the caller.
     */
    data class Aborted(
        val operationId: String,
        val reason: String,
    ) : WaitUntilReconciliationDecision

    /**
     * Fail closed. Durable state is ambiguous, contract-divergent, or unsafe
     * to act on. ZERO child execution. The caller must surface this as a step
     * failure carrying the same durable status implied by the rejected state.
     */
    data class RejectDivergence(
        val operationId: String,
        val reason: String,
    ) : WaitUntilReconciliationDecision
}
