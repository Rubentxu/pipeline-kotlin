package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The canonical cursor of a run moves for exactly two reasons, and no others.
 *
 * `ReplayCursor(runId, lastOpId, stageIndex, savedAt)` says where the canonical RUN
 * resumes. It is traversal state, so it has exactly one owner, and that owner has
 * exactly two rules:
 *
 *  - after an EXECUTION, every outcome that is not a [StepOutcome.Failure] advances it;
 *  - after a RECOVERY, only a recovered RUNNING operation that settled as a
 *    [StepOutcome.Success] advances it.
 *
 * The asymmetry is not an accident and this test pins both halves of it. Recovery has
 * always been stricter than execution about UNSTABLE, and relocating the cursor was
 * not licensed to change what advances.
 *
 * The second half of the guarantee is the EFFECT: a decision that says "hold" must
 * write nothing at all, and a decision that says "advance" must write the operation
 * and the stage the decision carries — never re-derive them from a context the caller
 * might have changed in between.
 *
 * Pure and total: the store here is a recorder, and nothing touches a disk, a clock
 * or a process.
 */
class CanonicalCursorAdvanceTest {

    private val store = RecordingCursorStore()
    private val advance = CanonicalCursorAdvance(store)

    private val failure = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the step failed"))

    private val recoveredRunning = InvocationReconciliation.RecoverRunning(
        DurableTaskTerminal.Exited(0, DurableTaskOutput(controlDir = "/control")),
    )

    @Test
    @DisplayName("an execution that did not fail advances the cursor")
    fun anExecutionThatDidNotFailAdvancesTheCursor() {
        assertEquals(
            CursorAdvance.Advance("op-1", 2),
            advance.decideAfterExecution("op-1", 2, StepOutcome.Success),
            "a successful execution advances the cursor to its own operation",
        )
        assertEquals(
            CursorAdvance.Advance("op-1", 2),
            advance.decideAfterExecution("op-1", 2, StepOutcome.Unstable),
            "an UNSTABLE execution is not a failure, so it advances the cursor too",
        )
    }

    @Test
    @DisplayName("a failed execution holds the cursor")
    fun aFailedExecutionHoldsTheCursor() {
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterExecution("op-1", 2, failure),
            "a failed execution leaves the cursor where it is: the run resumes at the failure",
        )
    }

    @Test
    @DisplayName("an execution that did not fail writes the run, the operation and the stage")
    fun anExecutionThatDidNotFailWritesTheRunTheOperationAndTheStage() {
        advance.advance(
            runId = "run-1",
            decision = advance.decideAfterExecution("op-1", 2, StepOutcome.Success),
        )

        assertEquals(
            listOf(CursorWrite(runId = "run-1", opId = "op-1", stageIndex = 2)),
            store.writes,
            "the write carries exactly the three facts the decision named",
        )
    }

    @Test
    @DisplayName("a failed execution writes nothing")
    fun aFailedExecutionWritesNothing() {
        advance.advance(
            runId = "run-1",
            decision = advance.decideAfterExecution("op-1", 2, failure),
        )

        assertEquals(
            emptyList<CursorWrite>(),
            store.writes,
            "holding the cursor means the store is not touched at all",
        )
    }

    @Test
    @DisplayName("a recovery advances the cursor only when a recovered running operation succeeded")
    fun aRecoveryAdvancesTheCursorOnlyWhenARecoveredRunningOperationSucceeded() {
        assertEquals(
            CursorAdvance.Advance("op-1", 2),
            advance.decideAfterRecovery("op-1", 2, recoveredRunning, StepOutcome.Success),
            "a recovered RUNNING operation that succeeded advances the cursor",
        )
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterRecovery("op-1", 2, recoveredRunning, StepOutcome.Unstable),
            "a recovered operation that settled UNSTABLE does not advance the cursor",
        )
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterRecovery("op-1", 2, recoveredRunning, failure),
            "a recovered operation that settled FAILED does not advance the cursor",
        )
    }

    @Test
    @DisplayName("a recovery that did not recover a running operation holds the cursor")
    fun aRecoveryThatDidNotRecoverARunningOperationHoldsTheCursor() {
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterRecovery("op-1", 2, InvocationReconciliation.Execute, StepOutcome.Success),
            "a fresh execution is not a recovery, so the recovery rule does not apply",
        )
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterRecovery("op-1", 2, InvocationReconciliation.ReuseCompleted, StepOutcome.Success),
            "a reused completion did not recover a running operation",
        )
        assertEquals(
            CursorAdvance.Hold,
            advance.decideAfterRecovery("op-1", 2, InvocationReconciliation.Diverged("op-1"), StepOutcome.Success),
            "a diverged operation did not recover a running operation",
        )
    }
}

/** One recorded cursor write. The fields are exactly what the store was asked to write. */
private data class CursorWrite(
    val runId: String,
    val opId: String,
    val stageIndex: Int,
)

/**
 * A [ReplayCursorStore] that records the writes and refuses the rest.
 *
 * The parallel-frame advance is refused on purpose: this cut moved the cursor of a
 * single canonical operation, and a parallel frame is a different write with a
 * different owner. Recording it would blur the two.
 */
private class RecordingCursorStore : ReplayCursorStore {
    val writes: MutableList<CursorWrite> = mutableListOf()

    override fun load(runId: String): ReplayCursor? = null

    override fun advance(runId: String, opId: String, stageIndex: Int) {
        writes += CursorWrite(runId = runId, opId = opId, stageIndex = stageIndex)
    }

    override fun advancePastParallelFrame(
        runId: String,
        frame: ParallelFrame,
        branchResults: List<BranchExecutionResult>,
        explicitMaxStageIndex: Int?,
    ): StageIndex = throw UnsupportedOperationException(
        "the canonical cursor of one operation does not cross a parallel frame",
    )
}
