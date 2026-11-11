package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.CloseFromChildren
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectAmbiguousOutcome
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ResumeBranches
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision.Start
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The parallel reconciler decides what happens to a parallel aggregate that
 * did not finish: it is started, it is resumed branch by branch, it is closed
 * from the evidence of its children, it is reused, or it is refused.
 *
 * The reconciler is a PURE function — it takes observed facts and returns a
 * decision, with no journal, no clock and no dispatch — so the whole
 * guarantee is a table of inputs and answers, and this file is that table.
 *
 * It had NO row at all. The type's own documentation says it owns zero
 * effects, and the callers that use it live in another module, where the one
 * test that builds the parallel engine never asks it for a plan: the
 * decision of what a parallel aggregate does next was measured by nobody, in
 * any module. Its sibling, the retry reconciler, had the same hole until
 * `RetryReconcilerTest` closed it.
 *
 * The limit of the class is on the class, not on the rows: a row without a
 * limit does not fail, it hangs the lane that runs it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ParallelReconcilerTest {

    private val fingerprint = Fingerprint("a".repeat(64))
    private val otherFingerprint = Fingerprint("b".repeat(64))

    // The stage is NOT zero: the refusal names the aggregate, and a name that
    // always says "-s0" would not notice that the stage it names is not the
    // stage it was given.
    private val aggregateId = ParallelAggregateId(runId = "run-1", stageIndex = 7)

    private fun child(
        branch: Int,
        status: OperationStatus,
        childIndex: Int = 0,
    ) = ParallelBranchChildSnapshot(branchIndex = branch, childIndex = childIndex, status = status)

    private fun aggregate(
        status: OperationStatus = OperationStatus.RUNNING,
        outcome: BranchTerminal? = null,
        fp: Fingerprint = fingerprint,
    ) = ParallelAggregateSnapshot(
        id = aggregateId,
        fingerprint = fp,
        status = status,
        semanticOutcome = outcome,
    )

    private fun input(
        branchCount: Int = 2,
        aggregateRow: ParallelAggregateSnapshot? = null,
        childrenByBranch: Map<Int, List<ParallelBranchChildSnapshot>> = emptyMap(),
        currentFingerprint: Fingerprint = fingerprint,
    ) = ParallelReconciliationInput(
        aggregateId = aggregateId,
        currentFingerprint = currentFingerprint,
        aggregateRow = aggregateRow,
        branchCount = branchCount,
        childrenByBranch = childrenByBranch,
    )

    private fun reconcile(input: ParallelReconciliationInput) = ParallelReconciler.reconcile(input)

    /**
     * The branches a START decision may dispatch, read through the decision's
     * own getter: the decision makes the executed and the not-executed sets
     * explicit, and the set it names is the whole answer.
     */
    private fun started(decision: ParallelDecision): List<Int> {
        val start = assertInstanceOf(
            Start::class.java,
            decision,
            "the aggregate was not started",
        )
        return start.branches
    }

    /**
     * The branches a RESUME decision may dispatch, read through the decision's
     * own getter: a branch that is not named is a branch that must not be
     * dispatched.
     */
    private fun resumed(decision: ParallelDecision): List<Int> {
        val resume = assertInstanceOf(
            ResumeBranches::class.java,
            decision,
            "the aggregate was not resumed",
        )
        return resume.branches
    }

    // ── the fresh aggregate ────────────────────────────────────────────────────

    @Test
    @DisplayName("an aggregate with no row and no children starts every branch")
    fun anAggregateWithNoRowAndNoChildrenStartsEveryBranch() {
        // Nothing is known: no aggregate row, no child row. Every branch is
        // launched, and the decision says WHICH ones, because the decision
        // makes the executed and the not-executed sets explicit.
        assertEquals(listOf(0, 1), started(reconcile(input(branchCount = 2))))
    }

    @Test
    @DisplayName("an aggregate admitted with no children starts every branch")
    fun anAggregateAdmittedWithNoChildrenStartsEveryBranch() {
        // The aggregate row exists and says RUNNING, but no branch has a row
        // yet: the aggregate was admitted and nothing was launched yet. The
        // answer is the same as the fresh one, and it says so for a different
        // reason.
        assertEquals(listOf(0, 1), started(reconcile(input(aggregateRow = aggregate()))))
    }

    @Test
    @DisplayName("a branch beyond the branch count is not a branch")
    fun aBranchBeyondTheBranchCountIsNotABranch() {
        // The children are grouped by branch index, and the compiled stage
        // says how many branches it has. A row for a branch that the stage
        // does not have is not evidence about a branch: it is a leftover of
        // another compilation.
        assertEquals(
            listOf(0, 1),
            started(
                reconcile(
                    input(
                        branchCount = 2,
                        childrenByBranch = mapOf(7 to listOf(child(7, OperationStatus.SUCCEEDED))),
                    ),
                ),
            ),
            "a branch outside the compiled branch count was read as a branch",
        )
    }

    // ── the reuse of a closed aggregate ────────────────────────────────────────

    @Test
    @DisplayName("an aggregate closed with a success is reused")
    fun anAggregateClosedWithASuccessIsReused() {
        assertEquals(
            ReuseSuccess,
            reconcile(input(aggregateRow = aggregate(OperationStatus.SUCCEEDED, BranchTerminal.Succeeded))),
        )
    }

    @Test
    @DisplayName("an aggregate closed with an unstable is reused")
    fun anAggregateClosedWithAnUnstableIsReused() {
        assertEquals(
            ReuseUnstable,
            reconcile(input(aggregateRow = aggregate(OperationStatus.UNSTABLE, BranchTerminal.Unstable))),
        )
    }

    @Test
    @DisplayName("an aggregate closed with a failure is reused")
    fun anAggregateClosedWithAFailureIsReused() {
        assertEquals(
            ReuseFailure,
            reconcile(input(aggregateRow = aggregate(OperationStatus.FAILED, BranchTerminal.Failed("boom")))),
        )
    }

    @Test
    @DisplayName("an aggregate aborted with a failure is reused")
    fun anAggregateAbortedWithAFailureIsReused() {
        // ABORTED is one of the four statuses that carry a close. An abort
        // that the aggregate persisted is a fact about the aggregate, and the
        // restart reuses it instead of running the branches again.
        assertEquals(
            ReuseFailure,
            reconcile(input(aggregateRow = aggregate(OperationStatus.ABORTED, BranchTerminal.Failed("boom")))),
        )
    }

    @Test
    @DisplayName("an aggregate that is still running is not reused, even with an outcome")
    fun anAggregateThatIsStillRunningIsNotReusedEvenWithAnOutcome() {
        // The carrier alone does not reuse anything: the STATUS has to say
        // that the aggregate closed. A running aggregate that happens to
        // carry an outcome is an aggregate that is still open.
        assertEquals(
            listOf(0, 1),
            started(reconcile(input(aggregateRow = aggregate(OperationStatus.RUNNING, BranchTerminal.Succeeded)))),
        )
    }

    @Test
    @DisplayName("an aggregate that closed without its carrier reuses nothing")
    fun anAggregateThatClosedWithoutItsCarrierReusesNothing() {
        // A terminal status WITHOUT the semantic carrier is a row that lost
        // the fact it was supposed to carry. The reconciler cannot guess it,
        // so it does not reuse it: it starts the branches again.
        assertEquals(
            listOf(0, 1),
            started(reconcile(input(aggregateRow = aggregate(OperationStatus.SUCCEEDED, outcome = null)))),
        )
    }

    // ── the divergence ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an aggregate of another contract is rejected, naming the aggregate")
    fun anAggregateOfAnotherContractIsRejectedNamingTheAggregate() {
        // The fingerprint law: a row whose fingerprint is not the current one
        // means the contract changed under the aggregate. The reconciler fails
        // closed, and the refusal names WHICH aggregate it saw.
        val decision = reconcile(input(aggregateRow = aggregate(fp = otherFingerprint)))
        val refusal = assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "a divergent aggregate was not rejected",
        )
        assertTrue(
            refusal.reason.contains("parallel aggregate fingerprint mismatch at 'run-1-s7'"),
            "the refusal did not name the aggregate it saw: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("the divergence is rejected before the aggregate's own outcome is reused")
    fun theDivergenceIsRejectedBeforeTheAggregatesOwnOutcomeIsReused() {
        // The order is the contract: a row that does not belong to this
        // contract cannot be reused either, whatever it carries.
        val decision = reconcile(
            input(
                aggregateRow = aggregate(
                    status = OperationStatus.SUCCEEDED,
                    outcome = BranchTerminal.Succeeded,
                    fp = otherFingerprint,
                ),
            ),
        )
        assertInstanceOf(
            RejectDivergence::class.java,
            decision,
            "a divergent aggregate was reused instead of being rejected",
        )
    }

    // ── the resume ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a branch that has not finished is resumed and the finished one is not")
    fun aBranchThatHasNotFinishedIsResumedAndTheFinishedOneIsNot() {
        // The decision says WHICH branches may be dispatched, and the ones
        // that finished may not be dispatched again: a terminal child is never
        // re-executed.
        assertEquals(
            listOf(1),
            resumed(
                reconcile(
                    input(
                        childrenByBranch = mapOf(
                            0 to listOf(child(0, OperationStatus.SUCCEEDED)),
                            1 to listOf(child(1, OperationStatus.RUNNING)),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a branch with no row at all is resumed")
    fun aBranchWithNoRowAtAllIsResumed() {
        // No row for a branch is not a branch that finished: it is a branch
        // that has not been observed. Reading the absence as a completion
        // would drop the work of that branch for ever.
        assertEquals(
            listOf(1),
            resumed(
                reconcile(
                    input(
                        childrenByBranch = mapOf(0 to listOf(child(0, OperationStatus.SUCCEEDED))),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a branch whose child is pending is resumed")
    fun aBranchWhoseChildIsPendingIsResumed() {
        // PENDING is the child that is not terminal: the writer has not
        // finished with it.
        assertEquals(
            listOf(1),
            resumed(
                reconcile(
                    input(
                        childrenByBranch = mapOf(
                            0 to listOf(child(0, OperationStatus.SUCCEEDED)),
                            1 to listOf(child(1, OperationStatus.PENDING)),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("every incomplete branch is resumed, in order")
    fun everyIncompleteBranchIsResumedInOrder() {
        // Three branches, one finished, one pending, one unobserved: the
        // decision names the two that may be dispatched, in the order of the
        // compiled stage.
        assertEquals(
            listOf(1, 2),
            resumed(
                reconcile(
                    input(
                        branchCount = 3,
                        childrenByBranch = mapOf(
                            0 to listOf(child(0, OperationStatus.SUCCEEDED)),
                            1 to listOf(child(1, OperationStatus.PENDING)),
                        ),
                    ),
                ),
            ),
        )
    }

    // ── the close from the children ────────────────────────────────────────────

    @Test
    @DisplayName("every branch succeeded and the aggregate is stale: it closes from the children")
    fun everyBranchSucceededAndTheAggregateIsStaleItClosesFromTheChildren() {
        // Every branch is terminal and the aggregate row did not close: the
        // children are the deeper evidence, and the aggregate closes with
        // their fold, launching nothing.
        assertEquals(
            CloseFromChildren(BranchTerminal.Succeeded),
            reconcile(
                input(
                    aggregateRow = aggregate(),
                    childrenByBranch = mapOf(
                        0 to listOf(child(0, OperationStatus.SUCCEEDED)),
                        1 to listOf(child(1, OperationStatus.SUCCEEDED)),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a branch that completed unstable closes the aggregate as unstable")
    fun aBranchThatCompletedUnstableClosesTheAggregateAsUnstable() {
        // An unstable child is a COMPLETION, not a failure: the work ran and
        // asked for attention. The aggregate closes as unstable, and it does
        // not fail.
        assertEquals(
            CloseFromChildren(BranchTerminal.Unstable),
            reconcile(
                input(
                    aggregateRow = aggregate(),
                    childrenByBranch = mapOf(
                        0 to listOf(
                            child(0, OperationStatus.SUCCEEDED, childIndex = 0),
                            child(0, OperationStatus.UNSTABLE, childIndex = 1),
                        ),
                        1 to listOf(child(1, OperationStatus.SUCCEEDED)),
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("a branch that completed unstable alone closes the aggregate as unstable")
    fun aBranchThatCompletedUnstableAloneClosesTheAggregateAsUnstable() {
        // The same answer from a branch whose only child is unstable.
        assertEquals(
            CloseFromChildren(BranchTerminal.Unstable),
            reconcile(
                input(
                    aggregateRow = aggregate(),
                    childrenByBranch = mapOf(
                        0 to listOf(child(0, OperationStatus.UNSTABLE)),
                        1 to listOf(child(1, OperationStatus.SUCCEEDED)),
                    ),
                ),
            ),
        )
    }

    // ── the ambiguity ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a branch with a failed child is rejected as ambiguous")
    fun aBranchWithAFailedChildIsRejectedAsAmbiguous() {
        // A FAILED row may hide an unstable outcome, and without a lossless
        // carrier the fold cannot tell the two apart. The reconciler fails
        // closed and says why.
        val decision = reconcile(
            input(
                childrenByBranch = mapOf(0 to listOf(child(0, OperationStatus.FAILED))),
            ),
        )
        val refusal = assertInstanceOf(
            RejectAmbiguousOutcome::class.java,
            decision,
            "an ambiguous branch was not rejected",
        )
        assertTrue(
            refusal.reason.contains("cannot distinguish real Failure from Unstable"),
            "the refusal did not say what it saw: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("a branch with a lost child is rejected as ambiguous")
    fun aBranchWithALostChildIsRejectedAsAmbiguous() {
        // Terminal, but neither successful, nor failed, nor unstable: the
        // branch cannot be reconstructed, so the aggregate fails closed.
        val decision = reconcile(
            input(
                childrenByBranch = mapOf(0 to listOf(child(0, OperationStatus.LOST))),
            ),
        )
        val refusal = assertInstanceOf(
            RejectAmbiguousOutcome::class.java,
            decision,
            "an ambiguous branch was not rejected",
        )
        assertTrue(
            refusal.reason.contains("non-succeeded/failed statuses"),
            "the refusal did not say what it saw: ${refusal.reason}",
        )
    }

    @Test
    @DisplayName("a branch that succeeded and failed is ambiguous")
    fun aBranchThatSucceededAndFailedIsAmbiguous() {
        // The branch did not succeed as a whole, and the failure cannot be
        // distinguished from an unstable outcome. The ambiguity wins over the
        // success, because the success is not of the branch.
        val decision = reconcile(
            input(
                childrenByBranch = mapOf(
                    0 to listOf(
                        child(0, OperationStatus.SUCCEEDED, childIndex = 0),
                        child(0, OperationStatus.FAILED, childIndex = 1),
                    ),
                ),
            ),
        )
        assertInstanceOf(
            RejectAmbiguousOutcome::class.java,
            decision,
            "an ambiguous branch was not rejected",
        )
    }

    @Test
    @DisplayName("the ambiguity of a branch is reported before the aggregate's outcome is reused")
    fun theAmbiguityOfABranchIsReportedBeforeTheAggregatesOutcomeIsReused() {
        // The order is the contract: the branches are read first, and a
        // branch that cannot be reconstructed refuses the whole aggregate,
        // whatever the aggregate row says it closed.
        val decision = reconcile(
            input(
                aggregateRow = aggregate(
                    status = OperationStatus.SUCCEEDED,
                    outcome = BranchTerminal.Succeeded,
                ),
                childrenByBranch = mapOf(0 to listOf(child(0, OperationStatus.FAILED))),
            ),
        )
        assertInstanceOf(
            RejectAmbiguousOutcome::class.java,
            decision,
            "the aggregate was reused although a branch was ambiguous",
        )
    }

    // ── the identity of the aggregate ──────────────────────────────────────────

    @Test
    @DisplayName("an aggregate id without a run is refused")
    fun anAggregateIdWithoutARunIsRefused() {
        assertThrows<IllegalArgumentException> { ParallelAggregateId(runId = "   ", stageIndex = 0) }
    }

    @Test
    @DisplayName("an aggregate id of a negative stage is refused")
    fun anAggregateIdOfANegativeStageIsRefused() {
        assertThrows<IllegalArgumentException> { ParallelAggregateId(runId = "run-1", stageIndex = -1) }
    }

    @Test
    @DisplayName("an aggregate id of the first stage is legal")
    fun anAggregateIdOfTheFirstStageIsLegal() {
        // The guard says `stageIndex >= 0`, and the ZERO stage is a real
        // stage: it is the first stage of a run. A guard that refused it
        // would refuse the first stage of every run, and the row that says
        // so is the one that pins the boundary of the guard — a refusal row
        // alone cannot tell "the guard works" from "the guard refuses
        // everything".
        assertEquals(0, ParallelAggregateId(runId = "run-1", stageIndex = 0).stageIndex)
    }

    @Test
    @DisplayName("the aggregate id keeps the body path it was given")
    fun theAggregateIdKeepsTheBodyPathItWasGiven() {
        // The identity of an aggregate carries the canonical path of its
        // body: two aggregates of the same run and the same stage but of a
        // different body are two different aggregates, and an identity that
        // dropped that part would merge them into one.
        val bodyPath = listOf(BlockSegment("0:step-1"), BlockSegment("1:step-2"))
        val id = ParallelAggregateId(runId = "run-1", stageIndex = 0, bodyPath = bodyPath)
        assertEquals(bodyPath, id.bodyPath)
    }

    @Test
    @DisplayName("the child row carries the branch and the child it belongs to")
    fun theChildRowCarriesTheBranchAndTheChildItBelongsTo() {
        // A child row is a row of a branch AND of a step inside it, and the
        // snapshot says which of the two it is. The reconciler groups the
        // rows by the KEY of the map, so it cannot see a row whose own field
        // says a different branch: this row is the one that pins the two
        // facts together.
        val row = child(branch = 1, status = OperationStatus.SUCCEEDED, childIndex = 2)
        assertEquals(1, row.branchIndex)
        assertEquals(2, row.childIndex)
    }
}
