package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.RunOutcome

/**
 * Which half of a saga's life one obligation is in.
 *
 * The phase is a READING of the durable status, never a second piece of state: a promise is
 * in exactly one phase, and the status the engine wrote is what says which one. An obligation
 * that is still waiting for its forward outcome is in [FORWARD]; one whose effect is settled
 * and is being undone is in [COMPENSATE]; one the engine will not move again is in [TERMINAL].
 *
 * [TERMINAL] does NOT mean "discharged". A compensation that failed is terminal too, and the
 * difference between a promise that was honoured and a promise that is stuck is carried by the
 * status and the next action, not by the phase.
 */
enum class SagaActionPhase {

    /** The forward effect's outcome is still open: nobody knows yet whether it happened. */
    FORWARD,

    /** The effect is settled and the saga is undoing it. */
    COMPENSATE,

    /** The engine will not move this promise again: it is discharged, or it waits for a human. */
    TERMINAL,
}

/**
 * What the world owes next for one obligation.
 *
 * This is the question an operator actually has — "what happens now?" — and it has an answer
 * for every status, including the ones where the answer is "nothing" and the ones where the
 * answer is "only a person can decide".
 */
sealed interface SagaNextAction {

    /** Nothing is owed: the promise is discharged and the world is as it should be. */
    data object None : SagaNextAction

    /** The forward effect may still be dispatched; nothing has been tried yet. */
    data object ProceedForward : SagaNextAction

    /**
     * The forward effect's outcome has to be established before anything else.
     *
     * Until it is, nobody can say whether there is anything to compensate, and compensating on
     * a guess can destroy the state the compensation would have needed.
     */
    data object SettleForward : SagaNextAction

    /** The effect stands and has to be undone. */
    data object Compensate : SagaNextAction

    /**
     * The compensation's outcome has to be established.
     *
     * Running the compensation again could undo the effect twice, so this is settled by
     * observation, not by retrying.
     */
    data object SettleCompensation : SagaNextAction

    /** Only a human can say what happens next. */
    data object Decide : SagaNextAction
}

/**
 * The classified reason an obligation is not in a clean state.
 *
 * The vocabulary is closed so a reader can act on it without parsing prose. Every case says
 * something that happened, not something that might happen: an obligation that is merely
 * waiting carries no error at all.
 */
sealed interface SagaActionError {

    /** The forward effect was dispatched and its answer never came back. */
    data object ForwardOutcomeLost : SagaActionError

    /** The forward effect's outcome cannot be established: nobody can say whether it happened. */
    data object ForwardOutcomeUnknown : SagaActionError

    /** The compensation ran and failed. */
    data object CompensationFailed : SagaActionError

    /** The compensation ran and nobody knows whether it did what it said. */
    data object CompensationUncertain : SagaActionError

    /** The saga stopped, and what happens to the resource is a human's decision. */
    data object SagaStopped : SagaActionError
}

/**
 * What one durable status MEANS: where the promise is, what is owed, what went wrong and
 * whether a person has to step in.
 *
 * The four answers come from ONE decision table, so they cannot disagree with each other: a
 * status cannot be "in the forward phase" here and "in the compensation phase" there, and an
 * obligation that needs a person cannot be paired with a next action that says nobody has to
 * decide.
 */
data class SagaStanding(
    /** Where the promise is in the saga's life. */
    val phase: SagaActionPhase,
    /** What the world owes next. */
    val next: SagaNextAction,
    /** Why the promise is not in a clean state, or `null` when nothing went wrong. */
    val error: SagaActionError?,
    /** Whether a human has to decide what happens to the resource. */
    val needsOperator: Boolean,
)

/**
 * The reading of a durable status.
 *
 * ## Why the table is exhaustive
 *
 * The table is a `when` over the whole status vocabulary, so a status added to the lifecycle
 * does not compile until somebody says what it means. A promise whose meaning cannot be
 * established is not a promise this build may report on, and a reader that guessed would be
 * exactly the guess this product exists to avoid.
 */
object SagaObligationStanding {

