package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt

/**
 * Pure reconciler for retry control state.
 *
 * ADR-0075 — authoritative spec for durable retry reconciliation.
 * The function takes only pure value snapshots
 * ([RetryReconciliationInput]) and returns only pure decisions
 * ([RetryReconciliationDecision]). It owns ZERO effects: no journal
 * writes, no clock, no child dispatch, no logging. The journal adapter
 * writes the decision; the coordinator dispatches the body.
 *
 * ## Crash-window semantics (ADR-0075 §7)
 *
 * | Window | Control row | Child row | Decision |
 * |--------|-------------|-----------|----------|
 * | W0     | absent      | absent    | [ScheduleAttempt](1) |
 * | W1     | present (PENDING/RUNNING) | absent | [ScheduleAttempt](attempt) |
 * | W2     | RUNNING     | RUNNING   | [ResumeAttempt](attempt) |
 * | W3     | stale       | child FAILED | [AdvanceAfterFailure] or [ReuseFailure] |
 * | W4/WindowC | stale  | child SUCCEEDED | [CloseSuccessFromChild](attempt) |
 * | W5     | terminal    | (any)     | [ReuseSuccess]/[ReuseFailure] |
 *
 * ## Terminality law (ADR-0075 §10)
 * Once a control row reaches a terminal status, it is final. The
 * reconciler never schedules a new child for that attempt — only
 * reuses the outcome.
 *
 * ## Fingerprint law (ADR-0075 §9)
 * If any control row fingerprint differs from the current retry
 * contract fingerprint, the reconciler fails closed with
 * [RejectDivergence]. No child is launched.
 *
 * ## Single-writer law (ADR-0075 §11)
 * Only the writer component MAY mutate control rows. The reconciler
 * only reads. This keeps the durable spine deterministic and avoids
 * the multi-writer race that produced E-EM-11 T1.
 */
object RetryReconciler {

    /**
     * Reconcile the durable retry state into exactly one
     * [RetryReconciliationDecision].
     */
    fun reconcile(input: RetryReconciliationInput): RetryReconciliationDecision {
        // Fingerprint gate (ADR-0075 §9) — fail closed before any effect.
        for (c in input.controlRows) {
            if (c.fingerprint != input.currentFingerprint) {
                return RejectDivergence(
                    operationId = input.controlIdentity.id(),
                    reason = "control fingerprint mismatch at attempt ${c.attempt}",
                )
            }
        }

        // Aggregate terminal reuse (W5 / ADR-0075 §10) — terminal means final.
        for (c in input.controlRows) {
            if (c.status == OperationStatus.SUCCEEDED) {
                return ReuseSuccess(c.attempt)
            }
            if (c.status.isFailureForRetry()) {
                return ReuseFailure(c.attempt)
            }
            if (c.status.isTerminal) {
                // ABORTED / DIVERGENT / LOST recorded as terminal control rows are reused
                // as a retry-side failure: the retry cannot make progress on that attempt.
                return ReuseFailure(c.attempt)
            }
        }

        // No control rows: legacy compat (ADR-0075 §8) or fresh retry (W0).
        if (input.controlRows.isEmpty()) {
            return reconcileLegacyOrFresh(input)
        }

        // Process attempts in attempt-ordinal order, deduped.
        val byAttempt = input.controlRows
            .groupBy { it.attempt }
            .mapValues { (_, rows) -> rows.first() }

        val maxPersistedAttempt = byAttempt.keys.max()
        for (attempt in 1..maxPersistedAttempt) {
            val control = byAttempt[attempt] ?: continue
            val children = input.childrenByAttempt[attempt].orEmpty()

            // W1 — control persisted but no child evidence yet: schedule that attempt.
            if (children.isEmpty()) {
                return ScheduleAttempt(attempt)
            }

            val inFlight = children.firstOrNull { !it.status.isTerminal }
            if (inFlight != null) {
                // W2 — child evidence is mid-run: resume that attempt, do not re-schedule.
                return ResumeAttempt(attempt)
            }

            val success = children.firstOrNull { it.isSuccess }
            if (success != null) {
                // W4 / Window C — child journal proves success.
                return CloseSuccessFromChild(attempt)
            }

            // Every child is terminal non-success: this attempt failed for retry purposes.
            if (attempt >= input.maxAttempts) {
                return ReuseFailure(attempt)
            }
            return AdvanceAfterFailure(from = attempt, to = attempt + 1)
        }

        // All recorded attempts are exhausted above maxAttempts — treated as terminal failure.
        return ReuseFailure(maxPersistedAttempt)
    }

