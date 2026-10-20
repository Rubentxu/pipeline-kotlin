package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt

/**
 * Pure reconciler for retry control state.
 * Authoritative spec for durable retry reconciliation.
 * The function takes only pure value snapshots
 * ([RetryReconciliationInput]) and returns only pure decisions
 * ([RetryReconciliationDecision]). It owns ZERO effects: no journal
 * writes, no clock, no child dispatch, no logging. The journal adapter
 * writes the decision; the coordinator dispatches the body.
 * ## Crash-window semantics
 * | Window | Control row | Child row | Decision |
 * |--------|-------------|-----------|----------|
 * | | absent | absent | [ScheduleAttempt](1) |
 * | | present (PENDING/RUNNING) | absent | [ScheduleAttempt](attempt) |
 * | | RUNNING | RUNNING | [ResumeAttempt](attempt) |
 * | | stale | child FAILED | [AdvanceAfterFailure] or [ReuseFailure] |
 * |/WindowC | stale | child SUCCEEDED | [CloseSuccessFromChild](attempt) |
 * | | terminal | (any) | [ReuseSuccess]/[ReuseFailure] |
 * ## Terminality law
 * Once a control row reaches a terminal status, it is final. The
 * reconciler never schedules a new child for that attempt — only
 * reuses the outcome.
 * ## Fingerprint law
 * If any control row fingerprint differs from the current retry
 * contract fingerprint, the reconciler fails closed with
 * [RejectDivergence]. No child is launched.
 * ## Single-writer law
 * Only the writer component MAY mutate control rows. The reconciler
 * only reads. This keeps the durable spine deterministic and avoids
 * The multi-writer race that produced
*/
object RetryReconciler {

    /**
     * Reconcile the durable retry state into exactly one
     * [RetryReconciliationDecision].
*/
    fun reconcile(input: RetryReconciliationInput): RetryReconciliationDecision {
        // Fail closed before any effect.
        for (c in input.controlRows) {
            if (c.fingerprint != input.currentFingerprint) {
                return RejectDivergence(
                    operationId = input.controlIdentity.id(),
                    reason = "control fingerprint mismatch at attempt ${c.attempt}",
                )
            }
        }

        // Aggregate terminal reuse— terminal means FINAL.
        // The reuse check fires only when
        // a terminal control row can be unambiguously identified as the FINAL
        // terminal state of the retry aggregate. Two distinct cases:
        //  - Latest persisted row is SUCCEEDED → ReuseSuccess at that row's
        //    ordinal (covers replay).
        //  - Latest terminal-failure row's ordinal is >= maxAttempts → the
        //    retry has truly exhausted its budget → ReuseFailure. Earlier
        //    terminal-failure rows are ignored because a fresh dispatch may
        //    Attempt 1 FAIL + attempt 2
        //    RUNNING must NOT collapse to ReuseFailure(1)). This also covers
        //    Where the writer forced an attempt N+1 row — the planner
        //    returns ReuseFailure(N) instead of scheduling N+1.
        val latest = input.controlRows.maxByOrNull { it.attempt }
        if (latest != null && latest.status == OperationStatus.SUCCEEDED) {
            return ReuseSuccess(latest.attempt)
        }
        if (latest != null && latest.status == OperationStatus.UNSTABLE) {
            // An unstable attempt is a COMPLETED attempt that fresh retry semantics
            // surface immediately — it never advances and never consumes the budget, so the
            // restart answers the same fact instead of exhausting or re-running.
            return ReuseUnstable(latest.attempt)
        }
        // Terminal-failure exhaustion. UNSTABLE is deliberately excluded: it is a completion
        //, not a poll failure, and counting it here would let a completed-unstable
        // attempt push the aggregate to ReuseFailure by ordinal arithmetic alone.
        val maxTerminalOrdinal = input.controlRows
            .filter {
                it.status.isPollFailure ||
                    (it.status.isTerminal && it.status != OperationStatus.UNSTABLE)
            }
            .maxOfOrNull { it.attempt }
        if (maxTerminalOrdinal != null && maxTerminalOrdinal >= input.maxAttempts) {
            return ReuseFailure(maxTerminalOrdinal)
        }

        // No control rows: legacy compat or fresh retry
        if (input.controlRows.isEmpty()) {
            return reconcileLegacyOrFresh(input)
        }

        // Process attempts in attempt-ordinal order, deduped.
        val byAttempt = input.controlRows
            .groupBy { it.attempt }
            .mapValues { (_, rows) -> rows.first() }

        val maxPersistedAttempt = byAttempt.keys.maxOrNull() ?: 0
        for (attempt in 1..maxPersistedAttempt) {
            val control = byAttempt[attempt] ?: continue
            // A terminal attempt with a successor in the control rows has
            // already been "advanced past" by the dispatch loop. Skip it so
            // the planner reaches the active attempt (e.g. attempt 2 RUNNING)
            // and returns ResumeAttempt, not another AdvanceAfterFailure.
            if ((control.status.isPollFailure || control.status.isTerminal) &&
                byAttempt.containsKey(attempt + 1)
            ) {
                continue
            }
            val rawChildren = input.childrenByAttempt[attempt].orEmpty()
            // Synthetic PENDING rows emitted by the production reader for
            // (attempt, childIndex) positions absent from the OperationJournal
            // are placeholders, NOT real evidence. Their meaning depends on
            // the control row status:
            //   - control RUNNING/PENDING + synthetic only → writer is
            //     mid-execution, journal not yet written → ResumeAttempt.
            //   - control FAIL/terminal + synthetic only → no child evidence,
            //     writer finished as failure → advance or exhaust.
            val realChildren = rawChildren.filterNot {
                it.fingerprint == null && it.status == OperationStatus.PENDING
            }
            val hasSyntheticPending = realChildren.size != rawChildren.size

            if (control.status == OperationStatus.SUCCEEDED) {
                // Control row says success; trust it
                return CloseSuccessFromChild(attempt)
            }

            if (control.status == OperationStatus.UNSTABLE) {
                // The control row says the attempt COMPLETED unstable; trust it exactly
                // like the SUCCEEDED row above, for the same reason the writer is the
                // authority: the restart reports the fact, it does not re-run or advance.
                return ReuseUnstable(attempt)
            }

            if (control.status.isPollFailure || control.status.isTerminal) {
                // Control row is terminal failure (/ advance / exhaustion)
                if (realChildren.isEmpty()) {
                    // No real child evidence: advance to the next attempt if
                    // the retry budget allows it; otherwise exhaust.
                    if (attempt >= input.maxAttempts) return ReuseFailure(attempt)
                    return AdvanceAfterFailure(from = attempt, to = attempt + 1)
                }
                val inFlight = realChildren.firstOrNull { !it.status.isTerminal }
                if (inFlight != null) {
                    // Should not normally happen: control FAIL but child
                    // in-flight. Trust the child as mid-execution.
                    return ResumeAttempt(attempt)
                }
                val success = realChildren.firstOrNull { it.isSuccess }
                if (success != null) {
                    // Window C variant: control FAIL stale + child SUCCEEDED.
                    return CloseSuccessFromChild(attempt)
                }
                // Every real child is terminal non-success.
                if (attempt >= input.maxAttempts) return ReuseFailure(attempt)
                return AdvanceAfterFailure(from = attempt, to = attempt + 1)
            }

            // Control row is RUNNING / PENDING (non-terminal).
            if (realChildren.isEmpty()) {
                if (hasSyntheticPending) {
                    // Writer is mid-execution; journal not yet written.
                    return ResumeAttempt(attempt)
                }
                // Control persisted but no child evidence at all:
                // schedule that attempt.
                return ScheduleAttempt(attempt)
            }

            val inFlight = realChildren.firstOrNull { !it.status.isTerminal }
            if (inFlight != null) {
                // Real child evidence is mid-run: resume that attempt.
                return ResumeAttempt(attempt)
            }

            val success = realChildren.firstOrNull { it.isSuccess }
            if (success != null) {
                // Child journal proves success
                return CloseSuccessFromChild(attempt)
            }

            // A child journal that says UNSTABLE is deeper evidence than the stale
            // RUNNING control row (same window as the child-success case above): the body
            // completed unstable and the crash happened before the control row was written.
            val unstableChild = realChildren.firstOrNull { it.status == OperationStatus.UNSTABLE }
            if (unstableChild != null) {
                return ReuseUnstable(attempt)
            }

            // Every real child is terminal non-success under a non-terminal
            // control row: trust the children's failure and advance/exhaust.
            if (attempt >= input.maxAttempts) return ReuseFailure(attempt)
            return AdvanceAfterFailure(from = attempt, to = attempt + 1)
        }

        // All recorded attempts exhausted above maxAttempts — terminal failure.
        return ReuseFailure(maxPersistedAttempt)
    }

