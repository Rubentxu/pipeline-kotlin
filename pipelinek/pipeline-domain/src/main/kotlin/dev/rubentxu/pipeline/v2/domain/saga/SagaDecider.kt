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
     * Decides what the engine must do NEXT with a Saga's compensations.
     *
     * ## The order is the compensation order
     *
     * The obligations are walked NEWEST FIRST, so the compensation that runs is the one for the
     * most recent effect. That is LIFO, and for a sequential Saga it is also the only order that
     * makes sense: undoing the newest action usually needs the state the older actions left
     * behind, so the older ones may only be undone after it.
     *
     * The walk STOPS at the first obligation that needs something. An obligation that is
     * unresolved blocks the older ones, because compensating an older resource while a newer
     * one is in doubt can destroy the state the newer compensation would have needed.
     *
     * ## What the decision is made of
     *
     * Only the durable state of the obligations. The store, the clock and the run are not
     * consulted, so the answer is the same in the process that armed the promises and in the
     * process that resumes the run after a crash. That is what makes the answer trustworthy
     * enough to compensate a real resource with.
     *
     * ## The states that need a human
     *
     * Two situations stop the Saga instead of continuing:
     *
     *  - The outcome of an effect is not established and nothing can query the provider.
     *  - A compensation ran and either failed or left an indeterminate result.
     *
     * In both cases the remaining obligations stay pending and durable. They are not silently
     * dropped and they are not compensated against a state that may not exist.
     *
     * @param snapshot the durable obligations of one Saga.
     * @param policy whether an outcome that is not established can be settled by querying the
     * provider.
     */
    fun nextCompensation(
        snapshot: SagaSnapshot,
        policy: CompensationPolicy,
    ): CompensationDirective {
        for (obligation in snapshot.inArmOrder.asReversed()) {
            val directive = directiveFor(obligation, policy)
            if (directive != null) {
                return directive
            }
        }
        return CompensationDirective.Settled(snapshot.sagaId)
    }

    /**
     * What one obligation demands, or `null` when it demands nothing.
     *
     * `null` means "keep walking": the obligation owes nothing, so an older one may still owe
     * something. It never means "skip this one and do the rest" — the walk stops at the first
     * obligation that demands something.
     */
    private fun directiveFor(
        obligation: CompensationObligation,
        policy: CompensationPolicy,
    ): CompensationDirective? = when (obligation.status) {
        // Nothing is owed: the promise was never used (the forward effect was never
        // dispatched), the forward effect had no effect, the compensation already ran, or the
        // Saga completed and kept the effect.
        CompensationObligationStatus.ARMED,
        CompensationObligationStatus.EFFECT_ABSENT,
        CompensationObligationStatus.COMPENSATED,
        CompensationObligationStatus.EFFECT_KEPT,
        -> null

        // The forward effect happened. Its compensation is owed, and it is owed before any
        // older one.
        CompensationObligationStatus.EFFECT_CONFIRMED ->
            CompensationDirective.Compensate(obligation)

        // The forward effect's outcome is not established. Ask the provider when there is one
        // that can be asked; a human decides when there is not.
        CompensationObligationStatus.EFFECT_PENDING,
        CompensationObligationStatus.EFFECT_UNKNOWN,
        -> when (policy) {
            CompensationPolicy.Reconcilable -> CompensationDirective.Reconcile(obligation)
            CompensationPolicy.NotReconcilable ->
                CompensationDirective.NeedsIntervention(
                    obligation,
                    InterventionReason.UNKNOWN_EFFECT,
                )
        }

        // The compensation was dispatched and its answer was lost. It may already have been
        // applied, so it is never applied again on a guess.
        CompensationObligationStatus.COMPENSATION_PENDING -> when (policy) {
            CompensationPolicy.Reconcilable -> CompensationDirective.Reconcile(obligation)
            CompensationPolicy.NotReconcilable ->
                CompensationDirective.NeedsIntervention(
                    obligation,
                    InterventionReason.COMPENSATION_UNCERTAIN,
                )
        }

        // The compensation ran and did not put the resource back.
        CompensationObligationStatus.COMPENSATION_FAILED ->
            CompensationDirective.NeedsIntervention(
                obligation,
                InterventionReason.COMPENSATION_FAILED,
            )

        // The compensation ran and nobody knows whether it put the resource back.
        CompensationObligationStatus.COMPENSATION_UNCERTAIN ->
            CompensationDirective.NeedsIntervention(
                obligation,
                InterventionReason.COMPENSATION_UNCERTAIN,
            )

        // A human was already asked. The obligation waits.
        CompensationObligationStatus.NEEDS_INTERVENTION ->
            CompensationDirective.NeedsIntervention(
                obligation,
                InterventionReason.UNKNOWN_EFFECT,
            )
    }

    /**
     * Decides whether a Saga may be COMPLETED, and what completing it keeps.
     *
     * ## What a completion means
     *
     * Completing a Saga says that its chain succeeded and that its effects STAY. The
     * obligations whose forward effect was confirmed are discharged without being paid, and the
     * promise they carry is void: nothing is owed afterwards, and a later process that reads the
     * same durable rows reaches the same conclusion — it will not compensate a Saga that
     * succeeded.
     *
     * ## What blocks it
     *
     * A completion claims the Saga ended well, so the durable state has to be able to say so
     * too. It cannot when:
     *
     *  - an effect's outcome is not established (nobody can say whether the forward happened);
     *  - a compensation failed or is uncertain (the Saga is in the middle of being undone);
     *  - the Saga was already compensated (its effects are gone, and completing it would claim
     *    a Saga whose resources do not exist).
     *
     * ## What does NOT block it
     *
     * An obligation that was never used (the forward was never dispatched) or whose effect had
     * no effect demands nothing, and the Saga may close with those actions simply absent. The
     * plugin is the authority on which actions its chain ran; the engine's job is to make sure
     * that nothing stays owed.
     *
     * ## The order of the walk
     *
     * The obligations are walked NEWEST FIRST, like the compensation walk: the newest unsettled
     * one is the one that needs attention, and the others depend on it.
     *
     * @param snapshot the durable obligations of one Saga.
     */
    fun completion(snapshot: SagaSnapshot): SagaCompletionDecision {
        for (obligation in snapshot.inArmOrder.asReversed()) {
            val reason = completionBlocker(obligation.status)
            if (reason != null) {
                return SagaCompletionDecision.Refused(obligation, reason)
            }
        }

        val kept = snapshot.inArmOrder
            .filter {
                it.status == CompensationObligationStatus.EFFECT_CONFIRMED ||
                    it.status == CompensationObligationStatus.EFFECT_KEPT
            }
            .map { it.actionId }
        val discharge = snapshot.inArmOrder
            .filter { it.status == CompensationObligationStatus.EFFECT_CONFIRMED }

        return SagaCompletionDecision.Completable(kept = kept, discharge = discharge)
    }

    /**
     * The reason this state blocks a completion, or `null` when it does not block it.
     */
    private fun completionBlocker(
        status: CompensationObligationStatus,
    ): InterventionReason? = when (status) {
        // Nothing is owed and nothing was undone: the promise was never used, the effect had
        // no effect, or the Saga already completed and kept it.
        CompensationObligationStatus.ARMED,
        CompensationObligationStatus.EFFECT_ABSENT,
        CompensationObligationStatus.EFFECT_KEPT,
        -> null

        // The effect happened: this is what the completion keeps, and what it discharges.
        CompensationObligationStatus.EFFECT_CONFIRMED -> null

        // The effect's outcome is not established. Completing would claim a Saga whose
        // resources nobody can describe.
        CompensationObligationStatus.EFFECT_PENDING,
        CompensationObligationStatus.EFFECT_UNKNOWN,
        -> InterventionReason.UNKNOWN_EFFECT

        // The Saga was already undone: its resources are gone.
        CompensationObligationStatus.COMPENSATED -> InterventionReason.SAGA_ALREADY_COMPENSATED

        // The compensation was dispatched and its answer was lost, or it came back
        // indeterminate: the Saga is in the middle of being undone and nobody can say how far.
        CompensationObligationStatus.COMPENSATION_PENDING,
        CompensationObligationStatus.COMPENSATION_UNCERTAIN,
        -> InterventionReason.COMPENSATION_UNCERTAIN

        // The compensation ran and did not put the resource back.
        CompensationObligationStatus.COMPENSATION_FAILED -> InterventionReason.COMPENSATION_FAILED

        // A human was already asked: the obligation waits, and so does the Saga.
        CompensationObligationStatus.NEEDS_INTERVENTION -> InterventionReason.UNKNOWN_EFFECT
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
