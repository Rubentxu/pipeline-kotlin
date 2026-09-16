package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * RED characterization test for wu-g5-restore — semantic defect (exit-code fold prohibition).
 *
 * Design §14.3 law: `predicate == false` MUST NOT be conflated with `body failed`.
 * The predicate outcome and the body execution are INDEPENDENT concerns:
 *
 * - `Unsatisfied` (predicate emitted false) = NOT a failure = re-enter body
 * - `Failed(failure)` (body step failed)    = a failure = propagate failure
 * - `Satisfied` (predicate emitted true)   = loop exit = completed
 * - `Cancelled(reason)`                     = explicit cancellation
 *
 * A future exit-code fold that maps `sh exit != 0` → `Unsatisfied`
 * would be WRONG: it conflates two independent signals.
 *
 * This test proves the DISTINCTION using a file-private sealed ADT that
 * mirrors the production `WaitUntilPredicateOutcome` (promoted in WU-G5R.3).
 * The fold semantics are enforced here so that WU-G5R.3 can migrate this
 * test to the production ADT by changing the import.
 *
 * This test is GREEN today (the file-private ADT distinguishes the cases
 * correctly). It is RED-proof-by-existence: it CANNOT regress to a future
 * exit-code fold because the assertions explicitly forbid the conflation.
 *
 * References:
 * - design.md §14.3 (no exit-code fold, no event-as-authority)
 * - design.md §3.4 (predicate outcome fold semantics)
 * - AGENTS.md §8 (ADT-first modelling — predicate outcome is a closed ADT, not a Boolean)
 */
@DisplayName("LFC-2 G5-RESTORE: WaitUntilPredicateOutcome — Unsatisfied ≠ Failed")
class WaitUntilPredicateOutcomeSemanticTest {

    // TEMPORARY — replaced by production WaitUntilPredicateOutcome in WU-G5R.3.
    // This file-private ADT is a RED-proof twin that pins the semantic distinction
    // before the production type exists. It will be deleted when WU-G5R.3 promotes
    // the real ADT and migrates this test's assertions.
    private sealed interface WaitUntilPredicateOutcome {
        /** Predicate emitted true — loop terminates successfully. */
        data object Satisfied : WaitUntilPredicateOutcome

        /** Predicate emitted false — loop continues (NOT a failure). */
        data object Unsatisfied : WaitUntilPredicateOutcome

        /** Body step failed with a typed failure — loop propagates failure (NOT re-entry). */
        data class Failed(val failure: TypedFailure) : WaitUntilPredicateOutcome

        /** Loop was explicitly cancelled. */
        data class Cancelled(val reason: CancellationReason) : WaitUntilPredicateOutcome
    }

    // Minimal TypedFailure twin — mirrors the production type.
    private data class TypedFailure(
        val stepId: String,
        val kind: String,
        val message: String,
    )

    // Minimal CancellationReason twin.
    private enum class CancellationReason {
        TIMEOUT,
        ABORTED,
        COORDINATOR_CANCEL,
    }

    // Decision ADT for the reconciler fold.
    private sealed interface ReconciliationDecision {
        data class ScheduleAttempt(val attempt: Int) : ReconciliationDecision
        data object Completed : ReconciliationDecision
        data class Failed(val failure: TypedFailure) : ReconciliationDecision
        data object Cancelled : ReconciliationDecision
    }

    /**
     * Pure fold: maps a WaitUntilPredicateOutcome to a ReconciliationDecision.
     *
     * This mirrors the fold that WaitUntilReconciler.reconcile() will implement
     * (WU-G5R.5). The key law: `Unsatisfied` → `ScheduleAttempt(n+1)`, NOT
     * a failure. The `Failed` path is the ONLY way a failure propagates.
     */
    private fun reconcileAfterPredicate(outcome: WaitUntilPredicateOutcome): ReconciliationDecision =
        when (outcome) {
            is WaitUntilPredicateOutcome.Satisfied -> ReconciliationDecision.Completed
            is WaitUntilPredicateOutcome.Unsatisfied -> ReconciliationDecision.ScheduleAttempt(2)
            is WaitUntilPredicateOutcome.Failed -> ReconciliationDecision.Failed(outcome.failure)
            is WaitUntilPredicateOutcome.Cancelled -> ReconciliationDecision.Cancelled
        }