    /**
     * Legacy compatibility branch.
     * Effective in two cases:
     * 1. Fresh retry with no persisted control row (/ first launch)
     *  2. Retries whose child rows pre-date the control row
     *     schema.
     * Policy A (safe reconstruction) applies only when child evidence is
     * unambiguous AND corresponds to a single retry attempt:
     *  - Exactly one attempt ordinal present, and that attempt's only
     *    child terminated with success → reconstruct aggregate success.
     * Policy B (reject) applies in every other legacy case:
     *  - Multi-attempt history without a control row (we cannot trust
     *    that more attempts were not scheduled).
     *  - Single-attempt legacy child failure (we cannot tell whether a
     *    higher attempt was already in flight when the failure was
     *    recorded).
     * Child entries with ambiguous status ordering
     * In every Policy B case the reconciler returns
     * [RejectDivergence] so the coordinator surfaces a step failure
     * rather than silently re-executing the body (which would be the
     * bug class).
*/
    private fun reconcileLegacyOrFresh(input: RetryReconciliationInput): RetryReconciliationDecision {
        // Merge the child row bucket with the legacy bucket.
        // When no control row exists we treat ANY child evidence uniformly.
        // Rows emitted by the production reader as
        // synthetic `Missing (PENDING)` placeholders carry `fingerprint = null`
        // and `status = PENDING`. These represent "no OperationJournal row
        // exists for this position" — NOT evidence from an older format. Without this
        // filter a fresh retry (empty journal + empty control row) would be
        // misclassified as ambiguous legacy data with multi-attempt history and
        // Rejected with `RejectDivergence`, breaking / first-launch semantics
        val legacy = (input.childrenByAttempt.values.flatten() + input.preControlChildren)
            .filterNot { it.fingerprint == null && it.status == OperationStatus.PENDING }
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

// Extensions removed — isPollFailure and isTerminal are now shared members of OperationStatus.





