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
 * The state the engine announces when a Saga's recovery is over.
 *
 * ## Why the vocabulary lives in the DOMAIN
 *
 * The Event Plane carries this state as a word, so somebody has to translate between the word
 * and the type. It used to be a pair of private constants in the engine, which means a reader
 * outside that module could not name the words it reads: the vocabulary had exactly one owner
 * and that owner could not be quoted.
 *
 * So the closed set is declared here, next to the other vocabularies of the Saga, and both
 * sides cite it: the engine writes the name, the reader parses the name.
 *
 * ## The wire token is the NAME
 *
 * Same convention as [InterventionReason]: the word on the plane IS the name of the case, so
 * there is no second list of spellings to drift.
 */
enum class SagaTerminalState {

    /** The Saga closed and left nothing owed. */
    SETTLED,

    /** The Saga could not close, and a human has to decide. */
    NEEDS_INTERVENTION,

    ;

    companion object {

        /** The word this state is written as on the Event Plane is its own name. */
        private val BY_TOKEN: Map<String, SagaTerminalState> = entries.associateBy { it.name }

        /** Every word this runtime accepts, for diagnostics on a refused spelling. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /** The state [token] names, or `null` when it names none of them. Never defaults. */
        fun parse(token: String): SagaTerminalState? = BY_TOKEN[token]
    }
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

    /**
     * The completion was NOT recorded, because the durable state refused the write.
     *
     * The mirror of [COMPENSATION_NOT_DISPATCHED]: there, the compensation never ran; here, the
     * discharge never happened. Nothing was written, so the Saga is still OPEN and its effects
     * are still owed — and a later process reads the same rows and reaches the same conclusion,
     * which is the whole point of refusing instead of pretending.
     */
    COMPLETION_NOT_RECORDED,

    /**
     * The Saga was already compensated, so it cannot be completed.
     *
     * Its effects were undone. Completing it now would claim that a Saga whose resources are
     * gone succeeded, and a run that says "success" while its Saga was undone is a lie about
     * the world. A human decides what the resources that are left mean.
     */
    SAGA_ALREADY_COMPENSATED,

    /**
     * The Saga's terminal state could not be read.
     *
     * Two causes, one consequence, and the consequence is the only thing that matters to the
     * reader: **a human has to decide**, because nobody can say whether the Saga closed.
     *
     * - The plane announced a word this build does not know. That happens when the history was
     *   written by a build with a wider vocabulary, and it is a fact about the READING, not
     *   about the Saga.
     * - The plane announced NO terminal at all, while the Saga had already armed an obligation.
     *   Then the Saga did not close, and the absence is the evidence: a Saga that owes
     *   something and never said how it ended is still open.
     * - The plane announced a terminal that contradicts itself, or that arrives without the
     *   detail it must carry (a `NEEDS_INTERVENTION` with no reason, a `SETTLED` with one).
     *   A record that disagrees with itself cannot be taken at face value in either direction.
     *
     * It is here, and not folded into [UNKNOWN_EFFECT], because the two are not the same: an
     * unknown effect is the engine saying "I applied the effect and cannot say what it did",
     * and this one is a reader saying "I cannot read what the engine said" — or that it never
     * said it. A reader that cannot read a terminal state must NOT read it as a settled one —
     * that would turn a broken Saga into a finished one, which is the one mistake this
     * vocabulary exists to prevent.
     */
    TERMINAL_UNREADABLE,

    ;

    companion object {

        /**
         * The reason this word names, or **null** for a word this build does not know.
         *
         * The Event Plane carries the reason as the NAME of the case, so somebody has to
         * translate between the name and the type. This is that boundary, and it mirrors
         * [dev.rubentxu.pipeline.v2.domain.FailureKind.parse]: the map is derived from [entries]
         * rather than written out again, and it deliberately does NOT fall back to a default —
         * a default here would turn a word this build does not know into a decision it appears
         * to have made.
         */
        private val BY_TOKEN: Map<String, InterventionReason> = entries.associateBy { it.name }

        /** Every word this runtime accepts, for diagnostics on a refused spelling. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /** The reason [token] names, or `null` when it names none of them. Never defaults. */
        fun parse(token: String): InterventionReason? = BY_TOKEN[token]
    }
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
