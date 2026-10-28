package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObservation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome

/**
 * The compensation loop's decision table, free of any store, clock or body.
 *
 * Extracting it is what makes the loop's rules testable as rules: every row
 * below is a claim about what the engine OUGHT to do for a durable state,
 * and a unit can now pin each row without arming a single obligation on
 * disk. The interpreter that runs the table keeps the effects — the reads,
 * the writes, the bodies — and obeys whatever this table says.
 */
internal object SagaCompensationRules {

    /** What the loop must do next for an obligation in the given durable state. */
    sealed interface Ask {
        /** The forward's answer was lost; ask the body that performs the effect. */
        data object Forward : Ask

        /** The compensation's answer was lost; ask the body that undoes the effect. */
        data object Compensation : Ask

        /** The state is not one a body can settle; a human decides. */
        data class Intervene(val reason: InterventionReason) : Ask
    }

    /**
     * One obligation state, one next action. The two recoverable states ask
     * the body that owns the lost answer — the forward for a lost forward,
     * the compensation for a lost compensation — and everything else is
     * named for a human rather than guessed at.
     */
    fun askFor(status: CompensationObligationStatus): Ask = when (status) {
        CompensationObligationStatus.EFFECT_PENDING,
        CompensationObligationStatus.EFFECT_UNKNOWN,
        -> Ask.Forward

        CompensationObligationStatus.COMPENSATION_PENDING -> Ask.Compensation

        else -> Ask.Intervene(unresolvedReason(status))
    }

    /** Why an unrecoverable state needs a human, stated in the domain's own vocabulary. */
    fun unresolvedReason(status: CompensationObligationStatus): InterventionReason = when (status) {
        CompensationObligationStatus.COMPENSATION_PENDING,
        CompensationObligationStatus.COMPENSATION_UNCERTAIN,
        -> InterventionReason.COMPENSATION_UNCERTAIN

        else -> InterventionReason.UNKNOWN_EFFECT
    }

    /**
     * What a compensation body produced, as the durable vocabulary sees it.
     *
     * Deliberately conservative: only a body that completed successfully is
     * a confirmed compensation. A body that was cancelled or came back
     * unstable did not establish that the resource was put back, and the
     * difference between "not back" and "nobody knows" decides whether the
     * Saga may continue compensating.
     */
    fun observationOf(outcome: BodyOutcome): CompensationObservation = when (outcome) {
        is BodyOutcome.Cancelled -> CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST)

        is BodyOutcome.Completed -> when (val result = outcome.outcome) {
            is StepOutcome.Success -> CompensationObservation.Confirmed(receipt = null)

            is StepOutcome.Failure -> CompensationObservation.Failed(result.failure.message)

            is StepOutcome.Unstable -> CompensationObservation.Uncertain(UncertaintyReason.ACK_LOST)
        }
    }

    /** The three names the domain's closed vocabulary projects to. */
    fun outcomeName(observation: CompensationObservation): String = when (observation) {
        is CompensationObservation.Confirmed -> "CONFIRMED"
        is CompensationObservation.Failed -> "FAILED"
        is CompensationObservation.Uncertain -> "UNCERTAIN"
    }

    /** The provider's message for a failure, the reason for an uncertainty. */
    fun detailOf(observation: CompensationObservation): String? = when (observation) {
        is CompensationObservation.Confirmed -> null
        is CompensationObservation.Failed -> observation.reason
        is CompensationObservation.Uncertain -> observation.reason.name
    }
}
