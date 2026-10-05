package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P1 of the Runtime Observation Contract Closure: `Unstable` must have ONE durable meaning.
 *
 * ## What this test is for
 *
 * `StepOutcome.Unstable` and `BranchTerminal.Unstable` were already their own cases in the two
 * semantic layers. `OperationStatus` was the only layer without one, and it collapsed the fact
 * **three different ways for the same condition**:
 *
 * ```text
 * StepOutcome.Unstable   -> OperationStatus.FAILED    (CanonicalStructuralDecisions)
 * BranchTerminal.Unstable -> OperationStatus.ABORTED  (ParallelStageEngine)
 * retry attempt Unstable -> persisted OperationStatus.FAILED
 * ```
 *
 * So a child row and the aggregate row of one branch described the same event two ways, and neither
 * matched the run-level terminal the CLI already reported as `unstable`. Choosing a single collapse
 * would have been arbitrary — there was no consistent behaviour to preserve, only three
 * inconsistent ones.
 *
 * ## Why the case is UNSTABLE and not a collapse
 *
 * Jenkins — the baseline in `AGENTS.md` STEP SEMANTICS — has `unstable` as a build result distinct
 * from `failure`, and that is exactly what `warnError` and `unstable` exist to produce. The durable
 * algebra is the owner of this fact, and the item's own rule applies: *if a property needs a new
 * carrier, extend the algebra or contract that owns it*.
 *
 * ## Harness fidelity
 *
 * This is a **pure contract test at HF0** — no coordinator, no filesystem, no process. It crosses
 * the production authority `StepOutcome.toOperationStatus()`, which is the single function that
 * decides the durable status for a semantic outcome. The private `ParallelStageEngine
 * .aggregateStatusOf` is not reachable from here, so the second representation is covered by
 * `UnstableSingleDurableStatusFitnessTest` in `:pipeline-architecture-tests`, which reads the
 * source. Splitting the claim this way is deliberate: one reachable authority tested behaviourally,
 * one private authority tested by fitness, neither silently uncovered.
 */
@DisplayName("P1 — Unstable has one durable meaning")
class P1UnstableSingleDurableStatusTest {

    private val scriptFailure = PipelineFailure(
        kind = FailureKind.SCRIPT,
        message = "boom",
    )

    @Test
    fun `Unstable is its own durable status, not a failure`() {
        assertEquals(
            OperationStatus.UNSTABLE,
            StepOutcome.Unstable.toOperationStatus(),
            "a declared-unstable outcome must persist as UNSTABLE; collapsing it to FAILED turns " +
                "a run that completed into a run that failed",
        )
    }

    @Test
    fun `Unstable is terminal so a reattach never re-executes it`() {
        assertTrue(
            OperationStatus.UNSTABLE.isTerminal,
            "UNSTABLE must be terminal: the operation finished, so nothing may transition out of it",
        )
        assertFalse(
            OperationStatus.UNSTABLE.isPollFailure,
            "UNSTABLE must NOT be a poll failure. isPollFailure drives retry and waitUntil to advance " +
                "to another attempt, and an unstable run does not retry — making it one would be a " +
                "behaviour change disguised as a representation change",
        )
    }

    @Test
    fun `a terminal UNSTABLE cannot reach another state, exactly like FAILED and SUCCEEDED`() {
        val terminals = listOf(
            OperationStatus.SUCCEEDED,
            OperationStatus.UNSTABLE,
            OperationStatus.FAILED,
            OperationStatus.ABORTED,
        )
        terminals.forEach { from ->
            OperationStatus.entries
                .filter { it != from }
                .forEach { to ->
                    assertTrue(
                        OperationStatus.transition(from, to).isFailure,
                        "$from is terminal, so reaching $to from it must be refused",
                    )
                }
        }
    }

    @Test
    fun `re-asserting UNSTABLE stays legal, because idempotence is not a move`() {
        // Pinned by `OperationStatusTest.same state transition is valid`: self-transition is
        // accepted for every state, terminal or not. Finality means "cannot reach a DIFFERENT
        // state". An earlier draft of this file asserted the opposite here and was wrong — it
        // failed on SUCCEEDED, a case that has always been legal.
        assertTrue(
            OperationStatus.transition(OperationStatus.UNSTABLE, OperationStatus.UNSTABLE).isSuccess,
            "re-asserting the status an operation already has must stay idempotent",
        )
    }

    @Test
    fun `RUNNING may become UNSTABLE, so the path that persists it is legal`() {
        val result = OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.UNSTABLE)
        assertTrue(
            result.isSuccess,
            "the retry engine and the parallel aggregate both persist UNSTABLE from a RUNNING row; " +
                "if the transition is illegal the journal write is a contract violation",
        )
    }

    @Test
    fun `Unstable is distinguishable from a plain failure, which is the whole point`() {
        assertEquals(
            OperationStatus.FAILED,
            StepOutcome.Failure(scriptFailure).toOperationStatus(),
            "a genuine script failure is still FAILED",
        )
        assertTrue(
            StepOutcome.Unstable.toOperationStatus() !=
                StepOutcome.Failure(scriptFailure).toOperationStatus(),
            "if these two agree, adding the case changed nothing and the whole point is lost",
        )
    }

    @Test
    fun `the four other terminal statuses keep their meanings`() {
        assertEquals(OperationStatus.SUCCEEDED, StepOutcome.Success.toOperationStatus())
        assertEquals(
            OperationStatus.FAILED_TIMEOUT,
            StepOutcome.Failure(scriptFailure.copy(kind = FailureKind.TIMEOUT)).toOperationStatus(),
            "a timeout is not an unstable run; it is a killed run",
        )
    }
}
