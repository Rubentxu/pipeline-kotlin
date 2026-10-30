package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The compensation loop's decision table, pinned row by row without a
 * store, a clock or a body. Each row is a claim about what the engine ought
 * to do for a durable state; the loop that runs the effects is verified
 * separately against the real durable substrate it drives.
 */
class SagaCompensationRulesTest {

    @Test
    fun `a lost forward answer asks the body that performs the effect`() {
        assertEquals(
            SagaCompensationRules.Ask.Forward,
            SagaCompensationRules.askFor(CompensationObligationStatus.EFFECT_PENDING),
        )
        assertEquals(
            SagaCompensationRules.Ask.Forward,
            SagaCompensationRules.askFor(CompensationObligationStatus.EFFECT_UNKNOWN),
        )
    }

    @Test
    fun `a lost compensation answer asks the body that undoes the effect`() {
        assertEquals(
            SagaCompensationRules.Ask.Compensation,
            SagaCompensationRules.askFor(CompensationObligationStatus.COMPENSATION_PENDING),
        )
    }

    @Test
    fun `an uncertain compensation needs a human, not another body`() {
        val ask = SagaCompensationRules.askFor(CompensationObligationStatus.COMPENSATION_UNCERTAIN)
        assertEquals(
            InterventionReason.COMPENSATION_UNCERTAIN,
            (ask as SagaCompensationRules.Ask.Intervene).reason,
        )
    }

    @Test
    fun `every other state is named for a human as an unknown effect`() {
        val ask = SagaCompensationRules.askFor(CompensationObligationStatus.EFFECT_CONFIRMED)
        assertEquals(
            InterventionReason.UNKNOWN_EFFECT,
            (ask as SagaCompensationRules.Ask.Intervene).reason,
        )
    }

    @Test
    fun `only a body that completed successfully confirms a compensation`() {
        assertEquals(
            CompensationObservation.Confirmed(receipt = null),
            SagaCompensationRules.observationOf(BodyOutcome.Completed(StepOutcome.Success)),
        )
    }

    @Test
    fun `a failed body is a failed compensation carrying the provider's message`() {
        val observation = SagaCompensationRules.observationOf(
            BodyOutcome.Completed(StepOutcome.Failure(PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, "undo refused"))),
        )

        assertEquals("undo refused", (observation as CompensationObservation.Failed).reason)
    }

    @Test
    fun `a cancelled body says the call was cancelled and not that the answer was lost`() {
        // The body was stopped on purpose, so nobody is waiting for an answer that will never
        // come. Saying the answer was lost would send the interpreter looking for a lost
        // acknowledgement that never existed.
        assertEquals(
            CompensationObservation.Uncertain(UncertaintyReason.CALL_CANCELLED),
            SagaCompensationRules.observationOf(BodyOutcome.Cancelled(dev.rubentxu.pipeline.v2.domain.step.CancellationReason.Deadline)),
        )
    }

    @Test
    fun `an unstable body is uncertainty, not failure and not success`() {
        // The body ran and did not settle: the answer is genuinely unknown, which is a
        // different fact from a call that was cancelled.
        assertEquals(
            CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST),
            SagaCompensationRules.observationOf(BodyOutcome.Completed(StepOutcome.Unstable)),
        )
    }

    @Test
    fun `the durable vocabulary projects each observation to its name and detail`() {
        assertEquals(
            "CONFIRMED",
            SagaCompensationRules.outcomeName(CompensationObservation.Confirmed(receipt = null)),
        )
        assertEquals(
            "UNCERTAIN",
            SagaCompensationRules.outcomeName(CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST)),
        )
        assertEquals(
            "ACK_LOST",
            SagaCompensationRules.detailOf(CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST)),
        )
    }
}
