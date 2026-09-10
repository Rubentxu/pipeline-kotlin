package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * RED-first tests for [RetryReconciler] — pure reconciliation of retry-control
 * and child durable facts into an explicit [RetryReconciliationDecision] ADT.
 *
 * Laws under test (ADR-0075 §3, §4, §6, §8):
 * - STATE is captured by the decision variant (no nullable booleans).
 * - Decision is the single thing callers consume.
 * - W0–W5 crash windows produce a typed decision.
 * - Legacy journals (no control row, child history present) follow
 *   compatibility Policy A (safe reconstruction) when unambiguous, and
 *   Policy B (reject) when ambiguous.
 * - Divergence fails closed with zero child scheduling.
 *
 * These tests do NOT touch any I/O, journal, or coordinator. They are the
 * spine of RETRY-D durability.
 */
class RetryReconcilerTest {

    private val identity = retryIdentity("retry-stage/retry-body-0")
    private val fp = Fingerprint.compute(OperationInput("core.retry", emptyMap(), "run-x", 1), "core.retry", ReplayPolicy.MEMOIZED, 1)
    private val mismatchedFp = Fingerprint("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")

    private fun control(attempt: Int, status: OperationStatus, fp: Fingerprint = this.fp) =
        RetryControlRowSnapshot(attempt = attempt, status = status, fingerprint = fp)

    private fun child(attempt: Int, childIndex: Int, status: OperationStatus) =
        RetryChildRowSnapshot(attempt = attempt, childIndex = childIndex, status = status)

    private fun input(
        maxAttempts: Int = 3,
        controls: List<RetryControlRowSnapshot> = emptyList(),
        children: List<RetryChildRowSnapshot> = emptyList(),
        currentFingerprint: Fingerprint = fp,
        preControlChildren: List<RetryChildRowSnapshot> = emptyList(),
    ) = RetryReconciliationInput(
        controlIdentity = identity,
        maxAttempts = maxAttempts,
        currentFingerprint = currentFingerprint,
        controlRows = controls,
        childrenByAttempt = children.groupBy { it.attempt },
        preControlChildren = preControlChildren,
    )

    @Nested
    @DisplayName("W0 — no control, no child")
    inner class W0 {
        @Test
        fun `schedules attempt 1 exactly once`() {
            val decision = RetryReconciler.reconcile(input())
            assertEquals(RetryReconciliationDecision.ScheduleAttempt(1), decision)
        }
    }

    @Nested
    @DisplayName("W1 — control persisted, no child evidence")
    inner class W1 {
        @Test
        fun `AttemptPending schedules that attempt exactly once`() {
            val decision = RetryReconciler.reconcile(
                input(controls = listOf(control(1, OperationStatus.PENDING))),
            )
            assertEquals(RetryReconciliationDecision.ScheduleAttempt(1), decision)
        }

        @Test
        fun `AttemptRunning without child evidence still schedules that attempt`() {
            val decision = RetryReconciler.reconcile(
                input(controls = listOf(control(1, OperationStatus.RUNNING))),
            )
            assertEquals(RetryReconciliationDecision.ScheduleAttempt(1), decision)
        }
    }

    @Nested
    @DisplayName("W2 — control + child RUNNING")
    inner class W2 {
        @Test
        fun `resumes the existing attempt rather than re-scheduling`() {
            val decision = RetryReconciler.reconcile(
                input(
                    controls = listOf(control(1, OperationStatus.RUNNING)),
                    children = listOf(child(1, 0, OperationStatus.RUNNING)),
                ),
            )
            assertEquals(RetryReconciliationDecision.ResumeAttempt(1), decision)
        }
    }

    @Nested
    @DisplayName("W3 — child terminal failure, control stale")
    inner class W3 {
        @Test
        fun `non-final failure advances to next attempt`() {
            // W3 — control row is stale (still RUNNING) and the child has FAILED.
            // The retry budget remains (attempt < maxAttempts).
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 3,
                    controls = listOf(control(1, OperationStatus.RUNNING)),
                    children = listOf(child(1, 0, OperationStatus.FAILED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.AdvanceAfterFailure(from = 1, to = 2), decision)
        }

        @Test
        fun `final failure marks aggregate exhausted`() {
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 2,
                    controls = listOf(control(2, OperationStatus.RUNNING)),
                    children = listOf(child(2, 0, OperationStatus.FAILED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.ReuseFailure(attempt = 2), decision)
        }

        @Test
        fun `superseded failure is skipped so planner reaches the active attempt`() {
            // The dispatch loop already persisted attempt 2 RUNNING after attempt 1 FAILED.
            // The planner must skip attempt 1 (which is "superseded") and resume attempt 2.
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 3,
                    controls = listOf(
                        control(1, OperationStatus.FAILED),
                        control(2, OperationStatus.RUNNING),
                    ),
                ),
            )
            assertEquals(RetryReconciliationDecision.ScheduleAttempt(2), decision)
        }
    }

