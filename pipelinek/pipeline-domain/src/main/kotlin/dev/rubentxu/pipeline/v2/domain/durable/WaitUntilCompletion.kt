package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason

/**
 * Why a waitUntil stopped. The one value the terminal decision is made from.
 *
 * ## What this replaces, and why a String could not hold it
 *
 * The three things a terminal produces were decided separately at **five** sites in
 * `WaitUntilEngine` — two reached by the fresh loop and three by the reconciler:
 *
 * | terminal | durable status | step outcome | wire token |
 * |---|---|---|---|
 * | satisfied | `SUCCEEDED` | `Success` | `completed` |
 * | deadline | `FAILED_TIMEOUT` | `Failure(TIMEOUT)` | `deadline-exceeded` |
 * | aborted | `ABORTED` | `Failure(ENGINE)` | `aborted` |
 *
 * Each site wrote all three by hand, so the agreement between them was a property
 * nobody held and a change to one column silently disagreed with the other two. Worse,
 * the third column was the only one a caller could *read back*: the wire token travelled
 * in `WaitUntilCompleted.outcome: String`, and a consumer that string-matched it had
 * become the authority on how the run ended. [WaitUntilCompletion] inverts that — the ADT
 * is the authority, and all three columns are **projections** of it.
 *
 * ## The wire token is projected, never read
 *
 * [wireOutcome] exists because the historical event schema must not move: S8 freezes
 * event schemas against it, so `completed` / `deadline-exceeded` / `aborted` stay
 * byte-identical. What changed is the direction. Nothing in this module parses it, and
 * no decision anywhere is allowed to be made from it. If a future version cannot express
 * its terminal as one of these three, the correct answer is a new case here plus a
 * schema decision — never a new token that only the wire knows about.
 *
 * ## What this deliberately does NOT decide
 *
 * **Whether to persist.** The reconciler's [WaitUntilReconciliationDecision.Aborted]
 * branch is read-only (P3-E E4): the row it was handed already says `ABORTED`, so
 * writing it back could only be a no-op — and was, until that write started throwing on
 * an attempt that existed nowhere and let the abort escape as an untyped crash. The
 * fresh path's cancellation, by contrast, *must* persist `ABORTED` before returning.
 * Both produce the same terminal, so terminal-ness cannot be the thing that decides
 * persistence. That stays with the caller, which is the only place that knows which row
 * it is standing on.
 *
 * [WaitUntilReconciliationDecision.RejectDivergence] is not a case here, and that is not
 * an oversight: it never emits `WaitUntilCompleted`, because it concludes the step
 * without concluding the wait. Admitting it would make the event assert an ending that
 * did not occur.
 *
 * @see WaitUntilReconciliationDecision for the non-terminal reconciler outcomes.
 */
sealed interface WaitUntilCompletion {

    /** The durable status this terminal writes, or reads as already written. */
    val durableStatus: OperationStatus

    /**
     * The historical `WaitUntilCompleted.outcome` token.
     *
     * Frozen by S8. Projected from the case and never interpreted by one.
     */
    val wireOutcome: String

    /** The typed step result this terminal returns to its caller. */
    fun toStepOutcome(): StepOutcome

    /** The predicate was true. The wait ended because its condition held. */
    data object Satisfied : WaitUntilCompletion {
        override val durableStatus: OperationStatus = OperationStatus.SUCCEEDED
        override val wireOutcome: String = "completed"
        override fun toStepOutcome(): StepOutcome = StepOutcome.Success
    }

    /**
     * The backoff ceiling was reached at poll [attempt].
     *
     * [ceilingMs] is carried because the failure message quotes it, and a message that
     * had to be assembled at the call site would put the wording back outside the case
     * that owns the terminal.
     */
    data class DeadlineExceeded(
        val attempt: Int,
        val ceilingMs: Long,
    ) : WaitUntilCompletion {
        override val durableStatus: OperationStatus = OperationStatus.FAILED_TIMEOUT
        override val wireOutcome: String = "deadline-exceeded"

        override fun toStepOutcome(): StepOutcome = StepOutcome.Failure(
            PipelineFailure(
                FailureKind.TIMEOUT,
                "waitUntil deadline exceeded at poll $attempt (${ceilingMs}ms backoff ceiling)",
            ),
        )
    }

    /**
     * The wait was stopped before its predicate held.
     *
     * Two arrivals reach this one terminal and they are told apart by [cause] rather than
     * collapsed into a sentence: the fresh loop hears it from the body it just ran, and the
     * reconciler learns it by *reading* a durable row that already said `ABORTED`. The old code
     * gave both a `String` reason, and that is what made the reconciler's message read
     * `waitUntil aborted: waitUntil aborted` — the literal concatenated with itself.
     */
    data class Aborted(val cause: WaitUntilAbortCause) : WaitUntilCompletion {
        override val durableStatus: OperationStatus = OperationStatus.ABORTED
        override val wireOutcome: String = "aborted"

        override fun toStepOutcome(): StepOutcome = StepOutcome.Failure(
            PipelineFailure(
                FailureKind.ENGINE,
                when (cause) {
                    is WaitUntilAbortCause.BodyCancelled ->
                        "waitUntil cancelled: ${cause.reason.name}"
                    // Not "waitUntil aborted: waitUntil aborted". The reconciler's only
                    // construction site passes the literal as its reason, so the prefix was
                    // already in the message before the reason was appended to it.
                    WaitUntilAbortCause.DurableRowAlreadyAborted -> "waitUntil aborted"
                },
            ),
        )
    }
}

/**
 * Why a waitUntil was aborted, as a closed set rather than a sentence.
 *
 * A `String` was the previous shape and it could not be pattern-matched, could not be proved
 * exhaustive, and produced a message that repeated itself. Both arrivals are still distinguishable
 * from the value alone.
 */
sealed interface WaitUntilAbortCause {

    /**
     * The body under the predicate reported a cancellation.
     *
     * [reason] is the typed [CancellationReason] the body carried. The message interpolates
     * `reason.name`, which is byte-identical to what the pre-ADT code produced by stringifying the
     * same enum, so nothing observable moved.
     */
    data class BodyCancelled(val reason: CancellationReason) : WaitUntilAbortCause

    /**
     * A durable control row already read `ABORTED`.
     *
     * No payload: [WaitUntilReconciliationDecision.Aborted] is the only construction site and its
     * `reason` is the constant literal `"waitUntil aborted"`, so there is nothing there that a
     * caller could match and nothing the case would lose by not carrying.
     */
    data object DurableRowAlreadyAborted : WaitUntilAbortCause
}

/**
 * Every wire token a [WaitUntilCompletion] can produce.
 *
 * The set is a value rather than a derivation so a test can hold the historical schema
 * against it: adding a terminal must be a visible change to this list, not a new literal
 * that appears in the engine.
 */
val WaitUntilCompletionWireOutcomes: Set<String> =
    listOf(
        WaitUntilCompletion.Satisfied,
        WaitUntilCompletion.DeadlineExceeded(attempt = 0, ceilingMs = 0),
        WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted),
    ).mapTo(LinkedHashSet()) { it.wireOutcome }
