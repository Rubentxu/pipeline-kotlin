package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The retry reconciler decides what happens to a durable operation that did
 * not finish: it is tried again, it is re-attached to, or it is closed as a
 * failure. That decision is the difference between work that is redone and
 * work that is repeated forever, so it is worth pinning permanently.
 *
 * The reconciler is a PURE function — it takes value snapshots and returns a
 * decision, with no journal, no clock and no dispatch — so the whole
 * guarantee is a table of inputs and answers, and this file is that table.
 *
 * It had NO row at all. The type's own documentation says it owns zero
 * effects, and the two callers that use it live in another module, where the
 * one test that builds the engine never asks it for a plan: the decision of
 * whether a durable failure is retried was measured by nobody, in any module.
 *
 * The limit of the class is on the class, not on the rows: a row without a
 * limit does not fail, it hangs the lane that runs it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RetryReconcilerTest {

    private val fingerprint = Fingerprint("a".repeat(64))
    private val otherFingerprint = Fingerprint("b".repeat(64))

    private fun control(
        attempt: Int,
        status: OperationStatus,
        fp: Fingerprint = fingerprint,
    ) = RetryControlRowSnapshot(attempt = attempt, status = status, fingerprint = fp)

    private fun child(
        attempt: Int,
        status: OperationStatus,
        childIndex: Int = 0,
        fp: Fingerprint? = fingerprint,
    ) = RetryChildRowSnapshot(attempt = attempt, childIndex = childIndex, status = status, fingerprint = fp)

    /**
     * The placeholder the production reader emits for a position that has no
     * journal row: PENDING, and no fingerprint of its own.
     */
    private fun placeholder(attempt: Int, childIndex: Int = 0) =
        child(attempt = attempt, status = OperationStatus.PENDING, childIndex = childIndex, fp = null)

    private fun input(
        maxAttempts: Int = 3,
        controlRows: List<RetryControlRowSnapshot> = emptyList(),
        childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>> = emptyMap(),
        preControlChildren: List<RetryChildRowSnapshot> = emptyList(),
        currentFingerprint: Fingerprint = fingerprint,
    ) = RetryReconciliationInput(
        controlIdentity = RetryControlIdentity(operationId = "retry-1"),
        maxAttempts = maxAttempts,
        currentFingerprint = currentFingerprint,
        controlRows = controlRows,
        childrenByAttempt = childrenByAttempt,
        preControlChildren = preControlChildren,
    )

    private fun reconcile(input: RetryReconciliationInput) = RetryReconciler.reconcile(input)

    // ── the fresh retry ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a retry with no evidence at all schedules the first attempt")
    fun aRetryWithNoEvidenceAtAllSchedulesTheFirstAttempt() {
        // The window the table of the KDoc opens with: no control row, no
        // child row. Nothing is known, so the first attempt is scheduled.
        assertEquals(ScheduleAttempt(1), reconcile(input()))
    }

    @Test
    @DisplayName("a placeholder without a control row is not a history")
    fun aPlaceholderWithoutAControlRowIsNotAHistory() {
        // The synthetic PENDING placeholder means "there is no journal row at
        // this position", NOT "an attempt happened here". Reading it as
        // evidence would reject every fresh retry as ambiguous.
        assertEquals(
            ScheduleAttempt(1),
            reconcile(input(preControlChildren = listOf(placeholder(1)))),
            "a placeholder was read as evidence of an attempt",
        )
    }

    // ── the terminal control row ──────────────────────────────────────────────

    @Test
    @DisplayName("the latest control row that succeeded is reused")
    fun theLatestControlRowThatSucceededIsReused() {
        // Terminality law: once a control row reaches a terminal status it is
        // final, and the reconciler reuses the outcome instead of scheduling
        // a new child for it.
        assertEquals(
            ReuseSuccess(1),
            reconcile(input(controlRows = listOf(control(1, OperationStatus.SUCCEEDED)))),
        )
    }

    @Test
    @DisplayName("the latest control row that completed unstable is reused")
    fun theLatestControlRowThatCompletedUnstableIsReused() {
        // UNSTABLE is not a failure: the work RAN and asked for attention. It
        // never advances the attempt and never consumes the budget.
        assertEquals(
            ReuseUnstable(1),
            reconcile(input(controlRows = listOf(control(1, OperationStatus.UNSTABLE)))),
        )
    }

    @Test
    @DisplayName("a terminal failure at the last allowed attempt is reused as a failure")
    fun aTerminalFailureAtTheLastAllowedAttemptIsReusedAsAFailure() {
        // The budget is spent: the aggregate is closed as a failure and no
        // further attempt is scheduled.
        assertEquals(
            ReuseFailure(2),
            reconcile(
                input(
                    maxAttempts = 2,
                    controlRows = listOf(control(2, OperationStatus.FAILED)),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a terminal control row with no child evidence advances to the next attempt")
    fun aTerminalControlRowWithNoChildEvidenceAdvancesToTheNextAttempt() {
        // The writer finished the attempt as a failure and the journal holds
        // nothing about it: there is no child to re-attach to, so the budget
        // decides. While there is budget the aggregate advances.
        assertEquals(
            AdvanceAfterFailure(from = 1, to = 2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(control(1, OperationStatus.FAILED)),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a terminal control row with an in-flight child resumes the attempt")
    fun aTerminalControlRowWithAnInFlightChildResumesTheAttempt() {
        // The control row says the attempt failed but the child journal says
        // the body is still running: the journal is the deeper evidence, so
        // the attempt is re-attached to instead of being run again.
        assertEquals(
            ResumeAttempt(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.FAILED)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.RUNNING))),
                ),
            ),
        )
    }

    // ── the divergence ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a control row of another contract is rejected, naming the attempt")
    fun aControlRowOfAnotherContractIsRejectedNamingTheAttempt() {
        // The fingerprint law: a control row whose fingerprint is not the
        // current one means the contract changed under the aggregate. The
        // reconciler fails closed, and the refusal names WHERE it saw it.
        val decision = reconcile(
            input(controlRows = listOf(control(1, OperationStatus.RUNNING, otherFingerprint))),
        )
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "a divergent control row was not rejected",
        )
        assertTrue(
            refusal.reason.contains("fingerprint mismatch at attempt 1"),
            "the refusal did not name the attempt it saw the divergence in: ${refusal.reason}",
        )
        assertEquals("retry-1", refusal.operationId, "the refusal did not name the operation")
    }

    // ── the compatibility branch (no control row) ─────────────────────────────

    @Test
    @DisplayName("a legacy child that succeeded in one attempt closes the aggregate as a success")
    fun aLegacyChildThatSucceededInOneAttemptClosesTheAggregateAsASuccess() {
        // Policy A of the legacy branch: the evidence is unambiguous, so the
        // aggregate is reconstructed as a success instead of being run again.
        assertEquals(
            CloseSuccessFromChild(1),
            reconcile(input(childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.SUCCEEDED))))),
        )
    }

    @Test
    @DisplayName("a legacy child before the control row closes the aggregate as a success")
    fun aLegacyChildBeforeTheControlRowClosesTheAggregateAsASuccess() {
        // The same decision, reached through the other bucket: the child rows
        // that pre-date the control row schema.
        assertEquals(
            CloseSuccessFromChild(1),
            reconcile(input(preControlChildren = listOf(child(1, OperationStatus.SUCCEEDED)))),
        )
    }

    @Test
    @DisplayName("a legacy history of more than one attempt is rejected as ambiguous")
    fun aLegacyHistoryOfMoreThanOneAttemptIsRejectedAsAmbiguous() {
        // Policy B: without a control row, more than one attempt means more
        // attempts may have been scheduled than the journal shows.
        val decision = reconcile(
            input(
                childrenByAttempt = mapOf(
                    1 to listOf(child(1, OperationStatus.SUCCEEDED)),
                    2 to listOf(child(2, OperationStatus.SUCCEEDED)),
                ),
            ),
        )
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "an ambiguous legacy history was not rejected",
        )
        assertTrue(
            refusal.reason.contains("multi-attempt"),
            "the refusal did not say what it saw: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("a legacy child that failed is rejected as ambiguous")
    fun aLegacyChildThatFailedIsRejectedAsAmbiguous() {
        // A failure without a control row cannot tell whether a higher
        // attempt was already in flight when the failure was recorded.
        val decision = reconcile(
            input(childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.FAILED)))),
        )
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "an ambiguous legacy history was not rejected",
        )
        assertTrue(
            refusal.reason.contains("ambiguous"),
            "the refusal did not say what it saw: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("a legacy child that is still running is rejected as ambiguous")
    fun aLegacyChildThatIsStillRunningIsRejectedAsAmbiguous() {
        // An in-flight child without a control row is the same ambiguity: the
        // aggregate may already be running somewhere else.
        val decision = reconcile(
            input(childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.RUNNING)))),
        )
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "an ambiguous legacy history was not rejected",
        )
        assertTrue(
            refusal.reason.contains("inFlight=true"),
            "the refusal did not say that the child was in flight: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("a legacy history with a success and a failure is rejected as ambiguous")
    fun aLegacyHistoryWithASuccessAndAFailureIsRejectedAsAmbiguous() {
        // The legacy branch admits a success only when NOTHING contradicts
        // it: a failure in the same attempt contradicts it, and the aggregate
        // is rejected instead of being closed on the success.
        val decision = reconcile(
            input(
                childrenByAttempt = mapOf(
                    1 to listOf(
                        child(1, OperationStatus.SUCCEEDED),
                        child(1, OperationStatus.FAILED, childIndex = 1),
                    ),
                ),
            ),
        )
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "a contradictory legacy history was not rejected",
        )
        assertTrue(
            refusal.reason.contains("ambiguous"),
            "the refusal did not say what it saw: ${refusal.reason}",
        )
    }

    // ── the control row that is not terminal ──────────────────────────────────

    @Test
    @DisplayName("a control row that is running with no child schedules the attempt")
    fun aControlRowThatIsRunningWithNoChildSchedulesTheAttempt() {
        // The second window of the table: the control row is there, the child
        // is not. The attempt is scheduled, and it is scheduled at the attempt
        // the control row names.
        assertEquals(
            ScheduleAttempt(1),
            reconcile(input(controlRows = listOf(control(1, OperationStatus.RUNNING)))),
        )
    }

    @Test
    @DisplayName("a control row that is running with only a placeholder child resumes the attempt")
    fun aControlRowThatIsRunningWithOnlyAPlaceholderChildResumesTheAttempt() {
        // The writer is mid-execution: the control row is persisted and the
        // journal row is not. The attempt is re-attached to, not re-run.
        assertEquals(
            ResumeAttempt(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(1 to listOf(placeholder(1))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a control row that is running with an in-flight child resumes the attempt")
    fun aControlRowThatIsRunningWithAnInFlightChildResumesTheAttempt() {
        assertEquals(
            ResumeAttempt(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.RUNNING))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a child that completed unstable under a stale control row is reused as unstable")
    fun aChildThatCompletedUnstableUnderAStaleControlRowIsReusedAsUnstable() {
        // The child journal proves more than the stale control row: the body
        // completed unstable and the crash happened before the control row was
        // written. The aggregate answers UNSTABLE, not a new attempt.
        assertEquals(
            ReuseUnstable(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.UNSTABLE))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a running control row whose child succeeded closes the aggregate as a success")
    fun aRunningControlRowWhoseChildSucceededClosesTheAggregateAsASuccess() {
        // The same window as the stale control row that failed: the child
        // journal proves the body completed, and the aggregate is closed as a
        // success instead of running the body again.
        assertEquals(
            CloseSuccessFromChild(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.SUCCEEDED))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a running control row whose child failed advances to the next attempt")
    fun aRunningControlRowWhoseChildFailedAdvancesToTheNextAttempt() {
        // The control row never reached a terminal state, but the child
        // journal proves the body failed: the failure is trusted and the
        // aggregate advances while the budget allows it.
        assertEquals(
            AdvanceAfterFailure(from = 1, to = 2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.FAILED))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a running control row whose child failed at the last attempt is reused as a failure")
    fun aRunningControlRowWhoseChildFailedAtTheLastAttemptIsReusedAsAFailure() {
        // The same window with the budget spent: the aggregate is closed as a
        // failure and no further attempt is scheduled.
        assertEquals(
            ReuseFailure(2),
            reconcile(
                input(
                    maxAttempts = 2,
                    controlRows = listOf(control(2, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(2 to listOf(child(2, OperationStatus.FAILED))),
                ),
            ),
        )
    }

    // ── the history that does not carry every attempt ────────────────────────

    @Test
    @DisplayName("a history that does not carry every attempt answers for the attempt it has")
    fun aHistoryThatDoesNotCarryEveryAttemptAnswersForTheAttemptItHas() {
        // The journal may not hold a row for every attempt: the reconciler
        // skips the ordinals it does not have and answers for the one it
        // does, instead of failing closed on a gap.
        assertEquals(
            ScheduleAttempt(2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(control(2, OperationStatus.RUNNING)),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a control row that succeeded under a later running attempt closes the aggregate as a success")
    fun aControlRowThatSucceededUnderALaterRunningAttemptClosesTheAggregateAsASuccess() {
        // The attempt that succeeded is not the latest one, so the reuse
        // shortcut at the top does not apply and the loop reaches it: the
        // success of that attempt still closes the aggregate.
        assertEquals(
            CloseSuccessFromChild(1),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(
                        control(1, OperationStatus.SUCCEEDED),
                        control(3, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a control row that completed unstable under a later running attempt is reused as unstable")
    fun aControlRowThatCompletedUnstableUnderALaterRunningAttemptIsReusedAsUnstable() {
        // The same window for the unstable attempt. UNSTABLE is a COMPLETION,
        // not a poll failure: it never advances the attempt and never
        // consumes the budget, so it is reused wherever it is found.
        assertEquals(
            ReuseUnstable(1),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(
                        control(1, OperationStatus.UNSTABLE),
                        control(3, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("an attempt that failed under a later running attempt is skipped and the active attempt answers")
    fun anAttemptThatFailedUnderALaterRunningAttemptIsSkippedAndTheActiveAttemptAnswers() {
        // The skip rule applies to a SUCCEEDED attempt too: the attempt that
        // already completed is not the answer, the attempt that is still
        // running is.
        assertEquals(
            ScheduleAttempt(2),
            reconcile(
                input(
                    controlRows = listOf(
                        control(1, OperationStatus.SUCCEEDED),
                        control(2, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a terminal failure with a successor is skipped and the active attempt answers")
    fun aTerminalFailureWithASuccessorIsSkippedAndTheActiveAttemptAnswers() {
        // The same window with the failure: the aggregate does not collapse
        // into the failure of an attempt the dispatch loop already left.
        assertEquals(
            ScheduleAttempt(2),
            reconcile(
                input(
                    controlRows = listOf(
                        control(1, OperationStatus.FAILED),
                        control(2, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a running attempt with a successor row is not skipped")
    fun aRunningAttemptWithASuccessorRowIsNotSkipped() {
        // Only a TERMINAL attempt is "advanced past": the skip rule reads the
        // status of the attempt, not just the presence of a later row. A
        // non-terminal attempt keeps its own answer (here: no child evidence,
        // so it is scheduled) even when a successor row exists.
        assertEquals(
            ScheduleAttempt(1),
            reconcile(
                input(
                    controlRows = listOf(
                        control(1, OperationStatus.RUNNING),
                        control(2, OperationStatus.FAILED),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a history whose last attempt failed answers with the last attempt")
    fun aHistoryWhoseLastAttemptFailedAnswersWithTheLastAttempt() {
        // The exhaustion is decided by the LAST terminal ordinal, not by the
        // first one the reader happened to see: a history that does not carry
        // every attempt is still read from its end.
        assertEquals(
            ReuseFailure(3),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(
                        control(1, OperationStatus.FAILED),
                        control(3, OperationStatus.FAILED),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("the budget decides the aggregate even when a later attempt was forced")
    fun theBudgetDecidesTheAggregateEvenWhenALaterAttemptWasForced() {
        // The budget is the authority of the aggregate: when the ordinal of
        // the last terminal failure reaches it, the aggregate is closed as a
        // failure and the attempt that was forced beyond the budget does not
        // reopen it. The decision is the safe one: the body is not run again.
        assertEquals(
            ReuseFailure(1),
            reconcile(
                input(
                    maxAttempts = 1,
                    controlRows = listOf(
                        control(1, OperationStatus.FAILED),
                        control(2, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a placeholder is not evidence next to a failed child")
    fun aPlaceholderIsNotEvidenceNextToAFailedChild() {
        // The reader emits a placeholder for every position the journal does
        // not hold. A bucket that mixes a placeholder and a real row is the
        // normal shape: the placeholder is dropped and the real failure is
        // the evidence.
        assertEquals(
            AdvanceAfterFailure(from = 1, to = 2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(control(1, OperationStatus.RUNNING)),
                    childrenByAttempt = mapOf(
                        1 to listOf(
                            placeholder(1, childIndex = 0),
                            child(1, OperationStatus.FAILED, childIndex = 1),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a history with two rows for the same attempt answers for the first one")
    fun aHistoryWithTwoRowsForTheSameAttemptAnswersForTheFirstOne() {
        // The journal may carry two rows for the same attempt: the reconciler
        // reads one of them and does not answer for the other.
        assertEquals(
            AdvanceAfterFailure(from = 1, to = 2),
            reconcile(
                input(
                    controlRows = listOf(
                        control(1, OperationStatus.FAILED),
                        control(1, OperationStatus.SUCCEEDED),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a control row that completed unstable under a later unstable attempt is reused as unstable")
    fun aControlRowThatCompletedUnstableUnderALaterUnstableAttemptIsReusedAsUnstable() {
        // The latest row decides the aggregate when it completed unstable,
        // even when the history does not carry every attempt: the completion
        // is the final fact of the aggregate.
        assertEquals(
            ReuseUnstable(3),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(
                        control(1, OperationStatus.SUCCEEDED),
                        control(3, OperationStatus.UNSTABLE),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("the budget decides the aggregate even when an attempt succeeded")
    fun theBudgetDecidesTheAggregateEvenWhenAnAttemptSucceeded() {
        // The same rule with a different terminal status: the exhaustion is
        // decided by the ORDINAL of the last terminal row, not by the status
        // it carries. A succeeded attempt does not reopen an aggregate whose
        // budget is spent.
        assertEquals(
            ReuseFailure(2),
            reconcile(
                input(
                    maxAttempts = 2,
                    controlRows = listOf(
                        control(2, OperationStatus.SUCCEEDED),
                        control(3, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

    // ── the terminal control row with a child ─────────────────────────────────

    @Test
    @DisplayName("a control row that failed with a child that succeeded closes the aggregate as a success")
    fun aControlRowThatFailedWithAChildThatSucceededClosesTheAggregateAsASuccess() {
        // Window C: the control row says the attempt failed, and the child
        // journal says it succeeded. The child is the deeper evidence, and the
        // aggregate is closed as a success instead of running the body again.
        assertEquals(
            CloseSuccessFromChild(1),
            reconcile(
                input(
                    controlRows = listOf(control(1, OperationStatus.FAILED)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.SUCCEEDED))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a control row that failed with a failed child advances to the next attempt")
    fun aControlRowThatFailedWithAFailedChildAdvancesToTheNextAttempt() {
        // The stale window: the attempt failed and the budget still has room.
        // The decision names BOTH ends of the transition, because the writer
        // persists it before launching the next child.
        assertEquals(
            AdvanceAfterFailure(from = 1, to = 2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(control(1, OperationStatus.FAILED)),
                    childrenByAttempt = mapOf(1 to listOf(child(1, OperationStatus.FAILED))),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a terminal attempt with a successor is skipped and the active attempt answers")
    fun aTerminalAttemptWithASuccessorIsSkippedAndTheActiveAttemptAnswers() {
        // The dispatch loop had already advanced past attempt 1: the attempt
        // that failed has a successor, so the reconciler does not collapse the
        // aggregate into that failure — it answers for the attempt that is
        // actually active.
        assertEquals(
            ScheduleAttempt(2),
            reconcile(
                input(
                    maxAttempts = 3,
                    controlRows = listOf(
                        control(1, OperationStatus.FAILED),
                        control(2, OperationStatus.RUNNING),
                    ),
                ),
            ),
        )
    }

}