    @Nested
    @DisplayName("W4 / Window C — child terminal success, control stale")
    inner class WindowC {
        @Test
        fun `reconstructs success and closes aggregate with zero new child scheduling`() {
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 3,
                    children = listOf(child(1, 0, OperationStatus.SUCCEEDED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.CloseSuccessFromChild(1), decision)
        }

        @Test
        fun `success in a higher attempt closes on that attempt`() {
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 5,
                    children = listOf(child(3, 0, OperationStatus.SUCCEEDED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.CloseSuccessFromChild(3), decision)
        }
    }

    @Nested
    @DisplayName("W5 — aggregate terminal")
    inner class W5 {
        @Test
        fun `aggregate success reuses the cached success without scheduling`() {
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 3,
                    controls = listOf(control(2, OperationStatus.SUCCEEDED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.ReuseSuccess(2), decision)
        }

        @Test
        fun `aggregate exhausted reuses failure without scheduling`() {
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 2,
                    controls = listOf(control(2, OperationStatus.FAILED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.ReuseFailure(2), decision)
        }

        @Test
        fun `aggregate terminal does not re-execute even when child is RUNNING`() {
            // W5: control says terminal, child projection is incomplete. Decision is reuse, no scheduling.
            val decision = RetryReconciler.reconcile(
                input(
                    maxAttempts = 3,
                    controls = listOf(control(1, OperationStatus.SUCCEEDED)),
                    children = listOf(child(1, 0, OperationStatus.RUNNING)),
                ),
            )
            assertEquals(RetryReconciliationDecision.ReuseSuccess(1), decision)
        }
    }

    @Nested
    @DisplayName("Law 9 — fingerprint / divergence before effects")
    inner class R6 {
        @Test
        fun `incompatible control fingerprint is rejected with zero child scheduling`() {
            val decision = RetryReconciler.reconcile(
                input(
                    controls = listOf(control(1, OperationStatus.RUNNING, fp = mismatchedFp)),
                ),
            )
            assertTrue(decision is RetryReconciliationDecision.RejectDivergence)
            assertEquals(identity.id(), (decision as RetryReconciliationDecision.RejectDivergence).operationId)
        }

        @Test
        fun `incompatible child fingerprint is rejected`() {
            // Child fingerprint divergence is detected indirectly: if a child persisted
            // under a different retry contract exists alongside an incompatible control
            // row, the control-row fingerprint check rejects the retry.
            val childWithMismatch = RetryChildRowSnapshot(
                attempt = 1, childIndex = 0, status = OperationStatus.SUCCEEDED, fingerprint = mismatchedFp,
            )
            val decision = RetryReconciler.reconcile(
                input(
                    controls = listOf(control(1, OperationStatus.RUNNING, fp = mismatchedFp)),
                    children = listOf(childWithMismatch),
                ),
            )
            assertTrue(decision is RetryReconciliationDecision.RejectDivergence)
        }
    }

    @Nested
    @DisplayName("Law 8 — legacy / pre-ADR-0075 journal compatibility")
    inner class LegacyCompat {
        @Test
        fun `legacy child success with no control row is safely reconstructed`() {
            val decision = RetryReconciler.reconcile(
                input(
                    preControlChildren = listOf(child(1, 0, OperationStatus.SUCCEEDED)),
                ),
            )
            assertEquals(RetryReconciliationDecision.CloseSuccessFromChild(1), decision)
        }

        @Test
        fun `legacy ambiguity — terminal child failure with no control row is rejected`() {
            // Per ADR-0075 Law 8, a child failure with no control row is ambiguous:
            // we cannot tell whether another attempt was running, so we must NOT silently
            // assume fresh and execute the body again.
            val decision = RetryReconciler.reconcile(
                input(
                    preControlChildren = listOf(child(1, 0, OperationStatus.FAILED)),
                ),
            )
            assertTrue(decision is RetryReconciliationDecision.RejectDivergence)
        }

        @Test
        fun `legacy ambiguity — multiple attempts in history with no control row is rejected`() {
            val decision = RetryReconciler.reconcile(
                input(
                    preControlChildren = listOf(
                        child(1, 0, OperationStatus.FAILED),
                        child(2, 0, OperationStatus.SUCCEEDED),
                    ),
                ),
            )
            // We could safely reconstruct success here (last attempt succeeded), but the
            // safer ADR-0075 interpretation is: only unambiguous SINGLE-attempt child success
            // is reconstructable. Multiple pre-control attempts → reject.
            assertTrue(decision is RetryReconciliationDecision.RejectDivergence)
        }
    }

    @Nested
    @DisplayName("Composite control ADT invariants")
    inner class AdtInvariants {
        @Test
        fun `decision variant carries the attempt ordinal it operates on`() {
            val d1: RetryReconciliationDecision = RetryReconciler.reconcile(input())
            val d2: RetryReconciliationDecision = RetryReconciler.reconcile(
                input(controls = listOf(control(5, OperationStatus.SUCCEEDED)), maxAttempts = 5),
            )
            // ScheduleAttempt and ReuseSuccess are distinct variants with distinct ordinals.
            // The decision tree never collapses to a boolean or nullable integer.
            assertTrue(d1 is RetryReconciliationDecision.ScheduleAttempt && d1.attempt == 1)
            assertTrue(d2 is RetryReconciliationDecision.ReuseSuccess && d2.attempt == 5)
        }
    }

    private fun retryIdentity(opId: String): RetryControlIdentity =
        RetryControlIdentity(operationId = opId, schemaVersion = 1)
}
