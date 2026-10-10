package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId

/**
 * M2 — closed ADT returned by [RuntimeControlPort.cancel].
 *
 * Each case names a real outcome of a cancel call; new cases are compile errors
 * at every `when` site. Mirrors the M1 refusal-extension pattern.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §4.3.
 */
sealed interface CancelOutcome {

    /**
     * This call flipped the run to `CANCELLED`. The terminal journal row was
     * committed and the seal was applied.
     *
     * `attempt` is always `AttemptId(1)` for the M2 first cut (cancel does not
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
     * The run was already `CANCELLED` by a prior call; this call was a no-op.
     * NOT a refusal — idempotency is the contract, and the caller is allowed to
     * retry without surfacing an error (audit D.3, B.2).
     */
    data class AlreadyCancelled(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : CancelOutcome

    /** The cancel was refused; `refusal` names the closed reason. */
    data class Refused(val refusal: CancelRefusal) : CancelOutcome
}
