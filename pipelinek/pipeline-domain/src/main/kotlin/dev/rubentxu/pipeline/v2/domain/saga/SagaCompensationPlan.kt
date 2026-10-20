package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The durable state of ONE Saga, as the compensation decision sees it.
 *
 * ## Why a snapshot and not the store
 *
 * The decision must be answerable WITHOUT the store, the clock and the run: a rule that
 * depends on circumstances the engine does not know would decide one thing in the original run
 * and another thing after a restart. So the snapshot carries the obligations and nothing else,
 * and the decision reads it.
 *
 * The obligations ARE the durable state. There is no second copy of "what happened to the
 * effect" kept anywhere else: the status of an obligation is the fact, so a process that
 * restarts and reads the store rebuilds the whole decision from what it finds there. That is
 * what makes the decision answerable by a LATER process, which is the only process that ever
 * has to answer it.
 *
 * ## The order of the obligations IS the arm order
 *
 * The obligations are listed OLDEST FIRST, and that order is the arm order: a sequential Saga
 * arms the promise of an action just before it runs that action's forward effect, so the arm
 * order is the action order, and the newest armed obligation is the newest effect. That is
 * what "LIFO" means here, and it is why the order is not a parameter of the decision: the
 * snapshot's order is the only order there is.
 *
 * Two obligations armed within the same millisecond are ordered by their ids, which is
 * arbitrary but DETERMINISTIC. A decision that differs between two processes reading the same
 * durable state is a decision that cannot be trusted, and an arbitrary-but-fixed order is
 * preferable to an accidental one.
 */
data class SagaSnapshot(
    val sagaId: SagaId,
    val obligations: List<CompensationObligation>,
) {
    init {
        require(obligations.all { it.sagaId == sagaId }) {
            "a snapshot holds the obligations of ONE saga"
        }
    }

    /** The obligations in arm order: oldest first. */
    val inArmOrder: List<CompensationObligation>
        get() = obligations.sortedWith(
            compareBy(
                { it.createdAtMs },
                { it.actionId.value },
                { it.compensationId.value },
            ),
        )
}

/**
 * What the engine says should happen NEXT with a Saga's compensations.
 *
 * The four cases are the whole of the recovery vocabulary, and the order of the questions is
 * the order of the cases: is anything owed, can it be settled, must it be settled, or does a
 * human have to decide.
 */
sealed interface CompensationDirective {

    /** Every obligation of the Saga is settled. Nothing is owed. */
    data class Settled(val sagaId: SagaId) : CompensationDirective

    /**
     * The forward effect of this obligation happened, so its compensation is owed and must run
     * before any older one.
     */
    data class Compensate(val obligation: CompensationObligation) : CompensationDirective

    /**
     * The forward effect's outcome is not established, but the provider can be asked.
     *
     * The engine does NOT compensate on a maybe: a compensation against an effect that may
     * never have happened can destroy something that was never created.
     */
    data class Reconcile(val obligation: CompensationObligation) : CompensationDirective

    /**
     * Stop. A human decides.
     *
     * The rest of the Saga's obligations stay pending and durable, and the newest unsettled one
     * is the one that needs attention — the others depend on it.
     */
    data class NeedsIntervention(
        val obligation: CompensationObligation,
        val reason: InterventionReason,
    ) : CompensationDirective
}
