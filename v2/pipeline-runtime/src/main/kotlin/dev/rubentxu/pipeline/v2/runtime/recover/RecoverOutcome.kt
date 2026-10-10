package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation

/**
 * M2 — closed ADT returned by [RuntimeRecoverPort.recover]. Each case names a
 * real outcome; new cases are compile errors at every `when` site.
 *
 * Mirrors the audit's B.3 shape and the `PK-SPEC-02-RUNTIME-OBSERVATION.md`
 * `RecoveryChoice` ADT (plus the `AlreadyRecovered` idempotency case).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5.3.
 */
sealed interface RecoverOutcome {

    /**
     * The run reached a known terminal state and the recovery is complete. The
     * `terminal` carries the typed [TerminalObservation].
     *
     * This is the only outcome that does NOT re-execute external effects; the
     * terminal is the journal's own observation (audit D.4, no-rerun invariant).
     */
    data class RecoveredTerminal(
        val attempt: AttemptId,
        val terminal: TerminalObservation,
        val terminalAtMs: Long,
        /** What the recovery did, for audit. */
        val report: RecoverReport,
    ) : RecoverOutcome

    /**
     * The substrate is still reattachable and no terminal has been observed yet.
     * `deadlineMs` is the wall-clock deadline after which a follow-up call should
     * re-check.
     */
    data class ReattachPending(
        val attempt: AttemptId,
        val deadlineMs: Long,
    ) : RecoverOutcome

    /**
     * The recovery cannot proceed. `reason` names the closed [RecoverRefusal] so
     * a `when` over the typed reasons fails to compile when a new failure mode
     * is added.
     */
    data class FailClosed(val reason: RecoverRefusal) : RecoverOutcome

    /**
     * The run was already recovered by a prior call; this call was a no-op.
     * NOT a refusal — idempotency is the contract.
     */
    data class AlreadyRecovered(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : RecoverOutcome
}
