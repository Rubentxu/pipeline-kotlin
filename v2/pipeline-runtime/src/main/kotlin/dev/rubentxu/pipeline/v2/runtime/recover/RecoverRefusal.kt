package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * M2 — closed ADT of reasons a recover call failed closed.
 *
 * The audit's B.3 names `NotRecoverable`, `UnknownRun`, `JournalIncompatible`.
 * This design adds `StorageError`, `SubstrateUnavailable`, and `LeaseHeldByAnother`
 * (mirroring the cancel port — recover also MUST NOT cross lease boundaries, audit
 * G.3 by analogy).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5.4.
 */
sealed interface RecoverRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : RecoverRefusal

    /**
     * The substrate was inspected and yielded no recoverable evidence
     * (analogous to `RunningSubprocessObservation.Lost`). The run is genuinely
     * `Lost`, not `Unobservable` — these are different cases on purpose.
     */
    data object NotRecoverable : RecoverRefusal

    /**
     * The substrate could not be inspected (analogous to
     * `RunningSubprocessObservation.Unavailable` / `UnobservableCause
     * .NoControlRootConfigured`).
     */
    data class SubstrateUnavailable(val cause: String) : RecoverRefusal

    /**
     * The journal is on a schema this PK cannot read. The audit's
     * `journal incompatible` test case is pinned here; the recover port refuses
     * rather than attempting to interpret a schema it does not understand.
     */
    data class JournalIncompatible(val version: String) : RecoverRefusal

    /**
     * The run is held by another process whose lease is still live. Recover
     * MUST NOT cross lease boundaries (audit B.8, G.3 by analogy); the live
     * authority must release before recover can proceed.
     */
    data class LeaseHeldByAnother(val details: String) : RecoverRefusal

    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : RecoverRefusal
}