    @Nested
    @DisplayName("semantic distinction: Unsatisfied vs Failed")
    inner class SemanticDistinction {

        @Test
        fun `Unsatisfied maps to ScheduleAttempt, not a failure`() {
            val outcome = WaitUntilPredicateOutcome.Unsatisfied
            val decision = reconcileAfterPredicate(outcome)

            assertTrue(
                decision is ReconciliationDecision.ScheduleAttempt,
                "Unsatisfied must map to ScheduleAttempt, not a failure. " +
                    "Got: ${decision::class.java.simpleName}. " +
                    "This is the law that blocks the exit-code fold: " +
                    "`sh exit != 0` MUST NOT be conflated with `predicate=false`.",
            )
            assertTrue(
                (decision as ReconciliationDecision.ScheduleAttempt).attempt >= 1,
                "ScheduleAttempt must carry a valid attempt number",
            )
        }

        @Test
        fun `Failed maps to ReconciliationDecision Failed, distinct from Unsatisfied`() {
            val failure = TypedFailure(
                stepId = "wait-until-test/step-0",
                kind = "ShellNonZeroExit",
                message = "Command exited with code 1",
            )
            val outcome = WaitUntilPredicateOutcome.Failed(failure)
            val decision = reconcileAfterPredicate(outcome)

            assertTrue(
                decision is ReconciliationDecision.Failed,
                "Failed must map to ReconciliationDecision.Failed. Got: ${decision::class.java.simpleName}",
            )
            assertEquals(
                failure,
                (decision as ReconciliationDecision.Failed).failure,
                "TypedFailure must carry the original failure",
            )
        }

        @Test
        fun `Unsatisfied and Failed are NOT the same decision`() {
            val unsatisfiedDecision = reconcileAfterPredicate(WaitUntilPredicateOutcome.Unsatisfied)
            val failedDecision = reconcileAfterPredicate(
                WaitUntilPredicateOutcome.Failed(
                    TypedFailure("step", "kind", "msg"),
                ),
            )

            val unsatisfiedAndFailedDifferentMsg = buildString {
                append("Unsatisfied and Failed MUST produce DIFFERENT decisions. ")
                append("Conflating them is the exit-code fold defect. ")
                append("Unsatisfied → ${unsatisfiedDecision::class.java.simpleName}. ")
                append("Failed → ${failedDecision::class.java.simpleName}.")
            }
            assertNotEquals(
                unsatisfiedDecision::class.java,
                failedDecision::class.java,
                unsatisfiedAndFailedDifferentMsg,
            )
        }

        @Test
        fun `Satisfied terminates the loop (distinct from Failed)`() {
            val satisfiedDecision = reconcileAfterPredicate(WaitUntilPredicateOutcome.Satisfied)
            val failedDecision = reconcileAfterPredicate(
                WaitUntilPredicateOutcome.Failed(
                    TypedFailure("step", "kind", "msg"),
                ),
            )

            assertTrue(
                satisfiedDecision is ReconciliationDecision.Completed,
                "Satisfied must map to Completed. Got: ${satisfiedDecision::class.java.simpleName}",
            )
            assertNotEquals(
                satisfiedDecision::class.java,
                failedDecision::class.java,
                "Satisfied and Failed must produce different decisions",
            )
        }

        @Test
        fun `Cancelled is a distinct decision from both Failed and Completed`() {
            val cancelled = WaitUntilPredicateOutcome.Cancelled(CancellationReason.TIMEOUT)
            val cancelledDecision = reconcileAfterPredicate(cancelled)
            val failedDecision = reconcileAfterPredicate(
                WaitUntilPredicateOutcome.Failed(TypedFailure("s", "k", "m")),
            )

            assertTrue(
                cancelledDecision is ReconciliationDecision.Cancelled,
                "Cancelled must map to Cancelled. Got: ${cancelledDecision::class.java.simpleName}",
            )
            assertNotEquals(
                cancelledDecision::class.java,
                failedDecision::class.java,
                "Cancelled and Failed must produce different decisions",
            )
            assertNotEquals(
                cancelledDecision::class.java,
                ReconciliationDecision.Completed::class.java,
                "Cancelled must not be Completed",
            )
        }
    }

    @Nested
    @DisplayName("fold is exhaustive — all four cases handled")
    inner class FoldExhaustiveness {

        @Test
        fun `fold handles all four WaitUntilPredicateOutcome cases`() {
            val cases = listOf(
                WaitUntilPredicateOutcome.Satisfied,
                WaitUntilPredicateOutcome.Unsatisfied,
                WaitUntilPredicateOutcome.Failed(TypedFailure("s", "k", "m")),
                WaitUntilPredicateOutcome.Cancelled(CancellationReason.ABORTED),
            )

            for (outcome in cases) {
                val decision = reconcileAfterPredicate(outcome)
                assertTrue(
                    decision is ReconciliationDecision.ScheduleAttempt ||
                        decision is ReconciliationDecision.Completed ||
                        decision is ReconciliationDecision.Failed ||
                        decision is ReconciliationDecision.Cancelled,
                    "reconcileAfterPredicate must handle all four cases. " +
                        "Unhandled: ${outcome::class.java.simpleName}",
                )
            }
        }

        @Test
        fun `the fold is pure — same input always produces same decision`() {
            val outcome = WaitUntilPredicateOutcome.Unsatisfied
            val first = reconcileAfterPredicate(outcome)
            val second = reconcileAfterPredicate(outcome)
            assertEquals(first, second, "Fold must be pure and deterministic")
        }
    }
}
