package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.runtime.inspect.FencingToken

/**
 * M2 — closed ADT of reasons a cancel call was refused.
 *
 * The audit's B.2 names `LeaseHeldByAnother`, `UnknownRun`, `RunTerminal`,
 * `JournalUnavailable`. This design adds `IncompatibleRunState` for runs in a
 * terminal state that is not `Cancelled` (e.g. a run that already reached
 * `Succeeded` or `Failed`).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §4.4.
 */
sealed interface CancelRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : CancelRefusal

    /**
     * The run has already reached a terminal state whose outcome is NOT
     * `Cancelled`. The terminal outcome is named in `outcome` so a caller can
     * decide whether the cancel is moot.
     */
    data class RunTerminal(val outcome: String) : CancelRefusal

    /**
     * The run is held by another process whose lease is still live. The
     * `ownerId` and `fencingToken` name the live authority; the cancel port
     * MUST NOT cross lease boundaries (audit B.2, B.8, G.3).
     */
    data class LeaseHeldByAnother(
        val ownerId: String,
        val fencingToken: FencingToken,
    ) : CancelRefusal

    /**
     * The run is in a state where cancel does not apply. Example: a run whose
     * lease was released but whose journal row is non-terminal — a tombstone
     * rather than a run.
     */
    data class IncompatibleRunState(val details: String) : CancelRefusal

    /**
     * The journal could not be written. `cause` is a short diagnostic. A cancel
     * that cannot write its terminal fact is a fail-closed refusal, NOT a
     * fallback to `start()` or `run()` (audit B.2).
     */
    data class JournalUnavailable(val cause: String) : CancelRefusal

    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : CancelRefusal
}
