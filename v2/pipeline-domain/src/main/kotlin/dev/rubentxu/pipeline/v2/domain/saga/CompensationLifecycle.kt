package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The lifecycle of a durable compensation obligation: which state may follow which.
 *
 * ## Why the rules are here and not in the store
 *
 * "May this obligation become COMPENSATED?" is a question about MEANING, and meaning does not
 * belong in a JDBC adapter. The adapter writes rows; this object decides whether the write is
 * a lie. Keeping the table in the domain is what makes it testable without a database, and
 * what makes the same rule apply to every writer: the plugin that reports an observation and
 * the engine that writes a pending marker are judged by the same table.
 *
 * ## The table is closed, and it is checked
 *
 * A transition that is not in the table is refused. That is the rule that stops a Saga from
 * inventing a history: an obligation whose forward effect was never confirmed cannot become
 * "compensated", and an obligation that was compensated cannot be reopened as "confirmed".
 *
 * Re-writing the state an obligation already holds IS allowed. A retry after a crash writes
 * the same fact twice, and refusing that would make the write path fragile for no reason.
 */
object CompensationLifecycle {

    /**
     * The states an obligation may move to from [from].
     *
     * Total: it answers for every state, including the terminal ones (which have no
     * successors at all) and the state an obligation is born in.
     */
    fun successors(from: CompensationObligationStatus): Set<CompensationObligationStatus> = when (from) {
        // The promise exists and the forward effect has not been dispatched. It may now be
        // dispatched, or the answer about it may already be known.
        CompensationObligationStatus.ARMED -> setOf(
            CompensationObligationStatus.EFFECT_PENDING,
            CompensationObligationStatus.EFFECT_CONFIRMED,
            CompensationObligationStatus.EFFECT_ABSENT,
            CompensationObligationStatus.EFFECT_UNKNOWN,
        )

        // The forward effect was dispatched and its answer was lost. The only thing that may
        // follow is the answer itself.
        CompensationObligationStatus.EFFECT_PENDING -> setOf(
            CompensationObligationStatus.EFFECT_CONFIRMED,
            CompensationObligationStatus.EFFECT_ABSENT,
            CompensationObligationStatus.EFFECT_UNKNOWN,
        )

        // The forward effect happened: the compensation is owed, and the obligation now moves
        // through the compensation's own lifecycle.
        CompensationObligationStatus.EFFECT_CONFIRMED -> setOf(
            CompensationObligationStatus.COMPENSATION_PENDING,
            CompensationObligationStatus.COMPENSATED,
            CompensationObligationStatus.COMPENSATION_FAILED,
            CompensationObligationStatus.COMPENSATION_UNCERTAIN,
        )

        // The forward effect had no effect: the obligation is closed and nothing follows.
        CompensationObligationStatus.EFFECT_ABSENT -> emptySet()

        // The answer is not established. A reconciler may settle it either way, and the engine
        // may record that nothing can settle it at all.
        CompensationObligationStatus.EFFECT_UNKNOWN -> setOf(
            CompensationObligationStatus.EFFECT_CONFIRMED,
            CompensationObligationStatus.EFFECT_ABSENT,
            CompensationObligationStatus.NEEDS_INTERVENTION,
        )

        // The compensation was dispatched and its answer was lost. Only the answer may follow.
        CompensationObligationStatus.COMPENSATION_PENDING -> setOf(
            CompensationObligationStatus.COMPENSATED,
            CompensationObligationStatus.COMPENSATION_FAILED,
            CompensationObligationStatus.COMPENSATION_UNCERTAIN,
            CompensationObligationStatus.NEEDS_INTERVENTION,
        )

        // The compensation was applied: the obligation is discharged and stays discharged.
        CompensationObligationStatus.COMPENSATED -> emptySet()

        // The compensation did not work, or may have worked: the Saga stops here and the
        // obligation waits for a human.
        //
        // These two are TERMINAL, and they do NOT move to NEEDS_INTERVENTION on purpose: that
        // state says "the outcome is indeterminate", and a compensation that is known to have
        // FAILED is not indeterminate. Moving there would throw away the one fact the operator
        // needs — whether the resource is back — and replace it with a weaker claim.
        CompensationObligationStatus.COMPENSATION_FAILED -> emptySet()

        CompensationObligationStatus.COMPENSATION_UNCERTAIN -> emptySet()

        // A human decides: no automatic transition follows.
        CompensationObligationStatus.NEEDS_INTERVENTION -> emptySet()
    }

    /**
     * Whether an obligation may move from [from] to [to].
     *
     * Writing the state an obligation already holds is allowed, so a retry is never a lie.
     */
    fun allows(from: CompensationObligationStatus, to: CompensationObligationStatus): Boolean =
        to == from || to in successors(from)

    /**
     * The state the forward effect's evidence puts the obligation in.
     *
     * The evidence is the whole input: an effect that was never attempted and an effect that
     * was attempted and had none both leave the obligation with nothing to compensate, and
     * both say so with [CompensationObligationStatus.EFFECT_ABSENT].
     */
    fun forwardStatus(evidence: EffectEvidence): CompensationObligationStatus = when (evidence) {
        EffectEvidence.NotAttempted,
        EffectEvidence.Absent,
        -> CompensationObligationStatus.EFFECT_ABSENT

        is EffectEvidence.Confirmed -> CompensationObligationStatus.EFFECT_CONFIRMED
        is EffectEvidence.Unknown -> CompensationObligationStatus.EFFECT_UNKNOWN
    }

    /**
     * The state the compensation's outcome puts the obligation in.
     */
    fun compensationStatus(observation: CompensationObservation): CompensationObligationStatus =
        when (observation) {
            is CompensationObservation.Confirmed -> CompensationObligationStatus.COMPENSATED
            is CompensationObservation.Failed -> CompensationObligationStatus.COMPENSATION_FAILED
            is CompensationObservation.Uncertain ->
                CompensationObligationStatus.COMPENSATION_UNCERTAIN
        }

    /**
     * The receipt that belongs with a forward effect's evidence, when there is one.
     *
     * Only a CONFIRMED effect has a receipt. Everything else has none, and saying so is
     * better than keeping a receipt that belongs to a state that no longer holds.
     */
    fun receiptOf(evidence: EffectEvidence): EffectReceiptRef? = when (evidence) {
        is EffectEvidence.Confirmed -> evidence.receipt
        EffectEvidence.NotAttempted,
        EffectEvidence.Absent,
        is EffectEvidence.Unknown,
        -> null
    }

    /**
     * The receipt that belongs with a compensation's outcome, when there is one.
     */
    fun receiptOf(observation: CompensationObservation): EffectReceiptRef? = when (observation) {
        is CompensationObservation.Confirmed -> observation.receipt
        is CompensationObservation.Failed,
        is CompensationObservation.Uncertain,
        -> null
    }
}
