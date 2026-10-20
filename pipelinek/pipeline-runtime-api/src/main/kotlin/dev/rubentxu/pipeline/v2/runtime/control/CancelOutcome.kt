package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.domain.AttemptId

/**
 * Closed ADT returned by [RuntimeControlPort.cancel].
 * Each case names a real outcome of a cancel call; new cases are compile errors
 * At every `when` site. Mirrors the refusal-extension pattern
*/
sealed interface CancelOutcome {

    /**
     * This call closed the run. The run's terminal bookend was appended to the
     * event lane, the cancellation was recorded in the journal, and the output
     * streams were sealed.
     *
     * The run's terminal outcome is the product's existing `aborted` token: the
     * run reached no outcome of its own, and the run-outcome vocabulary has one
     * word for that. The cancellation itself is recorded where it can be told
     * apart from an interrupted run — in the journal row, and in the
     * bookend's diagnostic.
     * `attempt` is always `AttemptId(1)` for the first cut (cancel does not
     * introduce a new attempt — the same attempt the writer was on is what gets
     * cancelled, and there is no AttemptId(2) for cancel).
*/
    data class Cancelled(
        val attempt: AttemptId,
        val terminalAtMs: Long,
        /** The number of streams that were sealed by this cancel call. */
        val streamsSealed: Int,
    ) : CancelOutcome

    /**
     * The run was already closed by a prior call; this call was a no-op.
     * NOT a refusal — idempotency is the contract, and the caller is allowed to
     * retry without surfacing an error.
*/
    data class AlreadyCancelled(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : CancelOutcome

    /** The cancel was refused; `refusal` names the closed reason. */
    data class Refused(val refusal: CancelRefusal) : CancelOutcome
}
