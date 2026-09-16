package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.step.WaitUntilPredicateOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Unit tests for [WaitUntilReconciler].
 *
 * WU-G5R.5 — mirrors [RetryReconcilerTest] but for the backoff-based polling model.
 *
 * ## Status semantics
 *
 * - RUNNING: attempt is in-flight. Reconciler returns ResumeAttempt(attempt).
 * - SUCCEEDED: predicate satisfied. Reconciler returns AdvanceAfterPredicateSatisfied(attempt).
 * - FAILED: predicate unsatisfied (body returned false).
 *   Reconciler computes next backoff from control.currentBackoffMs:
 *   nextBackoff = min(currentBackoffMs * 2, maxBackoffMs).
 *   - If nextBackoff > maxBackoffMs → DeadlineExceeded(attempt).
 *   - Otherwise → AdvanceAfterPredicateUnsatisfied(attempt + 1, nextBackoffMs).
 * - FAILED_TIMEOUT: the attempt that would exceed the ceiling carries this status.
 *   Reconciler returns DeadlineExceeded(attempt) (terminal, no more attempts).
 * - ABORTED: explicit cancellation. Reconciler returns Aborted.
 * - DIVERGENT / LOST: control fingerprint mismatch. Reconciler returns RejectDivergence.
 *
 * ## Backoff model
 *
 * The waitUntil polling loop uses exponential backoff toward a `maxBackoffMs` ceiling.
 *
 * The backoff stored in a control row is the backoff USED for that attempt:
 * - Attempt 1: initialRecurrencePeriodMs
 * - Attempt 2: min(initialRecurrencePeriodMs * 2, maxBackoffMs)
 * - Attempt 3: min(initialRecurrencePeriodMs * 4, maxBackoffMs)
 * - etc.
 *
 * When an attempt returns FAILED, the reconciler computes the NEXT backoff from
 * the current attempt's stored backoff:
 * nextBackoff = min(currentBackoffMs * 2, maxBackoffMs)
 *
 * The attempt that would exceed the ceiling carries FAILED_TIMEOUT (set by the
 * dispatch loop when the ceiling is hit), and the reconciler returns
 * DeadlineExceeded(attempt) for it.
 */
@Timeout(10)
class WaitUntilReconcilerTest {

    // =============================================================================
    // Helpers
    // =============================================================================

    private fun control(
        attempt: Int,
        status: OperationStatus,
        currentBackoffMs: Long = 1000L,
        fingerprint: String = FINGERPRINT,
    ) = WaitUntilControlRowSnapshot(
        attempt = attempt,
        status = status,
        currentBackoffMs = currentBackoffMs,
        fingerprint = Fingerprint(fingerprint),
    )

    private fun input(
        initialRecurrencePeriodMs: Long = 1000L,
        maxBackoffMs: Long = 8000L,
        controls: List<WaitUntilControlRowSnapshot> = emptyList(),
    ) = WaitUntilReconciliationInput(
        controlIdentity = WaitUntilControlIdentity(operationId = CONTROL_OP_ID),
        initialRecurrencePeriodMs = initialRecurrencePeriodMs,
        maxBackoffMs = maxBackoffMs,
        currentFingerprint = Fingerprint(FINGERPRINT),
        controlRows = controls,
    )

    private fun decide(input: WaitUntilReconciliationInput): WaitUntilReconciliationDecision =
        WaitUntilReconciler.reconcile(input)

    companion object {
        private const val CONTROL_OP_ID = "run-123/build/waitUntil/0/0"
        private const val FINGERPRINT = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
    }

    // =============================================================================
    // W0 — Fresh: no control rows exist
    // =============================================================================

