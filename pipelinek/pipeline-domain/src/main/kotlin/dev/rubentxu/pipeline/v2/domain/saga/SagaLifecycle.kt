package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The capability a Step must declare to ask the ENGINE to CLOSE a Saga.
 *
 * ## Why a second capability and not a wider first one
 *
 * [SAGA_RUNTIME_CAPABILITY] publishes the compensation port, and that port was published in
 * 0.64.0: it cannot grow a method without breaking every compiled consumer, because an
 * interface that gains an abstract method is a different interface for the bytecode that
 * already implements it.
 *
 * So the closing verb arrives as a NEW port with a NEW capability, and the new port EXTENDS
 * the old one. A plugin that declares [SAGA_LIFECYCLE_CAPABILITY] holds ONE handle with both
 * ways of ending a Saga — undo the effects, or keep them — and cannot end up with two
 * authorities that disagree about what a Saga owes.
 */
val SAGA_LIFECYCLE_CAPABILITY: StepCapability = StepCapability("saga.lifecycle")

/**
 * The LIFECYCLE port of a Saga: the two ways a Saga ends.
 *
 * ## The two endings, and why they are not one
 *
 * A Saga ends in exactly one of two ways, and they are opposites:
 *
 *  - **compensate** — the Saga failed, so the effects are undone. The obligations end
 *    [CompensationObligationStatus.COMPENSATED].
 *  - **complete** — the Saga succeeded, so the effects are KEPT. The obligations end
 *    [CompensationObligationStatus.EFFECT_KEPT].
 *
 * Until this port existed, only the first ending was expressible, and a Saga that ended well
 * had no way to say so: its obligations stayed owed for ever, a resumed run compensated a Saga
 * that had succeeded, and a run that reported success could show a compensated Saga in its own
 * history.
 *
 * ## Why the plugin asks and the engine decides
 *
 * The plugin knows whether its chain succeeded — only it does, and no durable state can say it
 * for the plugin. The engine knows what that means for the world: which obligations are still
 * owed, which are already undone, and whether the durable state allows a closing at all.
 *
 * So the plugin ASKS to complete, and the engine decides. A request the durable state cannot
 * corroborate is REFUSED, and the refusal is a fact with a reason — never a silent success.
 *
 * ## What this port does NOT do
 *
 * It does not decide the compensation order, it does not read the durable table, and it does not
 * let the plugin mark anything as kept without the engine having written the durable state
 * that says so. A plugin that says "the chain succeeded" is believed about THAT and about
 * nothing else.
 */
interface SagaLifecycle : SagaRuntime {

    /**
     * Closes [plan]'s Saga by KEEPING its effects.
     *
     * The obligations whose forward effect was confirmed are discharged without being
     * compensated: the effect happened, the Saga succeeded, and the promise to undo it is
     * void. Nothing is owed afterwards, and a later process that reads the same durable rows
     * reaches the same conclusion — it will not compensate a Saga that succeeded.
     *
     * The closing is refused, and a human is asked, when the durable state does not allow it:
     * an effect whose outcome is not established, a compensation that failed or is uncertain,
     * or a Saga that was already compensated.
     */
    suspend fun complete(plan: SagaPlan): SagaCompletionReport
}

/**
 * What the completion of a Saga did.
 *
 * @param kept the actions whose effects are KEPT, in arm order. An action is kept when its
 * forward effect was confirmed — now or in an earlier attempt — and was never undone.
 * @param directive the Saga's state when the closing stopped: settled, or needing a human.
 */
data class SagaCompletionReport(
    val sagaId: SagaId,
    val kept: List<SagaActionId>,
    val directive: CompensationDirective,
)

/**
 * What the engine decides about COMPLETING a Saga.
 *
 * ## Why it is a decision and not a boolean
 *
 * "May this Saga be completed?" is not a yes-or-no question: when the answer is no, the caller
 * needs to know WHICH obligation blocks it and WHY, because those are the two facts an
 * operator needs to decide what to do. A boolean would throw both away and leave the engine
 * guessing on the reader's behalf.
 */
sealed interface SagaCompletionDecision {

    /**
     * The Saga may be completed.
     *
     * @param kept the actions whose effects are kept, in arm order.
     * @param discharge the obligations that must be written to say so: the ones whose forward
     * effect was confirmed and never undone.
     */
    data class Completable(
        val kept: List<SagaActionId>,
        val discharge: List<CompensationObligation>,
    ) : SagaCompletionDecision

    /**
     * The Saga cannot be completed, and [obligation] is the one that says why.
     */
    data class Refused(
        val obligation: CompensationObligation,
        val reason: InterventionReason,
    ) : SagaCompletionDecision
}
