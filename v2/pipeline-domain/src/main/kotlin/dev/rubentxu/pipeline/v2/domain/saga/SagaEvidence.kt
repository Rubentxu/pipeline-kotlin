package dev.rubentxu.pipeline.v2.domain.saga

/**
 * Why the outcome of an external effect is UNKNOWN.
 *
 * The cases are closed on purpose. "Unknown" is not an excuse to skip the question: it is the
 * single most dangerous state in a Saga (the effect may or may not have happened, and the
 * compensation may or may not be needed), so the reason for it is a closed vocabulary that a
 * decision can be taken on, not a free-text excuse.
 */
enum class UncertaintyReason {
    /** The call may have reached the provider, but the acknowledgement did not come back. */
    ACK_LOST,

    /** The provider could not be reached at all. */
    PROVIDER_UNREACHABLE,

    /** The provider answered, and the answer does not settle whether the effect happened. */
    PROVIDER_INDETERMINATE,

    /**
     * The call was CANCELLED before an answer came back.
     *
     * It may still have reached the provider: a cancellation that is not acknowledged is the
     * same uncertainty as a lost acknowledgement, and it is named apart from it because the
     * remediation differs — the run was stopped on purpose, and the effect may have been
     * applied before the stop arrived.
     */
    CALL_CANCELLED,

    /**
     * The call TIMED OUT before an answer came back.
     *
     * The provider may still be working on it. A timeout is not a failure of the effect, and a
     * Saga that treats it as one skips a compensation it owes.
     */
    CALL_TIMED_OUT,
}

/**
 * What is known about the forward effect of a Saga action.
 *
 * This is the observation that the DECISION is taken on, and it is deliberately a closed
 * vocabulary of four states. Note what is NOT here: there is no "failed" state, because a failed
 * call whose effect is unknown is [Unknown], and a call that definitely did nothing is [Absent].
 * Collapsing those two would be the difference between "compensate" and "do not compensate".
 */
sealed interface EffectEvidence {

    /** The forward effect was never attempted. */
    data object NotAttempted : EffectEvidence

    /** The forward effect happened, and the provider gave a receipt for it. */
    data class Confirmed(val receipt: EffectReceiptRef) : EffectEvidence

    /** The forward was settled and had NO effect. */
    data object Absent : EffectEvidence

    /** The outcome cannot be established. */
    data class Unknown(val reason: UncertaintyReason) : EffectEvidence
}

/**
 * Whether an unknown outcome can be RECONCILED.
 *
 * The difference between "ask the provider" and "ask a human" is not a detail: it decides
 * whether a Saga that lost its acknowledgement ends up automatically or stalls, and that
 * decision belongs to the caller, who knows what the provider supports.
 */
sealed interface CompensationPolicy {

    /** The provider can be queried with a stable key: an unknown outcome can be settled. */
    data object Reconcilable : CompensationPolicy

    /** Nothing can query the provider: an unknown outcome needs a human. */
    data object NotReconcilable : CompensationPolicy
}

/**
 * Why the forward effect must not proceed.
 */
enum class ForwardDenial {
    /** No durable obligation was armed, so there is nothing that would compensate the effect. */
    OBLIGATION_NOT_ARMED,

    /** The same key was already armed under a different plan; the intent is ambiguous. */
    PLAN_DIVERGENCE,

    /** The durable store refused the arm, so the promise of compensation cannot be made. */
    ARM_NOT_PERSISTED,
}

/**
 * Why a Saga needs a human.
 */
enum class InterventionReason {
    /** The outcome of the forward effect is unknown and nothing can query the provider. */
    UNKNOWN_EFFECT,

    /**
     * An effect was observed but no durable obligation backs it.
     *
     * The engine cannot invent the plan that would have compensated it, and guessing a
     * compensation is how a Saga does more damage than the failure it was recovering from.
     */
    OBLIGATION_NOT_ARMED,

    /** The obligation was armed under a plan that does not match the current one. */
    PLAN_DIVERGENCE,

    /**
     * A compensation ran and FAILED.
     *
     * The compensations that depend on it are not attempted: a dependent compensation against
     * a state that was not restored can destroy more than the failure it was recovering from.
     */
    COMPENSATION_FAILED,

    /**
     * A compensation ran and its outcome is UNKNOWN.
     *
     * The same rule as a failed compensation, with one more reason to stop: the effect may
     * have happened, so running the compensation again would double it.
     */
    COMPENSATION_UNCERTAIN,

    /**
     * The compensation was NOT dispatched, because the durable state refused the write-ahead.
     *
     * The write-ahead is written BEFORE the body leaves the process, so a refusal means the body
     * never ran and the resource was NOT touched. The obligation stays owed and durable, and
     * whoever owns the run — a later process or a human — decides.
     *
     * It is a different fact from [COMPENSATION_UNCERTAIN]: there, something ran and nobody
     * knows what it did; here, nothing ran at all.
     */
    COMPENSATION_NOT_DISPATCHED,
}

/**
 * What the engine decides about the FORWARD effect.
 *
 * ## Why the forward and the compensation are two different ADTs
 *
 * The forward effect and the compensation are two different questions with two different
 * timelines. The forward question is asked BEFORE the effect happens ("may I proceed?"); the
 * compensation question is asked AFTER it is known what happened ("what is owed?"). Merging
 * them into one sealed type would produce states like "compensate but do not proceed" that are
 * not meaningful, and would make every reader of the result re-derive which question is being
 * answered.
 */
sealed interface ReconciliationDecision {

    /** The forward effect may proceed: the obligation is armed and committed. */
    data object ProceedForward : ReconciliationDecision

    /** The forward effect must not proceed, for [reason]. */
    data class DenyForward(val reason: ForwardDenial) : ReconciliationDecision

    /** The outcome is unknown and can be settled by querying the provider. */
    data object Reconcile : ReconciliationDecision

    /** The outcome is unknown and cannot be settled automatically. */
    data class NeedsIntervention(val reason: InterventionReason) : ReconciliationDecision
}

/**
 * What is owed to a compensation obligation.
 *
 * The verdict is about the OBLIGATION, not about the forward effect: it says whether the
 * obligation is discharged, still owed, or stuck.
 */
sealed interface CompensationVerdict {

    /** There is nothing to compensate: the forward effect was observed to have had no effect. */
    data object NotRequired : CompensationVerdict

    /** The obligation stands: the forward effect happened and has not been compensated. */
    data object Pending : CompensationVerdict

    /** The compensation ran and was confirmed, with [receipt] when the provider gave one. */
    data class Succeeded(val receipt: EffectReceiptRef?) : CompensationVerdict

    /** The compensation ran and failed, for [reason]. */
    data class Failed(val reason: String) : CompensationVerdict

    /** The obligation cannot be settled automatically. */
    data class NeedsIntervention(val reason: InterventionReason) : CompensationVerdict
}

/**
 * The outcome of a Saga decision.
 *
 * Either the engine speaks about the forward effect, or it speaks about the obligation. One
 * type, so a caller handles the result once, with the two vocabularies underneath it.
 */
sealed interface SagaDecision {

    /** The decision is about whether the forward effect may proceed. */
    data class Forward(val decision: ReconciliationDecision) : SagaDecision

    /** The decision is about the state of the compensation obligation. */
    data class Compensate(val verdict: CompensationVerdict) : SagaDecision
}
