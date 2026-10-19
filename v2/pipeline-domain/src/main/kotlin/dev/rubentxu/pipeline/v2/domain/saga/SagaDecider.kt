package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The pure decision core of a Saga.
 *
 * ## Why this is a separate object and not a method on the port
 *
 * Every question this object answers is answerable without touching a store, a clock or an
 * event sink. Keeping the decisions here means the rules can be exercised directly — including
 * the cases that are hard to reach through a running pipeline — and it means the runtime port
 * has nothing to decide, only to persist and to delegate.
 *
 * Every function is total: it answers for every combination of its arguments, including the
 * combinations nobody expects (a null obligation with a confirmed effect, an unknown effect with
 * no reconciler). There is no "cannot decide" branch, because a decision that depends on
 * circumstances the engine does not know is a decision that would differ between a restart and
 * the original run.
 */
object SagaDecider {

    /**
     * Decides what to do about the forward effect, given the obligation and the evidence.
     *
     * @param obligation the durable obligation, or `null` when none was armed.
     * @param evidence what is known about the forward effect.
     * @param policy whether an unknown outcome can be reconciled.
     */
    fun decide(
        obligation: CompensationObligation?,
        evidence: EffectEvidence,
        policy: CompensationPolicy,
    ): SagaDecision = when {
        // No obligation means no promise of compensation. A forward that has not started must
        // not start: there would be nothing to compensate it with.
        obligation == null && evidence is EffectEvidence.NotAttempted ->
            SagaDecision.Forward(ReconciliationDecision.DenyForward(ForwardDenial.OBLIGATION_NOT_ARMED))

        // No obligation, but the forward is known to have had no effect: nothing is owed, and
        // refusing here would be refusing a decision that needs no decision.
        obligation == null && evidence is EffectEvidence.Absent ->
            SagaDecision.Compensate(CompensationVerdict.NotRequired)

        // No obligation and the effect DID happen: the effect is real and the plan that would
        // have compensated it is unknown. A human decides; the engine does not guess.
        obligation == null ->
            SagaDecision.Forward(
                ReconciliationDecision.NeedsIntervention(InterventionReason.OBLIGATION_NOT_ARMED),
            )

        evidence is EffectEvidence.NotAttempted ->
            SagaDecision.Forward(ReconciliationDecision.ProceedForward)

        // The forward effect happened. The obligation stands.
        evidence is EffectEvidence.Confirmed ->
            SagaDecision.Compensate(CompensationVerdict.Pending)

        // The forward was settled with no effect. Nothing to compensate.
        evidence is EffectEvidence.Absent ->
            SagaDecision.Compensate(CompensationVerdict.NotRequired)

        // The forward outcome is unknown. Whether that is recoverable depends on the provider.
        policy is CompensationPolicy.Reconcilable ->
            SagaDecision.Forward(ReconciliationDecision.Reconcile)

        else ->
            SagaDecision.Forward(
                ReconciliationDecision.NeedsIntervention(InterventionReason.UNKNOWN_EFFECT),
            )
    }

    /**
     * Decides what an `arm` means, given the obligation that is already stored under the key.
     *
     * The rule is the one the Saga contract states out loud: the SAME key armed twice with the
     * SAME plan is the same promise, and it succeeds idempotently; the same key armed with a
     * DIFFERENT plan is a contradiction, and it is refused rather than overwritten. Overwriting
     * would destroy the record of the promise that was actually made.
     *
     * The comparison is on the PLAN and only on the plan. Everything else about the obligation —
     * its operation identities, its timestamps, its lease epoch — belongs to the moment it was
     * armed, and a second arm of the same promise must not appear to be a different promise
     * because it was armed a second later.
     *
     * @param existing the obligation already stored under the key, or `null` when there is none.
     * @param requested the obligation the caller wants to arm.
     */
    fun armOutcome(
        existing: CompensationObligation?,
        requested: CompensationObligation,
    ): CompensationArmOutcome = when {
        existing == null -> CompensationArmOutcome.Armed(requested)

        existing.planFingerprint == requested.planFingerprint ->
            CompensationArmOutcome.AlreadyArmed(existing)

        else -> CompensationArmOutcome.PlanDivergence(
            existing = existing,
            requestedFingerprint = requested.planFingerprint,
        )
    }
}
