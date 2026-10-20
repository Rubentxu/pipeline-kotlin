package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.RunOutcome

/**
 * What a Saga left behind when it closed.
 *
 * ## Why this is a type and not a word
 *
 * A Saga ends in one of two ways, and a reader that cannot tell them apart is a reader that
 * will call a broken Saga a finished one. The engine knows which one happened — it is the
 * difference between "the failure was undone" and "the failure is still standing" — and this
 * type is how that difference travels.
 *
 * ## Why it is NOT a [RunOutcome]
 *
 * A Saga is not a run. A run can fail and recover; a Saga that settled after a failure did
 * NOT make the run succeed, and a Saga that needs a human did not make the run fail. The two
 * questions have different answers, and the answer of one is never the answer of the other.
 * That is why [RunPresentation] holds both instead of collapsing them.
 *
 * ## Why the saga id is a plain [String]
 *
 * Because this type is read from a history, not written into one. The id arrives as a word on
 * the Event Plane, and the reader that shows it must be able to show a word it cannot
 * validate: a history written by another build can carry an id this build would refuse to
 * construct, and a reader that swallowed it would hide the very fact that it cannot read.
 * The raw word is the evidence; the domain's own [dev.rubentxu.pipeline.v2.domain.saga.SagaId]
 * stays the authority for ids the engine BUILDS.
 */
sealed interface SagaOutcome {

    /**
     * The Saga closed and left nothing owed.
     *
     * Either everything it owed was put back, or it never owed anything: both are a Saga
     * with nothing left to do, and neither is work for a human.
     */
    data class Settled(val sagaId: String) : SagaOutcome

    /**
     * The Saga could not close, and a human has to decide what to do about it.
     *
     * The [reason] is the closed vocabulary of the domain, so a reader can act on it without
     * parsing prose.
     */
    data class NeedsIntervention(val sagaId: String, val reason: InterventionReason) : SagaOutcome
}

/**
 * The run's result, presented with what the Sagas of the run left behind.
 *
 * ## The rule this type exists to hold
 *
 * **A Saga never softens the run's outcome.** A Saga that settled does not turn a failed run
 * green — the run failed, the failure was undone, and both facts are true at once. That is the
 * whole point of a Saga, and a presentation that reported "success" because the compensation
 * worked would be lying about the run that is still in the history.
 *
 * ## The rule the other way round
 *
 * **A Saga that needs a human keeps the run from being called finished.** A run that ended is
 * not the same as a run that is DONE: [isFinished] is the difference, and it is the question
 * an operator actually asks.
 */
data class RunPresentation(
    /** The run's own outcome. A Saga never changes it. */
    val outcome: RunOutcome,
    /** What each Saga of the run left behind, one entry per Saga, in the order the plane settled them. */
    val sagas: List<SagaOutcome>,
) {

    /**
     * True when a human has work.
     *
     * A Saga that settled is not work. A Saga that could not close is. And a run that ended
     * with work pending is not a finished run, however green its steps were.
     */
    val needsIntervention: Boolean get() = sagas.any { it is SagaOutcome.NeedsIntervention }

    /**
     * True when the run's story is over: it reached its end AND nobody has work left.
     *
     * ## Finished is NOT the same as successful
     *
     * A run that failed and whose failure was compensated IS finished: the Saga recovered, the
     * run failed, and both are true at once. How the run WENT is [outcome]; whether anything is
     * still to be DONE is this.
     *
     * ## And an aborted run is not finished either
     *
     * [RunOutcome.Aborted] is, by the domain's own definition, a run that was cancelled or
     * interrupted BEFORE reaching a normal terminal state. Its ending is not a result, it is the
     * absence of one, so it cannot be reported as finished — not even when no Saga left work
     * behind.
     */
    val isFinished: Boolean get() = outcome != RunOutcome.Aborted && !needsIntervention
}

/**
 * The presentation of a run that ran Sagas.
 *
 * ## Why the merge is a function and not a constructor
 *
 * Because the interesting part is the RULE, and a rule that lives in a `copy()` call is a rule
 * that a caller can forget. Here the rule is stated once: [original] goes through untouched,
 * and what the Sagas left is ADDED to it. The only way to soften a run's outcome is to not
 * call this function, and the only call site is the one that reads the plane.
 *
 * ## Why the Saga's outcomes are a list
 *
 * A run may run more than one Saga, and a reader that only saw the last one would be told a
 * half-truth: the first Saga may be the one that needs a human.
 */
fun mergeCompensationOutcome(
    original: RunOutcome,
    sagas: List<SagaOutcome>,
): RunPresentation = RunPresentation(outcome = original, sagas = sagas)
