package dev.rubentxu.pipeline.v2.application.durable.retry

import dev.rubentxu.pipeline.v2.application.durable.FileBasedRetryControlJournal
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlIdentity
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationInput
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciler

/**
 * Driver that ties together the durable control journal and the pure
 * [RetryReconciler] into a single decision point.
 *
 * ADR-0075 §4, §6, §7 — the driver is the THINKER, not the WRITER. It:
 *  1. Reads the durable state for the given retry logical invocation;
 *  2. Builds a [RetryReconciliationInput];
 *  3. Calls the pure [RetryReconciler] and returns the
 *     [RetryReconciliationDecision].
 *
 * The driver MUST NOT mutate the journal directly. The caller (the
 * dispatch loop in
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator])
 * is the single writer (ADR-0075 §11) — it persists the plan BEFORE
 * launching any child effect, and only the driver plans.
 *
 * ## Plan-only contract
 * [plan] is a pure function from the durable state to a decision. The
 * test [RetryReconciliationDriverTest] verifies that the planner does NOT
 * mutate the journal between reads. The caller (dispatch loop) is the
 * component that calls `beginAttempt` and `updateStatus` based on the
 * returned decision.
 *
 * ## Identity anchoring
 * The driver is bound to a specific retry logical invocation via the
 * [RetryControlIdentity] (operationId + schemaVersion). The Reconciler
 * uses the operationId to populate the [RetryReconciliationDecision.RejectDivergence]
 * diagnostics.
 *
 * ## Fingerprint anchoring
 * [fingerprint] is the retry contract fingerprint computed at the dispatch
 * site (parent bodyPath + maxAttempts + body digest). Stored and read
 * attempts are compared against this value by the Reconciler (ADR-0075 §9).
 */
class RetryReconciliationDriver(
    private val journal: FileBasedRetryControlJournal,
    private val identity: RetryControlIdentity,
    private val controlOpId: String,
    private val parentBodyPath: List<BlockSegment>,
    private val fingerprint: Fingerprint,
    private val maxAttempts: Int,
    private val runId: String,
    private val stageIndex: Int,
    private val stepIndex: Int,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(runId.isNotBlank()) { "runId must not be blank" }
    }

    /**
     * Plan-only reconciliation. Pure: returns the
     * [RetryReconciliationDecision] without mutating the journal.
     *
     * The dispatch loop is expected to:
     *  - On [RetryReconciliationDecision.ScheduleAttempt]: persist the
     *    control row BEFORE launching the child effect.
     *  - On [RetryReconciliationDecision.ResumeAttempt]: launch no new
     *    child effect; the existing RUNNING child will resume via the
     *    canonical child dispatch.
     *  - On [RetryReconciliationDecision.CloseSuccessFromChild]: persist
     *    the control row SUCCEEDED and stop.
     *  - On [RetryReconciliationDecision.AdvanceAfterFailure]: persist
     *    the next control row PENDING and loop.
     *  - On [RetryReconciliationDecision.ReuseSuccess] /
     *    [RetryReconciliationDecision.ReuseFailure]: no new work; the
     *    aggregate is already terminal.
     *  - On [RetryReconciliationDecision.RejectDivergence]: surface as
     *    a step failure with the reason carried by the variant.
     */
    fun plan(): RetryReconciliationDecision {
        val state = journal.readState(
            controlOpId = controlOpId,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            maxAttempts = maxAttempts,
            currentFingerprint = fingerprint,
        )
        return RetryReconciler.reconcile(
            RetryReconciliationInput(
                controlIdentity = identity,
                maxAttempts = maxAttempts,
                currentFingerprint = fingerprint,
                controlRows = state.controlRows,
                childrenByAttempt = state.childrenByAttempt,
                preControlChildren = state.preControlChildren,
            ),
        )
    }
}
