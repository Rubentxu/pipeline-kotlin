package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Contract tests for [ReplayCursorStore] interface.
 * Tests the interface contract per M3-R1 design.md §8 and C-014.
 */
class ReplayCursorStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun freshStore(clock: Clock): ReplayCursorStore {
        val dbPath = tempDir.resolve("cursor-contract-test.db").toString()
        val eventStore = SqliteEventStore(dbPath)
        return SqliteReplayCursorStoreImpl(eventStore.underlyingConnectionFactory(), clock)
    }

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    @Test
    fun `load returns null for unknown runId`() {
        val store = freshStore(systemClock)
        assertNull(store.load("nonexistent-run"))
    }

    @Test
    fun `advance then load returns the cursor`() {
        val store = freshStore(systemClock)
        store.advance("run-1", "op-5", 2)
        val cursor = store.load("run-1")
        assertNotNull(cursor)
        assertEquals("run-1", cursor!!.runId)
        assertEquals("op-5", cursor.lastOpId)
        assertEquals(2, cursor.stageIndex)
    }

    @Test
    fun `advance is idempotent - later advance wins`() {
        val store = freshStore(systemClock)
        store.advance("run-1", "op-first", 0)
        store.advance("run-1", "op-second", 1)
        val cursor = store.load("run-1")
        assertNotNull(cursor)
        assertEquals("op-second", cursor!!.lastOpId)
        assertEquals(1, cursor.stageIndex)
    }

    // M3-R4.3 T-05: advancePastParallelFrame derived key tests

    /**
     * Case 1: advancePastParallelFrame with runId="run-A" persists run-A:parallel as cursor key.
     *
     * Verifies that when advancePastParallelFrame is called with runId="run-A",
     * the cursor is stored with run_id="run-A:parallel" (derived from runId),
     * not the hardcoded "parallel-frame".
     */
    @Test
    fun `advancePastParallelFrame derives runId in cursor key`() {
        val store = freshStore(systemClock)
        val frame = ParallelFrame(
            branches = listOf(
                BranchSpec("branch-0", emptyList()),
                BranchSpec("branch-1", emptyList()),
            ),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        store.advancePastParallelFrame(
            runId = "run-A",
            frame = frame,
            branchResults = emptyList(),
            explicitMaxStageIndex = 5,
        )

        // The cursor should be stored with key "run-A:parallel"
        val cursor = store.load("run-A:parallel")
        assertNotNull(cursor, "Cursor should be stored with derived key run-A:parallel")
        assertEquals("run-A:parallel", cursor!!.runId)
        assertEquals("run-A:parallel-completed", cursor.lastOpId)
        assertEquals(5, cursor.stageIndex)
    }

    /**
     * Case 2: advancePastParallelFrame writes runId:parallel-completed as last_op_id.
     *
     * Verifies that the last_op_id column stores the derived key
     * "$runId:parallel-completed" rather than the hardcoded "parallel-frame-completed".
     */
    @Test
    fun `advancePastParallelFrame derives completed key in lastOpId`() {
        val store = freshStore(systemClock)
        val frame = ParallelFrame(
            branches = listOf(BranchSpec("branch-0", emptyList())),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        store.advancePastParallelFrame(
            runId = "run-B",
            frame = frame,
            branchResults = emptyList(),
            explicitMaxStageIndex = 3,
        )

        val cursor = store.load("run-B:parallel")
        assertNotNull(cursor)
        assertEquals("run-B:parallel-completed", cursor!!.lastOpId,
            "lastOpId should use derived key run-B:parallel-completed, not hardcoded string")
    }
}
