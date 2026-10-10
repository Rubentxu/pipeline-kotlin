package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 — closed ADT of reasons an inspection could not be served.
 *
 * Mirrors the M1 convention (`OutputRefusal`, `EventRecordReadRefusal`,
 * `EventFollowRefusal`): a sealed interface per port, no `Either`/`Result` reuse.
 *
 * The audit's B.1 names `NoEventStore`, `NoControlRoot`, `UnknownRun`,
 * `LeaseHeldByAnother`. This design formalises those plus the substrate gaps
 * the audit's §C unmasked (`InconsistentLeaseState`).
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §3.3.
 */
sealed interface IntrospectionRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownRun(val runId: String) : IntrospectionRefusal

    /** No `--control-root` was given at startup; there is no journal or lease to consult. */
    data object NoControlRoot : IntrospectionRefusal

    /** The event plane is not configured for this instance. */
    data object NoEventStore : IntrospectionRefusal

    /** The output plane is not configured for this instance. */
    data object NoOutputPlane : IntrospectionRefusal

    /**
     * The run is held by another process whose lease is still live. The `heldBy`
     * and `fencingToken` name the live authority; this is the same fact the
     * cancel port refuses on (audit B.2). The introspection port reports it; it
     * does NOT attempt to take over.
     */
    data class LeaseHeldByAnother(
        val heldBy: String,
        val fencingToken: FencingToken,
    ) : IntrospectionRefusal

    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : IntrospectionRefusal

    /**
     * The journal, the lease record, and the cursor store disagree about the
     * run's state. `details` is a one-line diagnostic so an operator can read
     * what the inspector saw.
     */
    data class InconsistentLeaseState(val details: String) : IntrospectionRefusal
}