    @Nested
    @DisplayName("W0 — fresh (no control rows)")
    inner class W0Fresh {
        @Test
        fun `fresh — schedules first attempt`() {
            val decision = decide(input())
            assertEquals(
                WaitUntilReconciliationDecision.ScheduleAttempt(1),
                decision,
                "fresh state must schedule attempt 1",
            )
        }

        @Test
        fun `fresh with large initial period — schedules first attempt`() {
            val decision = decide(input(initialRecurrencePeriodMs = 5000L))
            assertEquals(
                WaitUntilReconciliationDecision.ScheduleAttempt(1),
                decision,
            )
        }
    }

    // =============================================================================
    // W1 — Attempt in flight (RUNNING)
    // =============================================================================

    @Nested
    @DisplayName("W1 — attempt in flight (RUNNING)")
    inner class W1InFlight {
        @Test
        fun `resumes in-flight attempt without re-scheduling`() {
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.RUNNING))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.ResumeAttempt(1),
                decision,
                "in-flight attempt 1 must be resumed, not re-scheduled",
            )
        }

        @Test
        fun `attempt 2 in flight — resumes attempt 2`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.SUCCEEDED),
                        control(2, OperationStatus.RUNNING, currentBackoffMs = 2000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.ResumeAttempt(2),
                decision,
            )
        }
    }

    // =============================================================================
    // W2 — Predicate satisfied (SUCCEEDED)
    // =============================================================================

    @Nested
    @DisplayName("W2 — predicate satisfied (SUCCEEDED)")
    inner class W2Satisfied {
        @Test
        fun `satisfied at attempt 2 — reuses success without re-running body`() {
            val decision = decide(
                input(controls = listOf(
                    control(1, OperationStatus.FAILED, currentBackoffMs = 1000L),
                    control(2, OperationStatus.SUCCEEDED, currentBackoffMs = 2000L),
                )),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied(2),
                decision,
                "satisfied at attempt 2 must advance and close without re-running",
            )
        }

        @Test
        fun `satisfied at first attempt — closes immediately`() {
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.SUCCEEDED))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateSatisfied(1),
                decision,
            )
        }
    }

    // =============================================================================
    // W3 — Predicate unsatisfied (FAILED), below backoff ceiling
    // =============================================================================

    @Nested
    @DisplayName("W3 — predicate unsatisfied (FAILED), below ceiling")
    inner class W3Unsatisfied {
        /**
         * Attempt 1 FAILED with currentBackoffMs=1000.
         * nextBackoff = min(1000 * 2, 8000) = 2000.
         * AdvanceAfterPredicateUnsatisfied(attempt=2, nextBackoffMs=2000).
         */
        @Test
        fun `unsatisfied attempt 1 — advances to attempt 2 with backoff 2000ms`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(control(1, OperationStatus.FAILED, currentBackoffMs = 1000L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(attempt = 2, nextBackoffMs = 2000L),
                decision,
            )
        }

        /**
         * Attempt 2 FAILED with currentBackoffMs=2000.
         * nextBackoff = min(2000 * 2, 8000) = 4000.
         * AdvanceAfterPredicateUnsatisfied(attempt=3, nextBackoffMs=4000).
         */
        @Test
        fun `unsatisfied attempt 2 — advances to attempt 3 with backoff 4000ms`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.SUCCEEDED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED, currentBackoffMs = 2000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(attempt = 3, nextBackoffMs = 4000L),
                decision,
            )
        }

        /**
         * Attempt 1 FAILED with currentBackoffMs=500 (custom initial).
         * nextBackoff = min(500 * 2, 8000) = 1000.
         * AdvanceAfterPredicateUnsatisfied(attempt=2, nextBackoffMs=1000).
         */
        @Test
        fun `unsatisfied with custom initial period — correct backoff doubling`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 500L,
                    maxBackoffMs = 8000L,
                    controls = listOf(control(1, OperationStatus.FAILED, currentBackoffMs = 500L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(attempt = 2, nextBackoffMs = 1000L),
                decision,
            )
        }

        /**
         * Attempt 2 FAILED with currentBackoffMs=1000 (still below ceiling).
         * nextBackoff = min(1000 * 2, 8000) = 2000.
         * AdvanceAfterPredicateUnsatisfied(attempt=3, nextBackoffMs=2000).
         */
        @Test
        fun `unsatisfied attempt 2 with backoff 1000 — advances to attempt 3 with backoff 2000ms`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.SUCCEEDED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED, currentBackoffMs = 1000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(attempt = 3, nextBackoffMs = 2000L),
                decision,
            )
        }
    }

    // =============================================================================
    // W4 — Backoff ceiling reached (FAILED_TIMEOUT)
    // =============================================================================

    @Nested
    @DisplayName("W4 — backoff ceiling reached (FAILED_TIMEOUT)")
    inner class W4DeadlineExceeded {
        /**
         * Attempt 1 returned FAILED_TIMEOUT.
         * FAILED_TIMEOUT always returns DeadlineExceeded(attempt).
         */
        @Test
        fun `FAILED_TIMEOUT at attempt 1 — deadline exceeded at attempt 1`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(control(1, OperationStatus.FAILED_TIMEOUT, currentBackoffMs = 1000L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.DeadlineExceeded(1),
                decision,
            )
        }

        /**
         * Attempt 2 returned FAILED_TIMEOUT (currentBackoffMs = maxBackoffMs = 2000).
         * FAILED_TIMEOUT at attempt n → DeadlineExceeded(n) (terminal).
         */
        @Test
        fun `FAILED_TIMEOUT at attempt 2 — deadline exceeded at attempt 2`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 2000L,
                    controls = listOf(
                        control(1, OperationStatus.SUCCEEDED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED_TIMEOUT, currentBackoffMs = 2000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.DeadlineExceeded(2),
                decision,
            )
        }

        /**
         * Attempt 1 FAILED with currentBackoffMs = maxBackoffMs = 5000.
         * nextBackoff = min(10000, 5000) = 5000 = maxBackoffMs (not >).
         * Since 5000 > 5000 is false, it advances to attempt 2.
         * Attempt 2 carries FAILED_TIMEOUT (set by dispatch loop when ceiling was hit).
         */
        @Test
        fun `FAILED at ceiling — advance then FAILED_TIMEOUT next`() {
            // Attempt 1 FAILED with currentBackoffMs = maxBackoffMs = 5000.
            // nextBackoff = min(10000, 5000) = 5000 = ceiling → advance.
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 5000L,
                    maxBackoffMs = 5000L,
                    controls = listOf(control(1, OperationStatus.FAILED, currentBackoffMs = 5000L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(attempt = 2, nextBackoffMs = 5000L),
                decision,
            )
        }
    }

    // =============================================================================
    // Supersede-skip: stale terminal attempts with a successor
    // =============================================================================

    @Nested
    @DisplayName("supersede-skip — stale terminal with successor")
    inner class SupersedeSkip {
        @Test
        fun `superseded stale attempt with RUNNING successor — resumes the active attempt`() {
            val decision = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.FAILED, currentBackoffMs = 1000L), // stale
                        control(2, OperationStatus.RUNNING, currentBackoffMs = 2000L), // active
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.ResumeAttempt(2),
                decision,
                "superseded stale attempt must be skipped; active RUNNING must be resumed",
            )
        }
    }

    // =============================================================================
    // Terminal failure statuses
    // =============================================================================

    @Nested
    @DisplayName("terminal failure statuses")
    inner class TerminalFailures {
        @Test
        fun `ABORTED — returns Aborted decision`() {
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.ABORTED))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.Aborted(
                    operationId = CONTROL_OP_ID,
                    reason = "waitUntil aborted at attempt 1",
                ),
                decision,
            )
        }

        @Test
        fun `DIVERGENT — returns RejectDivergence decision`() {
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.DIVERGENT))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.RejectDivergence(
                    operationId = CONTROL_OP_ID,
                    reason = "control row at attempt 1 is DIVERGENT",
                ),
                decision,
            )
        }

        @Test
        fun `LOST — returns RejectDivergence decision`() {
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.LOST))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.RejectDivergence(
                    operationId = CONTROL_OP_ID,
                    reason = "control row at attempt 1 is LOST",
                ),
                decision,
            )
        }

        @Test
        fun `PENDING — matched by RUNNING PENDING branch returning ResumeAttempt`() {
            // PENDING is matched by the RUNNING/PENDING branch → ResumeAttempt.
            val decision = decide(
                input(controls = listOf(control(1, OperationStatus.PENDING))),
            )
            assertEquals(
                WaitUntilReconciliationDecision.ResumeAttempt(1),
                decision,
            )
        }
    }

    // =============================================================================
    // Backoff boundary cases
    // =============================================================================

    @Nested
    @DisplayName("backoff boundary cases")
    inner class BackoffBoundaries {
        @Test
        fun `large initial with small ceiling — FAILED at ceiling advances then FAILED_TIMEOUT`() {
            // Attempt 1 FAILED with currentBackoffMs = maxBackoffMs = 5000.
            // nextBackoff = min(10000, 5000) = 5000 = ceiling → advance to 2.
            val d1 = decide(
                input(
                    initialRecurrencePeriodMs = 5000L,
                    maxBackoffMs = 5000L,
                    controls = listOf(control(1, OperationStatus.FAILED, currentBackoffMs = 5000L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(2, 5000L),
                d1,
            )

            // Attempt 2 FAILED_TIMEOUT: DeadlineExceeded(2)
            val d2 = decide(
                input(
                    initialRecurrencePeriodMs = 5000L,
                    maxBackoffMs = 5000L,
                    controls = listOf(
                        control(1, OperationStatus.FAILED, currentBackoffMs = 5000L),
                        control(2, OperationStatus.FAILED_TIMEOUT, currentBackoffMs = 5000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.DeadlineExceeded(2),
                d2,
            )
        }

        @Test
        fun `sequence — 3 attempts FAILED then FAILED_TIMEOUT at ceiling`() {
            // Attempt 1 FAILED (currentBackoffMs=1000):
            // nextBackoff = min(2000, 8000) = 2000 → advance to 2
            val d1 = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(control(1, OperationStatus.FAILED, currentBackoffMs = 1000L)),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(2, 2000L),
                d1,
            )

            // Attempt 2 FAILED (currentBackoffMs=2000):
            // nextBackoff = min(4000, 8000) = 4000 → advance to 3
            val d2 = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.FAILED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED, currentBackoffMs = 2000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(3, 4000L),
                d2,
            )

            // Attempt 3 FAILED (currentBackoffMs=4000):
            // nextBackoff = min(8000, 8000) = 8000 = ceiling (not >).
            // Advance to attempt 4.
            val d3 = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.FAILED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED, currentBackoffMs = 2000L),
                        control(3, OperationStatus.FAILED, currentBackoffMs = 4000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.AdvanceAfterPredicateUnsatisfied(4, 8000L),
                d3,
            )

            // Attempt 4 FAILED_TIMEOUT (currentBackoffMs=8000 = ceiling):
            // FAILED_TIMEOUT always returns DeadlineExceeded(4)
            val d4 = decide(
                input(
                    initialRecurrencePeriodMs = 1000L,
                    maxBackoffMs = 8000L,
                    controls = listOf(
                        control(1, OperationStatus.FAILED, currentBackoffMs = 1000L),
                        control(2, OperationStatus.FAILED, currentBackoffMs = 2000L),
                        control(3, OperationStatus.FAILED, currentBackoffMs = 4000L),
                        control(4, OperationStatus.FAILED_TIMEOUT, currentBackoffMs = 8000L),
                    ),
                ),
            )
            assertEquals(
                WaitUntilReconciliationDecision.DeadlineExceeded(4),
                d4,
            )
        }
    }
}
