package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The capability a Step must declare to use the compensation PLAN port.
 *
 * ## Why a second capability and not a wider first one
 *
 * [SAGA_COMPENSATION_CAPABILITY] publishes the INTENT port: it may make a promise. This one
 * publishes the PLAN port: it may ask what is owed and report what happened. They are
 * different authorities — making a promise and moving a promise along its lifecycle — and a
 * Step that only needs to make a promise has no business being able to write the state that
 * judges it.
 *
 * Splitting them is also what keeps the published ABI additive: the first port was published
 * in 0.64.0 and cannot grow a method without breaking every compiled consumer, so the new
 * operations arrive as a new port with a new capability instead.
 */
val SAGA_PLAN_CAPABILITY: StepCapability = StepCapability("saga.plan")

/**
 * What was observed about a compensation that was ATTEMPTED.
 *
 * The vocabulary is closed because the difference between the three cases is the difference
 * between "the resource is back", "the resource is NOT back" and "nobody knows": each of them
 * decides whether the Saga may continue compensating, and a free-text excuse could not.
 */
sealed interface CompensationObservation {

    /** The compensation ran and was confirmed, with [receipt] when the provider gave one. */
    data class Confirmed(val receipt: EffectReceiptRef?) : CompensationObservation

    /** The compensation ran and failed, for [reason]. */
    data class Failed(val reason: String) : CompensationObservation

    /** The compensation ran and its outcome cannot be established. */
    data class Uncertain(val reason: UncertaintyReason) : CompensationObservation
}

/**
 * Why a durable record was REFUSED.
 *
 * A refusal is never an exception thrown at the plugin: the plugin reported what it observed
 * and the answer is "no", with a reason it can act on. Nothing is written when a refusal
 * happens, so the durable state keeps saying whatever it said before.
 */
sealed interface SagaRecordRejection {

    /** No obligation is stored under that key. */
    data object UnknownObligation : SagaRecordRejection

    /**
     * The obligation is not in a state from which that record can follow.
     *
     * This is the rule that stops a Saga from writing a compensation that was never owed: an
     * obligation whose forward effect was never confirmed cannot become "compensated".
     */
    data class IllegalTransition(
        val from: CompensationObligationStatus,
        val to: CompensationObligationStatus,
    ) : SagaRecordRejection

    /** Another process owns the run now: this writer lost the lease. */
    data class LeaseSuperseded(val heldEpoch: Long, val currentEpoch: Long) : SagaRecordRejection

    /**
     * The promise belongs to another plugin.
     *
     * A promise belongs to the plugin that armed it, and nobody else writes to it: the plan may
     * look the same, but the bodies behind it are the other plugin's.
     *
     * @property owner the plugin that armed the promise.
     * @property writer the plugin that tried to write to it.
     */
    data class ForeignOwner(val owner: String, val writer: String) : SagaRecordRejection
}

/**
 * What happened when the durable state of an obligation was recorded.
 */
sealed interface SagaRecordOutcome {

    /**
     * The obligation now holds [obligation]'s state, durably.
     *
     * [receiptRef] is the receipt that was written along with it, when there was one. The
     * domain value does not carry the receipt — it is a provider's opaque answer, not part of
     * the promise — so it is reported here, where the caller that just observed it can check
     * that what it saw is what is now durable.
     */
    data class Recorded(
        val obligation: CompensationObligation,
        val receiptRef: EffectReceiptRef?,
    ) : SagaRecordOutcome

    /** Nothing was written. */
    data class Refused(val reason: SagaRecordRejection) : SagaRecordOutcome
}

/**
 * The PLAN port of a Saga: what is owed, and the lifecycle of the obligation that owes it.
 *
 * ## What this port is
 *
 * It answers ONE question — "what does the Saga owe right now?" — and it records what the
 * plugin observed about the effects it triggered. The two halves belong together because the
 * answer is only as good as the records it is derived from: a decision taken on a state
 * nobody wrote is a decision taken on a guess.
 *
 * ## The write-ahead rule
 *
 * The lifecycle is written in two steps, and the order is the contract:
 *
 *  - [beginForward] / [beginCompensation] are written BEFORE the call leaves the process.
 *  - [recordForward] / [recordCompensation] are written AFTER the outcome is known.
 *
 * The gap between the two writes is the window in which a crash loses the answer. A row found
 * in the "pending" state is therefore not "nothing happened" but "something may have happened
 * and the answer was lost", which is the only honest reading — and the reason the Saga stops
 * there instead of compensating blindly.
 *
 * ## What this port is not
 *
 * It is not a way to reach the event store or the operation journal, and it cannot arm
 * obligations: that is [CompensationOperations], and the two are separate on purpose.
 */
interface SagaPlanOperations {

    /**
     * What the Saga owes NOW.
     *
     * Pure in effect: the answer is derived from the durable obligations of [sagaId] and from
     * nothing else, so two processes reading the same durable state get the same answer.
     *
     * @param sagaId the Saga whose obligations are asked about.
     * @param policy whether an outcome that is not established can be settled by querying the
     * provider.
     */
    fun next(sagaId: SagaId, policy: CompensationPolicy): CompensationDirective

    /**
     * Records that the forward effect is ABOUT TO BE DISPATCHED.
     *
     * Call it before the call leaves the process, never after: the whole point of it is to
     * survive a crash that happens while the answer is still in flight.
     */
    fun beginForward(key: CompensationKey): SagaRecordOutcome

    /**
     * Records what the forward effect turned out to be.
     *
     * @param key the obligation that promised to compensate the effect.
     * @param evidence what is known about the forward effect.
     */
    fun recordForward(key: CompensationKey, evidence: EffectEvidence): SagaRecordOutcome

    /**
     * Records that the compensation is ABOUT TO BE DISPATCHED.
     *
     * The mirror of [beginForward], and for the same reason: a compensation that may already
     * have been applied must never be applied twice.
     */
    fun beginCompensation(key: CompensationKey): SagaRecordOutcome

    /**
     * Records what the compensation turned out to be.
     *
     * @param key the obligation the compensation discharges.
     * @param observation what the compensation attempt produced.
     */
    fun recordCompensation(key: CompensationKey, observation: CompensationObservation): SagaRecordOutcome
}
