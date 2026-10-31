package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The guarantee: a durable waitUntil whose condition never holds ENDS, and it ends on the poll
 * whose interval had reached the backoff ceiling.
 *
 * Before this change the durable branch had NO terminator. The reconciler computed the NEXT
 * interval and asked whether IT would exceed the ceiling, but the interval is capped AT the
 * ceiling, so the question could never be answered `yes`: a condition that never held polled
 * forever, and the terminal the algebra already declares (`DeadlineExceeded`) was unreachable.
 * A wait with no end is not a slow wait, it is a hang that holds the run open.
 *
 * The rule under test is therefore ONE sentence: the backoff ceiling IS the deadline, so a poll
 * whose interval has reached the ceiling is the LAST poll.
 *
 * The rows are arranged so that each mutation of this rule turns EXACTLY ONE of them red:
 * capping the backoff without a ceiling turns `the ladder reaches the ceiling` red, and asking
 * the dead question again turns `the poll at the ceiling is the last one` red. The remaining
 * rows are the collateral detectors: they must stay green under both mutations, so a red row
 * means the rule moved and nothing else did.
 */
class WaitUntilReconcilerTest {

    private val fingerprint = Fingerprint("a".repeat(64))
    private val otherFingerprint = Fingerprint("b".repeat(64))

    @Test
    fun `a fresh waitUntil schedules its first poll`() {
        val decision = WaitUntilReconciler.reconcile(input())

        val scheduled = assertInstanceOf(
            WaitUntilReconciliationDecision.ScheduleAttempt::class.java,
            decision,
            "a waitUntil with no control rows must schedule a poll",
        )
        assertEquals(1, scheduled.attempt, "the first poll is attempt 1")
    }

    @Test
    fun `an unsatisfied poll below the ceiling advances to the next interval`() {
        val decision = WaitUntilReconciler.reconcile(
            input(rows = listOf(failedRow(attempt = 1, backoffMs = 1_000L))),
        )

        val advance = assertInstanceOf(
            WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied::class.java,
            decision,
            "a poll that ran below the ceiling must advance",
        )
        assertEquals(2, advance.attempt, "the next poll is the one after the one that failed")
        assertEquals(2_000L, advance.nextBackoffMs, "the interval doubles from the initial period")
    }

    @Test
    fun `the ladder reaches the ceiling exactly and does not pass it`() {
        // Six failed polls at 1s, 2s, 4s, 8s, 16s, 32s: the seventh poll runs with the
        // ceiling itself, 60s. This row is the guard on the CAP: an interval that could
        // pass the ceiling would make the wait longer than the deadline it declares.
        val decision = WaitUntilReconciler.reconcile(input(rows = ladderRows(upTo = 6)))

        val advance = assertInstanceOf(
            WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied::class.java,
            decision,
            "the poll below the ceiling must advance",
        )
        assertEquals(7, advance.attempt, "the seventh poll is the one that runs at the ceiling")
        assertEquals(60_000L, advance.nextBackoffMs, "the ceiling is reached, never passed")
    }

    @Test
    fun `the poll whose interval reached the ceiling is the last one`() {
        // Seven failed polls, the seventh running with the ceiling: the wait ends THERE.
        // The poll that reached the ceiling is the one the terminal names, because it is
        // the poll the operator can count.
        val decision = WaitUntilReconciler.reconcile(input(rows = ladderRows(upTo = 7)))

        val deadline = assertInstanceOf(
            WaitUntilReconciliationDecision.DeadlineExceeded::class.java,
            decision,
            "a poll at the ceiling must end the wait",
        )
        assertEquals(7, deadline.attempt, "the terminal names the poll that ran at the ceiling")
    }

    @Test
    fun `a persisted timeout row is read back as the same terminal`() {
        // The engine persists FAILED_TIMEOUT and then returns. If it crashed between the
        // two, the next run has to reach the SAME terminal from the row alone.
        val decision = WaitUntilReconciler.reconcile(
            input(
                rows = listOf(
                    WaitUntilControlRowSnapshot(
                        attempt = 4,
                        status = OperationStatus.FAILED_TIMEOUT,
                        currentBackoffMs = 60_000L,
                        fingerprint = fingerprint,
                    ),
                ),
            ),
        )

        val deadline = assertInstanceOf(
            WaitUntilReconciliationDecision.DeadlineExceeded::class.java,
            decision,
            "a persisted timeout row must be read back as the timeout",
        )
        assertEquals(4, deadline.attempt, "the terminal names the row it was read from")
    }

    @Test
    fun `a satisfied poll closes the wait without scheduling another one`() {
        val decision = WaitUntilReconciler.reconcile(
            input(
                rows = listOf(
                    WaitUntilControlRowSnapshot(
                        attempt = 1,
                        status = OperationStatus.SUCCEEDED,
                        currentBackoffMs = 1_000L,
                        fingerprint = fingerprint,
                    ),
                ),
            ),
        )

        val satisfied = assertInstanceOf(
            WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied::class.java,
            decision,
            "a satisfied poll must close the wait",
        )
        assertEquals(1, satisfied.attempt)
    }

    @Test
    fun `a contract that changed under the wait is rejected instead of resumed`() {
        val decision = WaitUntilReconciler.reconcile(
            input(
                rows = listOf(failedRow(attempt = 1, backoffMs = 1_000L, rowFingerprint = otherFingerprint)),
            ),
        )

        assertInstanceOf(
            WaitUntilReconciliationDecision.RejectDivergence::class.java,
            decision,
            "a divergent fingerprint must be rejected",
        )
    }

    @Test
    fun `a poll that was still running is resumed instead of started again`() {
        val decision = WaitUntilReconciler.reconcile(
            input(
                rows = listOf(
                    WaitUntilControlRowSnapshot(
                        attempt = 2,
                        status = OperationStatus.RUNNING,
                        currentBackoffMs = 2_000L,
                        fingerprint = fingerprint,
                    ),
                ),
            ),
        )

        val resumed = assertInstanceOf(
            WaitUntilReconciliationDecision.ResumeAttempt::class.java,
            decision,
            "an in-flight poll must be resumed",
        )
        assertEquals(2, resumed.attempt)
    }

    private fun input(
        rows: List<WaitUntilControlRowSnapshot> = emptyList(),
        initialRecurrencePeriodMs: Long = 1_000L,
        maxBackoffMs: Long = 60_000L,
        currentFingerprint: Fingerprint = fingerprint,
    ): WaitUntilReconciliationInput = WaitUntilReconciliationInput(
        controlIdentity = WaitUntilControlIdentity(operationId = "wait-until-op"),
        initialRecurrencePeriodMs = initialRecurrencePeriodMs,
        maxBackoffMs = maxBackoffMs,
        currentFingerprint = currentFingerprint,
        controlRows = rows,
    )

    private fun failedRow(
        attempt: Int,
        backoffMs: Long,
        rowFingerprint: Fingerprint = fingerprint,
    ): WaitUntilControlRowSnapshot = WaitUntilControlRowSnapshot(
        attempt = attempt,
        status = OperationStatus.FAILED,
        currentBackoffMs = backoffMs,
        fingerprint = rowFingerprint,
    )

    /**
     * The ladder of intervals, one row per poll: poll `n` ran with the interval
     * `2^(n-1) * initial`, capped at the ceiling.
     */
    private fun ladderRows(upTo: Int): List<WaitUntilControlRowSnapshot> =
        (1..upTo).map { attempt ->
            val interval = minOf(1_000L shl (attempt - 1), 60_000L)
            failedRow(attempt = attempt, backoffMs = interval)
        }
}
