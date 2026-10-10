package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * M2 — closed ADT of reasons a recover call failed closed.
 *
 * The audit's B.3 names `NotRecoverable`, `UnknownRun`, `JournalIncompatible`.
 * This design adds `StorageError`, `SubstrateUnavailable`, and `LeaseHeldByAnother`
 * (mirroring the cancel port — recover also MUST NOT cross lease boundaries, audit
 * G.3 by analogy).
 *
 * M3 adds `PinnedBytesOutsideRecoveredRegion` to honour the §12 retention-under-pin
 * invariant: a recover that drops pinned bytes would invalidate the consumer's
 * retention hold.
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

    /**
     * M3 — a recover was attempted on a run with active pins, and
     * those pins cover bytes the recover would have to release.
     *
     * A recover that drops pinned bytes would violate the contract §12
     * invariant ("no podar rangos hasta probar que su garantía activa se
     * transfirió a una copia durable, y que no existe pin"). The M2
     * recover port's composition rule explicitly does NOT modify
     * committed bytes; M3 extends that rule to: do NOT release pinned
     * bytes.
     *
     * The recover port MUST consult `OutputPinPort.pinsOf(stream,
     * range=intent.range)` BEFORE it returns `RecoveredTerminal`. If
     * any pin covers the recovered region, the port returns
     * `FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range, pins))`
     * instead.
     *
     * @property stream the stream whose pinned bytes the recover
     *                  would have released
     * @property range  the half-open range the recover would have
     *                  touched
     * @property pins   the active pins the consumer must release
     *                  before re-trying
     */
    data class PinnedBytesOutsideRecoveredRegion(
        val stream: OutputStreamId,
        val range: LongRange,
        val pins: List<OutputPin>,
    ) : RecoverRefusal
}
