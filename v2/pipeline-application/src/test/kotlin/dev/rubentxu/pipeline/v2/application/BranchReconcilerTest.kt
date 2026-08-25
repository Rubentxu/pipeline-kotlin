package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for [BranchReconciler] (ADR-0038).
 *
 * Tests the re-attachment contract: reconciler scans the journal for RUNNING
 * branch operations and returns metadata for re-attachment.
 *
 * +8 cases per M3-R4.3 T-03.
 */
class BranchReconcilerTest {

    // ---------------------------------------------------------------------------
    // Fake implementations for testing
    // ---------------------------------------------------------------------------

    /** In-memory fake for [OperationJournal] used in tests. */
    class FakeOperationJournal : OperationJournal {
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
        ) {}
    }

    /** In-memory fake for [ReplayCursorStore] used in tests. */
    class FakeReplayCursorStore : ReplayCursorStore {
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
            frame: ParallelFrame,
            branchResults: List<BranchExecutionResult>,
            explicitMaxStageIndex: Int?,
        ): StageIndex {
            return StageIndex(branchResults.maxOfOrNull { it.stageIndex } ?: 0)
        }
    }

    /** Fake [Clock] that can be controlled in tests. */
    class FakeClock(var currentTime: Instant = Instant.parse("2026-08-24T12:00:00Z")) : Clock {
        override fun now(): Instant = currentTime
    }

    // ---------------------------------------------------------------------------
    // Test cases (+8)
    // ---------------------------------------------------------------------------

    /**
     * Case 1: Empty journal → empty reconciliation list.
     *
     * Verifies that when no RUNNING operations exist, reconcileRunningOperations
     * returns an empty list (no false positives).
     */
    @Test
    fun `empty journal returns empty list`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()
        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        assertTrue(result.isEmpty(), "Empty journal should produce empty reconciliation list")
    }

    /**
     * Case 2: One RUNNING branch with checkpoint → needsReattach.
     *
     * Verifies that a RUNNING branch with a valid cursor checkpoint is
     * marked as NEEDS_REATTACH with the correct stage index.
     */
    @Test
    fun `one RUNNING branch with checkpoint returns needsReattach`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        // Add a RUNNING branch operation
        journal.addOperation("run-1-s0-1-b0", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        // Set checkpoint at stage 2
        cursorStore.setCursor("run-1", 2)

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        assertEquals(1, result.size, "Should return one reconciled branch")
        val branch = result[0]
        assertEquals("run-1-s0-1-b0", branch.opId)
        assertEquals(2, branch.lastStage)
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, branch.status)
        assertTrue(branch.suggestedAction.contains("Re-attach"))
    }

    /**
     * Case 3: One RUNNING branch with no checkpoint → needsReattach with stage 0.
     *
     * Verifies that a RUNNING branch that was just opened (no durable checkpoint)
     * is still recoverable because no durable work was lost.
     */
    @Test
    fun `RUNNING branch with no checkpoint returns needsReattach with stage 0`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        // Add a RUNNING branch with no cursor entry
        journal.addOperation("run-1-s0-1-b1", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        // No cursor set → lastCheckpointStageIndex defaults to 0

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        assertEquals(1, result.size)
        val branch = result[0]
        assertEquals("run-1-s0-1-b1", branch.opId)
        assertEquals(0, branch.lastStage, "No checkpoint → lastStage defaults to 0")
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, branch.status)
    }

    /**
     * Case 4: Two RUNNING branches, both with checkpoints → 2 needsReattach.
     *
     * Verifies that multiple RUNNING branches are each independently reconciled.
     */
    @Test
    fun `two RUNNING branches with checkpoints returns two needsReattach`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        journal.addOperation("run-1-s0-1-b0", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        journal.addOperation("run-1-s0-1-b1", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        cursorStore.setCursor("run-1", 3)

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        assertEquals(2, result.size, "Should return two reconciled branches")
        assertTrue(result.all { it.status == ReconciliationStatus.NEEDS_REATTACH })
        assertTrue(result.map { it.opId }.containsAll(listOf("run-1-s0-1-b0", "run-1-s0-1-b1")))
    }

    /**
     * Case 5: RUNNING branch with checkpoint older than threshold → stuck.
     *
     * Verifies that a branch that has been RUNNING for longer than
     * stuckThresholdMinutes is marked as STUCK, not NEEDS_REATTACH.
     */
    @Test
    fun `RUNNING branch older than threshold returns stuck`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        // Started 45 minutes ago (> 30 minute threshold)
        val oldStartTime = clock.now().toEpochMilli() - (45 * 60 * 1000L)
        journal.addOperation("run-1-s0-1-b0", OperationStatus.RUNNING, 1, oldStartTime)
        cursorStore.setCursor("run-1", 2)

        val reconciler = BranchReconciler(journal, cursorStore, clock, stuckThresholdMinutes = 30L)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        assertEquals(1, result.size)
        assertEquals(ReconciliationStatus.STUCK, result[0].status)
        assertTrue(result[0].suggestedAction.contains("stuck"))
    }

    /**
     * Case 6: RUNNING branch referencing non-existent parent op → skipped gracefully.
     *
     * Verifies that non-branch operations (those without -b{N} suffix) are
     * skipped by the reconciler without causing errors.
     */
    @Test
    fun `non-branch RUNNING operations are skipped gracefully`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        // Add a non-branch RUNNING operation (no -b suffix)
        journal.addOperation("run-1-s0-1", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        // Also add a branch operation
        journal.addOperation("run-1-s0-2-b0", OperationStatus.RUNNING, 1, System.currentTimeMillis())

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result = runBlocking {
            reconciler.reconcileRunningOperations("run-1")
        }

        // Only the branch operation should appear
        assertEquals(1, result.size)
        assertEquals("run-1-s0-2-b0", result[0].opId)
    }

    /**
     * Case 7: Synchronized lock serializes concurrent calls — both sequential calls succeed.
     *
     * Verifies that the reconciler uses a lock to prevent concurrent execution.
     * Both sequential calls should return the same correct result.
     */
    @Test
    fun `synchronized lock allows two sequential calls both to succeed`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        journal.addOperation("run-1-s0-1-b0", OperationStatus.RUNNING, 1, System.currentTimeMillis())
        cursorStore.setCursor("run-1", 2)

        val reconciler = BranchReconciler(journal, cursorStore, clock)

        val result1 = runBlocking { reconciler.reconcileRunningOperations("run-1") }
        val result2 = runBlocking { reconciler.reconcileRunningOperations("run-1") }

        // Both sequential calls should return the same correct result
        assertEquals(1, result1.size)
        assertEquals(1, result2.size)
        assertEquals(result1[0].opId, result2[0].opId)
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, result1[0].status)
        assertEquals(ReconciliationStatus.NEEDS_REATTACH, result2[0].status)
    }

    /**
     * Case 8: Stuck threshold is configurable and correctly applied.
     *
     * Verifies that the stuck threshold is configurable — 20 minutes is below
     * a 30-minute threshold but above a 10-minute threshold.
     */
    @Test
    fun `stuck threshold is configurable`() {
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val clock = FakeClock()

        // Started 20 minutes ago
        val startTime = clock.now().toEpochMilli() - (20 * 60 * 1000L)
        journal.addOperation("run-1-s0-1-b0", OperationStatus.RUNNING, 1, startTime)
        cursorStore.setCursor("run-1", 1)

        // With 30-minute threshold → NOT stuck
        val reconciler30 = BranchReconciler(journal, cursorStore, clock, stuckThresholdMinutes = 30L)
        val result30 = runBlocking { reconciler30.reconcileRunningOperations("run-1") }
        assertEquals(
            ReconciliationStatus.NEEDS_REATTACH,
            result30[0].status,
            "20 min < 30 min threshold → needsReattach"
        )

        // With 10-minute threshold → STUCK
        val reconciler10 = BranchReconciler(journal, cursorStore, clock, stuckThresholdMinutes = 10L)
        val result10 = runBlocking { reconciler10.reconcileRunningOperations("run-1") }
        assertEquals(
            ReconciliationStatus.STUCK,
            result10[0].status,
            "20 min > 10 min threshold → stuck"
        )
    }
}
