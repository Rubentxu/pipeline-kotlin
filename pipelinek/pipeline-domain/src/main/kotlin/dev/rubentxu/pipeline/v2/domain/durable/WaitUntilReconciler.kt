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
 * Mirrors [RetryReconciler] but for the waitUntil polling loop:
 * - No fixed attempt budget; polls with exponential backoff toward a ceiling.
 * - Control rows carry the backoff state at persistence time.
 * - The backoff ceiling IS the deadline. A poll whose interval has reached the
 *   ceiling is the LAST poll: if the condition still has not held, the wait
 *   ends there with [WaitUntilReconciliationDecision.DeadlineExceeded]. There
 *   is no separate wall-clock deadline in this aggregate, so the wait's
 *   duration is the sum of the ladder of intervals, not the ceiling itself.
 * - This is the SAME rule the inline polling loop applies, and it is stated
 *   once, here, in the only place that decides it.
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

        // No control rows: fresh waitUntil
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
                    // The row at [attempt] is the evidence. Carrying the number the
                    // decision was made at is what lets the engine stay read-only.
                    return Aborted(
                        operationId = input.controlIdentity.operationId,
                        attempt = attempt,
                        reason = "waitUntil aborted",
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
                    // The engine persisted FAILED_TIMEOUT on the poll whose
                    // interval had reached the ceiling — the last poll. This
                    // attempt IS that poll, so it is the one the terminal names.
                    // The row can be read back after a crash that happened
                    // between the persistence and the return, which is why
                    // this branch exists at all.
                    return DeadlineExceeded(attempt)
                }

                OperationStatus.FAILED -> {
                    // Predicate unsatisfied at this attempt. The attempt ran with
                    // `control.currentBackoffMs` as its poll interval.
                    //
                    // THE CEILING IS THE DEADLINE. A poll whose interval has
                    // reached the ceiling is the LAST poll: if the condition
                    // still has not held, the wait ends here. The inline
                    // polling loop applies the same rule
                    // (`currentBackoffMs >= maxBackoffMs`), and this is the
                    // only place that decides it.
                    //
                    // The previous code computed the NEXT interval and asked
                    // whether IT would exceed the ceiling. It never could:
                    // `computeNextBackoff` caps the next interval AT the
                    // ceiling, so `next > ceiling` could not be true. The
                    // branch was unreachable, and with it the durable wait had
                    // NO terminator at all: a condition that never held
                    // polled forever and the terminal the algebra already
                    // declares was never reached.
                    if (control.currentBackoffMs >= input.maxBackoffMs) {
                        return DeadlineExceeded(attempt)
                    }
                    // Advance to the next poll, one interval further down the
                    // ladder.
                    return AdvanceAfterPredicateUnsatisfied(
                        attempt = attempt + 1,
                        nextBackoffMs = computeNextBackoff(
                            currentBackoffMs = control.currentBackoffMs,
                            initialRecurrencePeriodMs = input.initialRecurrencePeriodMs,
                            maxBackoffMs = input.maxBackoffMs,
                        ),
                    )
                }

                OperationStatus.RUNNING,
                OperationStatus.PENDING -> {
                    // Resume this in-flight attempt.
                    return ResumeAttempt(attempt)
                }

                OperationStatus.UNSTABLE -> {
                    // The waitUntil engine resolves an unstable body to
                    // `WaitUntilPredicateOutcome.Failed`, so a waitUntil control row is not a state
                    // this engine produces. Fail closed rather than treat a known status as
                    // unknown: if it ever IS persisted, rejecting it as a divergence is the safe
                    // reading, and saying "unstable" beats a generic "unknown status".
                    return RejectDivergence(
                        operationId = input.controlIdentity.operationId,
                        reason = "waitUntil control row is UNSTABLE at attempt $attempt, " +
                            "which this engine does not produce",
                    )
                }

                OperationStatus.ABORTED,
                OperationStatus.DIVERGENT,
                OperationStatus.LOST,
                OperationStatus.FAILED_TIMEOUT,
                -> {
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
     * Exponential backoff: double the current backoff, cap at [maxBackoffMs].
     * On the first advance from a zero backoff, use [initialRecurrencePeriodMs].
     *
     * The ceiling is NOT decided here: the caller compares the CURRENT
     * interval against [maxBackoffMs] before reaching this function, and this
     * one only computes the interval of the NEXT poll. That is why the cap is
     * a `minOf` and not a refusal — the next interval is the ceiling at most,
     * never above it, so a poll's interval can REACH the ceiling and never
     * pass it. The old code asked this function's result whether it exceeded
     * the ceiling, and that question could not have an answer.
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