    /**
     * What [status] means.
     */
    fun of(status: CompensationObligationStatus): SagaStanding = when (status) {
        CompensationObligationStatus.ARMED ->
            SagaStanding(
                phase = SagaActionPhase.FORWARD,
                next = SagaNextAction.ProceedForward,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.EFFECT_PENDING ->
            SagaStanding(
                phase = SagaActionPhase.FORWARD,
                next = SagaNextAction.SettleForward,
                error = SagaActionError.ForwardOutcomeLost,
                needsOperator = false,
            )

        CompensationObligationStatus.EFFECT_CONFIRMED ->
            SagaStanding(
                phase = SagaActionPhase.COMPENSATE,
                next = SagaNextAction.Compensate,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.EFFECT_KEPT ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.None,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.EFFECT_ABSENT ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.None,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.EFFECT_UNKNOWN ->
            SagaStanding(
                phase = SagaActionPhase.FORWARD,
                next = SagaNextAction.SettleForward,
                error = SagaActionError.ForwardOutcomeUnknown,
                needsOperator = false,
            )

        CompensationObligationStatus.COMPENSATION_PENDING ->
            SagaStanding(
                phase = SagaActionPhase.COMPENSATE,
                next = SagaNextAction.SettleCompensation,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.COMPENSATED ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.None,
                error = null,
                needsOperator = false,
            )

        CompensationObligationStatus.COMPENSATION_FAILED ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.Decide,
                error = SagaActionError.CompensationFailed,
                needsOperator = true,
            )

        CompensationObligationStatus.COMPENSATION_UNCERTAIN ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.Decide,
                error = SagaActionError.CompensationUncertain,
                needsOperator = true,
            )

        CompensationObligationStatus.NEEDS_INTERVENTION ->
            SagaStanding(
                phase = SagaActionPhase.TERMINAL,
                next = SagaNextAction.Decide,
                error = SagaActionError.SagaStopped,
                needsOperator = true,
            )
    }
}

/**
 * One obligation, as an operator sees it.
 *
 * Every field is either a durable fact the engine wrote or a reading of one. Nothing here is
 * reconstructed from a log, and nothing here is a second opinion about what happened.
 */
data class SagaActionView(
    /** The saga this promise belongs to. */
    val sagaId: String,
    /** The action that made the promise. */
    val actionId: String,
    /** The compensation that owes the undo. */
    val compensationId: String,
    /** The resource the promise is about. */
    val resource: ResourceRef,
    /** What undoing it would do to the resource. */
    val compensationKind: CompensationKind,
    /** The digest of the plan the promise was armed under. */
    val planFingerprint: SagaPlanFingerprint,
    /** The durable status of the promise. */
    val status: CompensationObligationStatus,
    /** The receipt of the effect, when the durable state carries one. */
    val evidence: EffectReceiptRef?,
    /** Where the promise is in the saga's life. */
    val phase: SagaActionPhase,
    /** What the world owes next. */
    val next: SagaNextAction,
    /** Why the promise is not in a clean state, or `null` when nothing went wrong. */
    val error: SagaActionError?,
    /** Whether a person is on the hook for this promise. */
    val needsOperator: Boolean,
)

/**
 * Everything one run owes, as an operator sees it.
 *
 * The promises are listed in the order they were made, which is the order the compensations
 * would run in, so the list reads as the remaining work rather than as a set of rows.
 *
 * The verdict of the run and of each of its sagas is here too, because a promise on its own
 * does not say whether anybody is waiting: a saga that stopped leaves promises owed that the
 * engine will not touch again, and the person reading this has to know that.
 */
data class SagaRunView(
    /** The run these promises belong to. */
    val runId: String,
    /** The promises, oldest first. */
    val actions: List<SagaActionView>,
    /** What each saga of the run left behind. */
    val sagas: List<SagaOutcome>,
    /** How the run itself ended. */
    val outcome: RunOutcome,
) {

    /** Whether a person is on the hook for this run. */
    val needsOperator: Boolean
        get() = sagas.any { it is SagaOutcome.NeedsIntervention } || actions.any { it.needsOperator }
}
