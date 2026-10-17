package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reducer laws for [TaskTerminalDecision].
 *
 * Why these tests exist:
 *  - The legacy `when { result.timedOut -> Cancelled; result.cancelled -> Cancelled; else -> Exited }`
 *    in `ShExecution.executeNonDurableInvocation` is a 3-way decision over
 *    two mutually-non-exclusive booleans plus an exit code. A boolean pair
 *    makes an "unknown / inconsistent / both" state representable that the
 *    runtime's `init` already refuses, so the decider's job is to project
 *    the runtime's already-validated input into the smallest ADT that makes
 *    the three legitimate shapes distinct and the impossible one unrepresentable.
 *  - These are HF0 / pure-decider laws. No process, no clock, no filesystem.
 *    A reader who can name the project root and JUnit can rerun them.
 */
class TaskTerminalDecisionTest {

    private fun result(
        exitCode: Int = 0,
        timedOut: Boolean = false,
        cancelled: Boolean = false,
        start: Long = 0L,
        end: Long = 100L,
    ): TaskExecutionResult = TaskExecutionResult(
        exitCode = exitCode,
        timedOut = timedOut,
        cancelled = cancelled,
        startedAtEpochMs = start,
        endedAtEpochMs = end,
    )

    @Test
    fun `a result without flags is Exited with its exit code and elapsed time`() {
        val decision = decideTaskTerminal(result(exitCode = 7, start = 1_000, end = 1_750))
        assertTrue(decision is TaskTerminalDecision.Exited, "expected Exited, got $decision")
        decision as TaskTerminalDecision.Exited
        assertEquals(7, decision.exitCode)
        assertEquals(750L, decision.elapsedMs)
    }

    @Test
    fun `a zero-exit result without flags is Exited(0), and is the only path that satisfies succeeded`() {
        val decision = decideTaskTerminal(result(exitCode = 0))
        assertEquals(TaskTerminalDecision.Exited(0, 100L), decision)
    }

    @Test
    fun `a timedOut result projects to TimedOut, not Cancelled and not Exited`() {
        val decision = decideTaskTerminal(result(exitCode = 137, timedOut = true))
        assertEquals(TaskTerminalDecision.TimedOut(100L), decision)
    }

    @Test
    fun `a cancelled result projects to Cancelled, not TimedOut and not Exited`() {
        val decision = decideTaskTerminal(result(exitCode = 130, cancelled = true))
        assertEquals(TaskTerminalDecision.Cancelled(100L), decision)
    }

    @Test
    fun `timedOut wins over exitCode when both fields are populated`() {
        // The runtime's init rejects timedOut && cancelled, but exitCode is
        // allowed to be non-zero when timedOut; the watchdog kill leaves
        // whatever exit code the OS recorded. The decider must still classify
        // as TimedOut because the *cause* of termination is the watchdog.
        val decision = decideTaskTerminal(result(exitCode = 137, timedOut = true))
        assertTrue(decision is TaskTerminalDecision.TimedOut, "got $decision")
    }

    @Test
    fun `elapsedMs is always end - start regardless of classification`() {
        listOf(
            result(start = 10, end = 25, timedOut = true) to TaskTerminalDecision.TimedOut(15L),
            result(start = 10, end = 25, cancelled = true) to TaskTerminalDecision.Cancelled(15L),
            result(start = 10, end = 25, exitCode = 2) to TaskTerminalDecision.Exited(2, 15L),
        ).forEach { (input, expected) ->
            assertEquals(expected, decideTaskTerminal(input), "input=$input")
        }
    }

    @Test
    fun `decider is a pure function — same input produces the same output across calls`() {
        val input = result(exitCode = 0, start = 100, end = 250)
        val first = decideTaskTerminal(input)
        val second = decideTaskTerminal(input)
        val third = decideTaskTerminal(input)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `the three legitimate shapes are exhaustive and mutually exclusive`() {
        // exhaustive check is compile-time; this test guards against a future
        // refactor that adds a fourth case without updating the decider.
        val cases = listOf(
            decideTaskTerminal(result()),
            decideTaskTerminal(result(exitCode = 1)),
            decideTaskTerminal(result(timedOut = true)),
            decideTaskTerminal(result(cancelled = true)),
        )
        val kinds = cases.map { it::class }.toSet()
        assertEquals(3, kinds.size, "expected exactly 3 distinct shapes, got $kinds")
    }

    @Test
    fun `isSuccessFromDecision is true only for Exited(0, _)`() {
        assertTrue(isSuccessFromDecision(TaskTerminalDecision.Exited(0, 100L)))
        assertEquals(false, isSuccessFromDecision(TaskTerminalDecision.Exited(1, 100L)))
        assertEquals(false, isSuccessFromDecision(TaskTerminalDecision.TimedOut(100L)))
        assertEquals(false, isSuccessFromDecision(TaskTerminalDecision.Cancelled(100L)))
    }
}