package com.pipeline.v2.events

import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.ScriptingDiagnostic
import com.pipeline.v2.scripting.ScriptDiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for InMemoryEventStore: monotonic sequence, runId isolation, concurrent append.
 */
class InMemoryEventStoreTest {

    private fun makeEvent(runId: String, seq: Long) = RunStarted(
        eventId = UUID.randomUUID().toString(),
        runId = runId,
        sequence = seq,
        occurredAt = Instant.now(),
        scriptPath = "test.pipeline.kts",
    )

    @Test
    fun `monotonic sequence per runId`() {
        val store = InMemoryEventStore()
        val runId = "run-1"
        store.append(makeEvent(runId, 1L))
        store.append(makeEvent(runId, 2L))
        store.append(makeEvent(runId, 3L))

        val events = store.eventsFor(runId).toList()
        assertEquals(3, events.size)
        assertEquals(1L, events[0].sequence)
        assertEquals(2L, events[1].sequence)
        assertEquals(3L, events[2].sequence)
    }

    @Test
    fun `eventsFor is isolated per runId`() {
        val store = InMemoryEventStore()
        store.append(makeEvent("runA", 1L))
        store.append(makeEvent("runA", 2L))
        store.append(makeEvent("runB", 1L))

        val eventsA = store.eventsFor("runA").toList()
        val eventsB = store.eventsFor("runB").toList()

        assertEquals(2, eventsA.size)
        assertEquals(1, eventsB.size)
    }

    @Test
    fun `concurrent append is safe`() {
        val store = InMemoryEventStore()
        val runId = "concurrent-run"
        val threadCount = 8
        val eventsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val counter = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(threadCount)
        repeat(threadCount) {
            executor.submit {
                repeat(eventsPerThread) {
                    store.append(makeEvent(runId, 0L))
                    counter.incrementAndGet()
                }
                latch.countDown()
            }
        }
        latch.await()
        executor.shutdown()

        val events = store.eventsFor(runId).toList()
        assertEquals(threadCount * eventsPerThread, events.size)
        val sequences = events.map { it.sequence }.toSet()
        assertEquals(events.size, sequences.size, "All sequences must be unique")
    }
}
