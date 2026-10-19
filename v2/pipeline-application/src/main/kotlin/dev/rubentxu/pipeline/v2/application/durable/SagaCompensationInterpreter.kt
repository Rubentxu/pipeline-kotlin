package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaCompensationReport
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRuntime
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker

/**
 * The engine's compensation loop.
 *
 * ## The loop
 *
 * Ask the durable state what is owed → dispatch the body that owes it → record what happened →
 * ask again. Four steps, and the state is durable at every turn, so the loop can be re-entered
 * by a LATER PROCESS without inventing anything: the second process reads the same obligations
 * and reaches the same decision.
 *
 * ## The order is not decided here
 *
 * The loop never picks which obligation to compensate. That is
 * [dev.rubentxu.pipeline.v2.domain.saga.SagaDecider.nextCompensation], a pure function over the
 * durable state, and this class only obeys it. An engine that decided the order itself would
 * be a second, divergent copy of the rule.
 *
 * ## The write-ahead
 *
 * The obligation is moved to "the compensation is about to be dispatched" BEFORE the body
 * runs. If the process dies while the body is in flight, the durable state says so, and the
 * next process does NOT run the body again on a guess: it stops, because the compensation may
 * already have been applied.
 *
 * ## A refusal is a STOP, not a warning
 *
 * Both writes of the loop are asked for an answer, and the answer is obeyed. If the durable
 * state refuses the write-ahead, the body is not dispatched at all: a compensation that leaves
 * no "in flight" marker is a compensation nobody can fence, and the next process would apply it
 * a second time. If the durable state refuses the record, the Saga cannot claim the
 * compensation happened, and the loop stops instead of compensating an older obligation
 * against a state that was never proven to be restored.
 *
 * In both cases the report says what the durable state says, so the process that reads the
 * rows later reaches the same conclusion this one reached.
 *
 * ## The bodies run through the SAME dispatcher
 *
 * The compensation body is a child of the Saga Step's own body, dispatched through the slot
 * seam, so its journal rows are the rows that child would have had. There is no second
 * dispatcher and no second exactly-once law.
 */
class SagaCompensationInterpreter(
    private val planOperations: SagaPlanOperations,
    private val slotInvoker: BodySlotInvoker,
) : SagaRuntime {

    override suspend fun compensate(plan: SagaPlan): SagaCompensationReport {
        val compensated = mutableListOf<SagaActionId>()

        while (true) {
            when (val directive = planOperations.next(plan.sagaId, plan.policy)) {
                is CompensationDirective.Settled ->
                    return SagaCompensationReport(plan.sagaId, compensated, directive)

                // The engine has already decided that a human is needed. The remaining
                // obligations stay pending and durable; the loop stops here.
                is CompensationDirective.NeedsIntervention ->
                    return SagaCompensationReport(plan.sagaId, compensated, directive)

                // The outcome is not established and the provider could be asked. This build
                // has no way to ask it, so the Saga stops and says why, instead of
                // compensating something that may never have existed.
                is CompensationDirective.Reconcile ->
                    return SagaCompensationReport(
                        plan.sagaId,
                        compensated,
                        CompensationDirective.NeedsIntervention(
                            directive.obligation,
                            unresolvedReason(directive.obligation.status),
                        ),
                    )

                is CompensationDirective.Compensate -> {
                    val obligation = directive.obligation
                    val action = plan.action(obligation.actionId)
                        ?: return SagaCompensationReport(
                            plan.sagaId,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                // The engine cannot invent the plan that would have compensated
                                // it, and guessing a compensation is how a Saga does more damage
                                // than the failure it was recovering from.
                                InterventionReason.OBLIGATION_NOT_ARMED,
                            ),
                        )

                    // The write-ahead: BEFORE the body leaves the process.
                    //
                    // A refusal is not a warning: it means the durable state does not know that
                    // a compensation is about to run, so the body is NOT dispatched. A body that
                    // runs without that marker is a compensation nobody can fence — after a crash
                    // the row would still say "owed", and the next process would compensate a
                    // second time.
                    when (planOperations.beginCompensation(obligation.key)) {
                        is SagaRecordOutcome.Recorded -> Unit

                        is SagaRecordOutcome.Refused -> return SagaCompensationReport(
                            plan.sagaId,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                InterventionReason.COMPENSATION_NOT_DISPATCHED,
                            ),
                        )
                    }

                    val observation = observe(
                        slotInvoker.invoke(action.compensateSlot, BodyInvocationContext()),
                    )

                    // The answer has to become durable too. If the store refuses the record, the
                    // row keeps saying "the compensation is in flight", and the Saga cannot
                    // claim it is settled: a later process reads that same row and reaches the
                    // same conservative conclusion. So the loop stops here, and the reason is
                    // the one the row itself implies.
                    when (planOperations.recordCompensation(obligation.key, observation)) {
                        is SagaRecordOutcome.Recorded -> compensated += obligation.actionId

                        is SagaRecordOutcome.Refused -> return SagaCompensationReport(
                            plan.sagaId,
                            compensated,
                            CompensationDirective.NeedsIntervention(
                                obligation,
                                InterventionReason.COMPENSATION_UNCERTAIN,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Why an obligation that cannot be settled automatically needs a human.
     *
     * The two cases are not the same fact: an outcome that was never established is about
     * something that MAY NOT have happened, while an indeterminate compensation is about
     * something that MAY ALREADY have been undone.
     */
    private fun unresolvedReason(status: CompensationObligationStatus): InterventionReason =
        when (status) {
            CompensationObligationStatus.COMPENSATION_PENDING,
            CompensationObligationStatus.COMPENSATION_UNCERTAIN,
            -> InterventionReason.COMPENSATION_UNCERTAIN

            else -> InterventionReason.UNKNOWN_EFFECT
        }

    /**
     * What a compensation body produced, as the durable vocabulary sees it.
     *
     * The mapping is deliberately conservative: only a body that COMPLETED SUCCESSFULLY is a
     * confirmed compensation. A body that was cancelled or that came back unstable did not
     * establish that the resource was put back, and the difference between "not back" and
     * "nobody knows" decides whether the Saga may continue compensating.
     */
    private fun observe(outcome: BodyOutcome): CompensationObservation = when (outcome) {
        is BodyOutcome.Cancelled -> CompensationObservation.Uncertain(
            dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason.ACK_LOST,
        )

        is BodyOutcome.Completed -> when (val result = outcome.outcome) {
            is StepOutcome.Success -> CompensationObservation.Confirmed(receipt = null)

            is StepOutcome.Failure -> CompensationObservation.Failed(result.failure.message)

            is StepOutcome.Unstable -> CompensationObservation.Uncertain(
                dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason.ACK_LOST,
            )
        }
    }
}
