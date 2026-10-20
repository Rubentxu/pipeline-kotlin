package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The capability a Step must declare to use the compensation intent port.
 *
 * ## Why the port is behind a capability and not simply a public interface
 *
 * A capability is the runtime's existing, enforced answer to "what may this step reach?".
 * Declaring the requirement is what makes the absence of the port a NAMED, TYPED refusal at
 * the moment the step is bound, instead of a null dereference or a silent no-op inside the
 * plugin. The step declares it, the runtime checks it, and the check is the same one every
 * other capability already goes through.
 */
val SAGA_COMPENSATION_CAPABILITY: StepCapability = StepCapability("saga.compensation")

/**
 * Why an arm was REFUSED.
 *
 * A refusal is never an exception thrown at the plugin: the plugin asked to make a promise and
 * the answer is "no", with a reason it can act on. The most important case is
 * [StoreUnavailable] — a promise that could not be persisted is not a promise, and the forward
 * effect must not proceed behind an unpersisted promise.
 */
sealed interface ArmRejection {

    /** The caller is not allowed to write obligations for this run. */
    data class NotAuthorized(val reason: String) : ArmRejection

    /** The declaration was written under an intent version this build does not honour. */
    data class UnsupportedIntentVersion(val version: Int) : ArmRejection

    /** The durable store refused or could not be reached. */
    data class StoreUnavailable(val reason: String) : ArmRejection

    /** Another process owns the run now: this writer lost the lease. */
    data class LeaseSuperseded(val heldEpoch: Long, val currentEpoch: Long) : ArmRejection
}

/**
 * What happened when a compensation obligation was armed.
 *
 * The cases are the contract's whole idempotency story: [Armed] is the first write, [AlreadyArmed]
 * is the same promise asked twice, [PlanDivergence] is the same key with a different promise (and
 * is refused, not overwritten), and [Rejected] is "no promise was made".
 */
sealed interface CompensationArmOutcome {

    /** The obligation was written for the first time. */
    data class Armed(val obligation: CompensationObligation) : CompensationArmOutcome

    /** The obligation was already stored under this key with the same plan. */
    data class AlreadyArmed(val obligation: CompensationObligation) : CompensationArmOutcome

    /** The key is already armed under a different plan; the existing promise is kept. */
    data class PlanDivergence(
        val existing: CompensationObligation,
        val requestedFingerprint: SagaPlanFingerprint,
    ) : CompensationArmOutcome

    /** No promise was made. */
    data class Rejected(val reason: ArmRejection) : CompensationArmOutcome
}

/**
 * The INTENT port of a Saga: the only way a plugin may talk to the compensation machinery.
 *
 * ## What this port is
 *
 * Three operations, and the shape of each is the point:
 *
 *  - [arm] is the only MUTATION. It records the promise to compensate, and it does so durably
 *    before it answers. The caller is expected to treat a non-[CompensationArmOutcome.Armed]
 *    outcome as "the forward effect must not run".
 *  - [read] is a READ. It returns the obligation under a key, or `null`. It takes no cursor and
 *    returns no cursor: a read cannot become a way to walk the store, and it cannot be used to
 *    ask about a run other than the one being executed.
 *  - [decide] is PURE. It asks the engine what the evidence means, so that the rule lives in
 *    one place instead of being re-implemented (and mis-implemented) in every plugin.
 *
 * ## What this port is not
 *
 * It is not a general-purpose handle on the event store, the operation journal or the run's
 * database. A plugin that could append events or journal operations could forge the evidence
 * that these obligations are judged by. The port is deliberately narrow: the plugin states an
 * intent, and the engine decides what to persist and what to do.
 *
 * ## The invocation binding
 *
 * An instance of this port is bound to ONE invocation: it knows the run, the operation and the
 * lease of the step that received it. The plugin cannot name a different run, and the run is
 * not an argument of any operation. That is what makes "context bound to the invocation" a
 * property of the type rather than a convention.
 */
interface CompensationOperations {

    /**
     * Records the promise to compensate [declaration], durably, before answering.
     *
     * The operation is idempotent on `(sagaId, actionId, compensationId)`: arming the same
     * declaration twice yields [CompensationArmOutcome.AlreadyArmed] with the original
     * obligation, and arming a DIFFERENT plan under the same key yields
     * [CompensationArmOutcome.PlanDivergence], which keeps the original promise.
     *
     * @param declaration what to promise. Carries no runtime handle and no secret.
     */
    fun arm(declaration: CompensationDeclaration): CompensationArmOutcome

    /**
     * Reads the obligation stored under [key], or `null` when there is none.
     *
     * The read is scoped to the run being executed. A key that belongs to another run is not
     * visible here, and a read never creates, updates or reserves anything.
     */
    fun read(key: CompensationKey): CompensationObligation?

    /**
     * Asks the engine what the evidence means.
     *
     * Pure and total: the same arguments always produce the same decision, and the decision
     * does not depend on the store, the clock or the state of the run.
     *
     * @param obligation the obligation the evidence is about, or `null` when none was armed.
     * @param evidence what is known about the forward effect.
     * @param policy whether an unknown outcome can be reconciled.
     */
    fun decide(
        obligation: CompensationObligation?,
        evidence: EffectEvidence,
        policy: CompensationPolicy,
    ): SagaDecision
}
