package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciliationInput
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilReconciler

/**
 * Driver that ties together the durable control journal and the pure
 * [WaitUntilReconciler] into a single decision point.
 *
 * WU-G5R.5 — mirrors [RetryReconciliationDriver].
 *
 * The driver is the THINKER, not the WRITER:
 *  1. Reads the durable state for the given waitUntil logical invocation;
 *  2. Builds a [WaitUntilReconciliationInput];
 *  3. Calls the pure [WaitUntilReconciler] and returns the
 *     [WaitUntilReconciliationDecision].
 *
 * The driver MUST NOT mutate the journal. The caller (the dispatch loop) is
 * the single writer — it persists the plan BEFORE launching any child effect,
 * and only the driver plans.
 *
 * ## Plan-only contract
 * [plan] is a pure function from the durable state to a decision.
 */
class WaitUntilReconciliationDriver(
    private val journal: WaitUntilControlJournal,
    private val controlOpId: String,
    private val fingerprint: Fingerprint,
    private val initialRecurrencePeriodMs: Long,
    private val maxBackoffMs: Long,
) {
    /**
     * Plan-only reconciliation. Pure: returns the
     * [WaitUntilReconciliationDecision] without mutating the journal.
     */
    fun plan(): WaitUntilReconciliationDecision {
        val state = journal.readState(
            controlOpId = controlOpId,
            initialRecurrencePeriodMs = initialRecurrencePeriodMs,
            maxBackoffMs = maxBackoffMs,
            currentFingerprint = fingerprint,
        )
        return WaitUntilReconciler.reconcile(
            WaitUntilReconciliationInput(
                controlIdentity = WaitUntilControlIdentity(operationId = controlOpId),
                initialRecurrencePeriodMs = initialRecurrencePeriodMs,
                maxBackoffMs = maxBackoffMs,
                currentFingerprint = fingerprint,
                controlRows = state.controlRows,
            ),
        )
    }
}
