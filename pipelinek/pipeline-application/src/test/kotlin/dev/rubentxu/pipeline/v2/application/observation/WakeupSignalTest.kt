package dev.rubentxu.pipeline.v2.application.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The signal's contract has two halves and both are load-bearing: a writer
 * that announces must wake a parked reader (that is the entire latency win),
 * and a reader that parks must always come back at its deadline (that is
 * what makes dropped hints harmless). The tests use no fixed sleeps as
 * oracles: a present signal returns immediately, and an absent one is asked
 * for the shortest deadline the API accepts.
 */
class WakeupSignalTest {

    @Test
    fun `a signaled reader returns a hint without waiting for its deadline`() {
        val signal = WakeupSignal()
        assertTrue(signal.signal())

        assertEquals(ParkOutcome.Hint, signal.await(timeoutMs = 10_000))
    }

    @Test
    fun `a reader with no signal returns at its deadline`() {
        val signal = WakeupSignal()

        assertEquals(ParkOutcome.Deadline, signal.await(timeoutMs = 1))
    }

    @Test
    fun `a full queue drops further signals and the parked reader still wakes`() {
        val signal = WakeupSignal(capacity = 1)

        assertTrue(signal.signal())
        assertFalse(signal.signal())

        assertEquals(ParkOutcome.Hint, signal.await(timeoutMs = 10_000))
    }

    @Test
    fun `a burst wakes once per delivered signal and then falls back to the deadline`() {
        val signal = WakeupSignal(capacity = 4)
        repeat(3) { signal.signal() }

        assertEquals(ParkOutcome.Hint, signal.await(timeoutMs = 10_000))
        assertEquals(ParkOutcome.Hint, signal.await(timeoutMs = 10_000))
        assertEquals(ParkOutcome.Hint, signal.await(timeoutMs = 10_000))
        assertEquals(ParkOutcome.Deadline, signal.await(timeoutMs = 1))
    }

    @Test
    fun `an interrupted reader stops and keeps the interrupt flag for its owner`() {
        val signal = WakeupSignal()
        Thread.currentThread().interrupt()

        assertEquals(ParkOutcome.Interrupted, signal.await(timeoutMs = 10_000))
        assertTrue(Thread.interrupted())
    }
}