    /**
     * Legacy compatibility branch (ADR-0075 §8).
     *
     * Effective in two cases:
     *  1. Fresh retry with no persisted control row (W0 / first launch).
     *  2. Pre-ADR-0075 retries whose child rows pre-date the control row
     *     schema.
     *
     * Policy A (safe reconstruction) applies only when child evidence is
     * unambiguous AND corresponds to a single retry attempt:
     *  - Exactly one attempt ordinal present, and that attempt's only
     *    child terminated with success → reconstruct aggregate success.
     *
     * Policy B (reject) applies in every other legacy case:
     *  - Multi-attempt history without a control row (we cannot trust
     *    that more attempts were not scheduled).
     *  - Single-attempt legacy child failure (we cannot tell whether a
     *    higher attempt was already in flight when the failure was
     *    recorded).
     *  - Pre-ADR-0075 child entries with ambiguous status ordering.
     *
     * In every Policy B case the reconciler returns
     * [RejectDivergence] so the coordinator surfaces a step failure
     * rather than silently re-executing the body (which would be the
     * E-EM-11 T1 bug class).
     */
    private fun reconcileLegacyOrFresh(input: RetryReconciliationInput): RetryReconciliationDecision {
        // Merge the post-ADR child row bucket with the pre-ADR legacy bucket.
        // When no control row exists we treat ANY child evidence uniformly.
        val legacy = (input.childrenByAttempt.values.flatten() + input.preControlChildren)
        if (legacy.isEmpty()) {
            return ScheduleAttempt(1)
        }

        val byAttempt = legacy.groupBy { it.attempt }
        if (byAttempt.size > 1) {
            return RejectDivergence(
                operationId = input.controlIdentity.id(),
                reason = "legacy: multi-attempt child history without control row is ambiguous",
            )
        }

        val (attemptOrdinal, children) = byAttempt.entries.first()
        val success = children.firstOrNull { it.isSuccess }
        val failure = children.firstOrNull { it.isFailure }
        val anyInFlight = children.any { !it.status.isTerminal }

        if (success != null && failure == null && !anyInFlight) {
            return CloseSuccessFromChild(attemptOrdinal)
        }

        return RejectDivergence(
            operationId = input.controlIdentity.id(),
            reason = "legacy: child history without control row is ambiguous " +
                "(success=$success, failure=$failure, inFlight=$anyInFlight)",
        )
    }
}

/**
 * Internal helper: a terminal status counts as a retry-side failure if it
 * is not success. Used by the W5 reuse branch.
 */
private fun OperationStatus.isFailureForRetry(): Boolean = when (this) {
    OperationStatus.SUCCEEDED -> false
    OperationStatus.PENDING,
    OperationStatus.RUNNING -> false
    else -> true // FAILED, FAILED_TIMEOUT, ABORTED, DIVERGENT, LOST
}

/**
 * Internal helper: terminal status detection. The detail lives here so the
 * [OperationStatus] enum does not have to grow a domain-specific computed
 * property.
 */
private val OperationStatus.isTerminal: Boolean
    get() = when (this) {
        OperationStatus.SUCCEEDED,
        OperationStatus.FAILED,
        OperationStatus.FAILED_TIMEOUT,
        OperationStatus.ABORTED,
        OperationStatus.DIVERGENT,
        OperationStatus.LOST -> true
        OperationStatus.PENDING,
        OperationStatus.RUNNING -> false
    }
