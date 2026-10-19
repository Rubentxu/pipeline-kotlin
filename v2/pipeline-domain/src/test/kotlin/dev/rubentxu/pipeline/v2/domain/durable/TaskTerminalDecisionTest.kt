package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a consumer that asks "did this task succeed?" through [isSuccessFromDecision]
 * is never told `true` for a task the watchdog killed or that was cancelled.
 *
 * That question is not academic. [decideTaskTerminal] is published ABI of `pipeline-domain` and
 * four call sites depend on it -- `ShExecution`, `TarWriter`, `runCaptured` and
 * `ProcessDurableTaskRuntime` -- and the two of them that abort work on failure do it by asking
 * this one question. The watchdog kill is the case a boolean pair gets wrong: the process can
 * leave an exit code that a naive `exitCode == 0` would read as success, and the whole reason
 * the ADT exists is that the CAUSE survives the projection.
 *
 * `elapsedMs` is deliberately not asserted: measured 2026-10-19, no consumer reads it.
 */
class TaskTerminalDecisionTest {

    @Test
    fun aWatchdogKillIsNotASuccessEvenWhenTheExitCodeIsZero() {
        // The watchdog killed the process, yet the shell had already published a zero status.
        // The cause is the kill, so the answer to "did it succeed" has to be no.
        val decision = decideTaskTerminal(result(exitCode = 0, timedOut = true))

        assertTrue(decision is TaskTerminalDecision.TimedOut, "the watchdog kill must survive the projection, was: $decision")
        assertFalse(isSuccessFromDecision(decision), "a task the watchdog killed is not a success, whatever its exit code says")
    }

    @Test
    fun aCancellationIsNotASuccessEvenWhenTheExitCodeIsZero() {
        val decision = decideTaskTerminal(result(exitCode = 0, cancelled = true))

        assertTrue(decision is TaskTerminalDecision.Cancelled, "the cancellation must survive the projection, was: $decision")
        assertFalse(isSuccessFromDecision(decision), "a cancelled task is not a success, whatever its exit code says")
    }

    @Test
    fun aNonZeroExitIsAFailureButKeepsTheProcessAsTheCause() {
        // The process killed itself; nobody timed it out. If the exit code alone decided the
        // CAUSE, this would be indistinguishable from the watchdog case above.
        val decision = decideTaskTerminal(result(exitCode = 137))

        assertTrue(decision is TaskTerminalDecision.Exited, "a non-zero exit must not be reclassified as a timeout, was: $decision")
        assertFalse(isSuccessFromDecision(decision), "a non-zero exit is not a success")
    }

    @Test
    fun aPlainZeroExitIsTheOnlySuccess() {
        assertTrue(isSuccessFromDecision(decideTaskTerminal(result(exitCode = 0))))
    }

    private fun result(exitCode: Int, timedOut: Boolean = false, cancelled: Boolean = false) =
        TaskExecutionResult(
            exitCode = exitCode,
            timedOut = timedOut,
            cancelled = cancelled,
            startedAtEpochMs = 1_000,
            endedAtEpochMs = 1_500,
        )
}
