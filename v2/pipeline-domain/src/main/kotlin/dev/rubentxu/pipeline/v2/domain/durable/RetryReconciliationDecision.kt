package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Sealed ADT returned by [RetryReconciler.reconcile].
 *
 * ADR-0075 §3 + §4 — every reconciliation outcome is one of these
 * variants, each carrying the minimum evidence a downstream caller
 * needs to act. There are NO nullable booleans, no `failed: Boolean?`,
 * no `attempt: Int?` flag bags. The variant IS the state.
 *
 * ## Variants
 * - [ScheduleAttempt] — execute the body as a fresh attempt at [attempt].
 *   Only one writer must persist the control row BEFORE any child is
 *   launched. (ADR-0075 §6.)
 * - [ResumeAttempt] — re-attach to an in-flight attempt [attempt] whose
 *   child journal evidence is mid-run. NO new child is launched; the
 *   canonical child dispatch resumes.
 * - [CloseSuccessFromChild] — child evidence from a still-attached
 *   attempt proves success. The aggregate becomes terminal success
 *   for the retry; ZERO new child executions are scheduled.
 * - [AdvanceAfterFailure] — attempt [from] failed; advance to [to].
 *   The writer persists this transition BEFORE launching any new child.
 * - [ReuseSuccess] — aggregate succeeded at [attempt]. No child
 *   execution. The journal row is the source of truth.
 * - [ReuseFailure] — aggregate failed at [attempt] (terminal exhaustion).
 *   No child execution.
 * - [RejectDivergence] — durable state is ambiguous or contract-divergent.
 *   ZERO child execution. The caller MUST surface this as a step failure
 *   with the same status the durable state implied.
 */
sealed interface RetryReconciliationDecision {

    /** Execute the body as a fresh attempt at [attempt]. */
    data class ScheduleAttempt(val attempt: Int) : RetryReconciliationDecision

    /** Re-attach to an in-flight attempt [attempt] without launching a new child. */
    data class ResumeAttempt(val attempt: Int) : RetryReconciliationDecision

    /**
     * Window C (ADR-0075 §7) — child evidence at attempt [attempt]
     * proves success; close the aggregate as success without scheduling
     * any new child execution.
     */
    data class CloseSuccessFromChild(val attempt: Int) : RetryReconciliationDecision

    /**
     * Attempt [from] failed with retry budget remaining; advance to
     * [to]. The writer persists the new control row before launching
     * any child.
     */
    data class AdvanceAfterFailure(val from: Int, val to: Int) : RetryReconciliationDecision

    /** Aggregate succeeded at [attempt]. No new child execution. */
    data class ReuseSuccess(val attempt: Int) : RetryReconciliationDecision

    /** Aggregate failed at [attempt] (terminal exhaustion). No new child execution. */
    data class ReuseFailure(val attempt: Int) : RetryReconciliationDecision

    /**
     * Fail closed. Durable state is ambiguous, contract-divergent, or
     * unsafe to act on. ZERO child execution. The caller must surface
     * this as a step failure carrying the same durable status implied
     * by the rejected state.
     */
    data class RejectDivergence(
        val operationId: String,
        val reason: String,
    ) : RetryReconciliationDecision
}
