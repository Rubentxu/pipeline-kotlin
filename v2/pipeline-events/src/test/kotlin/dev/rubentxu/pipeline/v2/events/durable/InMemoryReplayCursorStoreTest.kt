package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryReplayCursorStoreTest {

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-09-02T10:00:00Z")
    }

    @Test
    fun `load with no cursor returns null`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        assertNull(store.load("run-1"))
    }

    @Test
    fun `advance then load returns the cursor`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        store.advance("run-1", "run-1-s0-0", 1)

        val cursor = store.load("run-1")
        assertEquals("run-1-s0-0", cursor!!.lastOpId)
        assertEquals(1, cursor.stageIndex)
    }

    @Test
    fun `advance to an earlier stage is a no-op`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        store.advance("run-1", "run-1-s0-0", 3)
        store.advance("run-1", "run-1-s0-1", 1)

        assertEquals(3, store.load("run-1")!!.stageIndex)
    }

    @Test
    fun `advance to the same stage updates the op id`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        store.advance("run-1", "run-1-s0-0", 3)
        store.advance("run-1", "run-1-s0-1", 3)

        assertEquals("run-1-s0-1", store.load("run-1")!!.lastOpId)
    }

    @Test
    fun `advance rejects negative stage indices`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        assertThrows(IllegalArgumentException::class.java) {
            store.advance("run-1", "op", -1)
        }
    }

    @Test
    fun `advancePastParallelFrame uses explicit max and CAS semantics`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        val first = store.advancePastParallelFrame(
            "run-1",
            frame = ParallelFrame(branches = emptyList(), joinPolicy = JoinPolicy.ALL_COMPLETE),
            branchResults = emptyList(),
            explicitMaxStageIndex = 5,
        )
        assertEquals(5, first.value)
        assertEquals(5, store.load("run-1:parallel")!!.stageIndex)
        assertEquals("run-1:parallel-completed", store.load("run-1:parallel")!!.lastOpId)

        // Earlier join barrier is a no-op (CAS rule).
        store.advancePastParallelFrame(
            "run-1",
            frame = ParallelFrame(branches = emptyList(), joinPolicy = JoinPolicy.ALL_COMPLETE),
            branchResults = emptyList(),
            explicitMaxStageIndex = 2,
        )
        assertEquals(5, store.load("run-1:parallel")!!.stageIndex)
    }

    @Test
    fun `advancePastParallelFrame computes max from branch results`() {
        val store = InMemoryReplayCursorStore(FixedClock())

        val result = store.advancePastParallelFrame(
            "run-1",
            frame = ParallelFrame(branches = emptyList(), joinPolicy = JoinPolicy.ALL_COMPLETE),
            branchResults = listOf(
                BranchExecutionResult(0, 3),
                BranchExecutionResult(1, 7),
                BranchExecutionResult(2, 5),
            ),
        )

        assertEquals(7, result.value)
    }
}
