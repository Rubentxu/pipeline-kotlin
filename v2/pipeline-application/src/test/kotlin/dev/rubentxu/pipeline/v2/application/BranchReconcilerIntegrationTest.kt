package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * EC-1 Integration Test: BranchReconciler called at start of walkPipelineSpecDurable
 * returns NEEDS_REATTACH for RUNNING branch with checkpoint.
 *
 * Validates that when a branch is left RUNNING (e.g., after SIGKILL), the reconciler
 * correctly identifies it as NEEDS_REATTACH and the result is usable by walkParallelFrame
 * to drive resume decisions.
 */
class BranchReconcilerIntegrationTest {

    private class FakeOperationJournal : OperationJournal {
        private val operations = mutableMapOf<String, Pair<RerunOperation, Long?>>()

        fun addOperation(id: String, status: OperationStatus, attempt: Int, startedAt: Long? = null) {
            val op = RerunOperation(
                id = id,
                fingerprint = Fingerprint("a".repeat(64)),
                input = OperationInput("test", emptyMap(), "run", 1),
                output = null,
                status = status,
                attempt = attempt,
            )
            operations[id] = op to startedAt
        }

        fun clear() = operations.clear()

        override fun append(op: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation, deadlineMs: Long?) {}
        override fun get(opId: String): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? = operations[opId]?.first
        override fun get(opId: String, attempt: Int): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? =
            operations[opId]?.first?.takeIf { it.attempt == attempt }
        override fun listForRun(runId: String): List<dev.rubentxu.pipeline.v2.domain.durable.DurableOperation> {
            return operations.values.map { it.first }
        }
        override fun getDeadlineMs(opId: String, attempt: Int): Long? = null
        override fun getEndedAt(opId: String, attempt: Int): Long? = null
        override fun getStartedAt(opId: String, attempt: Int): Long? = operations[opId]?.second
        override fun beginOperation(
            opId: String,
            attempt: Int,
            fingerprint: String,
            inputJson: String,
            deadlineMs: Long?,
            branchIndex: Int?,
        ) {}
    }

    private class FakeReplayCursorStore : ReplayCursorStore {
        private val cursors = mutableMapOf<String, ReplayCursor>()

        fun setCursor(runId: String, stageIndex: Int) {
            cursors[runId] = ReplayCursor(runId, "op-$runId", stageIndex, System.currentTimeMillis())
        }

        fun clear() = cursors.clear()

        override fun load(runId: String): ReplayCursor? = cursors[runId]
        override fun advance(runId: String, opId: String, stageIndex: Int) {
            cursors[runId] = ReplayCursor(runId, opId, stageIndex, System.currentTimeMillis())
        }
        override fun advancePastParallelFrame(
            runId: String,
            frame: dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame,
            branchResults: List<dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult>,
            explicitMaxStageIndex: Int?,
        ): dev.rubentxu.pipeline.v2.events.durable.StageIndex {
            return dev.rubentxu.pipeline.v2.events.durable.StageIndex(branchResults.maxOfOrNull { it.stageIndex } ?: 0)
        }
    }

    private class FakeClock(private var currentInstant: Instant = Instant.parse("2026-08-24T12:00:00Z")) :
        dev.rubentxu.pipeline.v2.domain.durable.Clock {
        override fun now(): Instant = currentInstant
    }

    @Test
    fun `reconciler called at start of resume returns NEEDS_REATTACH for RUNNING branch with checkpoint`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        val runId = "test-run-001"
        // Simulate: branch-1 was left RUNNING after SIGKILL
        val branch1OpId = OpId.forBranch(runId, stageIndex = 0, stepIndex = 0, branchIndex = 1).format()
        journal.addOperation(branch1OpId, OperationStatus.RUNNING, attempt = 1, System.currentTimeMillis())

        // Set checkpoint at stage 2
        cursorStore.setCursor(runId, stageIndex = 2)

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        // Call reconcileRunningOperations as it would be called from walkPipelineSpecDurable
        val reconciledBranches = runBlocking {
            reconciler.reconcileRunningOperations(runId)
        }

        assertEquals(1, reconciledBranches.size, "Should return exactly one reconciled branch")
        val branch = reconciledBranches[0]

        assertEquals(branch1OpId, branch.opId, "opId should match branch-1's opId")
        assertEquals(2, branch.lastStage, "lastStage should match cursor stageIndex")
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, branch.status, "RUNNING branch with checkpoint should be NEEDS_REATTACH")
        assertTrue(branch.suggestedAction.contains("Re-attach"), "suggestedAction should mention Re-attach")
    }

    @Test
    fun `reconciler returns SUCCESS for completed branches when no RUNNING branches`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        val runId = "test-run-002"
        // No RUNNING operations — all branches completed normally
        val branch0OpId = OpId.forBranch(runId, stageIndex = 0, stepIndex = 0, branchIndex = 0).format()
        journal.addOperation(branch0OpId, OperationStatus.SUCCEEDED, attempt = 1, System.currentTimeMillis())

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val reconciledBranches = runBlocking {
            reconciler.reconcileRunningOperations(runId)
        }

        // M3-R4.4: Returns SUCCESS entries for ALL branches (not just RUNNING)
        // This allows walkParallelFrame to skip SUCCEEDED branches instead of re-executing
        assertEquals(1, reconciledBranches.size, "Should return SUCCESS entry for completed branch")
        assertEquals(ReconciliationStatus.SUCCESS, reconciledBranches[0].status)
    }

    @Test
    fun `reconciler maps branch index correctly for parallel frame`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        val runId = "test-run-003"
        // Simulate: branch-0 and branch-2 completed, branch-1 is RUNNING
        val branch0OpId = OpId.forBranch(runId, stageIndex = 0, stepIndex = 0, branchIndex = 0).format()
        val branch1OpId = OpId.forBranch(runId, stageIndex = 0, stepIndex = 0, branchIndex = 1).format()
        val branch2OpId = OpId.forBranch(runId, stageIndex = 0, stepIndex = 0, branchIndex = 2).format()

        journal.addOperation(branch0OpId, OperationStatus.SUCCEEDED, attempt = 1, System.currentTimeMillis())
        journal.addOperation(branch1OpId, OperationStatus.RUNNING, attempt = 1, System.currentTimeMillis())
        journal.addOperation(branch2OpId, OperationStatus.SUCCEEDED, attempt = 1, System.currentTimeMillis())

        cursorStore.setCursor(runId, stageIndex = 1)

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val reconciledBranches = runBlocking {
            reconciler.reconcileRunningOperations(runId)
        }

        // M3-R4.4: Returns entries for ALL branches (SUCCEEDED + RUNNING)
        // SUCCEEDED branches have status SUCCESS, RUNNING has NEEDS_REATTACH
        assertEquals(3, reconciledBranches.size, "Should return all 3 branches")

        val byBranchIndex = reconciledBranches.associateBy {
            OpId.parse(it.opId)?.branchIndex
        }

        assertEquals(ReconciliationStatus.SUCCESS, byBranchIndex[0]?.status, "branch-0 is SUCCEEDED")
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, byBranchIndex[1]?.status, "branch-1 is RUNNING")
        assertEquals(ReconciliationStatus.SUCCESS, byBranchIndex[2]?.status, "branch-2 is SUCCEEDED")
    }
}
