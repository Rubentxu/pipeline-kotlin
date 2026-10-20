package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The rule a Saga's outcome may never break: it never softens the run.
 *
 * These are the invariants of outcome conservation and of no success from absence,
 * exercised where they can be read in one screen. A test that says "the compensation worked"
 * must also say "the run still failed", because that pair is the whole promise of a Saga.
 */
class RunPresentationTest {

    private val failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "the action ship failed")

    @Nested
    inner class Merging {

        @Test
        fun `the run's outcome survives the merge untouched`() {
            val presentation = mergeCompensationOutcome(RunOutcome.Success, listOf(SagaOutcome.Settled("checkout")))

            assertEquals(RunOutcome.Success, presentation.outcome)
        }

        @Test
        fun `a failed run stays failed when its Saga was compensated`() {
            // The invariant that defines a Saga: the failure was undone, and the failure is
            // still a failure. A presentation that reported success here would be lying about
            // the run that is still in the history.
            val presentation = mergeCompensationOutcome(
                RunOutcome.Failure(failure),
                listOf(SagaOutcome.Settled("checkout")),
            )

            assertEquals(RunOutcome.Failure(failure), presentation.outcome)
            assertFalse(presentation.needsIntervention)
        }

        @Test
        fun `a Saga that needs a human does not make the run fail`() {
            // The other direction: a Saga is not a run. The run failed or not on its own facts.
            val presentation = mergeCompensationOutcome(
                RunOutcome.Success,
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.UNKNOWN_EFFECT)),
            )

            assertEquals(RunOutcome.Success, presentation.outcome)
            assertTrue(presentation.needsIntervention)
        }

        @Test
        fun `every Saga of the run is kept, not only the last one`() {
            val presentation = mergeCompensationOutcome(
                RunOutcome.Success,
                listOf(
                    SagaOutcome.Settled("checkout"),
                    SagaOutcome.NeedsIntervention("shipping", InterventionReason.COMPENSATION_FAILED),
                    SagaOutcome.Settled("billing"),
                ),
            )

            assertEquals(3, presentation.sagas.size)
            assertTrue(presentation.needsIntervention)
        }
    }

    @Nested
    inner class Finishing {

        @Test
        fun `a successful run with nothing pending is finished`() {
            val presentation = mergeCompensationOutcome(RunOutcome.Success, listOf(SagaOutcome.Settled("checkout")))

            assertTrue(presentation.isFinished)
        }

        @Test
        fun `a run with a Saga pending is not finished, however green it was`() {
            val presentation = mergeCompensationOutcome(
                RunOutcome.Success,
                listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.UNKNOWN_EFFECT)),
            )

            assertFalse(presentation.isFinished)
            assertTrue(presentation.needsIntervention)
        }

        @Test
        fun `a failed run whose Saga recovered IS finished, because finished is not successful`() {
            // The distinction the name has to carry: "did it work?" is the outcome, and
            // "is there anything left to do?" is this. A recovered failure answers both.
            val presentation = mergeCompensationOutcome(RunOutcome.Failure(failure), listOf(SagaOutcome.Settled("checkout")))

            assertTrue(presentation.isFinished)
            assertFalse(presentation.needsIntervention)
            assertEquals(RunOutcome.Failure(failure), presentation.outcome)
        }

        @Test
        fun `an aborted run is never finished, even with nothing pending`() {
            // Aborted is, by the domain's own definition, a run that never reached a terminal
            // state. Its ending is the absence of one.
            val presentation = mergeCompensationOutcome(RunOutcome.Aborted, emptyList())

            assertFalse(presentation.isFinished)
            assertFalse(presentation.needsIntervention)
        }

        @Test
        fun `an unstable run that left nothing pending is finished`() {
            val presentation = mergeCompensationOutcome(RunOutcome.Unstable, emptyList())

            assertTrue(presentation.isFinished)
        }
    }
}
