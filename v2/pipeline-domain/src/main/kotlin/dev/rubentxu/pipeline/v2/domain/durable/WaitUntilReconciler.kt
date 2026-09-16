package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.Aborted
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.DeadlineExceeded
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision.ScheduleAttempt

/**
 * Pure reconciler for waitUntil predicate polling state.
 *
 * Mirrors [RetryReconciler] but for the waitUntil polling loop:
 * - No fixed attempt budget; polls with exponential backoff toward a ceiling.
 * - Control rows carry the backoff state at persistence time.
 * - Aggregate terminates on predicate satisfaction or backoff ceiling.
 *
 * The function takes only pure value snapshots ([WaitUntilReconciliationInput])
 * and returns only pure decisions ([WaitUntilReconciliationDecision]).
 * It owns ZERO effects: no journal writes, no clock, no child dispatch, no logging.
 */
object WaitUntilReconciler {

    /**
     * Reconcile the durable waitUntil state into exactly one
     * [WaitUntilReconciliationDecision].
     */
    fun reconcile(input: WaitUntilReconciliationInput): WaitUntilReconciliationDecision {
        // Fingerprint gate — fail closed before any effect.
        for (row in input.controlRows) {
            if (row.fingerprint != input.currentFingerprint) {
                return RejectDivergence(
                    operationId = input.controlIdentity.operationId,
                    reason = "control fingerprint mismatch at attempt ${row.attempt}",
                )
            }
        }

        // No control rows: fresh waitUntil (W0).
        if (input.controlRows.isEmpty()) {
            return ScheduleAttempt(1)
        }

        // Process attempts in attempt-ordinal order, deduped.
        val byAttempt = input.controlRows
            .groupBy { it.attempt }
            .mapValues { (_, rows) -> rows.first() }

        val maxPersistedAttempt = byAttempt.keys.maxOrNull() ?: 0

        for (attempt in 1..maxPersistedAttempt) {
            val control = byAttempt[attempt] ?: continue

            // Supersede-skip (mirrors RetryReconciler): a terminal attempt with
            // a successor in the control rows has already been "advanced past".
            // Skip it so the planner reaches the active attempt.
            if ((control.status.isPollFailure || control.status.isTerminal) &&
                byAttempt.containsKey(attempt + 1)
            ) {
                continue
            }

            when (control.status) {
                OperationStatus.SUCCEEDED -> {
                    // Predicate satisfied at this attempt.
                    return AdvanceAfterPredicateSatisfied(attempt)
                }

                OperationStatus.ABORTED -> {
                    return Aborted(
                        operationId = input.controlIdentity.operationId,
                        reason = "waitUntil aborted at attempt $attempt",
                    )
                }

                OperationStatus.DIVERGENT,
                OperationStatus.LOST -> {
                    return RejectDivergence(
                        operationId = input.controlIdentity.operationId,
                        reason = "control row at attempt $attempt is ${control.status}",
                    )
                }

                OperationStatus.FAILED_TIMEOUT -> {
                    // The dispatch loop set FAILED_TIMEOUT when the NEXT backoff would
                    // have exceeded the ceiling. This attempt IS the one that hit the
                    // ceiling: DeadlineExceeded(attempt) (terminal).
                    return DeadlineExceeded(attempt)
                }

                OperationStatus.FAILED -> {
                    // Predicate unsatisfied. The attempt used control.currentBackoffMs.
                    // Compute the next backoff from the current backoff.
                    val nextBackoff = computeNextBackoff(
                        currentBackoffMs = control.currentBackoffMs,
                        initialRecurrencePeriodMs = input.initialRecurrencePeriodMs,
                        maxBackoffMs = input.maxBackoffMs,
                    )
                    if (nextBackoff > input.maxBackoffMs) {
                        // The NEXT attempt would exceed the ceiling.
                        return DeadlineExceeded(attempt + 1)
                    }
                    // Advance to next poll.
                    return AdvanceAfterPredicateUnsatisfied(
                        attempt = attempt + 1,
                        nextBackoffMs = nextBackoff,
                    )
                }

                OperationStatus.RUNNING,
                OperationStatus.PENDING -> {
                    // Resume this in-flight attempt.
                    return ResumeAttempt(attempt)
                }

                else -> {
                    return RejectDivergence(
                        operationId = input.controlIdentity.operationId,
                        reason = "unknown control status ${control.status} at attempt $attempt",
                    )
                }
            }
        }

        // All recorded attempts exhausted: schedule the next poll.
        // Start from the initial recurrence period if no rows exist,
        // or from the last persisted backoff if rows exist.
        val lastBackoff = input.controlRows.maxByOrNull { it.attempt }?.currentBackoffMs
            ?: input.initialRecurrencePeriodMs
        return ScheduleAttempt(maxPersistedAttempt + 1)
    }

    /**
     * Compute the next backoff from the current backoff.
     *
     * Exponential backoff: double the current backoff, cap at [maxBackoffMs].
     * On the first advance from a zero backoff, use [initialRecurrencePeriodMs].
     */
    private fun computeNextBackoff(
        currentBackoffMs: Long,
        initialRecurrencePeriodMs: Long,
        maxBackoffMs: Long,
    ): Long {
        val base = if (currentBackoffMs == 0L) initialRecurrencePeriodMs else currentBackoffMs
        val next = base * 2
        return minOf(next, maxBackoffMs)
    }
}
